#!/usr/bin/env python3
"""Read a riposte-diag recording and say what the car install did.

    check.py DIR_OR_FILE...        summary + verdicts, exit 1 when an invariant fails

Input: diag-*.log files from /data/misc/riposte/diag (rav4 car diag pull), lines of
`ts=<epoch> up=<s> probe=<name> k=v ...` (riposte-diag.sh). The summary lists each probe's
first and last reading and every transition of the fields that matter; the verdicts are the
invariants of a healthy car install, so a CI run on a pulled recording (or on the fixture)
turns a 40-minute drive into one screen.
"""
import glob
import os
import sys

# The fields whose changes tell the story; everything else is context.
WATCHED = {
    "mcu": ("tty", "bridge", "listen", "established"),
    "usb": ("mode", "state", "devices", "tty"),
    "audio": ("players", "zlink", "ap_up"),
    "launcher": ("pid", "boot_completed", "bench"),
    "bt": ("enabled",),
    "car": ("video",),
}
# A CANable 2.0 enumerates as this vendor:product (gs_usb / candleLight).
CANABLE_IDS = ("1d50:606f", "16d0:117e")
SETTLE_S = 120


def parse(path):
    rows = []
    with open(path, errors="replace") as f:
        for line in f:
            fields = {}
            for tok in line.split():
                if "=" in tok:
                    k, v = tok.split("=", 1)
                    fields[k] = v
            if "probe" in fields:
                rows.append(fields)
    return rows


def transitions(rows, probe):
    keys = WATCHED.get(probe, ())
    seen = None
    out = []
    for r in rows:
        if r["probe"] != probe:
            continue
        cur = tuple(r.get(k, "") for k in keys)
        if cur != seen:
            out.append((int(r.get("up", 0)), dict(zip(keys, cur))))
            seen = cur
    return out


def verdicts(rows):
    """Invariants after the boot has settled (SETTLE_S of uptime)."""
    settled = [r for r in rows if int(r.get("up", 0)) >= SETTLE_S]
    by = lambda p: [r for r in settled if r["probe"] == p]
    out = []

    def check(name, ok, detail):
        out.append((name, ok, detail))

    launcher = by("launcher")
    check("launcher up", bool(launcher) and all(r.get("pid", "0") != "0" for r in launcher),
          f"{sum(1 for r in launcher if r.get('pid', '0') == '0')} readings without a launcher process")
    bench = launcher[-1].get("bench", "") if launcher else ""
    check("car build (not bench)", bench != "1", f"ro.riposte.os.bench={bench or 'unset'}")

    mcu = by("mcu")
    check("mcu port present", bool(mcu) and all(r.get("tty") == "1" for r in mcu),
          f"{sum(1 for r in mcu if r.get('tty') != '1')} readings without /dev/ttyHS1")
    check("mcu bridge listening or linked", bool(mcu) and all(
        r.get("listen", "0") != "0" or r.get("established", "0") != "0" for r in mcu),
        f"{sum(1 for r in mcu if r.get('listen', '0') == '0' and r.get('established', '0') == '0')} readings with nothing on :5588")
    check("launcher holds the mcu", bool(mcu) and mcu[-1].get("established", "0") != "0",
          f"last reading established={mcu[-1].get('established') if mcu else 'none'}")

    usb = by("usb")
    check("usb port is host", bool(usb) and all(r.get("mode") == "host" for r in usb),
          f"modes seen: {sorted({r.get('mode', '') for r in usb})}")
    check("canable enumerated", bool(usb) and any(
        any(i in r.get("devices", "") for i in CANABLE_IDS) for r in usb),
        "no CANable vendor:product in any reading")

    audio = by("audio")
    check("carplay daemon alive", bool(audio) and all(r.get("zlink", "") not in ("", "0") for r in audio),
          f"{sum(1 for r in audio if r.get('zlink', '') in ('', '0'))} readings without z-link")
    return out


def main(paths):
    files = []
    for p in paths:
        files += sorted(glob.glob(os.path.join(p, "diag-*.log"))) if os.path.isdir(p) else [p]
    if not files:
        print("no diag-*.log found", file=sys.stderr)
        return 2
    failed = False
    for path in files:
        rows = parse(path)
        if not rows:
            print(f"{path}: empty")
            continue
        span = int(rows[-1].get("up", 0)) - int(rows[0].get("up", 0))
        print(f"== {os.path.basename(path)}: {len(rows)} readings over {span} s of uptime")
        for probe in WATCHED:
            for up, fields in transitions(rows, probe):
                print(f"  {up:6d}s {probe:9s} " + " ".join(f"{k}={v}" for k, v in fields.items() if v != ""))
        for name, ok, detail in verdicts(rows):
            print(f"  [{'ok' if ok else 'FAIL'}] {name}: {detail}")
            failed |= not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["."]))
