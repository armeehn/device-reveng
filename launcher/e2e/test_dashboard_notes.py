"""Every Vehicle tile's provenance note must be fully drawn, not clipped.

    pytest ──uiautomator dump◀── Vehicle page (status-bar dashboard icon)

Found 2026-09-17 on the emulator farm: the middle column's three tiles split
the height equally and the Outside / Ignition tiles cut their note to a
12 px strip under the value.

    CARSIM_SERIAL    adb serial of the instance (required; unset = skip)
"""

from __future__ import annotations

import os
import re
import time

import pytest

from test_carsim import adb, ui_dump
from test_carsim_commute import dismiss_onboarding, node_bounds, tap, texts

SERIAL = os.environ.get("CARSIM_SERIAL")

# labelMedium on the 240 dpi panel draws about 23 px; anything under this is a clipped line.
MIN_NOTE_PX = 20
DASHBOARD_ICON = "Vehicle dashboard"
VEHICLE_TITLE = "Vehicle"
# The notes as DashboardScreen.kt writes them for a desk instance with no car attached.
# "vendor ACC broadcast" is not among them any more: the Ignition tile printed it on a unit
# that had never heard the car, because CarEvents.accOn fails open to true and a readout cannot
# tell that default from a real ACC event. The tile waits for CarEvents.accSeen now (#255), so
# a bench instance reads "no reading yet".
NOTES = ("no reading yet", "no frame yet")

pytestmark = pytest.mark.carsim


def text_heights(dump: str, text: str) -> list[int]:
    return [int(y1) - int(y0) for y0, y1 in re.findall(
        rf'<node[^>]*\btext="{re.escape(text)}"[^>]*bounds="\[\d+,(\d+)\]\[\d+,(\d+)\]"', dump)]


@pytest.fixture(scope="module")
def vehicle_dump() -> str:
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    icon = node_bounds(dismiss_onboarding(), "content-desc", DASHBOARD_ICON)
    assert icon, "no Vehicle dashboard icon in the status bar; not on Home"
    tap(icon)
    time.sleep(1.5)
    dump = ui_dump()
    assert VEHICLE_TITLE in texts(dump), f"Vehicle page did not open; on screen: {texts(dump)}"
    yield dump
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def test_tile_notes_are_not_clipped(vehicle_dump):
    clipped = {}
    for note in NOTES:
        heights = text_heights(vehicle_dump, note)
        assert heights, f"note {note!r} not on the Vehicle page; on screen: {texts(vehicle_dump)}"
        short = [h for h in heights if h < MIN_NOTE_PX]
        if short:
            clipped[note] = short
    assert not clipped, f"tile notes clipped under {MIN_NOTE_PX} px: {clipped}"
