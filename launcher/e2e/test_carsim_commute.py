"""A whole drive reaches the readouts: carsim.py's `commute` and `replay-door-cycle`, over adb.

    carsim.py ──0xA5 relay (0x31 climate, 0x32 rpm, 0x11 doors)──▶ McuOwner ──▶ CarEvents.climate / VehicleState
              ──raw bus (0x361 speed, 0x3BC gear, 0x4A5 doors)───▶ SlcanLinkSource ──▶ RawCanDecoder ──▶ parked gate
    pytest ──uiautomator dump◀── Home climate card, Vehicle page, status bar

Riposte OS 0.2 has no canbus2, so nothing here comes from a vendor broadcast: every string
asserted below was drawn from a frame the simulator put on one of the two carriers. The
timeline marks are carsim.py SCENARIOS["commute"]; cases are ordered by the clock, and each
one waits for its mark before looking, so a red case names the readout that did not follow.

Same environment as test_carsim.py (`headunit carsim e2e`), plus:
    CARSIM_CAPTURE   candump file for the replay case (default: the one staged next to carsim.py)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

from test_carsim import CARSIM, MCU, CAN, PACKAGE, SERIAL, adb, logcat, since, ui_dump, wait_for

CAPTURE = Path(os.environ.get("CARSIM_CAPTURE", str(CARSIM.parent.parent.parent / "capture-2026-09-07-door-cycle.log")))

# Marks in SCENARIOS["commute"], seconds from the simulator's start.
CLIMATE_FIRST_S = 8.0        # temp=18 fan=2
CLIMATE_SECOND_S = 14.0      # temp=22.5 fan=4
GEAR_D_S = 15.0
RAMP_UP_DONE_S = 26.0        # 0→60 over 10 s from 16 s
CRUISE_END_S = 50.0          # 60 km/h holds until the ramp down
REVERSE_ON_S = 59.2
REVERSE_OFF_S = 66.0
PARKED_S = 66.2              # gear P, 0 km/h, ACC still on
ACC_OFF_S = 73.0
COMMUTE_HOLD_S = 30

# The door-cycle capture as the CANable heard it: driver open from the first frame, first
# closed at 47.7 s, then open/closed every few seconds until the end at ~87 s (1x speed).
REPLAY_START_S = 4.0
REPLAY_FIRST_CLOSE_S = 47.7
REPLAY_END_S = 90.0

HOME_SETTLE_S = 8.0          # launcher restart + handshake before the first dump is worth taking
FIRST_CLIMATE_WINDOW_S = CLIMATE_SECOND_S - CLIMATE_FIRST_S
SETTINGS_SWIPES_MAX = 6      # the hub is a long list; Vehicle sits ~17 rows down
SETTINGS_FLINGS = 2          # two flings land near it; a dump costs ~2 s, so fling first
FLING_MS = 300
TILE_ROW_PX = 93             # SettingRow pitch on the Vehicle page (uiautomator bounds)
SCROLL_MS = 800              # slow enough that Compose scrolls instead of flinging
TAP_SAFE_BOTTOM_Y = 650      # a row centred below this is clipped by the 720 px panel edge
VEHICLE_PAGE_SUBTITLE = "What the car is reporting, live"

# What the launcher draws, verbatim (ClimateCard.kt, VehicleTiles.kt, StatusBar.kt, NavCard.kt).
FAN_MAX = 7
PARKED_ONLY_LOCKED = "available when parked"
ONBOARDING_TITLE = "Welcome to Car Launcher"
NODE_TEXT = 'text="{}"'

pytestmark = pytest.mark.carsim


def node_bounds(dump: str, attr: str, value: str) -> tuple[int, int] | None:
    """Centre of the first node whose `attr` equals `value`, in panel pixels."""
    m = re.search(rf'<node[^>]*\b{attr}="{re.escape(value)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not m:
        return None
    x0, y0, x1, y1 = (int(v) for v in m.groups())
    return (x0 + x1) // 2, (y0 + y1) // 2


def tap(xy: tuple[int, int]) -> None:
    adb("shell", "input", "tap", str(xy[0]), str(xy[1]))


def has_text(dump: str, text: str) -> bool:
    return NODE_TEXT.format(text) in dump


def dismiss_onboarding() -> str:
    """A wiped instance boots into onboarding; Skip lands on Home. Returns the Home dump."""
    dump = ui_dump()
    skip = node_bounds(dump, "text", "Skip") if ONBOARDING_TITLE in dump else None
    if skip:
        tap(skip)
        time.sleep(2)
        dump = ui_dump()
    return dump


def fling_up() -> None:
    adb("shell", "input", "swipe", "960", "650", "960", "150", str(FLING_MS))
    time.sleep(0.8)


def fling_down() -> None:
    adb("shell", "input", "swipe", "960", "150", "960", "650", str(FLING_MS))
    time.sleep(0.8)


def open_vehicle_page() -> None:
    """Home → Settings (status bar) → scroll the hub → Vehicle."""
    settings = node_bounds(dismiss_onboarding(), "content-desc", "Settings")
    assert settings, "no Settings button in the status bar; not on Home"
    tap(settings)
    time.sleep(1.5)
    # The hub keeps its scroll position across a HOME press, so start from the top.
    for _ in range(SETTINGS_FLINGS):
        fling_down()
    for _ in range(SETTINGS_FLINGS):
        fling_up()
    for _ in range(SETTINGS_SWIPES_MAX):
        row = node_bounds(ui_dump(), "text", "Vehicle")
        if not row:
            fling_up()
            continue
        if row[1] > TAP_SAFE_BOTTOM_Y:
            # A row clipped by the bottom edge takes the tap on its visible sliver or not at
            # all; bring it up two rows first.
            scroll_tiles(2)
            continue
        tap(row)
        time.sleep(1.5)
        assert has_text(ui_dump(), VEHICLE_PAGE_SUBTITLE), "the Vehicle row tap did not open the page"
        return
    pytest.fail("the Settings hub never showed a Vehicle row")


def scroll_tiles(rows: int) -> None:
    """Scroll the Vehicle page by whole rows without a fling, so the offset is known."""
    px = rows * TILE_ROW_PX
    adb("shell", "input", "swipe", "960", str(400 + px), "960", "400", str(SCROLL_MS))
    time.sleep(0.8)


def back_home() -> None:
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.5)


def start_scenario(scenario: str, log: Path, *extra: str) -> subprocess.Popen:
    proc = subprocess.Popen(
        [sys.executable, str(CARSIM), scenario, "--mcu", MCU, "--can", CAN, "--log", str(log), *extra],
    )
    adb("shell", "logcat", "-c")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    return proc


def require_wired() -> None:
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if adb("shell", "getprop", "ro.riposte.os.car_owner").strip() != "1":
        pytest.skip("instance is not wired for the car owner (`headunit carsim <N> ...` first)")


@pytest.fixture(scope="module")
def commute():
    """Restart the launcher against a fresh commute run; yield the process, then stop it."""
    require_wired()
    log = Path("carsim-commute.log")
    proc = start_scenario("commute", log, "--hold", str(COMMUTE_HOLD_S))
    start = time.monotonic()
    yield {"proc": proc, "start": start, "log": log}
    proc.terminate()
    proc.wait(timeout=10)


def test_home_climate_card_follows_the_relay(commute):
    # 0x31 through McuOwner → CarEvents.climate → the Home card: "18.0℃" and "2/7", then the
    # second setpoint replaces both. The card's own labels, not the Vehicle page's.
    since(commute, max(CLIMATE_FIRST_S + 1.0, HOME_SETTLE_S))
    dismiss_onboarding()

    def first_setpoint():
        dump = ui_dump()
        return (has_text(dump, "18.0℃") and has_text(dump, f"2/{FAN_MAX}")) or None
    wait_for("the climate card at 18.0℃ fan 2", FIRST_CLIMATE_WINDOW_S, first_setpoint)

    since(commute, CLIMATE_SECOND_S)

    def second_setpoint():
        dump = ui_dump()
        return (has_text(dump, "22.5℃") and has_text(dump, f"4/{FAN_MAX}")) or None
    wait_for("the climate card at 22.5℃ fan 4", GEAR_D_S + 6.0 - CLIMATE_SECOND_S, second_setpoint)


def test_vehicle_page_shows_the_drive(commute):
    # Opened during the ramp, read during the cruise: gear D (raw 0x3BC), 60 km/h (raw 0x361),
    # 2100 rpm (relay 0x32) and the climate summary (relay 0x31) on one page, both carriers.
    since(commute, GEAR_D_S)
    open_vehicle_page()
    since(commute, RAMP_UP_DONE_S)

    def cruising():
        dump = ui_dump()
        ok = (
            has_text(dump, "Gear") and has_text(dump, "D")
            and has_text(dump, "60 km/h")
            and has_text(dump, "2100 rpm")
            and "22.5°C, fan 4" in dump
        )
        return ok or None
    wait_for("the Vehicle page in D at 60 km/h, 2100 rpm, climate 22.5°C fan 4", CRUISE_END_S - RAMP_UP_DONE_S - 4.0, cruising)
    assert "bus link closed" not in logcat("SlcanLinkSource"), "the raw-bus link died mid-drive"
    back_home()


def test_parked_gate_locks_while_moving(commute):
    # Still cruising: the Home speed tile reads 60 and the parked-only shelves are locked. The
    # gate reads the raw bus only (CarEvents.CAN_SPEED_TRUSTED is false), so this is 0x361.
    def locked():
        dump = ui_dump()
        return (has_text(dump, "60 km/h") and PARKED_ONLY_LOCKED in dump) or None
    wait_for("60 km/h on Home with the parked-only shelves locked", CRUISE_END_S - (time.monotonic() - commute["start"]), locked)


def test_reverse_screen_at_the_spot(commute):
    since(commute, REVERSE_ON_S + 1.0)

    def reversed_on_screen():
        dump = ui_dump().lower()
        return ("camera" in dump and "reverse" in dump) or None
    wait_for("the reverse-camera screen", REVERSE_OFF_S - REVERSE_ON_S - 1.0, reversed_on_screen)


def test_parked_gate_releases_at_rest(commute):
    # Back in P at 0 km/h with ACC still on: the tile reads 0 and the shelves unlock.
    since(commute, PARKED_S + 1.0)

    def released():
        dump = ui_dump()
        return (has_text(dump, "0 km/h") and PARKED_ONLY_LOCKED not in dump) or None
    wait_for("0 km/h on Home with the shelves unlocked", ACC_OFF_S - PARKED_S - 1.0, released)

    since(commute, ACC_OFF_S + 1.0)
    text = commute["log"].read_text()
    assert "ramp done at 60 km/h" in text and "ramp done at 0 km/h" in text
    assert "mcu rx MODE" in text, "carsim never saw the owner handshake"


@pytest.fixture(scope="module")
def door_replay():
    """The candump capture on the bus carrier, at 1x; the ticker's own frames pause meanwhile."""
    require_wired()
    if not CAPTURE.is_file():
        pytest.skip(f"no capture at {CAPTURE}; set CARSIM_CAPTURE")
    log = Path("carsim-replay.log")
    proc = start_scenario("replay-door-cycle", log, "--capture", str(CAPTURE), "--hold", "5")
    start = time.monotonic()
    yield {"proc": proc, "start": start, "log": log}
    proc.terminate()
    proc.wait(timeout=10)


