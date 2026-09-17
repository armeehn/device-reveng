"""The handbrake gate on Riposte OS 0.2, end to end: carsim's `brake` scenario, the launcher's
SysVar mirror, and the suite's video player covering its picture (RAV4-98).

    pytest ──subprocess──▶ carsim.py ──TCP──▶ QEMU virtio port ──▶ McuOwner ──▶ SysVarMirror
       │                                                                             │
       │                     com.ripostelabs.video BrakeGate ◀── content://…sysvar ◀─┘
       └────adb content query / uiautomator dump ◀──────────────┘

Two readers of the same row. `content query` proves the launcher serves it; the uiautomator dump
of the player proves the suite read it: `brake_panel` is GONE (absent from the dump) while the
brake is on and VISIBLE while it is off.

Environment: as test_carsim.py (`headunit carsim e2e`). The player cases need the suite's
`com.ripostelabs.video` installed and the launcher under its RELEASE id, which on the x86 farm is
the `farm` build (`HEADUNIT_VARIANT=farm headunit build <wt>`): the suite queries
`com.ripostelabs.carlauncher.sysvar`, and a `.debug` launcher serves `.debug.sysvar` instead.
They skip otherwise; the row cases run against any variant.
"""
from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

from test_carsim import CARSIM, CAN, MCU, PACKAGE, SERIAL, UNHANDLED_ACK, adb, logcat, ui_dump, wait_for
from test_carsim_radio import centre, tap

# carsim.py SCENARIOS["brake"], in seconds. The launcher opens the port ~17 s after the restart,
# so nothing that matters happens before 25 s.
BRAKE_ON_S = 25.0
BRAKE_OFF_S = 55.0
BRAKE_ON_AGAIN_S = 85.0
HANDSHAKE_S = 20.0
SCREEN_S = 10.0          # a `71` → mirror → notifyChange → BrakeGate re-read → layout pass
ROW_S = 10.0

# The row as the vendor named it (SysVarMirror.kt); "1" gates, "0" opens.
KEY = "Sys_CurBreakSate"
GATED = "1"
OPEN = "0"
ROW = re.compile(rf"keyname={KEY}, keyvalue=(\d)")

RELEASE_ID = "com.ripostelabs.carlauncher"
VIDEO = "com.ripostelabs.video"
PLAYER = f"{VIDEO}/.PlayerActivity"
COVER = f'resource-id="{VIDEO}:id/brake_panel"'
CLIP = Path(__file__).parent / "assets" / "brake-gate.mp4"     # 2 min of black, 64x64, ~5 KB
CLIP_ON_DEVICE = "/sdcard/Movies/brake-gate.mp4"
MEDIA_VIDEOS = "content://media/external/video/media"
MEDIA_ID = re.compile(rf"_id=(\d+), _display_name={re.escape(CLIP.name)}")   # `--where` throws here

pytestmark = pytest.mark.carsim


def sysvar_uri() -> str:
    return f"content://{PACKAGE}.sysvar/SysVar"


def brake_row() -> str | None:
    """The mirror's keyvalue for the brake row, or None while the launcher has not served it."""
    out = adb("shell", "content", "query", "--uri", sysvar_uri())
    m = ROW.search(out)
    return m.group(1) if m else None


def row_is(value: str):
    return lambda: brake_row() == value or None


def cover_shown() -> bool:
    """uiautomator dumps only what is laid out: a GONE brake_panel is simply absent."""
    return COVER in ui_dump()


def cover_is(shown: bool):
    return lambda: cover_shown() == shown or None


@pytest.fixture(scope="module")
def brake():
    """Restart the launcher against carsim's `brake` scenario; yield the run's start time."""
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if adb("shell", "getprop", "ro.riposte.os.car_owner").strip() != "1":
        pytest.skip("instance is not wired for the car owner (`headunit carsim <N> ...` first)")

    log = Path("carsim-brake.log")
    log.unlink(missing_ok=True)
    proc = subprocess.Popen(
        [sys.executable, str(CARSIM), "brake", "--mcu", MCU, "--can", CAN, "--log", str(log), "--hold", "120"],
    )
    start = time.monotonic()
    adb("shell", "logcat", "-c")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")

    dump = wait_for("the launcher", SCREEN_S, lambda: ui_dump() if PACKAGE in ui_dump() else None)
    if centre(dump, "text", "Skip"):
        tap(dump, "text", "Skip")
    wait_for("MODE_ACK on the MCU port", HANDSHAKE_S, lambda: UNHANDLED_ACK in logcat("McuOwner") or None)

    yield {"proc": proc, "start": start, "log": log}
    adb("shell", "am", "force-stop", VIDEO)
    proc.terminate()
    proc.wait(timeout=10)


@pytest.fixture(scope="module")
def player(brake):
    """The suite's video player on screen with a clip loaded, so BrakeGate is subscribed."""
    if VIDEO not in adb("shell", "pm", "list", "packages", VIDEO):
        pytest.skip(f"{VIDEO} is not installed on the instance")
    if PACKAGE != RELEASE_ID:
        pytest.skip(f"launcher is {PACKAGE}; the suite reads {RELEASE_ID}.sysvar (use the farm build)")

    adb("push", str(CLIP), CLIP_ON_DEVICE)
    for perm in ("android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE"):
        adb("shell", "pm", "grant", VIDEO, perm)
    # A `file://` path is not readable by a targetSdk 33 app ("Can't play this video", and the
    # dialog hides the activity from the dump): hand the player a MediaStore uri instead.
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", f"file://{CLIP_ON_DEVICE}")
    media_id = wait_for("the clip in MediaStore", SCREEN_S, lambda: MEDIA_ID.search(
        adb("shell", "content", "query", "--uri", MEDIA_VIDEOS, "--projection", "_id:_display_name")))
    # PlayerActivity is not exported; the farm's adbd runs as root, which may start it anyway.
    adb("shell", "am", "start", "-n", PLAYER, "-d", f"{MEDIA_VIDEOS}/{media_id.group(1)}", "-t", "video/mp4")
    wait_for("the video player", SCREEN_S, lambda: ui_dump() if f'resource-id="{VIDEO}:id/video"' in ui_dump() else None)
    return brake


def since(run, mark_s: float) -> None:
    remaining = run["start"] + mark_s - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)


# Ordered by the timeline: each case sleeps to its mark, then waits for both readers.

def test_row_opens_while_the_brake_is_on(brake):
    since(brake, BRAKE_ON_S)
    wait_for(f"{KEY}={OPEN} after `brake on`", ROW_S, row_is(OPEN))


def test_cover_hidden_while_the_brake_is_on(player):
    since(player, BRAKE_ON_S)
    wait_for("no cover on the player", SCREEN_S, cover_is(False))


def test_row_gates_when_the_brake_comes_off(brake):
    since(brake, BRAKE_OFF_S)
    wait_for(f"{KEY}={GATED} after `brake off`", ROW_S, row_is(GATED))


def test_cover_shown_when_the_brake_comes_off(player):
    since(player, BRAKE_OFF_S)
    wait_for("the cover on the player", SCREEN_S, cover_is(True))


def test_row_opens_again_when_the_brake_returns(brake):
    since(brake, BRAKE_ON_AGAIN_S)
    wait_for(f"{KEY}={OPEN} after the second `brake on`", ROW_S, row_is(OPEN))


def test_cover_hidden_again_when_the_brake_returns(player):
    since(player, BRAKE_ON_AGAIN_S)
    wait_for("the cover gone again", SCREEN_S, cover_is(False))
