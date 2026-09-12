"""CarLauncher end-to-end checks, driven in natural language by ARTEMIS.

Each goal starts from the launcher home and must leave the head unit there
again, so cases are order-independent. Assert on facts a driver would see:
what is on screen, what a tap did, whether anything crashed.
"""

from __future__ import annotations

import pytest

HOME = (
    "You are on an Android car head unit running a custom launcher (landscape, 1920x720). "
    "If you are not on the launcher home screen, press HOME first. "
)
BACK_HOME = " Finally return to the launcher home screen."


@pytest.mark.e2e
def test_home_status_bar(run_goal):
    out = run_goal(
        HOME + "Look at the status bar along the top of the home screen without tapping anything. "
        "Report whether a clock is shown, whether a brightness percentage is shown, "
        "and whether a Settings gear icon is present.",
        {"clock_visible": True, "brightness_percent_visible": True, "settings_icon_visible": True},
    ).data
    assert out["clock_visible"] is True
    assert out["brightness_percent_visible"] is True
    assert out["settings_icon_visible"] is True


@pytest.mark.e2e
def test_home_tiles(run_goal):
    out = run_goal(
        HOME + "Without tapping anything, list the large tiles on the home screen. "
        "Report whether a Media tile, a Navigation tile and an Apps tile are present, "
        "and copy the status text shown inside the Media tile.",
        {"media_tile": True, "navigation_tile": True, "apps_tile": True, "media_status_text": ""},
    ).data
    assert out["media_tile"] and out["navigation_tile"] and out["apps_tile"]
    assert isinstance(out["media_status_text"], str) and out["media_status_text"].strip()


@pytest.mark.e2e
def test_settings_round_trip(run_goal):
    out = run_goal(
        HOME + "Tap the Settings gear in the status bar. Report whether a settings screen opened "
        "and the label of the first settings entry you can see. Then press BACK once and report "
        "whether you are on the launcher home screen again.",
        {"settings_opened": True, "first_entry": "", "back_on_home": True},
    ).data
    assert out["settings_opened"] is True
    assert out["first_entry"].strip()
    assert out["back_on_home"] is True


@pytest.mark.e2e
def test_theme_toggle_is_reversible(run_goal):
    out = run_goal(
        HOME + "Note whether the home screen background is currently light or dark. Tap the "
        "'Toggle day/night theme' button in the status bar, wait two seconds, and note the "
        "background again. Tap it once more and note it a third time.",
        {"before": "light|dark", "after_first_tap": "light|dark", "after_second_tap": "light|dark"},
    ).data
    assert out["after_first_tap"] != out["before"], "first tap did not change the theme"
    assert out["after_second_tap"] == out["before"], "second tap did not restore the theme"


@pytest.mark.e2e
def test_apps_search_finds_chrome(run_goal):
    out = run_goal(
        HOME + "Open the Apps tile. Use its search field to search for 'Chrome'. Report whether a "
        "Chrome entry is listed in the results. Do not launch Chrome." + BACK_HOME,
        {"search_field_present": True, "chrome_listed": True, "on_home_at_end": True},
    ).data
    assert out["search_field_present"] is True
    assert out["chrome_listed"] is True
    assert out["on_home_at_end"] is True


@pytest.mark.e2e
def test_quick_controls_panel(run_goal):
    out = run_goal(
        HOME + "Tap the 'Quick controls' button in the status bar. Report whether a panel or sheet "
        "with toggles opened and how many toggles or buttons it contains. Close it without changing "
        "any setting." + BACK_HOME,
        {"panel_opened": True, "control_count": 0, "on_home_at_end": True},
    ).data
    assert out["panel_opened"] is True
    assert int(out["control_count"]) >= 1
    assert out["on_home_at_end"] is True


@pytest.mark.e2e
def test_navigation_around_home_raises_no_crash(run_goal):
    out = run_goal(
        HOME + "Open each of these in turn and press BACK after each: the Media tile, the Navigation "
        "tile, the Apps tile, the Phone button, the Vehicle dashboard button. At the end report "
        "whether any dialog saying an app 'keeps stopping', 'isn't responding' or 'has stopped' "
        "appeared at any point, and whether the launcher home screen is showing.",
        {"crash_dialog_seen": False, "on_home_at_end": True},
    ).data
    assert out["crash_dialog_seen"] is False
    assert out["on_home_at_end"] is True
