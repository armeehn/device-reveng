"""The suite's PermissionGate, driven the way a driver would: deny, tap the app's grant
control, allow (rav4-apps Track D, RAV4-66).

    pytest ──adb──▶ pm revoke ──▶ am start ──▶ system dialog ──▶ tap "Don't allow"
       │                                                            │
       │      app shows its grant control ◀───── PermissionGate.onDenied
       │      tap it ──▶ dialog again ──▶ tap allow ──▶ grant control gone, dumpsys granted=true

One case per migrated app. Each skips when the app is not installed on the instance, so the
file runs anywhere the farm does; install the suite's app-debug.apk files to make them count.
Apps whose ask has no grant control (gps, speedometer, weather, lamp, projection) are not here:
their denial path is a status line, checked by hand in their PRs.

Environment: as test_carsim.py (`headunit carsim e2e`), but no carsim is needed; only the
CARSIM_SERIAL adb serial.
"""
from __future__ import annotations

import re
import time

import pytest

from test_carsim import SERIAL, adb, ui_dump, wait_for

SCREEN_S = 10.0

# The dialog's buttons as the AVD words them. Location asks add a precise/approximate variant.
DENY = ("Don’t allow", "Keep approximate location")
ALLOW = ("Allow", "While using the app", "Change to precise location")

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

pytestmark = pytest.mark.carsim


class App:
    def __init__(self, package: str, activity: str, *permissions: str, grant_id: str = "grant"):
        self.package = package
        self.activity = activity
        self.permissions = permissions
        self.grant_id = grant_id

    def __repr__(self) -> str:
        return self.package.rsplit(".", 1)[-1]


APPS = [
    App("com.ripostelabs.recorder", ".MainActivity", "android.permission.RECORD_AUDIO"),
    App("com.ripostelabs.music", ".MainActivity", "android.permission.READ_MEDIA_AUDIO"),
    App("com.ripostelabs.video", ".ListActivity", "android.permission.READ_MEDIA_VIDEO"),
    App("com.ripostelabs.contacts", ".MainActivity", "android.permission.READ_CONTACTS"),
    App("com.ripostelabs.soundmeter", ".MainActivity", "android.permission.RECORD_AUDIO"),
    App("com.ripostelabs.bluetooth", ".MainActivity",
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN"),
    App("com.ripostelabs.photos", "com.szchoiceway.photoreader.activity.RunActivity",
        "android.permission.READ_MEDIA_IMAGES"),
]


def centre_of(dump: str, attr: str, values: tuple[str, ...]) -> tuple[int, int] | None:
    for v in values:
        m = re.search(rf'<node[^>]*{attr}="{re.escape(v)}"[^>]*>', dump)
        if not m:
            continue
        b = BOUNDS.search(m.group(0))
        if b:
            x1, y1, x2, y2 = (int(n) for n in b.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(at: tuple[int, int]) -> None:
    adb("shell", "input", "tap", str(at[0]), str(at[1]))


def dialog_button(values: tuple[str, ...]):
    return lambda: centre_of(ui_dump(), "text", values)


def grant_control(app: App):
    return lambda: centre_of(ui_dump(), "resource-id", (f"{app.package}:id/{app.grant_id}",))


def granted(app: App) -> bool:
    out = adb("shell", "dumpsys", "package", app.package)
    return all(re.search(rf"{re.escape(p)}: granted=true", out) for p in app.permissions)


@pytest.mark.parametrize("app", APPS, ids=repr)
def test_deny_grant_allow(app: App):
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if app.package not in adb("shell", "pm", "list", "packages", app.package):
        pytest.skip(f"{app.package} is not installed on the instance")

    adb("shell", "am", "force-stop", app.package)
    for permission in app.permissions:
        adb("shell", "pm", "revoke", app.package, permission)
        # Two denials make Android 13 stop asking (USER_FIXED) and requestPermissions() answers
        # "denied" with no dialog. A revoke does not clear that; this does, so the case is
        # repeatable on an instance that has already been through it.
        adb("shell", "pm", "clear-permission-flags", app.package, permission, "user-fixed", "user-set")
    adb("shell", "am", "start", "-n", f"{app.package}/{app.activity}")

    # 1. The ask comes up on its own; deny it.
    tap(wait_for(f"{app}: the permission dialog", SCREEN_S, dialog_button(DENY)))

    # 2. Denied: the app's grant control is on screen. Tap it: the dialog comes back.
    tap(wait_for(f"{app}: its grant control after a denial", SCREEN_S, grant_control(app)))
    tap(wait_for(f"{app}: the dialog again from the grant control", SCREEN_S, dialog_button(ALLOW)))

    # 3. Allowed: the control is gone and the system agrees.
    wait_for(f"{app}: grant control gone after allow", SCREEN_S,
             lambda: None if grant_control(app)() else True)
    assert granted(app), f"{app}: {app.permissions} not all granted after allow"
