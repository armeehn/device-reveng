"""The Phone screen's dial pad must be tappable from the driver's seat.

    pytest ──uiautomator dump◀── Phone screen (status-bar phone icon)

Reads the pad's geometry off the panel and asserts every key clears the minimum
tap target. Found 2026-09-17 on the emulator farm: the left column stacked the
status block, Answer/Hang up, the number field and Call at fixed heights, and
the twelve keys shared what was left (about 23 dp each on the 480 dp panel).

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

# Panel is 1920x720 at 240 dpi, so 1 dp = 1.5 px (headunit-emulator-farm).
PX_PER_DP = 1.5
MIN_TAP_DP = 48                 # Material minimum touch target; the car wants nothing smaller
MIN_TAP_PX = int(MIN_TAP_DP * PX_PER_DP)
DIAL_KEYS = "123456789*0#"
FIELD_KEYS = ("+", "Backspace")   # the two keys in the number field, by text / content-desc
PHONE_TITLE = "Phone"

pytestmark = pytest.mark.carsim


def key_size(dump: str, key: str) -> tuple[int, int] | None:
    """Width and height of the tappable box behind a dial key label, in panel pixels.

    The label is a Text node with no bounds of its own worth measuring; the key is
    the clickable node that contains it, so walk up to the enclosing clickable box.
    """
    label = re.search(rf'<node[^>]*\btext="{re.escape(key)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if not label:
        return None
    lx0, ly0, lx1, ly1 = (int(v) for v in label.groups())

    best = None
    for m in re.finditer(r'<node[^>]*\bclickable="true"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump):
        x0, y0, x1, y1 = (int(v) for v in m.groups())
        if x0 <= lx0 and y0 <= ly0 and x1 >= lx1 and y1 >= ly1:
            size = (x1 - x0, y1 - y0)
            if best is None or size[0] * size[1] < best[0] * best[1]:
                best = size
    return best


def open_phone() -> str:
    # HOME first: a previous case (or a hand on the browser stream) may have left another screen up.
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    phone = node_bounds(dismiss_onboarding(), "content-desc", PHONE_TITLE)
    assert phone, "no Phone icon in the status bar; not on Home"
    tap(phone)
    time.sleep(1.5)
    dump = ui_dump()
    assert PHONE_TITLE in texts(dump), f"Phone screen did not open; on screen: {texts(dump)}"
    return dump


@pytest.fixture(scope="module")
def phone_dump() -> str:
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    dump = open_phone()
    yield dump
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def test_dial_keys_clear_min_tap_target(phone_dump):
    small = {}
    for key in DIAL_KEYS:
        size = key_size(phone_dump, key)
        assert size, f"dial key {key!r} not on the Phone screen; on screen: {texts(phone_dump)}"
        if size[0] < MIN_TAP_PX or size[1] < MIN_TAP_PX:
            small[key] = size
    assert not small, f"dial keys under {MIN_TAP_DP} dp ({MIN_TAP_PX} px): {small}"


def test_field_keys_clear_min_tap_target(phone_dump):
    """`+` and backspace sit in the number field; they are keys too and get the same minimum."""
    small = {}
    for key in FIELD_KEYS:
        m = re.search(rf'<node[^>]*\b(?:text|content-desc)="{re.escape(key)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', phone_dump)
        assert m, f"{key!r} not on the Phone screen; on screen: {texts(phone_dump)}"
        x0, y0, x1, y1 = (int(v) for v in m.groups())
        if x1 - x0 < MIN_TAP_PX or y1 - y0 < MIN_TAP_PX:
            small[key] = (x1 - x0, y1 - y0)
    assert not small, f"number field keys under {MIN_TAP_DP} dp ({MIN_TAP_PX} px): {small}"
