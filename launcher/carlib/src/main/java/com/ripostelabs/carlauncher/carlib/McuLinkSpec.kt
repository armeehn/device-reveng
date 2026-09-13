package com.ripostelabs.carlauncher.carlib

/**
 * McuLinkSpec — which carrier the car link rides on, from one system property.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     getprop riposte.mcu.link ──▶ parse() ──▶ McuLinkSpec ──▶ open() ──▶ McuLink ──▶ McuOwner
 *     getprop riposte.canbus.link ─▶ parse() ──▶ McuLinkSpec ──▶ open() ──▶ McuLink ──▶ SlcanLinkSource
 *
 * ── Grammar ─────────────────────────────────────────────────────────────────────────────────────
 *
 *     tty:<path>[@<baud>]   a serial node set up with stty; `@0` = leave the speed alone
 *     dev:<path>            a character device with no line discipline (QEMU virtserialport)
 *     tcp:<host>:<port>     a socket; 10.0.2.2 is the emulator's alias for its host
 *
 * An unset or blank property means the vendor tty (`/dev/ttyHS1` @ 115200), so a car that never
 * heard of this property behaves exactly as before. A malformed value is an error, not a fallback:
 * silently opening the vendor tty on a desk rig would read as "MCU silent".
 */
sealed class McuLinkSpec {

    data class Tty(val path: String, val baud: Int) : McuLinkSpec()

    data class Dev(val path: String) : McuLinkSpec()

    data class Tcp(val host: String, val port: Int) : McuLinkSpec()

    fun open(): McuLink = when (this) {
        is Tty -> TtyLink.open(path, baud)
        is Dev -> CharDevLink.open(path)
        is Tcp -> TcpLink.open(host, port)
    }

    companion object {
        /** Carrier for the MCU conversation ([McuOwner]). */
        const val PROP_MCU_LINK = "riposte.mcu.link"

        /** Carrier for the raw body bus in slcan text ([SlcanLinkSource]); unset = no such link. */
        const val PROP_CANBUS_LINK = "riposte.canbus.link"

        private const val SCHEME_TTY = "tty"
        private const val SCHEME_DEV = "dev"
        private const val SCHEME_TCP = "tcp"
        private const val SCHEME_SEPARATOR = ':'
        private const val BAUD_SEPARATOR = '@'
        private const val PORT_MAX = 65535

        val VENDOR: McuLinkSpec = Tty(TtyLink.VENDOR_PATH, TtyLink.VENDOR_BAUD)

        /** Blank → [VENDOR]. Anything else must parse, or this throws with the offending text. */
        fun parse(text: String?): McuLinkSpec {
            val value = text?.trim().orEmpty()
            if (value.isEmpty()) {
                return VENDOR
            }

            val scheme = value.substringBefore(SCHEME_SEPARATOR)
            val rest = value.substringAfter(SCHEME_SEPARATOR, missingDelimiterValue = "")
            require(rest.isNotEmpty()) { "link spec '$value' has no target" }

            return when (scheme) {
                SCHEME_TTY -> parseTty(rest)
                SCHEME_DEV -> Dev(rest)
                SCHEME_TCP -> parseTcp(rest)
                else -> throw IllegalArgumentException("link spec '$value': unknown scheme '$scheme'")
            }
        }

        /** Blank → null: the bus link is optional, unlike the MCU link. */
        fun parseOptional(text: String?): McuLinkSpec? {
            if (text.isNullOrBlank()) {
                return null
            }
            return parse(text)
        }

        private fun parseTty(rest: String): Tty {
            val path = rest.substringBefore(BAUD_SEPARATOR)
            val baudText = rest.substringAfter(BAUD_SEPARATOR, missingDelimiterValue = "")
            if (baudText.isEmpty()) {
                return Tty(path, TtyLink.VENDOR_BAUD)
            }

            val baud = baudText.toIntOrNull()
            require(baud != null && baud >= TtyLink.BAUD_UNCHANGED) { "tty baud '$baudText' is not a number" }
            return Tty(path, baud)
        }

        private fun parseTcp(rest: String): Tcp {
            val host = rest.substringBeforeLast(SCHEME_SEPARATOR)
            val portText = rest.substringAfterLast(SCHEME_SEPARATOR, missingDelimiterValue = "")
            val port = portText.toIntOrNull()
            require(host.isNotEmpty() && port != null && port in 1..PORT_MAX) { "tcp spec '$rest' is not host:port" }
            return Tcp(host, port)
        }
    }
}
