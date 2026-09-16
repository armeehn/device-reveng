"""Wheel-key gestures off the owner's 0x11 relay: carsim.py's `gestures` scenario, over adb.

    carsim.py ──0xA5 relay (0x11 held every 100 ms, then released)──▶ McuOwner ──▶ HiworldCanDecoder
              ──▶ CarEvents.ownerListener ──▶ WheelGestures ──▶ "wheel gesture: LongPress(key=MODE)"
    pytest ──logcat CarEvents ◀── the gesture line;  ──uiautomator dump ◀── the Media screen MODE opens

Riposte OS 0.2 has no canbus2, so there is no MCU_MSG_CAN_ALL_INFO broadcast: the only wheel
byte pair the launcher sees is the one the box relays, and the hold must be timed off those
frames. Same environment as test_carsim.py (`headunit carsim e2e`). Chords stay for the car.
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest

from test_carsim import logcat, since, ui_dump, wait_for
from test_carsim_commute import dismiss_onboarding, require_wired, seen, start_scenario

# Marks in SCENARIOS["gestures"], seconds from the simulator's start.
HOLD_MODE_S = 6.0            # wheelhold MODE, 900 ms
DOUBLE_PLAY_S = 9.0          # wheeldouble PLAY_PAUSE
GESTURES_HOLD_S = 20

HOME_SETTLE_S = 5.0
GESTURE_WINDOW_S = 6.0       # hold, engine threshold and the log line, with slack for adb
MEDIA_TITLE = "Media"        # MediaScreen's header, what MODE long opens by default

LONG_MODE = "wheel gesture: LongPress(key=MODE)"
DOUBLE_PLAY = "wheel gesture: DoublePress(key=PLAY_PAUSE)"

pytestmark = pytest.mark.carsim


@pytest.fixture(scope="module")
def gestures():
    """Restart the launcher against a fresh gestures run; yield the process, then stop it."""
    require_wired()
    log = Path("carsim-gestures.log")
    proc = start_scenario("gestures", log, "--hold", str(GESTURES_HOLD_S))
    start = time.monotonic()
    yield {"proc": proc, "start": start, "log": log}
    proc.terminate()
    proc.wait(timeout=10)


def test_held_mode_is_a_long_press_and_opens_media(gestures):
    since(gestures, HOME_SETTLE_S)
    dismiss_onboarding()
    since(gestures, HOLD_MODE_S)

    def long_logged():
        return LONG_MODE if LONG_MODE in logcat("CarEvents") else None
    wait_for("the MODE long press in logcat", GESTURE_WINDOW_S, long_logged)

    def media_open():
        return seen(ui_dump(), MEDIA_TITLE)
    wait_for("the Media screen after MODE held", GESTURE_WINDOW_S, media_open)


def test_two_quick_play_presses_are_a_double_press(gestures):
    since(gestures, DOUBLE_PLAY_S)

    def double_logged():
        return DOUBLE_PLAY if DOUBLE_PLAY in logcat("CarEvents") else None
    wait_for("the PLAY_PAUSE double press in logcat", GESTURE_WINDOW_S, double_logged)

    # Negative control: the held MODE must not also have read as a plain press.
    assert "wheel gesture: Press(key=MODE)" not in logcat("CarEvents"), "MODE hold also fired a plain press"
