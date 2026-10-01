#!/usr/bin/env python3
"""Car ingest: a small resumable upload service for road-noise captures and diag logs.

The head unit reaches it over the estate's WireGuard tailnet only. There is no password:
the caller's tailnet identity is the credential. Every request asks the local tailscaled
"who is this IP?" and only a node carrying an allowed tag (tag:car) gets an answer.

    car (tag:car) ──tailnet──► ingest :8797 ──► <noise root>/<day>/<id>.wav + .json
                                    │              <noise root>/index.jsonl
                                    │          ──► <diag root>/<day>/<device>/<name>
                                    └─ GET /v1/models/** read-only from <models root>

Uploads are content addressed by sha256, so a capture has the same address before and
after a reboot, and a resume needs no server-issued id:

    POST  /v1/uploads                 {"kind","sha256","size","name","meta"} -> {"offset","complete"}
    HEAD  /v1/uploads/<kind>/<sha256> -> Upload-Offset, Upload-Length, Upload-Complete
    PATCH /v1/uploads/<kind>/<sha256> Upload-Offset: n, body = bytes [n, n+len)
          -> 204 + Upload-Offset, or 200 {"complete": true, "sha256"} on the last chunk
    GET   /v1/wants                   the trainer's wants.json plus have_s per band
    GET   /v1/models/<path>           Range supported
    GET   /v1/health

A file reaches the share only after its sha256 matches. Partials live in the state dir,
never on the share. Python stdlib only.
"""
import argparse
import datetime
import fcntl
import hashlib
import http.server
import json
import os
import re
import shutil
import socket
import socketserver
import struct
import subprocess
import threading
import time
import urllib.parse

KINDS = ("road-noise", "diag")

# Per-file ceilings. A 30 s capture is 2.9 MB; a log ring file is a few MB.
MAX_BYTES = {"road-noise": 64 * 1024 * 1024, "diag": 32 * 1024 * 1024}
MAX_CHUNK = 8 * 1024 * 1024
MAX_JSON = 64 * 1024

# What a road-noise capture must be (CONTRACT.md section 2).
WAV_RATE = 48000
WAV_CHANNELS = 1
WAV_BITS = 16
WAV_PCM = 1
SIDECAR_REQUIRED = ("schema", "started_at", "duration_s", "sample_rate", "channels", "source")
SIDECAR_SCHEMA = "road-noise/1"

ID_HEX = 16
SHA_RE = re.compile(r"^[0-9a-f]{64}$")
NAME_RE = re.compile(r"[^A-Za-z0-9._-]")
WHOIS_TTL_S = 60

HTTP_OK = 200
HTTP_CREATED = 201
HTTP_NO_CONTENT = 204
HTTP_PARTIAL = 206
HTTP_BAD = 400
HTTP_FORBIDDEN = 403
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_TOO_BIG = 413
HTTP_RANGE = 416
HTTP_UNPROCESSABLE = 422
HTTP_QUOTA = 429
HTTP_NO_SPACE = 507

# Lets the service bind the tailnet address before tailscaled has brought it up.
IP_FREEBIND = 15


class Config:
    def __init__(self, noise_root, diag_root, models_root, state_dir, host, port,
                 allowed_tags=("tag:car",), daily_device_bytes=1024 * 1024 * 1024,
                 min_free_bytes=20 * 1024 * 1024 * 1024):
        self.noise_root = noise_root
        self.diag_root = diag_root
        self.models_root = models_root
        self.state_dir = state_dir
        self.host = host
        self.port = port
        self.allowed_tags = set(allowed_tags)
        self.daily_device_bytes = daily_device_bytes
        self.min_free_bytes = min_free_bytes


class Peer:
    def __init__(self, name, tags):
        self.name = name
        self.tags = tags


class Refused(Exception):
    """A request the service answers with an error status and a one-line reason."""

    def __init__(self, status, reason, headers=None):
        super().__init__(reason)
        self.status = status
        self.reason = reason
        self.headers = headers or {}


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def sha_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def safe_name(name):
    """A file name with no directory part and no odd characters: '../x y' -> 'x_y'."""
    base = os.path.basename(str(name or "").replace("\\", "/")) or "file"
    base = NAME_RE.sub("_", base).lstrip(".")
    return base[:120] or "file"


