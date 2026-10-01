# Uplink: the car talks to its own server

The head unit uploads road-noise captures and its diagnostic log ring to a server the owner
runs, and fetches noise-suppression models back. The server trains on what the car sends.
Nothing leaves the car except road noise with speech removed (the launcher's
`UPLINK.md` has the capture and speech rules) and the unit's own logs.

```
 head unit                                          owner's server
 ┌───────────────────────────────┐                  ┌──────────────────────────────────┐
 │ launcher uplink               │                  │ ingest.py (this folder)          │
 │   │ SOCKS5 127.0.0.1:1055     │   WireGuard      │   binds the tailnet address only │
 │   ▼                           │   (tailnet)      │   caller must carry tag:car      │
 │ tailscaled, userspace,        │ ───────────────▶ │   road-noise/<day>/<id>.wav+json │
 │ --shields-up                  │                  │   car-diag/<day>/<node>/<file>   │
 │ (riposte-uplink.sh, init)     │ ◀─────────────── │   models/** (read-only)          │
 └───────────────────────────────┘                  └──────────────────────────────────┘
```

## The link

The unit joins a Tailscale-protocol network (a self-hosted control server works) as a node
tagged `tag:car`. The network policy lets `tag:car` reach the ingest port and nothing else.

- **Userspace `tailscaled`, not the Tailscale app.** It needs no VPN consent, no UI and no
  account login on the unit. It starts from init (`riposte_uplink` in `riposte.rc`) on
  car-owner builds. It takes no route and no tun device, so the car's own networking
  (Bluetooth tethering, Wi-Fi, the CarPlay access point) is untouched. Only a client that
  dials the local SOCKS5 port reaches the network.
- **`--shields-up`.** The network can reach no port on the car. The policy cannot express
  that, because its rules only allow and never deny. Tailscale's peer API still answers a
  peer, with 403 for every request from another node.
- **The binary lives on `/data`.** The two official static programs are about 70 MB and
  the 0.2 image set has about 50 MB of room left in `super`. So `riposte-uplink.sh` fetches
  the official tarball once, refuses it unless its sha256 equals the pin in the script, and
  unpacks it under `/data/misc/riposte/tailscale` (root only, mode 0700). Bump `TS_VERSION`
  and `TS_SHA256` together.
- **DNS.** Go programs read `/etc/resolv.conf`, which Android does not ship. The image adds
  `/system/etc/resolv.conf` with two public resolvers. Android itself never reads it.
- **Joining.** `enroll.sh SERIAL LOGIN_SERVER INGEST_URL [NAME] < keyfile` writes a
  single-use `tag:car` key into a root-only file on the unit. The init script logs in with
  it and deletes it. The node keeps its identity in `state/` from then on. The key travels
  on stdin only.
- **Battery and data.** The daemon idles between uploads. It keeps one connection to its
  relay and polls the control server, a few hundred bytes a minute. When the unit sleeps
  (ACC off), the daemon sleeps with it and reconnects on wake.

## The ingest service

`ingest.py` is Python standard library only, with `apkinfo.py` beside it. `install-ingest.sh
SHARE_ROOT TAILNET_IP TAILSCALE_SOCKET [SUITE_ROOT]` installs both as `car-ingest.service`,
running as the share's owner. It binds the tailnet address only; `IP_FREEBIND` lets it start before the address
exists.

Identity: each request's source address goes to the local `tailscaled` (`tailscale whois`).
A node without an allowed tag gets 403. The node's name becomes the `device` field. WireGuard
already encrypts and authenticates every packet, so there is no password to leak.

Uploads are addressed by the file's sha256, so a resume after a reboot needs no state from
the server:

| request | answer |
|---|---|
| `POST /v1/uploads` `{"kind","sha256","size","name","meta"}` | `201` new, `200` known; body `{"offset","complete"}` |
| `HEAD /v1/uploads/<kind>/<sha256>` | `Upload-Offset`, `Upload-Length`, `Upload-Complete: 0/1` |
| `PATCH /v1/uploads/<kind>/<sha256>`, header `Upload-Offset: n` | `204` + new offset; `200 {"complete":true,"sha256"}` on the last chunk |
| wrong offset | `409` with the server's `Upload-Offset` |
| sha256 or WAV check fails | `422`; the partial is dropped, start again |
| `GET /v1/wants` | the trainer's per-band targets with `have_s` summed from the index |
| `GET /v1/models/<path>` | the models folder, read-only, `Range` supported |
| `GET /v1/releases/manifest.json` | the APKs the car may install (below) |
| `GET /v1/releases/<path>.apk` | one APK the manifest names, read-only, `Range` supported |

Kinds: `road-noise` (48 kHz mono PCM16 WAV plus a `road-noise/1` sidecar in `meta`) and
`diag` (any file). Limits: 64 MB per capture, 32 MB per log file, 8 MB per chunk, 1 GB per
node per day, and a free-space floor on the share. A file is copied into place under a dot
name and renamed, so a reader never sees half of one. Then a line goes into `index.jsonl`.

The client deletes its local copy only after a `complete` answer whose sha256 equals its
own.

## Releases

The car updates its launcher, car service and suite apps from the same service and port, so
the network policy needs no new rule. `--releases-root` is the folder the release publisher
fills with `carlauncher-<name>-vc<code>.apk` and `carservice-<name>-vc<code>.apk`; the newest of
each is offered. `--suite-root` holds one `<package>.apk` per suite app. `apkinfo.py` reads each
file once per size and modification time, with the standard library only:

```json
{"schema": "riposte-releases/1", "generated": "2026-10-01T18:00:00Z", "apps": [
  {"role": "launcher", "package": "com.ripostelabs.carlauncher", "version_code": 975,
   "version_name": "0.7", "path": "carlauncher-0.7-vc975.apk", "sha256": "...", "size": 8150240,
   "cert_sha256": "..."},
  {"role": "suite", "package": "com.ripostelabs.clock", "version_code": 3, "path":
   "suite/com.ripostelabs.clock.apk", "...": "..."}]}
```

A file that does not parse (half copied, not an APK) is left out. Only `.apk` names in those
two folders are served. `cert_sha256` is for people: the car checks the signer against its own
pins. What the car does with this is in the launcher's `UPLINK.md`.

## Tests

- `python3 -m unittest -v` in this folder: the server end to end on localhost with a fake
  `whois`. It covers resume, conflicts, verify, quotas, identity, wants and models, and
  (`test_releases.py`, on APKs built byte by byte) the release manifest and its files.
- `os/test-uplink.sh`: the init script with fakes for the device. It covers the owner gate,
  the sha256 pin, the daemon flags, and that the key file goes only after a login.
