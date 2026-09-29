package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * PhoneInternet — the head unit borrows the CarPlay iPhone's internet over Bluetooth PAN.
 *
 * Riposte OS 0.2 runs PanService in the PANU role (`bt-pan`, DHCP by the Bluetooth APEX's
 * tethering network factory). Android only dials PAN when someone flips the per-device
 * "Internet access" switch; this class flips it when a CarPlay session comes up.
 *
 * ```
 *  zlink CONNECTED ──▶ onSessionUp(address?) ──▶ PhoneInternetRules.target ──▶ PanLink.connect
 *                                                                              │ policy ALLOWED
 *                                                                              ▼ + connect
 *  iPhone Personal Hotspot ◀── PAN (PANU → NAP) ◀── PanService ◀───────────────┘
 *        (checked after 5 / 10 / 20 / 40 s; still down after the last = hotspot off)
 * ```
 *
 * The switch is kept in the Bluetooth database across reboots, but nothing dials from it:
 * AOSP's PhonePolicy only connects PAN when another profile (HFP, A2DP) of that phone comes
 * up, and Settings draws the switch from the live link, so it reads "off" after every boot.
 * [keepUp] closes that gap: every minute it redials a switched-on phone whose PAN is down.
 *
 * Routing is left alone: Android's network ranking keeps Wi-Fi (the car router) ahead of
 * the Bluetooth network. A session ending only stops the retries; the PAN link stays until
 * the phone drops it. iOS cannot be told to turn its hotspot on, so "hotspot off" is logged.
 */
class PhoneInternet(
    private val link: PanLink,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /** Bumped on every session edge, so retries of an older session drop out. */
    private var session = 0

    /** Target of the live session; null when none. */
    private var target: String? = null

    /** Keep-up rounds so far; picks which switched-on phone the next round dials. */
    private var round = 0

    /**
     * Redial, CarPlay or not, the phone whose "Internet access" switch is on: 10 s after the
     * launcher starts (the PAN proxy binds meanwhile), then every minute while PAN is down.
     * A phone out of range costs one failed page per minute.
     */
    fun keepUp() {
        schedule(PhoneInternetRules.KEEP_FIRST_MS) { sweep() }
    }

    /** One keep-up round; reschedules itself. */
    @Synchronized
    private fun sweep() {
        val allowed = link.bonded().map { it.address }.filter(link::allowed)
        val pick = PhoneInternetRules.redial(allowed.associateWith(link::state), round++)
        if (pick != null) {
            val asked = link.connect(pick)
            log("keep-up: PAN connect $pick ${if (asked) "requested" else "refused by the stack"}")
        }
        schedule(PhoneInternetRules.KEEP_MS) { sweep() }
    }

    /** A CarPlay session came up; [address] is the phone's, when the projection app names it. */
    @Synchronized
    fun onSessionUp(address: String?) {
        val pick = PhoneInternetRules.target(address, link.bonded())
        if (pick == null) {
            log("no single iPhone to ask for internet (CarPlay gave no address); doing nothing")
            return
        }

        // A repeated CONNECTED for the same phone is already being handled.
        if (pick == target) {
            return
        }
        session++
        target = pick
        attempt(session, pick, 0)
    }

    /** The session ended: stop retrying. The PAN link is left for the phone to drop. */
    @Synchronized
    fun onSessionDown() {
        session++
        target = null
    }

    /** Check [address]; connect it when down, then look again after the backoff. */
    @Synchronized
    private fun attempt(id: Int, address: String, n: Int) {
        if (id != session) {
            return
        }
        val state = link.state(address)
        if (state == PanState.CONNECTED) {
            log("connected: using $address's internet over Bluetooth")
            return
        }

        val delay = PhoneInternetRules.backoffMs(n)
        if (delay == null) {
            log("gave up on $address after $n tries: iPhone hotspot off? (Settings > Personal Hotspot)")
            return
        }
        if (state != PanState.CONNECTING) {
            val asked = link.connect(address)
            log("try ${n + 1}: PAN connect $address ${if (asked) "requested" else "refused by the stack"}")
        }
        schedule(delay) { attempt(id, address, n + 1) }
    }

    private companion object {
        const val TAG = "PhoneInternet"
    }
}

/** A bonded Bluetooth device as [PhoneInternetRules] sees it. */
data class BtPeer(val address: String, val name: String?, val aclConnected: Boolean)

/** `BluetoothProfile.STATE_*` for the PAN link, folded to what [PhoneInternet] acts on. */
enum class PanState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ;

    companion object {
        /** `BluetoothProfile.STATE_CONNECTING` / `STATE_CONNECTED`; the rest count as down. */
        private const val STATE_CONNECTING = 1
        private const val STATE_CONNECTED = 2

        fun of(state: Int): PanState = when (state) {
            STATE_CONNECTED -> CONNECTED
            STATE_CONNECTING -> CONNECTING
            else -> DISCONNECTED
        }
    }
}

/** The PAN profile proxy behind an interface, so [PhoneInternet] runs on the JVM. */
interface PanLink {
    fun bonded(): List<BtPeer>
    fun state(address: String): PanState
    /** True when PAN's connection policy for [address] is ALLOWED: the "Internet access" switch. */
    fun allowed(address: String): Boolean
    /** Allow PAN for [address] and dial it. False when the stack refused the request. */
    fun connect(address: String): Boolean
}

/** Pure decisions behind [PhoneInternet]. */
object PhoneInternetRules {

    /** Checks after each connect: 5, 10, 20, 40 s, then give up. */
    private val BACKOFF_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)

    /** First keep-up round after start, then the gap between rounds. */
    internal const val KEEP_FIRST_MS = 10_000L
    internal const val KEEP_MS = 60_000L

    /** Bonded names iOS gives out, e.g. "Sasha's iPhone", "iPhone 15". */
    private const val IPHONE_MARK = "iphone"

    /** The delay before the check after try [n] (0-based); null once the tries are spent. */
    fun backoffMs(n: Int): Long? = BACKOFF_MS.getOrNull(n)

    /**
     * The switched-on phone to redial from [states] (address → PAN state), taking turns by
     * [round] when several are down. Null while any PAN link is up or coming up: PANU holds one.
     */
    fun redial(states: Map<String, PanState>, round: Int): String? {
        if (states.values.any { it != PanState.DISCONNECTED }) {
            return null
        }
        val down = states.keys.toList()
        if (down.isEmpty()) {
            return null
        }
        return down[round % down.size]
    }

    /**
     * The phone to ask: CarPlay's [address] when it is bonded; else the one bonded iPhone;
     * with several, the one iPhone on an ACL link (CarPlay's iAP2 RFCOMM keeps it up).
     * Null when that leaves anything but exactly one.
     */
    fun target(address: String?, bonded: List<BtPeer>): String? {
        if (address != null) {
            return bonded.firstOrNull { it.address.equals(address, ignoreCase = true) }?.address
        }
        val iphones = bonded.filter { it.name?.contains(IPHONE_MARK, ignoreCase = true) == true }
        if (iphones.size == 1) {
            return iphones.single().address
        }
        return iphones.filter { it.aclConnected }.singleOrNull()?.address
    }
}
