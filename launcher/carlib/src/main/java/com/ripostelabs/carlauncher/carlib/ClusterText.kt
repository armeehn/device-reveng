package com.ripostelabs.carlauncher.carlib

import java.time.LocalDateTime
import java.util.Locale

/** One box payload for the cluster, `[len, cmd, data…]`; only [ClusterText] builds one. */
class ClusterFrame internal constructor(val payload: IntArray)

/**
 * ClusterText — what stock canbus2 tells the instrument cluster through the HiWorld box
 * (`HiworldCanParseToyota.java`), as fixed-size payloads that [McuOwnerProtocol.cluster] wraps:
 *
 * ```
 *  0D 91 band+1 "NN" … "98.1"      station: bank, preset, frequency   SendRadioInfo       :1034
 *  20 id  UTF-16LE text (28 B)     title 92, album 93, artist 94,     sendMediaStrToCan   :1172
 *                                   caller name C4                    handleBTPhoneNameEvent :1247
 *  1B CD state 00 00 number        phone state, number when ringing   handleBTPhoneNumEvent  :1214
 *  0A CB 00 HH mm 00 00 24h yy M d clock and date                     sendSysRTCTimerToCan   :1958
 * ```
 *
 * UNVERIFIED ON THE CAR: no capture shows the cluster reacting. The bytes follow stock; whether
 * the RAV4's cluster shows each field depends on trim.
 */
object ClusterText {

    /** The `20 id` text fields. */
    enum class Field(val code: Int) {
        TITLE(0x92),
        ALBUM(0x93),
        ARTIST(0x94),
        CALLER(0xC4),
    }

    /** `DateFormat.is24HourFormat`, byte 7 of the clock frame. */
    enum class HourFormat(val code: Int) {
        H12(0),
        H24(1),
    }

    /** btsuite's `HBCP_STATUS_HSHF_*` values (BTUtils.java:115-118), as the vendor feed carries them. */
    const val HSHF_CONNECTED = 3
    const val HSHF_OUTGOING = 4
    const val HSHF_INCOMING = 5
    const val HSHF_ACTIVE = 6

    private const val CMD_RADIO = 0x91
    private const val CMD_TEXT = 0x20
    private const val CMD_PHONE = 0xCD
    private const val CMD_CLOCK = 0xCB

    private const val RADIO_SIZE = 15
    private const val TEXT_SIZE = 34
    private const val PHONE_SIZE = 29
    private const val CLOCK_SIZE = 12

    /** Stock caps: 28 bytes of text, 22 of number (11 chars, cut earlier at :603). */
    private const val TEXT_MAX_BYTES = 28
    private const val NUMBER_MAX_BYTES = 22
    private const val NUMBER_AT = 5

    private const val FIRST_AM_BAND = 3
    private const val CLOCK_YEAR_BASE = 2000

    /** Stock's phone byte: connected 0, incoming 1, outgoing 2, active 4, anything else 6. */
    private const val PHONE_IDLE = 0
    private const val PHONE_INCOMING = 1
    private const val PHONE_OUTGOING = 2
    private const val PHONE_ACTIVE = 4
    private const val PHONE_NONE = 6

    /**
     * The station line. [preset] is the tuner's 0-based current preset (`getRadioNum`), shown
     * 1-based; FM [freq] is 10 kHz units shown in MHz to one place, AM is whole kHz.
     */
    fun radio(band: Int, preset: Int, freq: Int): ClusterFrame {
        val out = frame(RADIO_SIZE, CMD_RADIO)
        out[2] = band + 1
        ascii(String.format(Locale.US, "%02d", preset + 1)).copyInto(out, 3)

        val shown = if (band < FIRST_AM_BAND) {
            String.format(Locale.US, "%.1f", freq * 0.01f)
        } else {
            freq.toString()
        }
        val bytes = ascii(shown)
        bytes.copyInto(out, RADIO_SIZE - bytes.size)
        return ClusterFrame(out)
    }

    fun text(field: Field, text: String): ClusterFrame {
        val out = frame(TEXT_SIZE, CMD_TEXT)
        out[1] = field.code
        utf16(text, TEXT_MAX_BYTES).copyInto(out, 2)
        return ClusterFrame(out)
    }

