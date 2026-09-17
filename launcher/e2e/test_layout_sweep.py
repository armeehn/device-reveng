"""Every launcher screen reachable from Home and the Settings hub passes two layout checks.

    pytest ──uiautomator dump◀── each screen, one tap deep

  * no clickable under 48 dp on either axis (a key, chip or icon the driver cannot hit)
  * no text node shorter than a glyph (a label clipped by its container)

Both bit on 2026-09-17: the Phone dial pad had 23 dp keys and the Vehicle tiles
cut their notes to 12 px strips. Nodes touching the bottom edge are ignored, they
are rows of a scrolling list cut by the panel, not by a layout. Only nodes owned
by the launcher count, so Chrome or Maps opening from a Home tile is not judged.

    CARSIM_SERIAL             adb serial of the instance (required; unset = skip)
    CARSIM_LAUNCHER_PACKAGE   installed launcher id (default: the debug build)
"""

from __future__ import annotations

import os
import re
import time

import pytest

from test_carsim import adb
from test_carsim import ui_dump as raw_dump
from test_carsim_commute import dismiss_onboarding, tap, texts

SERIAL = os.environ.get("CARSIM_SERIAL")
LAUNCHER = os.environ.get("CARSIM_LAUNCHER_PACKAGE", "com.ripostelabs.carlauncher.debug")

PX_PER_DP = 1.5                # 1920x720 @ 240 dpi
MIN_TAP_PX = int(48 * PX_PER_DP)
MIN_TEXT_PX = 20               # labelMedium draws 23 px; under this a line is clipped
PANEL_H = 720
EDGE_PX = 8                    # a node ending within this of the bottom is a scroll cut-off
HUB_ROW_MIN_W = 1500           # Settings hub rows span the panel; anything narrower is a control
HUB_ROW_MIN_H = 100            # rows are 120 px; the read-only banner above them is 56 and inert
HUB_STEPS_MAX = 9              # the hub is ~20 rows of 138 px; 9 drags of 400 px cover it
SCROLL_PX = 400
SCROLL_MS = 800
SETTINGS_ICON = "Settings"
SETTLE_S = 1.5
LEAVE_S = 8.0                  # slow pages gather before they draw; the hub stays up meanwhile

NODE = re.compile(r"<node ([^>]*)/?>")
ATTR = re.compile(r'(\S+?)="([^"]*)"')

pytestmark = pytest.mark.carsim


def nodes(dump: str) -> list[dict]:
    out = []
    for m in NODE.finditer(dump):
        a = dict(ATTR.findall(m.group(1)))
        b = [int(v) for v in re.findall(r"\d+", a.get("bounds", "[0,0][0,0]"))]
        a["box"] = (b[0], b[1], b[2] - b[0], b[3] - b[1])
        out.append(a)
    return out


def label(n: dict, all_nodes: list[dict]) -> str:
    """content-desc, own text, or the first text drawn inside the node's box."""
    if n.get("content-desc"):
        return n["content-desc"]
    if n.get("text"):
        return n["text"]
    x, y, w, h = n["box"]
    for k in all_nodes:
        kx, ky, kw, kh = k["box"]
        if k.get("text") and kx >= x and ky >= y and kx + kw <= x + w and ky + kh <= y + h:
            return k["text"]
    return "?"


def at_bottom_edge(box) -> bool:
    return box[1] + box[3] >= PANEL_H - EDGE_PX


def defects(dump: str) -> list[str]:
    ns = [n for n in nodes(dump) if n.get("package", "").startswith(LAUNCHER)]
    found = []
    for n in ns:
        x, y, w, h = n["box"]
        if at_bottom_edge(n["box"]):
            continue
        if n.get("clickable") == "true" and (w < MIN_TAP_PX or h < MIN_TAP_PX):
            found.append(f"tap target {label(n, ns)!r} is {w}x{h} px at ({x},{y})")
        if n.get("text") and 0 < h < MIN_TEXT_PX:
            found.append(f"text {n['text']!r} clipped to {h} px at ({x},{y})")
    return found


def home() -> str:
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1.2)
    return dismiss_onboarding()


