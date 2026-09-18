"""Monkey-stress every suite app on a farm instance and collect the crashes it reports.

    python suite_monkey.py emulator-5556 [events] [seed]

Package-restricted, no system keys, throttled so the emulator keeps up. Output: JSON per app
(crash lines, ANRs), progress on stderr. Deterministic for a seed, so a crash reproduces; an
ANR on an app's very first launch after install did not (bluetooth, 2026-09-18, 0 of 4
re-runs), so treat a lone NOT RESPONDING as a lead, a CRASH with a cause as a bug.
"""
import json
import re
import subprocess
import sys

SERIAL = sys.argv[1]
EVENTS = int(sys.argv[2]) if len(sys.argv) > 2 else 500
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 42
LAUNCHER_PKG = "com.ripostelabs.carlauncher"
THROTTLE_MS = 60


def adb(*a, timeout=300):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout)
    return r.stdout + r.stderr


def packages():
    out = adb("shell", "pm", "list", "packages")
    return sorted(p.split(":", 1)[1] for p in out.split()
                  if p.startswith("package:com.ripostelabs.") and LAUNCHER_PKG not in p)


def grant_all(pkg):
    out = adb("shell", "dumpsys", "package", pkg)
    block = out.split("requested permissions:", 1)[-1].split("install permissions:", 1)[0]
    for perm in re.findall(r"(android\.permission\.[A-Z_]+)", block):
        adb("shell", "pm", "grant", pkg, perm)


report = {}
for pkg in packages():
    grant_all(pkg)
    adb("shell", "am", "force-stop", pkg)
    adb("logcat", "-c")
    out = adb("shell", "monkey", "-p", pkg, "-s", str(SEED), "--throttle", str(THROTTLE_MS),
              "--pct-syskeys", "0", "--pct-anyevent", "0", "--pct-appswitch", "0",
              "--ignore-security-exceptions", "-v", str(EVENTS))
    crash = [l for l in out.splitlines() if l.startswith("// CRASH:") or l.startswith("// NOT RESPONDING")]
    cause = [l.strip() for l in out.splitlines() if l.startswith("// Long Msg") or l.startswith("// Short Msg")]
    stack = [l for l in adb("logcat", "-d", "-s", "AndroidRuntime:E").splitlines()
             if "at com.ripostelabs" in l][:3]
    adb("shell", "am", "force-stop", pkg)
    report[pkg] = {"crash": crash, "cause": cause, "stack": stack}
    print(f"{pkg}: {'CRASH ' + (cause[0] if cause else '') if crash else 'ok'}", file=sys.stderr)

print(json.dumps(report, indent=1))
