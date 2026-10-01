#!/usr/bin/env python3
"""Tests for ingest.py: resumable upload, sha256 verify, quotas, identity, wants, models.

Runs the real HTTP server on 127.0.0.1 with a fake tailnet whois, so every test goes
through the same request path the car uses. No network, no tailscale, stdlib only.
"""
import hashlib
import http.client
import json
import os
import shutil
import struct
import tempfile
import threading
import unittest

import ingest

CAR_IP = "127.0.0.1"
CAR_TAGS = ["tag:car"]
SAMPLE_RATE = 48000


def wav(seconds=1.0, rate=SAMPLE_RATE, channels=1, seed=1):
    """A PCM16 WAV of pseudo-random samples. Different seeds give different sha256s."""
    frames = int(seconds * rate)
    data = bytes(((i * 7919 + seed * 104729) & 0xFF) for i in range(frames * channels * 2))
    header = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVE"
    header += b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate, rate * channels * 2, channels * 2, 16)
    header += b"data" + struct.pack("<I", len(data))
    return header + data


def sidecar(**extra):
    meta = {
        "schema": "road-noise/1",
        "started_at": "2026-10-01T16:02:11Z",
        "duration_s": 1.0,
        "sample_rate": SAMPLE_RATE,
        "channels": 1,
        "source": "auto",
        "band": "highway",
        "tags": ["Highway"],
    }
    meta.update(extra)
    return meta


def sha(blob):
    return hashlib.sha256(blob).hexdigest()