def check_wav(path):
    """Refuse anything but a 48 kHz mono PCM16 WAV. Returns the duration in seconds."""
    with open(path, "rb") as f:
        head = f.read(4096)
    if len(head) < 44 or head[0:4] != b"RIFF" or head[8:12] != b"WAVE":
        raise Refused(HTTP_UNPROCESSABLE, "not a RIFF/WAVE file")

    # Walk the chunks: fmt first, then data.
    pos, fmt, data_len = 12, None, None
    while pos + 8 <= len(head):
        cid, clen = head[pos:pos + 4], struct.unpack("<I", head[pos + 4:pos + 8])[0]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", head[pos + 8:pos + 24])
        if cid == b"data":
            data_len = clen
            break
        pos += 8 + clen + (clen & 1)
    if fmt is None or data_len is None:
        raise Refused(HTTP_UNPROCESSABLE, "WAV has no fmt or data chunk")

    tag, channels, rate, _, _, bits = fmt
    if (tag, channels, rate, bits) != (WAV_PCM, WAV_CHANNELS, WAV_RATE, WAV_BITS):
        raise Refused(HTTP_UNPROCESSABLE,
                      f"want PCM16 mono 48 kHz, got tag={tag} ch={channels} rate={rate} bits={bits}")
    return data_len / (rate * channels * bits // 8)


def check_sidecar(meta):
    if not isinstance(meta, dict):
        raise Refused(HTTP_BAD, "meta must be an object")
    missing = [k for k in SIDECAR_REQUIRED if k not in meta]
    if missing:
        raise Refused(HTTP_BAD, "sidecar missing " + ", ".join(missing))
    if meta["schema"] != SIDECAR_SCHEMA:
        raise Refused(HTTP_BAD, f"sidecar schema must be {SIDECAR_SCHEMA}")


class Store:
    """Partials, completion markers, quotas and placement on the share."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        for sub in ("partial", "done", "usage"):
            os.makedirs(os.path.join(cfg.state_dir, sub), exist_ok=True)

    # --- paths -------------------------------------------------------------------------

    def _part(self, kind, sha):
        return os.path.join(self.cfg.state_dir, "partial", f"{kind}-{sha}.part")

    def _meta(self, kind, sha):
        return os.path.join(self.cfg.state_dir, "partial", f"{kind}-{sha}.json")

    def _done(self, kind, sha):
        return os.path.join(self.cfg.state_dir, "done", f"{kind}-{sha}")

    def _usage(self, device, day):
        return os.path.join(self.cfg.state_dir, "usage", f"{safe_name(device)}-{day}")

    # --- state -------------------------------------------------------------------------

    def status(self, kind, sha):
        """(offset, size, complete) or None when the address is unknown."""
        done = self._done(kind, sha)
        if os.path.exists(done):
            with open(done) as f:
                rec = json.load(f)
            return rec["bytes"], rec["bytes"], True
        meta = self._meta(kind, sha)
        if not os.path.exists(meta):
            return None
        with open(meta) as f:
            size = json.load(f)["size"]
        return os.path.getsize(self._part(kind, sha)), size, False

    def done_record(self, kind, sha):
        with open(self._done(kind, sha)) as f:
            return json.load(f)

    def used_today(self, device):
        path = self._usage(device, utc_now().strftime("%Y-%m-%d"))
        if not os.path.exists(path):
            return 0
        with open(path) as f:
            return int(f.read().strip() or 0)

    def _charge(self, device, n):
        path = self._usage(device, utc_now().strftime("%Y-%m-%d"))
        with open(path, "a+") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            f.seek(0)
            total = int(f.read().strip() or 0) + n
            f.seek(0)
            f.truncate()
            f.write(str(total))

    # --- operations --------------------------------------------------------------------

    def create(self, peer, req):
        kind, sha, size = req.get("kind"), str(req.get("sha256", "")), req.get("size")
        if kind not in KINDS:
            raise Refused(HTTP_BAD, f"kind must be one of {', '.join(KINDS)}")
        if not SHA_RE.match(sha):
            raise Refused(HTTP_BAD, "sha256 must be 64 lowercase hex digits")
        if not isinstance(size, int) or size <= 0:
            raise Refused(HTTP_BAD, "size must be a positive integer")
        if size > MAX_BYTES[kind]:
            raise Refused(HTTP_TOO_BIG, f"{kind} files are capped at {MAX_BYTES[kind]} bytes")
        meta = req.get("meta") or {}
        if kind == "road-noise":
            check_sidecar(meta)

        with self.lock:
            st = self.status(kind, sha)
            if st is not None:
                return HTTP_OK, st[0], st[2]

            # Quotas apply to new uploads only, so a resume never gets stuck behind them.
            if self.used_today(peer.name) + size > self.cfg.daily_device_bytes:
                raise Refused(HTTP_QUOTA, "daily quota for this device is used up")
            root = self.cfg.noise_root if kind == "road-noise" else self.cfg.diag_root
            if shutil.disk_usage(root).free - size < self.cfg.min_free_bytes:
                raise Refused(HTTP_NO_SPACE, "share is below its free-space floor")

            rec = {"kind": kind, "sha256": sha, "size": size, "name": safe_name(req.get("name")),
                   "meta": meta, "device": peer.name, "created_at": iso(utc_now())}
            open(self._part(kind, sha), "wb").close()
            with open(self._meta(kind, sha), "w") as f:
                json.dump(rec, f)
            self._charge(peer.name, size)
            return HTTP_CREATED, 0, False

    def append(self, kind, sha, offset, body):
        """Append one chunk. Returns (offset, record-or-None) where a record means complete."""
        with self.lock:
            st = self.status(kind, sha)
            if st is None:
                raise Refused(HTTP_NOT_FOUND, "unknown upload")
            cur, size, complete = st
            if complete:
                return cur, self.done_record(kind, sha)
            if offset != cur:
                raise Refused(HTTP_CONFLICT, "offset mismatch", {"Upload-Offset": str(cur)})
            if cur + len(body) > size:
                raise Refused(HTTP_BAD, "chunk runs past the declared size")
            with open(self._part(kind, sha), "ab") as f:
                f.write(body)
                f.flush()
                os.fsync(f.fileno())
            cur += len(body)
            if cur < size:
                return cur, None
            return cur, self._finish(kind, sha)

    def _forget(self, kind, sha):
        for p in (self._part(kind, sha), self._meta(kind, sha)):
            if os.path.exists(p):
                os.remove(p)

    def _finish(self, kind, sha):
        """Verify, place on the share, index, mark done. Caller holds the lock."""
        part = self._part(kind, sha)
        with open(self._meta(kind, sha)) as f:
            rec = json.load(f)

        actual = sha_file(part)
        if actual != sha:
            self._forget(kind, sha)
            raise Refused(HTTP_UNPROCESSABLE, f"sha256 mismatch: got {actual}")
        try:
            duration = check_wav(part) if kind == "road-noise" else None
        except Refused:
            self._forget(kind, sha)
            raise

        now = utc_now()
        day = now.strftime("%Y-%m-%d")
        if kind == "road-noise":
            row = self._place_noise(rec, part, day, now, duration)
        else:
            row = self._place_diag(rec, part, day, now)

        done = dict(row, bytes=rec["size"])
        with open(self._done(kind, sha), "w") as f:
            json.dump(done, f)
        self._forget(kind, sha)
        return done

    def _publish(self, src, dest):
        """Copy into the destination folder under a dot name, then rename: never half a file."""
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        tmp = os.path.join(os.path.dirname(dest), "." + os.path.basename(dest) + ".tmp")
        shutil.copyfile(src, tmp)
        os.replace(tmp, dest)

    def _append_index(self, root, row):
        with open(os.path.join(root, "index.jsonl"), "a") as f:
            fcntl.flock(f, fcntl.LOCK_EX)
            f.write(json.dumps(row, sort_keys=True) + "\n")

    def _place_noise(self, rec, part, day, now, duration):
        sha = rec["sha256"]
        cid = sha[:ID_HEX]
        rel = f"{day}/{cid}.wav"
        meta = dict(rec["meta"], received_at=iso(now), device=rec["device"], sha256=sha,
                    bytes=rec["size"], id=cid)

        dest = os.path.join(self.cfg.noise_root, rel)
        self._publish(part, dest)
        side = dest[:-4] + ".json"
        tmp = os.path.join(os.path.dirname(side), "." + os.path.basename(side) + ".tmp")
        with open(tmp, "w") as f:
            json.dump(meta, f, indent=1, sort_keys=True)
        os.replace(tmp, side)

        row = {"id": cid, "path": rel, "sha256": sha, "bytes": rec["size"],
               "duration_s": meta.get("duration_s", duration), "band": meta.get("band"),
               "tags": meta.get("tags", []), "device": rec["device"], "received_at": iso(now),
               "source": meta.get("source")}
        self._append_index(self.cfg.noise_root, row)
        return row

    def _place_diag(self, rec, part, day, now):
        device = safe_name(rec["device"])
        name = rec["name"]
        rel = f"{day}/{device}/{name}"
        dest = os.path.join(self.cfg.diag_root, rel)
        if os.path.exists(dest):
            stem, ext = os.path.splitext(name)
            rel = f"{day}/{device}/{stem}-{rec['sha256'][:8]}{ext}"
            dest = os.path.join(self.cfg.diag_root, rel)
        self._publish(part, dest)
        row = {"path": rel, "sha256": rec["sha256"], "bytes": rec["size"], "device": rec["device"],
               "received_at": iso(now), "name": rec["name"]}
        self._append_index(self.cfg.diag_root, row)
        return row

    # --- reads -------------------------------------------------------------------------

    def wants(self):
        """The trainer's wants.json with have_s summed per band from the index."""
        out = {"updated": None, "bands": {}}
        path = os.path.join(self.cfg.noise_root, "wants.json")
        if os.path.exists(path):
            with open(path) as f:
                theirs = json.load(f)
            out["updated"] = theirs.get("updated")
            for band, want in (theirs.get("bands") or {}).items():
                out["bands"][band] = dict(want, have_s=0.0)

        index = os.path.join(self.cfg.noise_root, "index.jsonl")
        if not os.path.exists(index):
            return out
        with open(index) as f:
            for line in f:
                if not line.strip():
                    continue
                row = json.loads(line)
                band = row.get("band")
                if not band:
                    continue
                slot = out["bands"].setdefault(band, {"have_s": 0.0})
                slot["have_s"] = slot.get("have_s", 0.0) + float(row.get("duration_s") or 0)
        return out


def tailnet_whois(socket_path, binary="tailscale"):
    """Ask the local tailscaled who owns an IP. Cached, because the car sends many chunks."""
    cache = {}

    def lookup(ip):
        hit = cache.get(ip)
        if hit and hit[0] > time.monotonic():
            return hit[1]
        try:
            out = subprocess.run([binary, f"--socket={socket_path}", "whois", "--json", ip],
                                 capture_output=True, timeout=5, check=True).stdout
            node = json.loads(out)["Node"]
        except (subprocess.SubprocessError, ValueError, KeyError, OSError):
            return None
        peer = Peer(node.get("Name", "").split(".")[0] or node.get("ComputedName", "?"),
                    node.get("Tags") or [])
        cache[ip] = (time.monotonic() + WHOIS_TTL_S, peer)
        return peer

    return lookup


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "car-ingest/1"
    protocol_version = "HTTP/1.1"

    # --- plumbing ----------------------------------------------------------------------

    def log_message(self, fmt, *args):
        peer = getattr(self, "peer", None)
        who = peer.name if peer else self.client_address[0]
        print(f"{who} {fmt % args}", flush=True)

    def _send(self, status, payload=None, headers=None):
        body = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        if payload is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _body(self, limit):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise Refused(HTTP_TOO_BIG, f"body over {limit} bytes")
        return self.rfile.read(n) if n else b""

    def _auth(self):
        self.peer = self.server.whois(self.client_address[0])
        if self.peer is None or not (set(self.peer.tags) & self.server.cfg.allowed_tags):
            raise Refused(HTTP_FORBIDDEN, "caller is not a car on the tailnet")

    def _route(self):
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        return [p for p in path.split("/") if p]

    def _upload_addr(self, parts):
        if len(parts) != 4 or parts[2] not in KINDS or not SHA_RE.match(parts[3]):
            raise Refused(HTTP_NOT_FOUND, "no such upload")
        return parts[2], parts[3]

    def _run(self, fn):
        try:
            self._auth()
            fn(self._route())
        except Refused as r:
            # Drain the body so a keep-alive connection stays in sync.
            if self.command in ("POST", "PATCH") and not getattr(self, "_drained", False):
                n = int(self.headers.get("Content-Length") or 0)
                if 0 < n <= MAX_CHUNK:
                    self.rfile.read(n)
            self._send(r.status, {"error": r.reason}, r.headers)

    # --- verbs -------------------------------------------------------------------------

    def do_GET(self):
        self._run(self._get)

    def do_HEAD(self):
        self._run(self._head)

    def do_POST(self):
        self._run(self._post)

    def do_PATCH(self):
        self._run(self._patch)

    def _get(self, parts):
        store = self.server.store
        if parts == ["v1", "health"]:
            free = shutil.disk_usage(self.server.cfg.noise_root).free
            return self._send(HTTP_OK, {"ok": True, "free_bytes": free, "device": self.peer.name})
        if parts == ["v1", "wants"]:
            return self._send(HTTP_OK, store.wants())
        if parts[:2] == ["v1", "models"] and len(parts) > 2:
            return self._model(parts[2:])
        raise Refused(HTTP_NOT_FOUND, "no such path")

    def _head(self, parts):
        kind, sha = self._upload_addr(parts)
        st = self.server.store.status(kind, sha)
        if st is None:
            raise Refused(HTTP_NOT_FOUND, "unknown upload")
        offset, size, complete = st
        self._send(HTTP_OK, None, {"Upload-Offset": str(offset), "Upload-Length": str(size),
                                   "Upload-Complete": "1" if complete else "0"})

    def _post(self, parts):
        if parts != ["v1", "uploads"]:
            raise Refused(HTTP_NOT_FOUND, "no such path")
        raw = self._body(MAX_JSON)
        self._drained = True
        try:
            req = json.loads(raw)
        except ValueError:
            raise Refused(HTTP_BAD, "body is not JSON")
        status, offset, complete = self.server.store.create(self.peer, req)
        payload = {"offset": offset, "complete": complete}
        if complete:
            payload.update(self.server.store.done_record(req["kind"], req["sha256"]))
        self._send(status, payload, {"Upload-Offset": str(offset)})

    def _patch(self, parts):
        kind, sha = self._upload_addr(parts)
        try:
            offset = int(self.headers.get("Upload-Offset", ""))
        except ValueError:
            raise Refused(HTTP_BAD, "Upload-Offset header is required")
        body = self._body(MAX_CHUNK)
        self._drained = True
        cur, done = self.server.store.append(kind, sha, offset, body)
        if done is None:
            return self._send(HTTP_NO_CONTENT, None, {"Upload-Offset": str(cur)})
        self._send(HTTP_OK, dict(done, complete=True), {"Upload-Offset": str(cur)})

    def _model(self, rel):
        root = os.path.realpath(self.server.cfg.models_root)
        path = os.path.realpath(os.path.join(root, *rel))
        if not path.startswith(root + os.sep) or not os.path.isfile(path):
            raise Refused(HTTP_NOT_FOUND, "no such model file")

        size = os.path.getsize(path)
        start, end, status = 0, size - 1, HTTP_OK
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip())
            if not m or (not m.group(1) and not m.group(2)):
                raise Refused(HTTP_RANGE, "bad Range", {"Content-Range": f"bytes */{size}"})
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            if start > end:
                raise Refused(HTTP_RANGE, "range outside file", {"Content-Range": f"bytes */{size}"})
            status = HTTP_PARTIAL

        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == HTTP_PARTIAL:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                block = f.read(min(left, 1024 * 1024))
                if not block:
                    break
                self.wfile.write(block)
                left -= len(block)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True

    def server_bind(self):
        # Bind the tailnet address even if tailscaled has not raised it yet.
        try:
            self.socket.setsockopt(socket.IPPROTO_IP, IP_FREEBIND, 1)
        except OSError:
            pass
        super().server_bind()