def test_replay_reaches_the_raw_decoder(door_replay):
    # 0x4A5 byte 3 = 0x80 → RawCanDecoder → "driver" on the Open tile; 0x3BC → Gear P; then the
    # capture's first close blanks the tile, and the next open brings it back. Three states of
    # one door, none of them from the MCU relay: the ticker is paused while the capture plays.
    since(door_replay, HOME_SETTLE_S)
    open_vehicle_page()
    # The Open tile sits past the fold behind Speed, Wheels, Gear, Engine, Intake air and
    # Coolant; two rows up keeps Gear in view as the anchor whether or not Open is drawn.
    scroll_tiles(2)

    def driver_open():
        dump = ui_dump()
        return (has_text(dump, "Open") and has_text(dump, "driver") and has_text(dump, "P")) or None
    wait_for("driver door open + gear P from the capture", REPLAY_FIRST_CLOSE_S - HOME_SETTLE_S, driver_open)

    def driver_closed():
        dump = ui_dump()
        return (has_text(dump, "Gear") and not has_text(dump, "driver")) or None
    wait_for("the driver door closed", REPLAY_END_S - (time.monotonic() - door_replay["start"]), driver_closed)
    wait_for("the driver door open again", REPLAY_END_S - (time.monotonic() - door_replay["start"]), driver_open)

    back_home()
    assert "bus link closed" not in logcat("SlcanLinkSource"), "the raw-bus link died mid-replay"
