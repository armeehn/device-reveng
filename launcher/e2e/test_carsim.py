"""The simulated vehicle reaches the launcher: carsim.py's smoke scenario, asserted over adb.

    pytest ──subprocess──▶ carsim.py ──TCP──▶ QEMU virtio ports ──▶ launcher (McuOwner, SlcanLinkSource)
       └────adb logcat / uiautomator dump ◀──────────────────────────────┘

No model in the loop: every assert reads what the launcher itself logged or drew, so a red
case names a car-link fact. The ARTEMIS suite covers the natural-language layer separately.

Environment (all set by `headunit carsim e2e`):
    CARSIM_SERIAL             adb serial of the wired instance (required; unset = skip)
    CARSIM_MCU / CARSIM_CAN   host:port of the two QEMU chardevs carsim.py talks to
    CARSIM_LAUNCHER_PACKAGE   installed launcher id (default: the debug build)
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import pytest

SERIAL = os.environ.get("CARSIM_SERIAL")
MCU = os.environ.get("CARSIM_MCU", "127.0.0.1:5700")
CAN = os.environ.get("CARSIM_CAN", "127.0.0.1:5701")
PACKAGE = os.environ.get("CARSIM_LAUNCHER_PACKAGE", "com.ripostelabs.carlauncher.debug")
CARSIM = Path(__file__).parent / "carsim" / "carsim.py"

# The smoke timeline (carsim.py SCENARIOS["smoke"]) in seconds; asserts wait past these marks,
# so the cases below are ordered by the clock: reverse, ramp, then the end-of-run checks.
SMOKE_REVERSE_ON_S = 5.0
SMOKE_REVERSE_OFF_S = 12.0
SMOKE_RAMP_DONE_S = 17.5
SMOKE_RAMP_HOLD_S = 9.0      # 43 km/h holds until the ramp down at 27 s
SMOKE_DONE_S = 32.0
HANDSHAKE_S = 10.0
POLL_S = 1.0

# What the launcher prints, verbatim (McuOwner.kt, CarLinkReading.kt, ReverseCameraScreen.kt).
RUNNING_ACKED = re.compile(r"acked=true frames=(\d+)")
UNHANDLED_ACK = "unhandled opcode 0x70"

pytestmark = pytest.mark.carsim


def adb(*args: str, timeout: float = 30) -> str:
    out = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)
    return out.stdout


def logcat(*tags: str) -> str:
    return adb("logcat", "-d", "-s", *[f"{t}:*" for t in tags])


def ui_dump() -> str:
    adb("shell", "uiautomator", "dump", "/data/local/tmp/carsim-ui.xml")
    return adb("shell", "cat", "/data/local/tmp/carsim-ui.xml")


def wait_for(what: str, deadline_s: float, probe) -> str:
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        found = probe()
        if found:
            return found
        time.sleep(POLL_S)
    pytest.fail(f"{what} not seen within {deadline_s:.0f}s")


@pytest.fixture(scope="module")
def smoke():
    """Restart the launcher against a fresh carsim smoke run; yield the process, then stop it."""
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    if adb("shell", "getprop", "ro.riposte.os.car_owner").strip() != "1":
        pytest.skip("instance is not wired for the car owner (`headunit carsim <N> ...` first)")

    log = Path("carsim-smoke.log")
    proc = subprocess.Popen(
        [sys.executable, str(CARSIM), "smoke", "--mcu", MCU, "--can", CAN, "--log", str(log), "--hold", "60"],
    )
    adb("shell", "logcat", "-c")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    start = time.monotonic()
    yield {"proc": proc, "start": start, "log": log}
    proc.terminate()
    proc.wait(timeout=10)


def since(smoke, mark_s: float) -> None:
    """Sleep until the timeline has passed `mark_s` seconds."""
    remaining = smoke["start"] + mark_s - time.monotonic()
    if remaining > 0:
        time.sleep(remaining)


def test_reverse_gate_reacts(smoke):
    since(smoke, SMOKE_REVERSE_ON_S + 1.5)
    # Camera id 1 is absent on the emulator, so the screen shows its "no camera" verdict;
    # either text proves CarEvents.reverse flipped from the 71 frame.
    def reversed_on_screen():
        dump = ui_dump().lower()
        return ("camera" in dump and "reverse" in dump) or None
    wait_for("the reverse-camera screen", SMOKE_REVERSE_OFF_S - SMOKE_REVERSE_ON_S, reversed_on_screen)


def test_speed_ramp_flips_the_parked_gate(smoke):
    since(smoke, SMOKE_RAMP_DONE_S)
    assert "ramp done at 43 km/h" in smoke["log"].read_text()
    assert "bus link closed" not in logcat("SlcanLinkSource"), "the raw-bus link died mid-drive"

    # 0x361 on the bus → RawCanDecoder → CarEvents.motion MOVING: the speed tile shows the ramp's
    # end value and the parked-only shelves say so (StatusBar: "— available when parked").
    def moving_on_screen():
        dump = ui_dump()
        return ("43 km/h" in dump and "available when parked" in dump) or None
    wait_for("the 43 km/h tile with the parked-only shelves locked", SMOKE_RAMP_HOLD_S, moving_on_screen)


def test_owner_runs_and_is_acked(smoke):
    # The MODE_ACKs for POWER_ON/MCU_VERSION are not awaited, so McuOwner logs them as
    # unhandled 0x70: that line is proof the handshake bytes crossed the port both ways.
    wait_for("MODE_ACK on the MCU port", HANDSHAKE_S, lambda: UNHANDLED_ACK in logcat("McuOwner") or None)
    since(smoke, SMOKE_DONE_S)
    text = smoke["log"].read_text()
    assert "acks=" in text and "mcu rx MODE 63" in text, "carsim never saw the SRC_NULL handshake"
    assert re.search(r"acks=([1-9]\d*)", text), "carsim acknowledged nothing"


def test_volume_chip_moves(smoke):
    since(smoke, SMOKE_DONE_S)
    text = smoke["log"].read_text()
    assert "MAIN_VOLUME 21" in text
    # VOL_UP panel key → launcher echoes 08 00 → carsim answers 79 with the new level.
    assert "mcu rx SYSTEM_KEY 00" in text and "MAIN_VOLUME 22" in text