def serve(cfg, whois):
    """Build a bound server. The caller runs serve_forever()."""
    srv = Server((cfg.host, cfg.port), Handler)
    srv.cfg = cfg
    srv.whois = whois
    srv.store = Store(cfg)
    return srv


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--noise-root", required=True)
    ap.add_argument("--diag-root", required=True)
    ap.add_argument("--models-root", required=True)
    ap.add_argument("--state-dir", required=True)
    ap.add_argument("--host", required=True, help="the tailnet address; never 0.0.0.0")
    ap.add_argument("--port", type=int, default=8797)
    ap.add_argument("--tailscale-socket", required=True)
    ap.add_argument("--allow-tag", action="append", default=None)
    ap.add_argument("--daily-device-mb", type=int, default=1024)
    ap.add_argument("--min-free-gb", type=int, default=20)
    a = ap.parse_args()
    if a.host in ("0.0.0.0", "::", ""):
        ap.error("bind the tailnet address only")

    cfg = Config(a.noise_root, a.diag_root, a.models_root, a.state_dir, a.host, a.port,
                 allowed_tags=a.allow_tag or ["tag:car"],
                 daily_device_bytes=a.daily_device_mb * 1024 * 1024,
                 min_free_bytes=a.min_free_gb * 1024 * 1024 * 1024)
    srv = serve(cfg, tailnet_whois(a.tailscale_socket))
    print(f"car-ingest on {a.host}:{a.port} for {sorted(cfg.allowed_tags)}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
