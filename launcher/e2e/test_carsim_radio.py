"""The tuner on Riposte OS 0.2: carsim.py's `radio` scenario, driven from the launcher's own screen.

    pytest ──subprocess──▶ carsim.py ──TCP──▶ QEMU virtio port ──▶ McuOwner ──▶ RadioStateHolder ──▶ RadioScreen
       └────adb input tap / uiautomator dump ◀──────────────────────────────────────────────────────┘

Unlike the smoke cases the vehicle sends nothing on its own here: opening the tuner screen makes
the launcher claim SRC_RADIO (`01 01`), carsim answers with the `73` reports, and every later
frame is a reaction to a tap. Each assert pairs what the screen drew with what crossed the wire,
so a red case names the side that is wrong.

Environment: as test_carsim.py (`headunit carsim e2e`). The drawer case needs the suite's
`com.ripostelabs.radio` installed on the instance; it skips otherwise.
"""

from __future__ import annotations

import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

from test_carsim import CARSIM, MCU, CAN, PACKAGE, SERIAL, UNHANDLED_ACK, adb, logcat, ui_dump, wait_for

# The scenario is quiet after its handshake window (carsim.py SCENARIOS["radio"]).
HANDSHAKE_S = 15.0
SCREEN_S = 10.0          # a tap → re-poll → recomposition, with margin for a cold emulator
SETTLE_S = 1.5           # the launcher's own tap feedback before the next tap lands

# The tuner carsim models: 96.3 MHz "CBC R1" on FM, then one seek step of 0.2 MHz; AM lands
# on the dial's low end (carsim.py FM_STEP / AM_MIN, formatFreqLabel in RadioCard.kt).
FM_START = "96.3 MHz"
FM_AFTER_SEEK = "96.5 MHz"
AM_START = "530 kHz"
STATION = "CBC R1"

SUITE_RADIO = "com.ripostelabs.radio"
HOME_CARD_IDLE = "Radio unavailable"

# What crosses the wire, as carsim logs it (McuOwnerProtocol: 02 11 seek up, 02 1F AM).
WIRE_CLAIM = "mcu rx MODE 01"
WIRE_SEEK_UP = "mcu rx RADIO_KEY 11"
WIRE_AM = "mcu rx RADIO_KEY 1f"

NODE = re.compile(r'<node[^>]*?%s="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

pytestmark = pytest.mark.carsim


def centre(dump: str, attr: str, value: str) -> tuple[int, int] | None:
    m = NODE.pattern % (attr, re.escape(value))
    found = re.search(m, dump)
    if not found:
        return None
    x1, y1, x2, y2 = (int(v) for v in found.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(dump: str, attr: str, value: str) -> None:
    at = centre(dump, attr, value)
    assert at, f'no node with {attr}="{value}" on screen'
    adb("shell", "input", "tap", str(at[0]), str(at[1]))
    time.sleep(SETTLE_S)


def on_screen(*texts: str):
    def probe():
        dump = ui_dump()
        return dump if all(t in dump for t in texts) else None
    return probe


@pytest.fixture(scope="module")
def radio():
    """Restart the launcher against carsim's `radio` scenario and land on Home with the tuner idle."""
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if adb("shell", "getprop", "ro.riposte.os.car_owner").strip() != "1":
        pytest.skip("instance is not wired for the car owner (`headunit carsim <N> ...` first)")

    log = Path("carsim-radio.log")
    log.unlink(missing_ok=True)
    proc = subprocess.Popen(
        [sys.executable, str(CARSIM), "radio", "--mcu", MCU, "--can", CAN, "--log", str(log), "--hold", "300"],
    )
    adb("shell", "logcat", "-c")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")

    # A fresh install lands on Onboarding; Skip is its top-right pill.
    dump = wait_for("the launcher", SCREEN_S, lambda: ui_dump() if PACKAGE in ui_dump() else None)
    if centre(dump, "text", "Skip"):
        tap(dump, "text", "Skip")
    wait_for("MODE_ACK on the MCU port", HANDSHAKE_S, lambda: UNHANDLED_ACK in logcat("McuOwner") or None)

    yield {"proc": proc, "log": log}
    proc.terminate()
    proc.wait(timeout=10)


def test_tuner_screen_claims_the_source(radio):
    # Home's radio card is idle until the tuner reports: nothing has crossed the port yet.
    dump = wait_for("the idle radio card", SCREEN_S, on_screen(HOME_CARD_IDLE))
    assert WIRE_CLAIM not in radio["log"].read_text(), "SRC_RADIO was sent before the tuner screen opened"

    tap(dump, "text", HOME_CARD_IDLE)

    # RadioScreen.claimRadio → 01 01 → MODE_ACK + the 73 reports → RadioStateHolder → readout.
    wait_for("the tuner readout", SCREEN_S, on_screen(FM_START, STATION))
    text = radio["log"].read_text()
    assert WIRE_CLAIM in text and "MODE_ACK 01" in text
    assert f"RADIO ps={STATION!r}" in text


def test_seek_up_moves_the_readout(radio):
    tap(ui_dump(), "content-desc", "Seek up")

    wait_for("the seek result", SCREEN_S, on_screen(FM_AFTER_SEEK, STATION))
    text = radio["log"].read_text()
    assert WIRE_SEEK_UP in text and "RADIO freq=9650" in text


def test_am_toggle_switches_band(radio):
    tap(ui_dump(), "text", "AM")

    wait_for("the AM readout", SCREEN_S, on_screen(AM_START))
    text = radio["log"].read_text()
    assert WIRE_AM in text and "RADIO band=3" in text


def test_suite_radio_hidden_from_drawer(radio):
    if SUITE_RADIO not in adb("shell", "pm", "list", "packages", SUITE_RADIO):
        pytest.skip(f"{SUITE_RADIO} is not installed; the hide rule needs it present to prove absence")
    resolved = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.LAUNCHER", SUITE_RADIO)
    assert SUITE_RADIO in resolved, "the suite radio has no launcher activity, so the drawer would never list it"

    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    dump = wait_for("the home grid", SCREEN_S, on_screen("Apps", "Quick launch"))

    # App tiles carry their label as content-desc (Chrome is the positive control); the suite
    # radio resolves for PackageManager yet AppRepository drops it while the owner is active.
    assert centre(dump, "content-desc", "Chrome"), "no app tile at all: the grid did not render"
    assert centre(dump, "content-desc", "Radio") is None, "the suite radio is in the drawer on the owner path"
