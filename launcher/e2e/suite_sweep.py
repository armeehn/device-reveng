"""Sweep every com.ripostelabs.* suite app on a farm instance: launch, layout checks, one tap deep, crashes.

Not a pytest case: the suite is another repo (rav4-apps) and its 28 APKs must be installed on the
instance first (the served set from launcher.hq, plus the launcher's `farm` variant so the apps
find its providers). Runs next to test_layout_sweep.py and reuses its parsing and rules. Output:
a JSON report on stdout, one PNG per app main screen in ./shots/, progress on stderr.

    CARSIM_SERIAL=emulator-5556 python suite_sweep.py emulator-5556 [package ...]

First run 2026-09-17: 28/28 opened, 0 crashes, 7 real defects -> rav4-apps #39 and #41.
"""
import json
import os
import re
import subprocess
import sys
import time

os.environ.setdefault("CARSIM_SERIAL", sys.argv[1])
import test_layout_sweep as L  # noqa: E402

SERIAL = sys.argv[1]
LAUNCHER_PKG = "com.ripostelabs.carlauncher"
MAX_TAPS = 12            # per app: bounded walk, the main screen's first clickables
SETTLE = 2.5
SHOTS = "shots"


def adb(*a, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout).stdout


def packages():
    out = adb("shell", "pm", "list", "packages")
    return sorted(p.split(":", 1)[1] for p in out.split()
                  if p.startswith("package:com.ripostelabs.") and LAUNCHER_PKG not in p)


def defects_for(dump, pkg):
    ns = [n for n in L.nodes(dump) if n.get("package", "") == pkg]
    found = []
    for n in ns:
        x, y, w, h = n["box"]
        if L.at_bottom_edge(n["box"]):
            continue
        if n.get("clickable") == "true" and (w < L.MIN_TAP_PX or h < L.MIN_TAP_PX):
            found.append(f"tap target {L.label(n, ns)!r} is {w}x{h} px at ({x},{y})")
        if n.get("text") and 0 < h < L.MIN_TEXT_PX:
            found.append(f"text {n['text']!r} clipped to {h} px at ({x},{y})")
    return found


def crashes(pkg):
    out = adb("logcat", "-d", "-s", "AndroidRuntime:E")
    hits = [l for l in out.splitlines() if pkg in l and ("Process:" in l or "FATAL" in l)]
    return hits[:3]


PERM_BUTTONS = ("While using the app", "Allow", "Only this time", "OK")
PERM_CONTROLLER = "permissioncontroller"


def grant_all(pkg):
    """pm grant every requested permission; non-runtime ones fail harmlessly."""
    out = adb("shell", "dumpsys", "package", pkg)
    block = out.split("requested permissions:", 1)[-1].split("install permissions:", 1)[0]
    for perm in re.findall(r"(android\.permission\.[A-Z_]+)", block):
        adb("shell", "pm", "grant", pkg, perm)


def dismiss_permission_dialogs():
    """A dialog pm grant could not pre-empt (special permissions): press its allow button."""
    for _ in range(4):
        if PERM_CONTROLLER not in top_activity():
            return
        dump = L.ui_dump()
        box = next((b for t, b in L.clickables(dump) if t in PERM_BUTTONS), None)
        if box is None:
            L.back()
        else:
            L.tap_wait(box)


def top_activity():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/", out)
    return m.group(1) if m else ""


def launch(pkg):
    adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(SETTLE)
    dismiss_permission_dialogs()
    # force-stop does not clear another package's activity from the app's task (the bluetooth
    # app opens system Bluetooth settings), so a relaunch can surface that page instead.
    for _ in range(3):
        top = top_activity()
        if top == pkg or PERM_CONTROLLER in top:
            return
        L.back()


def on_top(pkg):
    return top_activity() == pkg


def shoot(pkg):
    os.makedirs(SHOTS, exist_ok=True)
    adb("shell", "screencap", "-p", f"/data/local/tmp/{pkg}.png")
    adb("pull", f"/data/local/tmp/{pkg}.png", f"{SHOTS}/{pkg}.png")


report = {}
adb("logcat", "-c")
only = set(sys.argv[2:])   # optional package filter for a re-check
for pkg in packages():
    if only and pkg not in only:
        continue
    entry = {"opened": False, "defects": {}, "crashes": [], "screens": 0}
    grant_all(pkg)
    launch(pkg)
    if not on_top(pkg):
        entry["crashes"] = crashes(pkg)
        report[pkg] = entry
        print(f"{pkg}: did not come to the front", file=sys.stderr)
        adb("shell", "am", "force-stop", pkg)
        continue
    entry["opened"] = True
    main = L.ui_dump()
    shoot(pkg)
    d = defects_for(main, pkg)
    if d:
        entry["defects"]["main"] = d
    entry["screens"] = 1
    targets = [(t, b) for t, b in L.clickables(main) if not L.at_bottom_edge(b)][:MAX_TAPS]
    for name, box in targets:
        L.tap_wait(box)
        dump = L.ui_dump()
        if not on_top(pkg):
            # left the app (an intent to Maps, a share sheet): come back, do not judge
            launch(pkg)
            continue
        if L.texts(dump) != L.texts(main):
            entry["screens"] += 1
            d = defects_for(dump, pkg)
            if d:
                entry["defects"][name] = d
            L.back()
            time.sleep(0.6)
            if not on_top(pkg):
                launch(pkg)
    entry["crashes"] = crashes(pkg)
    adb("shell", "am", "force-stop", pkg)
    report[pkg] = entry
    print(f"{pkg}: screens={entry['screens']} defects={sum(len(v) for v in entry['defects'].values())} crashes={len(entry['crashes'])}", file=sys.stderr)

print(json.dumps(report, indent=1))
