package com.ripostelabs.carlauncher.input

import com.ripostelabs.carlauncher.carlib.GameApps

/**
 * WheelGamepad — the steering wheel as a game controller, while a game has the screen.
 *
 * The launcher already turns the wheel's buttons into [NavKey]s to drive its own screens. When
 * an emulator frontend is in the foreground those same presses are forwarded to it as gamepad
 * key events instead, so RetroArch's menus and the games in them can be driven from the wheel
 * with no extra hardware. Nothing here decides *how* a press is delivered; that is root's
 * `input keyevent`, in the activity.
 *
 * ── The mapping ─────────────────────────────────────────────────────────────────────────────────
 *
 *     wheel            NavKey            gamepad
 *     vol+ / vol-      UP / DOWN         D-pad up / down
 *     prev / next      LEFT / RIGHT      D-pad left / right
 *     mode             CENTER            A   (confirm)
 *     return / hangup  BACK              B   (back)
 *     play/pause       MEDIA_PLAY_PAUSE  START (RetroArch menu toggle)
 *     home             HOME              never forwarded — see below
 *
 * ── HOME is the way out, and is never taken ─────────────────────────────────────────────────────
 * A driver in a game with no touchscreen reach needs one press that always returns to the
 * launcher. HOME stays HOME whatever is in the foreground; the negative control in the tests
 * exists to keep it that way.
 *
 * ── Foreground, not focus ───────────────────────────────────────────────────────────────────────
 * Forwarding is gated on the foreground *package* being a known frontend from [GameApps], read
 * from `dumpsys activity`. Gating on "the launcher is not focused" would forward wheel presses to
 * the vendor's reverse-camera window or a phone call, which is exactly the wrong moment to be
 * pressing A.
 */
object WheelGamepad {

    /** Android `KeyEvent` key codes. Constants rather than the class so this stays JVM-testable. */
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_START = 108

    /** The key code a wheel press becomes in a game, or null for a press that keeps its meaning. */
    fun keyCodeFor(nav: NavKey): Int? = when (nav) {
        NavKey.UP -> KEYCODE_DPAD_UP
        NavKey.DOWN -> KEYCODE_DPAD_DOWN
        NavKey.LEFT, NavKey.MEDIA_PREV -> KEYCODE_DPAD_LEFT
        NavKey.RIGHT, NavKey.MEDIA_NEXT -> KEYCODE_DPAD_RIGHT
        NavKey.CENTER -> KEYCODE_BUTTON_A
        NavKey.BACK -> KEYCODE_BUTTON_B
        NavKey.MEDIA_PLAY_PAUSE -> KEYCODE_BUTTON_START

        // HOME is the way out. OPEN_* are the wheel's source keys and keep opening our screens.
        NavKey.HOME, NavKey.OPEN_MEDIA, NavKey.OPEN_RADIO, NavKey.OPEN_PHONE -> null
    }

    /** Whether [packageName] is an emulator frontend we forward to. Null is never a game. */
    fun isGame(packageName: String?): Boolean =
        packageName != null && GameApps.KNOWN.any { it.packageName == packageName }

    /**
     * The package that owns the foreground activity, from a `dumpsys activity activities` line
     * such as `topResumedActivity=ActivityRecord{... com.retroarch.aarch64/com.retroarch.browser.
     * retroactivity.RetroActivityFuture t12}`. Null when the line does not carry one.
     */
    fun packageFromTopResumed(line: String?): String? {
        val text = line ?: return null
        val match = TOP_RESUMED.find(text) ?: return null
        return match.groupValues[1]
    }

    /** `<package>/<activity>` inside the record; the package is everything before the slash. */
    private val TOP_RESUMED = Regex("""topResumedActivity=.*?\s([a-zA-Z][\w.]*)/[\w.$]+""")
}
