"""Every installed suite app starts and stays up.

    pytest ──adb──▶ am start <launcher activity> ──▶ resumed? ──▶ no FATAL EXCEPTION for it?

The suite is 28 packages built without Gradle and installed by a timer nobody watches; a
launch crash in one of them shows up as a driver's tap doing nothing. One case per installed
`com.ripostelabs.*` package except the launcher itself. Permissions are left as they are: a
permission dialog on top is a fine first screen, a crash is not.

Environment: as test_carsim.py (`headunit carsim e2e`); only the adb serial is needed.
"""
from __future__ import annotations

import re
import time

import pytest

from test_carsim import SERIAL, adb

LAUNCHER = "com.ripostelabs.carlauncher"
PREFIX = "com.ripostelabs."
SETTLE_S = 4.0
RESUME_S = 10.0

pytestmark = pytest.mark.carsim


def installed_suite() -> list[str]:
    if not SERIAL:
        return []
    out = adb("shell", "pm", "list", "packages", PREFIX)
    pkgs = sorted(re.findall(rf"package:({re.escape(PREFIX)}[a-z]+)$", out, re.M))
    return [p for p in pkgs if p != LAUNCHER]


def launcher_activity(package: str) -> str | None:
    out = adb("shell", "cmd", "package", "resolve-activity", "--brief",
              "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", package)
    m = re.search(rf"^({re.escape(package)}/\S+)$", out, re.M)
    return m.group(1) if m else None


def resumed_package() -> str | None:
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"ResumedActivity: ActivityRecord\{[^}]*\s(\S+)/", out)
    return m.group(1) if m else None


def fatal_for(package: str) -> str | None:
    out = adb("logcat", "-d", "-s", "AndroidRuntime:E")
    m = re.search(rf"FATAL EXCEPTION.*\n.*Process: {re.escape(package)},.*\n((?:.*\n){{0,3}})", out)
    return m.group(0) if m else None


@pytest.mark.parametrize("package", installed_suite(), ids=lambda p: p.rsplit(".", 1)[-1])
def test_app_starts_and_stays_up(package: str):
    activity = launcher_activity(package)
    if not activity:
        pytest.skip(f"{package}: no launcher activity (a service-only member)")

    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "force-stop", package)
    adb("shell", "logcat", "-c")
    adb("shell", "am", "start", "-n", activity)
    time.sleep(SETTLE_S)

    # Resumed, or behind a permission dialog it just raised: both are the app being up. Anything
    # else in front (Home, a crash dialog) is a failure and the FATAL, if any, is the message.
    end = time.monotonic() + RESUME_S
    front = None
    while time.monotonic() < end:
        front = resumed_package()
        if front in (package, "com.google.android.permissioncontroller", "com.android.permissioncontroller"):
            break
        time.sleep(1)
    fatal = fatal_for(package)
    assert fatal is None, f"{package} crashed on launch:\n{fatal}"
    assert front in (package, "com.google.android.permissioncontroller", "com.android.permissioncontroller"), \
        f"{package} is not in front after launch; {front} is"
