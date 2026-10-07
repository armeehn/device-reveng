package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode

/**
 * Which sources come back after a cold boot (RAV4-170), as stock's `Sys_Last_Mode` does
 * (EventService.java:3898-3905).
 *
 *     setSource(MUSIC) ──▶ OwnerHost stores 11 ──▶ reboot ──▶ lastSource() = 11 ──▶ launcher opens Music
 *     setSource(NULL)  ──▶ not stored: the owner's own modes never replace the person's choice
 */
object SourceResume {

    /**
     * The sources a person picks. CarPlay is one: without it, an older Music pick outlived every
     * CarPlay drive and reopened at each cold boot. The launcher opens no app for CarPlay, since
     * the phone reconnects by itself.
     */
    private val PLAYABLE = setOf(Mode.RADIO, Mode.BT_MUSIC, Mode.MOVIE, Mode.MUSIC, Mode.CARPLAY)

    fun keeps(mode: Mode): Boolean = mode in PLAYABLE

    /** A stored code back to its mode; null for none or for a mode that is not kept. */
    fun parse(code: Int): Mode? = Mode.entries.firstOrNull { it.code == code }?.takeIf(::keeps)
}