def ui_dump() -> str:
    """A dump that failed (the ARTEMIS helper holding UiAutomation, say) must not pass as an empty screen."""
    xml = raw_dump()
    assert "<hierarchy" in xml, f"uiautomator dump returned no hierarchy: {xml[:200]!r}"
    return xml


def tap_wait(box) -> None:
    """Tap a box's centre (the shared tap() wants a point) and let the screen settle before its dump."""
    x, y, w, h = box
    tap((x + w // 2, y + h // 2))
    time.sleep(SETTLE_S)


def back() -> None:
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.8)


def scroll_down() -> None:
    """A slow drag of a fixed distance; a fling's travel depends on velocity and never repeats."""
    adb("shell", "input", "swipe", "960", "600", "960", str(600 - SCROLL_PX), str(SCROLL_MS))
    time.sleep(0.8)


def clickables(dump: str) -> list[tuple[str, tuple]]:
    ns = nodes(dump)
    return [(label(n, ns), n["box"]) for n in ns if n.get("clickable") == "true"]


def hub_rows(dump: str) -> list[tuple[str, tuple]]:
    return [(t, b) for t, b in clickables(dump)
            if b[2] >= HUB_ROW_MIN_W and b[3] >= HUB_ROW_MIN_H and not at_bottom_edge(b)]


@pytest.fixture(scope="module", autouse=True)
def device():
    if not SERIAL:
        pytest.skip("CARSIM_SERIAL is not set; run through `headunit carsim e2e`")
    yield
    adb("shell", "input", "keyevent", "KEYCODE_HOME")


def test_home_and_one_tap_deep():
    h = home()
    problems = {"Home": defects(h)}
    for name, box in clickables(h):
        home()
        tap_wait(box)
        dump = ui_dump()
        problems[name] = defects(dump)
        if "Toggle" in name:
            tap_wait(box)   # a toggle is a state change, not a screen; put it back
        back()
    bad = {k: v for k, v in problems.items() if v}
    assert not bad, "\n".join(f"{k}: {d}" for k, v in bad.items() for d in v)


def open_hub() -> str:
    h = home()
    icon = next((b for t, b in clickables(h) if t == SETTINGS_ICON), None)
    assert icon, f"no Settings icon on Home; on screen: {texts(h)}"
    tap_wait(icon)
    return ui_dump()


def wait_leave(hub: str) -> str | None:
    """The page's dump once it has replaced the hub; None if the hub is still up after LEAVE_S.

    Setup doctor, SysVar export and System & about gather before they draw, so a fixed
    wait after the tap sees the hub and calls the row dead. Whole text lists are compared:
    some pages open under a "Settings" breadcrumb, so their first node matches the hub's.
    """
    end = time.monotonic() + LEAVE_S
    while time.monotonic() < end:
        page = ui_dump()
        if texts(page) != texts(hub):
            return page
        time.sleep(0.7)
    return None


def hub_at(step: int) -> str:
    """The hub scrolled `step` drags down from the top, freshly dumped."""
    open_hub()
    for _ in range(step):
        scroll_down()
    return ui_dump()


def row_box(hub: str, title: str):
    return next((b for t, b in hub_rows(hub) if t == title), None)


def test_every_settings_page():
    checked: dict[str, list[str]] = {}
    for step in range(HUB_STEPS_MAX):
        hub = hub_at(step)
        titles = [t for t, _ in hub_rows(hub) if t not in checked]
        if not titles and step > 0:
            break
        for title in titles:
            # BACK does not restore the hub's scroll, so re-enter and find the row again by name.
            hub = hub_at(step)
            box = row_box(hub, title)
            if box is None:
                continue
            tap_wait(box)
            page = wait_leave(hub)
            if page is None:
                checked[title] = [f"tap on the row at {box} did not leave the hub in {LEAVE_S:.0f} s"]
            else:
                checked[title] = defects(page)
            back()
    assert len(checked) >= 10, f"hub walk found only {sorted(checked)}"
    bad = {k: v for k, v in checked.items() if v}
    assert not bad, "\n".join(f"Settings/{k}: {d}" for k, v in bad.items() for d in v)
