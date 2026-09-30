package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.ripostelabs.carlauncher.carlib.RootShell
import java.time.Instant
import java.time.ZoneId

/**
 * RAV4-175: automatic time, automatic zone, the zone itself and the language, as stock's
 * System page has them (DataManage.java:221-238, GlobalDataStatic.java:84).
 *
 *     Settings row ──▶ autoCommand / zoneCommand ──▶ root shell ──▶ Settings.Global, AlarmManager
 *     Language row ──▶ Android's language page
 *
 * The launcher is not platform-signed, and a privileged permission the image's allowlist lacks
 * stops the boot, so writes go through the root shell as the other Settings writes do. Stock's
 * DST switch (`can_dst_set_key`) added an hour to a fixed offset. The image keeps a real zone,
 * so daylight saving follows the zone and the row only reports it.
 */
object SystemTime {

    enum class Auto { TIME, ZONE }

    enum class Switch { ON, OFF }

    enum class Dst { IN_EFFECT, NOT_NOW, NEVER }

    /** Region zones only: Etc/GMT+8 and friends read backwards and confuse a driver. */
    private val REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

    private val ZONES: List<String> by lazy {
        ZoneId.getAvailableZoneIds().filter { it.substringBefore('/') in REGIONS && '/' in it }.sorted()
    }

    fun zones(): List<String> = ZONES

    fun autoCommand(auto: Auto, switch: Switch): String {
        val key = when (auto) {
            Auto.TIME -> Settings.Global.AUTO_TIME
            Auto.ZONE -> Settings.Global.AUTO_TIME_ZONE
        }
        val value = when (switch) {
            Switch.ON -> 1
            Switch.OFF -> 0
        }
        return "settings put global $key $value"
    }

    /** Null for anything the picker does not offer, so no free text reaches the shell. */
    fun zoneCommand(id: String): String? {
        if (id !in ZONES) {
            return null
        }

        return "cmd alarm set-timezone $id"
    }

    fun dst(zone: ZoneId, now: Instant): Dst {
        val rules = zone.rules
        if (rules.isDaylightSavings(now)) {
            return Dst.IN_EFFECT
        }

        return if (rules.nextTransition(now) == null) Dst.NEVER else Dst.NOT_NOW
    }

    fun isAuto(context: Context, auto: Auto): Boolean {
        val key = when (auto) {
            Auto.TIME -> Settings.Global.AUTO_TIME
            Auto.ZONE -> Settings.Global.AUTO_TIME_ZONE
        }
        return Settings.Global.getInt(context.contentResolver, key, 1) == 1
    }

    fun setAuto(auto: Auto, switch: Switch, shell: (String) -> RootShell.Result = RootShell::exec): Boolean =
        shell(autoCommand(auto, switch)).ok

    fun setZone(id: String, shell: (String) -> RootShell.Result = RootShell::exec): Boolean {
        val command = zoneCommand(id) ?: return false
        return shell(command).ok
    }

    /** Android's language page: changing the system locale needs a permission only the platform holds. */
    fun openLanguages(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
