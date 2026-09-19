package com.ripostelabs.carlauncher.carlib

/**
 * The source title the owner path answers where the vendor gateway answered
 * `getValidModeTitleInfor` ("Bluetooth", "USB", the built-in player). On Riposte OS 0.2 the
 * launcher sets the MCU mode itself, so the last mode it set is the source; modes that are
 * not an audio source (NONE, POWER_ON, MCU_VERSION, IDLE, ...) have no title.
 */
object SourceTitle {

    private val TITLES: Map<McuOwnerProtocol.Mode, String> = mapOf(
        McuOwnerProtocol.Mode.RADIO to "Radio",
        McuOwnerProtocol.Mode.BT to "Bluetooth",
        McuOwnerProtocol.Mode.BT_MUSIC to "Bluetooth",
        McuOwnerProtocol.Mode.MUSIC to "Music",
        McuOwnerProtocol.Mode.ANDROID to "Android",
        McuOwnerProtocol.Mode.CARPLAY to "CarPlay",
        McuOwnerProtocol.Mode.AUX to "AUX",
    )

    /** Null when no audio source is selected. */
    fun of(mode: McuOwnerProtocol.Mode?): String? = mode?.let { TITLES[it] }
}
