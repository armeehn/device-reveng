"""Panel keys on Riposte OS 0.2: carsim's `panel-radio` scenario presses the unit's RADIO key.

    carsim.py ──72 36──▶ McuOwner ──▶ SwcFallback.mcuKey ──▶ CAR_KEY_RADIO ──▶ NavKey.OPEN_RADIO
                                                                                    │
    the tuner screen claims SRC_RADIO (`01 01`) ◀────────────────────────────────────┘

Until 2026-09-17 `MCU_KEY_RADIO` (54) had no CAR_KEY mapping, so the press was decoded and
dropped. The assert pairs the screen (the FM readout) with the wire (the claim carsim logs).

Environment: as test_carsim.py (`headunit carsim e2e`).
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

import pytest

from test_carsim import CARSIM, CAN, MCU, PACKAGE, SERIAL, UNHANDLED_ACK, adb, logcat, ui_dump, wait_for
from test_carsim_radio import FM_START, STATION, WIRE_CLAIM, centre, on_screen, tap

KEY_RADIO_S = 25.0       # carsim.py SCENARIOS["panel-radio"]
HANDSHAKE_S = 20.0
SCREEN_S = 10.0

pytestmark = pytest.mark.carsim


@pytest.fixture(scope="module")
def panel():
    """Restart the launcher against carsim's `panel-radio` scenario; yield the run."""
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if adb("shell", "getprop", "ro.riposte.os.car_owner").strip() != "1":
        pytest.skip("instance is not wired for the car owner (`headunit carsim <N> ...` first)")

    log = Path("carsim-panel.log")
    log.unlink(missing_ok=True)
    proc = subprocess.Popen(
        [sys.executable, str(CARSIM), "panel-radio", "--mcu", MCU, "--can", CAN, "--log", str(log), "--hold", "60"],
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
    proc.terminate()
    proc.wait(timeout=10)


def test_radio_key_opens_the_tuner(panel):
    remaining = panel["start"] + KEY_RADIO_S - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)

    # Screen: the tuner with carsim's station. Wire: the claim the screen made.
    wait_for("the tuner screen after `key RADIO`", SCREEN_S, on_screen(FM_START, STATION))
    assert WIRE_CLAIM in panel["log"].read_text(), "the tuner screen opened but never claimed SRC_RADIO"
