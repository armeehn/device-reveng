package com.ripostelabs.carlauncher.data

/**
 * v0.4.3.8 — the vendor SysVar keys the raw browser ([com.ripostelabs.carlauncher.ui.settings.AdvancedSettingsScreen])
 * must never write.
 *
 * The browser enumerates the **live** table — all ~455 keys — and lets any row be replaced with
 * free text. That is the point of it, and for almost every key the worst case is a setting that
 * looks wrong until you put it back. For the handful below the worst case is a unit that does not
 * come back, so those rows are rendered read-only with the reason shown next to them.
 *
 * This is a refuse-list, not an allow-list, on purpose: an allow-list would silence the browser's
 * whole reason to exist (surfacing keys we have not catalogued). Each entry carries **why** it is
 * here, because a future reader deleting one needs to know what it costs.
 *
 * Nothing here writes to the vehicle. [reasonFor] is a pure lookup.
 */
object ProtectedSettingKeys {

    /**
     * Key → the one-line reason it cannot be edited here. Shown verbatim in the browser.
     */
    private val REASONS: Map<String, String> = mapOf(
        // v2.4.2 incident, recorded in SteeringWheelSettingsScreen's KDoc: the gateway stores a
        // JSON object under this key and does Gson.fromJson on it inside
        // EventService.initSysEventState. A scalar there throws during init, so the vendor gateway
        // crash-loops ON BOOT and takes the top bar, SWC and HVAC with it. The SWC screen stopped
        // writing it; typing "1" into this row reached the same write by the back door.
        SettingKeys.WHEEL_KEY_LEARN_CUSTOM to
            "Vendor stores JSON here — a plain value crash-loops the head unit on boot",
        SettingKeys.WHEEL_CUSTOM_KEY_SAVE to
            "Commits a wheel-key learn we cannot construct — same boot crash-loop",

        // The gateway reads the panel geometry from these at startup. A wrong width, height or
        // density gives an unreadable or blank screen, and the screen is how you would fix it.
        SettingKeys.SCREEN_WIDTH to
            "Panel geometry — a wrong value leaves no readable screen to fix it from",
        SettingKeys.SCREEN_HEIGHT to
            "Panel geometry — a wrong value leaves no readable screen to fix it from",
        SettingKeys.SCREEN_DENSITY to
            "Panel geometry — a wrong value leaves no readable screen to fix it from",

        // Serial link speeds. Get one wrong and the head unit stops talking to the MCU or the CAN
        // box: no reverse camera, no steering-wheel keys, no climate — and no way back through
        // this app, which needs that same link.
        SettingKeys.CAN_BAUD_RATE to
            "Link speed to the CAN box — a wrong value silences reverse, SWC and climate",
        SettingKeys.MCU_COM_BAUDRATE to
            "Link speed to the MCU — a wrong value silences reverse, SWC and climate",
        SettingKeys.AIR_CONDITIONING_BAUD to
            "Link speed to the A/C panel — a wrong value silences climate control",
    )

    /** The reason [key] is read-only in the raw browser, or null if it is freely editable. */
    fun reasonFor(key: String): String? = REASONS[key]

    /** True when the raw browser must not offer an edit for [key]. */
    fun isProtected(key: String): Boolean = REASONS.containsKey(key)
}
