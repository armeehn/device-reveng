"""Cold-start time of every suite app on a farm instance, best of N `am start -S -W` runs.

The absolute numbers are the emulator's (swiftshader, no GPU); the spread between apps is
what matters, and even that with care: on 2026-09-18 the three slowest (soundmeter 3.8 s,
compass 2.9, lamp 2.7 against a 1.6 s median) were the three custom-drawn gauges, and moving
soundmeter's recorder off the main thread changed nothing, so the first frame's shader
compile under swiftshader is the likely cost. Measure on the unit before acting on a rank.

    python suite_startup.py emulator-5556 [runs]
"""
import json
import re
import statistics
import subprocess
import sys

SERIAL = sys.argv[1]
RUNS = int(sys.argv[2]) if len(sys.argv) > 2 else 3
LAUNCHER_PKG = "com.ripostelabs.carlauncher"
SLOW_FACTOR = 1.5   # above this multiple of the median an app is flagged


def adb(*a, timeout=90):
    return subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout).stdout


def packages():
    out = adb("shell", "pm", "list", "packages")
    return sorted(p.split(":", 1)[1] for p in out.split()
                  if p.startswith("package:com.ripostelabs.") and LAUNCHER_PKG not in p)


def launcher_activity(pkg):
    out = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", pkg)
    return out.strip().splitlines()[-1].strip()


def grant_all(pkg):
    """Runtime permissions first: a dialog left on top makes the next start a 0 ms bring-to-front."""
    out = adb("shell", "dumpsys", "package", pkg)
    block = out.split("requested permissions:", 1)[-1].split("install permissions:", 1)[0]
    for perm in re.findall(r"(android\.permission\.[A-Z_]+)", block):
        adb("shell", "pm", "grant", pkg, perm)


def cold_start_ms(activity, pkg):
    out = adb("shell", "am", "start", "-S", "-W", "-n", activity)   # -S: stop the process first
    if "LaunchState: COLD" not in out:
        return None   # a warm or hot start measures nothing; -S is not always enough
    m = re.search(r"TotalTime:\s+(\d+)", out)
    return int(m.group(1)) if m else None


results = {}
for pkg in packages():
    act = launcher_activity(pkg)
    grant_all(pkg)
    times = [t for t in (cold_start_ms(act, pkg) for _ in range(RUNS)) if t is not None]
    adb("shell", "am", "force-stop", pkg)
    results[pkg] = min(times) if times else None
    print(f"{pkg}: {times}", file=sys.stderr)

valid = [t for t in results.values() if t]
median = statistics.median(valid) if valid else 0
report = {pkg: {"best_ms": t, "slow": bool(t and median and t > SLOW_FACTOR * median)} for pkg, t in results.items()}
report["_median_ms"] = median
print(json.dumps(report, indent=1))
