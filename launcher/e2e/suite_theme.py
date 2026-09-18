"""Do the suite apps follow the launcher's theme? Screenshot each under two themes and compare.

The 28 apps paint the launcher's palette through its theme provider at start. An app that
never repaints shows the same dominant colour under Midnight (dark) and Riposte (day, cream).
Runs on a farm instance with the `farm` launcher and the suite installed:

    CARSIM_SERIAL=emulator-5556 python suite_theme.py emulator-5556

Output: JSON on stdout (per app: dominant colour per theme, verdict), PNGs in ./theme-shots/.
"""
import json
import os
import re
import subprocess
import sys
import time

from PIL import Image

os.environ.setdefault("CARSIM_SERIAL", sys.argv[1])
import test_layout_sweep as L  # noqa: E402

SERIAL = sys.argv[1]
LAUNCHER_PKG = "com.ripostelabs.carlauncher"
THEMES = ("Midnight", "Riposte")   # dark navy vs day cream; the two ends of the built-in list
THEMES_ICON = "Themes"
SHOTS = "theme-shots"
SETTLE = 7.0     # the farm cold-starts a sensor app in ~3.5 s; 3 s screenshots caught the splash
# Two dominant colours closer than this (max channel delta) count as "did not change".
SAME_COLOUR_DELTA = 24


def adb(*a, timeout=60):
    return subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout).stdout


def packages():
    out = adb("shell", "pm", "list", "packages")
    return sorted(p.split(":", 1)[1] for p in out.split()
                  if p.startswith("package:com.ripostelabs.") and LAUNCHER_PKG not in p)


def top_activity():
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/", out)
    return m.group(1) if m else ""


def grant_all(pkg):
    out = adb("shell", "dumpsys", "package", pkg)
    block = out.split("requested permissions:", 1)[-1].split("install permissions:", 1)[0]
    for perm in re.findall(r"(android\.permission\.[A-Z_]+)", block):
        adb("shell", "pm", "grant", pkg, perm)


def launch(pkg):
    adb("shell", "am", "force-stop", pkg)
    adb("shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(SETTLE)
    for _ in range(4):
        top = top_activity()
        if top == pkg:
            return True
        if "permissioncontroller" in top:
            dump = L.ui_dump()
            box = next((b for t, b in L.clickables(dump) if t in ("While using the app", "Allow", "Only this time", "OK")), None)
            if box:
                L.tap_wait(box)
                continue
        L.back()
    return top_activity() == pkg


def set_theme(name):
    """Home → Themes → drag until the card is on screen → tap it."""
    L.home()
    h = L.ui_dump()
    icon = next((b for t, b in L.clickables(h) if t == THEMES_ICON), None)
    assert icon, f"no Themes icon on Home: {L.texts(h)}"
    L.tap_wait(icon)
    for _ in range(6):
        dump = L.ui_dump()
        card = next((n["box"] for n in L.nodes(dump) if n.get("text") == name), None)
        if card and not L.at_bottom_edge(card):
            L.tap_wait(card)
            L.home()
            return
        L.scroll_down()
    raise AssertionError(f"theme card {name!r} not found")


def shoot(pkg, theme):
    os.makedirs(SHOTS, exist_ok=True)
    path = f"{SHOTS}/{pkg}.{theme}.png"
    adb("shell", "screencap", "-p", "/data/local/tmp/t.png")
    adb("pull", "/data/local/tmp/t.png", path)
    return path


def dominant(path):
    im = Image.open(path).convert("RGB").resize((192, 72))
    colours = im.getcolors(192 * 72)
    return max(colours)[1]


def same(a, b):
    return max(abs(x - y) for x, y in zip(a, b)) < SAME_COLOUR_DELTA


only = set(sys.argv[2:])   # optional package filter for a re-check
shots = {}
for theme in THEMES:
    set_theme(theme)
    shots[theme] = {}
    for pkg in packages():
        if only and pkg not in only:
            continue
        grant_all(pkg)
        if not launch(pkg):
            shots[theme][pkg] = None
            print(f"{theme} {pkg}: did not open", file=sys.stderr)
            continue
        shots[theme][pkg] = dominant(shoot(pkg, theme))
        adb("shell", "am", "force-stop", pkg)
        print(f"{theme} {pkg}: {shots[theme][pkg]}", file=sys.stderr)

report = {}
for pkg in packages():
    if only and pkg not in only:
        continue
    a, b = shots[THEMES[0]].get(pkg), shots[THEMES[1]].get(pkg)
    verdict = "unknown" if a is None or b is None else ("STUCK" if same(a, b) else "follows")
    report[pkg] = {THEMES[0]: a, THEMES[1]: b, "verdict": verdict}
print(json.dumps(report, indent=1))