    /** The phone line; stock adds the number only while a call rings or dials. */
    fun call(hshf: Int, number: String?): ClusterFrame {
        val out = frame(PHONE_SIZE, CMD_PHONE)
        out[2] = phoneCode(hshf)

        val ringing = hshf == HSHF_INCOMING || hshf == HSHF_OUTGOING
        if (ringing && !number.isNullOrEmpty()) {
            utf16(number, NUMBER_MAX_BYTES).copyInto(out, NUMBER_AT)
        }
        return ClusterFrame(out)
    }

    fun clock(now: LocalDateTime, format: HourFormat): ClusterFrame {
        val out = frame(CLOCK_SIZE, CMD_CLOCK)
        out[3] = now.hour
        out[4] = now.minute
        out[7] = format.code
        out[8] = maxOf(now.year, CLOCK_YEAR_BASE) - CLOCK_YEAR_BASE
        out[9] = now.monthValue
        out[10] = now.dayOfMonth
        return ClusterFrame(out)
    }

    private fun phoneCode(hshf: Int): Int = when (hshf) {
        HSHF_CONNECTED -> PHONE_IDLE
        HSHF_OUTGOING -> PHONE_OUTGOING
        HSHF_INCOMING -> PHONE_INCOMING
        HSHF_ACTIVE -> PHONE_ACTIVE
        else -> PHONE_NONE
    }

    /** `new byte[size]` with stock's length byte (size - 2) and command. */
    private fun frame(size: Int, cmd: Int): IntArray {
        val out = IntArray(size)
        out[0] = size - 2
        out[1] = cmd
        return out
    }

    private fun ascii(s: String): IntArray = s.toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }.toIntArray()

    /** Stock's stringToUnicode0: low byte then high byte per char (CanDataParseBase.java:1810). */
    private fun utf16(s: String, maxBytes: Int): IntArray =
        s.flatMap { listOf(it.code and 0xFF, it.code shr 8) }.take(maxBytes).toIntArray()
}

/**
 * Sends the cluster what changed, as stock does: each text field only when it differs from the
 * last one sent (`mStrTitleOld` and friends, :1128-1154), the phone line only on a new state
 * (`mOldBtState`, :1192), the station on a new station. The clock goes every time it is asked.
 */
class ClusterFeed(private val send: (ClusterFrame) -> Unit) {

    private val lastText = HashMap<ClusterText.Field, String>()
    private var lastStation: Triple<Int, Int, Int>? = null
    private var lastCall: Pair<Int, String?>? = null

    /** The playing app's metadata; blank reads as stock's "unknown". NowPlaying has no album. */
    fun onMedia(title: String?, artist: String?) {
        sendText(ClusterText.Field.TITLE, title)
        sendText(ClusterText.Field.ARTIST, artist)
        sendText(ClusterText.Field.ALBUM, null)
    }

    fun onRadio(state: RadioState) {
        if (state.freq == 0) {
            return
        }
        val station = Triple(state.band, state.presetNumber, state.freq)
        if (station == lastStation) {
            return
        }
        lastStation = station
        send(ClusterText.radio(state.band, state.presetNumber, state.freq))
    }

    /** [hshf] is the vendor feed's `HBCP_STATUS_HSHF_*`; [name] is the phonebook's, when found. */
    fun onCall(hshf: Int?, number: String?, name: String?) {
        val call = (hshf ?: 0) to number
        if (call != lastCall) {
            lastCall = call
            send(ClusterText.call(call.first, number))
        }
        if (name.isNullOrBlank()) {
            return
        }
        sendText(ClusterText.Field.CALLER, name)
    }

    fun onClock(now: LocalDateTime, format: ClusterText.HourFormat) {
        send(ClusterText.clock(now, format))
    }

    private fun sendText(field: ClusterText.Field, value: String?) {
        val text = value?.takeIf { it.isNotBlank() } ?: UNKNOWN
        if (lastText[field] == text) {
            return
        }
        lastText[field] = text
        send(ClusterText.text(field, text))
    }

    private companion object {
        /** `EnvironmentCompat.MEDIA_UNKNOWN`, what stock sends for an empty field. */
        const val UNKNOWN = "unknown"
    }
}