class Harness(unittest.TestCase):
    """One server per test, on its own temp tree."""

    tags = CAR_TAGS

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="ingest-test-")
        self.cfg = ingest.Config(
            noise_root=os.path.join(self.tmp, "road-noise"),
            diag_root=os.path.join(self.tmp, "car-diag"),
            models_root=os.path.join(self.tmp, "models"),
            state_dir=os.path.join(self.tmp, "state"),
            host="127.0.0.1",
            port=0,
            daily_device_bytes=10 * 1024 * 1024,
            min_free_bytes=0,
        )
        for d in (self.cfg.noise_root, self.cfg.diag_root, self.cfg.models_root):
            os.makedirs(d)
        whois = lambda ip: ingest.Peer("rav4", list(self.tags)) if ip == CAR_IP else None
        self.server = ingest.serve(self.cfg, whois)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        shutil.rmtree(self.tmp)

    def call(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        raw = resp.read()
        conn.close()
        payload = raw or None
        if raw and resp.getheader("Content-Type") == "application/json":
            payload = json.loads(raw)
        return resp.status, dict(resp.getheaders()), payload

    def create(self, blob, kind="road-noise", meta=None, name="capture.wav"):
        body = {"kind": kind, "sha256": sha(blob), "size": len(blob), "name": name,
                "meta": sidecar() if meta is None else meta}
        return self.call("POST", "/v1/uploads", body)

    def patch(self, blob, offset, chunk, kind="road-noise"):
        part = blob[offset:offset + chunk]
        return self.call("PATCH", f"/v1/uploads/{kind}/{sha(blob)}", part,
                         {"Upload-Offset": str(offset), "Content-Type": "application/offset+octet-stream"})

    def upload(self, blob, chunk=64 * 1024, **kw):
        status, _, body = self.create(blob, **kw)
        self.assertIn(status, (200, 201), body)
        offset = body["offset"]
        last = None
        while offset < len(blob):
            last = self.patch(blob, offset, chunk, kind=kw.get("kind", "road-noise"))
            if last[0] == 204:
                offset = int(last[1]["Upload-Offset"])
                continue
            break
        return last

    def index(self, root):
        path = os.path.join(root, "index.jsonl")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [json.loads(line) for line in f if line.strip()]


class UploadTest(Harness):

    def test_chunked_upload_lands_verified_on_share(self):
        blob = wav(seed=1)
        status, _, body = self.upload(blob)
        self.assertEqual(200, status, body)
        self.assertTrue(body["complete"])
        self.assertEqual(sha(blob), body["sha256"])

        rows = self.index(self.cfg.noise_root)
        self.assertEqual(1, len(rows))
        row = rows[0]
        self.assertEqual(sha(blob), row["sha256"])
        self.assertEqual("rav4", row["device"])
        self.assertEqual("highway", row["band"])

        stored = os.path.join(self.cfg.noise_root, row["path"])
        with open(stored, "rb") as f:
            self.assertEqual(sha(blob), sha(f.read()))
        with open(stored[:-4] + ".json") as f:
            meta = json.load(f)
        self.assertEqual("rav4", meta["device"])
        self.assertEqual(sha(blob), meta["sha256"])
        self.assertEqual("auto", meta["source"])

    def test_resume_after_disconnect_continues_at_server_offset(self):
        blob = wav(seed=2)
        self.create(blob)
        half = len(blob) // 2
        status, headers, _ = self.patch(blob, 0, half)
        self.assertEqual(204, status)

        # A fresh client (the car after a reboot) asks where to continue.
        status, headers, _ = self.call("HEAD", f"/v1/uploads/road-noise/{sha(blob)}")
        self.assertEqual(200, status)
        self.assertEqual(str(half), headers["Upload-Offset"])

        status, _, body = self.patch(blob, half, len(blob))
        self.assertEqual(200, status, body)
        self.assertTrue(body["complete"])

    def test_create_again_mid_upload_reports_offset(self):
        blob = wav(seed=3)
        self.create(blob)
        self.patch(blob, 0, 1000)
        status, _, body = self.create(blob)
        self.assertEqual(200, status)
        self.assertEqual(1000, body["offset"])
        self.assertFalse(body["complete"])

    def test_wrong_offset_is_conflict_with_current_offset(self):
        blob = wav(seed=4)
        self.create(blob)
        self.patch(blob, 0, 500)
        status, headers, _ = self.patch(blob, 100, 500)
        self.assertEqual(409, status)
        self.assertEqual("500", headers["Upload-Offset"])

    def test_sha_mismatch_rejects_and_forgets_partial(self):
        blob = wav(seed=5)
        lie = bytearray(blob)
        lie[-1] ^= 0xFF
        body = {"kind": "road-noise", "sha256": sha(blob), "size": len(blob), "name": "x.wav", "meta": sidecar()}
        self.call("POST", "/v1/uploads", body)
        status, _, resp = self.call("PATCH", f"/v1/uploads/road-noise/{sha(blob)}", bytes(lie),
                                    {"Upload-Offset": "0"})
        self.assertEqual(422, status, resp)
        self.assertEqual([], self.index(self.cfg.noise_root))
        status, _, _ = self.call("HEAD", f"/v1/uploads/road-noise/{sha(blob)}")
        self.assertEqual(404, status)

    def test_complete_upload_is_idempotent(self):
        blob = wav(seed=6)
        self.upload(blob)
        status, _, body = self.create(blob)
        self.assertEqual(200, status)
        self.assertTrue(body["complete"])
        status, headers, _ = self.call("HEAD", f"/v1/uploads/road-noise/{sha(blob)}")
        self.assertEqual("1", headers["Upload-Complete"])
        self.assertEqual(1, len(self.index(self.cfg.noise_root)))

    def test_stereo_wav_is_refused(self):
        blob = wav(channels=2, seed=7)
        status, _, body = self.upload(blob)
        self.assertEqual(422, status, body)
        self.assertEqual([], self.index(self.cfg.noise_root))

    def test_wrong_rate_is_refused(self):
        blob = wav(rate=16000, seed=8)
        status, _, _ = self.upload(blob)
        self.assertEqual(422, status)

    def test_sidecar_missing_required_key_is_refused(self):
        meta = sidecar()
        del meta["started_at"]
        status, _, body = self.create(wav(seed=9), meta=meta)
        self.assertEqual(400, status)
        self.assertIn("started_at", body["error"])

    def test_oversize_file_is_refused(self):
        body = {"kind": "road-noise", "sha256": "a" * 64, "size": ingest.MAX_BYTES["road-noise"] + 1,
                "name": "big.wav", "meta": sidecar()}
        status, _, _ = self.call("POST", "/v1/uploads", body)
        self.assertEqual(413, status)

    def test_daily_device_quota(self):
        self.cfg.daily_device_bytes = len(wav(seed=10)) + 10
        status, _, _ = self.upload(wav(seed=10))
        self.assertEqual(200, status)
        status, _, body = self.create(wav(seed=11))
        self.assertEqual(429, status, body)

    def test_bad_kind_and_bad_sha_are_refused(self):
        status, _, _ = self.call("POST", "/v1/uploads", {"kind": "photos", "sha256": "a" * 64, "size": 1,
                                                         "name": "x", "meta": {}})
        self.assertEqual(400, status)
        status, _, _ = self.call("HEAD", "/v1/uploads/road-noise/../../etc")
        self.assertEqual(404, status)

    def test_diag_lands_under_day_and_device(self):
        blob = b"10-01 16:00:00 I launcher: hello\n" * 100
        status, _, body = self.upload(blob, kind="diag", meta={}, name="launcher.0.log")
        self.assertEqual(200, status, body)
        rows = self.index(self.cfg.diag_root)
        self.assertEqual(1, len(rows))
        path = os.path.join(self.cfg.diag_root, rows[0]["path"])
        self.assertTrue(rows[0]["path"].endswith("/rav4/launcher.0.log"), rows[0]["path"])
        with open(path, "rb") as f:
            self.assertEqual(blob, f.read())

    def test_diag_name_cannot_escape(self):
        blob = b"x" * 10
        status, _, body = self.upload(blob, kind="diag", meta={}, name="../../etc/passwd")
        self.assertEqual(200, status, body)
        rows = self.index(self.cfg.diag_root)
        self.assertNotIn("..", rows[0]["path"])

    def test_diag_same_name_new_content_keeps_both(self):
        self.upload(b"first\n", kind="diag", meta={}, name="kmsg.log")
        self.upload(b"second\n", kind="diag", meta={}, name="kmsg.log")
        paths = [r["path"] for r in self.index(self.cfg.diag_root)]
        self.assertEqual(2, len(set(paths)))


class IdentityTest(Harness):

    tags = ["tag:laptop"]

    def test_untagged_peer_is_forbidden(self):
        status, _, _ = self.call("GET", "/v1/wants")
        self.assertEqual(403, status)
        status, _, _ = self.create(wav(seed=12))
        self.assertEqual(403, status)


class WantsTest(Harness):

    def test_wants_adds_have_seconds_per_band(self):
        with open(os.path.join(self.cfg.noise_root, "wants.json"), "w") as f:
            json.dump({"updated": "2026-10-01T18:00:00Z", "bands": {"highway": {"target_s": 60}}}, f)
        self.upload(wav(seed=13))
        self.upload(wav(seed=14, seconds=2.0), meta=sidecar(duration_s=2.0, band="city", tags=["City"]))
        status, _, body = self.call("GET", "/v1/wants")
        self.assertEqual(200, status)
        self.assertEqual(60, body["bands"]["highway"]["target_s"])
        self.assertAlmostEqual(1.0, body["bands"]["highway"]["have_s"])
        self.assertAlmostEqual(2.0, body["bands"]["city"]["have_s"])

    def test_no_wants_file_is_empty_targets(self):
        status, _, body = self.call("GET", "/v1/wants")
        self.assertEqual(200, status)
        self.assertEqual({}, body["bands"])


class ModelsTest(Harness):

    def test_models_served_with_range(self):
        d = os.path.join(self.cfg.models_root, "rnnoise")
        os.makedirs(d)
        with open(os.path.join(d, "manifest.json"), "wb") as f:
            f.write(b"0123456789")
        status, _, body = self.call("GET", "/v1/models/rnnoise/manifest.json")
        self.assertEqual(200, status)
        status, headers, body = self.call("GET", "/v1/models/rnnoise/manifest.json", headers={"Range": "bytes=2-5"})
        self.assertEqual(206, status)
        self.assertEqual(b"2345", body)
        self.assertEqual("bytes 2-5/10", headers["Content-Range"])

    def test_models_traversal_is_not_found(self):
        status, _, _ = self.call("GET", "/v1/models/../road-noise/index.jsonl")
        self.assertEqual(404, status)
        status, _, _ = self.call("GET", "/v1/models/%2e%2e/state")
        self.assertEqual(404, status)


if __name__ == "__main__":
    unittest.main()
