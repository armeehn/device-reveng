"""The suite's PermissionGate, driven the way a driver would: deny, tap the app's grant
control, allow (rav4-apps Track D, RAV4-66).

    pytest ──adb──▶ pm revoke ──▶ am start ──▶ system dialog ──▶ tap "Don't allow"
       │                                                            │
       │      app shows its grant control ◀───── PermissionGate.onDenied
       │      tap it ──▶ dialog again ──▶ tap allow ──▶ grant control gone, dumpsys granted=true

One case per migrated app. Each skips when the app is not installed on the instance, so the
file runs anywhere the farm does; install the suite's app-debug.apk files to make them count.
Apps whose ask has no grant control (gps, speedometer, weather, lamp, projection) get the
shorter loop: deny, and the status line the app promised must be on screen.

Environment: as test_carsim.py (`headunit carsim e2e`), but no carsim is needed; only the
CARSIM_SERIAL adb serial.
"""
from __future__ import annotations

import re
import time

import pytest

from test_carsim import SERIAL, adb, ui_dump, wait_for

SCREEN_S = 10.0
RE_ASK_S = 4.0           # a location app asks again on the resume after the dialog

# The dialog's buttons as the AVD words them. Location asks add a precise/approximate variant.
DENY = ("Don’t allow", "Keep approximate location")
ALLOW = ("Allow", "While using the app", "Change to precise location")

BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

pytestmark = pytest.mark.carsim


class App:
    def __init__(
        self,
        package: str,
        activity: str,
        *permissions: str,
        grant_id: str = "grant",
        grant_text: str | None = None,
        denied_text: str | None = None,
        arm_text: str | None = None,
    ):
        self.package = package
        self.activity = activity
        self.permissions = permissions
        self.grant_id = grant_id
        self.grant_text = grant_text          # a grant control with no resource id (calendar)
        self.denied_text = denied_text        # apps with no grant control: what a denial shows
        self.arm_text = arm_text              # a control to tap before the ask comes (projection)

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
    App("com.ripostelabs.calendar", ".MainActivity", "android.permission.READ_CALENDAR",
        grant_text="Grant access"),
]

# No grant control: the denial is a line of text, and the ask comes again on the next resume.
STATUS_APPS = [
    # The gauge screens redraw continuously, so `uiautomator dump` never reaches idle there and
    # their status text cannot be read; for these the assert is "still up, still denied".
    # Coarse is revoked with fine: with coarse held, the dialog offers "Keep approximate
    # location", which does not count as a denial, and an app that asks on every resume then
    # never leaves the dialog. Plain "Don't allow" twice is what Android stops asking after.
    App("com.ripostelabs.gps", ".GpsActivity",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    App("com.ripostelabs.speedometer", ".SpeedometerActivity",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
    # Its status line is replaced as soon as the fallback city loads: the city is the evidence.
    App("com.ripostelabs.weather", ".WeatherActivity", "android.permission.ACCESS_COARSE_LOCATION",
        denied_text="Kelowna, BC"),
    App("com.ripostelabs.lamp", ".LampActivity",
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
        denied_text="Bluetooth permission refused"),
    App("com.ripostelabs.projection", ".MainActivity",
        "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
        "android.permission.NEARBY_WIFI_DEVICES",
        denied_text="wireless: permission denied", arm_text="WIRELESS"),
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


def poll(seconds: float, probe):
    """Like wait_for, but a miss is an answer (None), not a failure."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        found = probe()
        if found:
            return found
        time.sleep(1)
    return None


def tap(at: tuple[int, int]) -> None:
    adb("shell", "input", "tap", str(at[0]), str(at[1]))


DIALOG_TITLE = "Allow "   # every runtime-permission dialog title starts this way


def dialog_button(values: tuple[str, ...]):
    """A dialog button's centre. The location dialog is taller than the 720 px panel and its
    "Don't allow" sits below the edge; when the title is up but the button is not, scroll the
    dialog and look again."""
    def probe():
        dump = ui_dump()
        at = centre_of(dump, "text", values)
        if at or DIALOG_TITLE not in dump:
            return at
        adb("shell", "input", "swipe", "960", "650", "960", "300", "300")
        return centre_of(ui_dump(), "text", values)
    return probe


def grant_control(app: App):
    if app.grant_text:
        return lambda: centre_of(ui_dump(), "text", (app.grant_text,))
    return lambda: centre_of(ui_dump(), "resource-id", (f"{app.package}:id/{app.grant_id}",))


def text_on_screen(fragment: str):
    return lambda: fragment in ui_dump() or None


def resumed(app: App) -> bool:
    out = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"ResumedActivity: ActivityRecord\{[^}]*\s(\S+)/", out)
    return bool(m) and m.group(1) == app.package


def granted(app: App) -> bool:
    out = adb("shell", "dumpsys", "package", app.package)
    return all(re.search(rf"{re.escape(p)}: granted=true", out) for p in app.permissions)


def revoke_and_start(app: App) -> None:
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if app.package not in adb("shell", "pm", "list", "packages", app.package):
        pytest.skip(f"{app.package} is not installed on the instance")

    # The previous case's app may still be in front (the projection app with its AP up);
    # start every case from Home so the first dump belongs to this app.
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "force-stop", app.package)
    for permission in app.permissions:
        adb("shell", "pm", "revoke", app.package, permission)
        # Two denials make Android 13 stop asking (USER_FIXED) and requestPermissions() answers
        # "denied" with no dialog. A revoke does not clear that; this does, so the case is
        # repeatable on an instance that has already been through it.
        adb("shell", "pm", "clear-permission-flags", app.package, permission, "user-fixed", "user-set")
    adb("shell", "am", "start", "-n", f"{app.package}/{app.activity}")
    if app.arm_text:
        tap(wait_for(f"{app}: its {app.arm_text} control", SCREEN_S, lambda: centre_of(ui_dump(), "text", (app.arm_text,))))


@pytest.mark.parametrize("app", APPS, ids=repr)
def test_deny_grant_allow(app: App):
    revoke_and_start(app)

    # 1. The ask comes up on its own; deny it.
    tap(wait_for(f"{app}: the permission dialog", SCREEN_S, dialog_button(DENY)))

    # 2. Denied: the app's grant control is on screen. Tap it: the dialog comes back.
    tap(wait_for(f"{app}: its grant control after a denial", SCREEN_S, grant_control(app)))
    tap(wait_for(f"{app}: the dialog again from the grant control", SCREEN_S, dialog_button(ALLOW)))

    # 3. Allowed: the control is gone and the system agrees.
    wait_for(f"{app}: grant control gone after allow", SCREEN_S,
             lambda: None if grant_control(app)() else True)
    assert granted(app), f"{app}: {app.permissions} not all granted after allow"


@pytest.mark.parametrize("app", STATUS_APPS, ids=repr)
def test_deny_shows_status(app: App):
    revoke_and_start(app)

    # The location apps ask again on the resume that follows the dialog, so the status line
    # sits behind a second ask; the second denial is the one Android stops asking after.
    tap(wait_for(f"{app}: the permission dialog", SCREEN_S, dialog_button(DENY)))
    # Each tap comes from a fresh dump, so it lands on a dialog that is there, never on the
    # app behind one that just closed.
    again = poll(RE_ASK_S, dialog_button(DENY))
    if again:
        tap(again)
    if app.denied_text:
        wait_for(f"{app}: '{app.denied_text}' after a denial", SCREEN_S, text_on_screen(app.denied_text))
    else:
        wait_for(f"{app}: still in the foreground after a denial", SCREEN_S, lambda: resumed(app) or None)
    assert not granted(app), f"{app}: granted after a denial"
