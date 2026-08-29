package com.reveng.carlauncher.carlib

/**
 * LinClimateDecoder — pure-Kotlin decoder for the **Toyota RAV4 (XA50, 2019 Hybrid) climate LIN
 * bus**, the A/C-amplifier(master) ↔ climate-panel(slave) link. This is a THIRD, independent car
 * data path in the launcher, alongside the raw powertrain CAN bus ([RawCanDecoder]) and the vendor
 * HiWorld digest ([HiworldCanDecoder]). All three fold into ONE model — see [VehicleState] /
 * [LinClimateState] and the merge seams in `LinReaderService.kt`.
 *
 * Ground truth: the bitzero.tech / ufnalski RAV4 XA50 climate reverse-engineering notes. **We only
 * ever listen** — nothing here (or in the reader) builds a LIN frame to transmit.
 *
 * ── LIN on the wire, and what a plain USB-UART actually sees ────────────────────────────────────
 * A LIN frame is: BREAK, sync `0x55`, protected-ID byte (PID), N data bytes, checksum. The bus is
 * **19200 baud, 8N1**. A real LIN transceiver frames the BREAK in hardware; a *plain* USB-UART with
 * no LIN framing usually surfaces the BREAK as a spurious `0x00` (a framing error read as a null
 * byte) right before the `0x55`. So the robust strategy — implemented by the reader and mirrored in
 * [selfTest] — is to **sync on `0x55`**, take the next byte as the PID, then slice a known number of
 * data bytes via [frameDataLenFor] and read one trailing checksum byte.
 *
 * ── Protected ID vs raw 6-bit ID (the parity subtlety) ──────────────────────────────────────────
 * A LIN PID = 6-bit frame ID in bits 0..5 plus two parity bits:
 *   P0 = ID0 ^ ID1 ^ ID2 ^ ID4         (bit 6)
 *   P1 = ~(ID1 ^ ID3 ^ ID4 ^ ID5)      (bit 7)
 * The RE notes quote the **observed post-sync byte** as `0xB1` (status) and `0x39` (buttons). Working
 * the parity out (see [selfTest]):
 *   • `0xB1` = PID for raw ID **0x31** with parity bits `0b10` — i.e. `0xB1` really is the
 *     parity-carrying PID, and the "frame ID" you'd quote in a LIN description file is `0x31`.
 *   • `0x39` = PID for raw ID **0x39** whose parity bits both happen to be `0b00`, so the PID equals
 *     its own raw ID numerically. This one is genuinely ambiguous on paper (PID-with-parity vs raw
 *     ID) precisely because its parity is zero.
 * We therefore **match on the observed byte** (`0xB1` / `0x39`, the [PID_STATUS] / [PID_BUTTONS]
 * constants) but also expose the stripped 6-bit id via [rawId] and a parity check via [pidValid].
 *
 * ── Checksum: classic vs enhanced (documented ambiguity) ────────────────────────────────────────
 * LIN has two checksum forms over a modulo-255 carry-folded sum, then one's-complemented:
 *   • **classic**  — sum of the DATA bytes only (LIN 1.x).
 *   • **enhanced** — sum of the PID + the DATA bytes (LIN 2.x). The RE notes describe the enhanced
 *     form (`~(sum of ID + all data)`), so [enhancedChecksum] is the primary; [classicChecksum] is
 *     the fallback. [verifyChecksum] tries both and reports which validated. IMPORTANT: the bitzero
 *     example payload `80 0X 13 00 2C 2C 00 81` is the **8 data bytes only** — it does NOT include
 *     the trailing checksum byte — so which variant the amplifier actually emits can only be nailed
 *     from a live capture (grab the real checksum byte, run [verifyChecksum]). Until then the
 *     variant is UNCONFIRMED; the reader treats a checksum mismatch as "note but still decode",
 *     never as a hard drop, so an unconfirmed variant can't blind the UI.
 *
 * Pure Kotlin, zero Android imports — unit-testable off-device via [selfTest] / [main].
 */
object LinClimateDecoder {

    // ── Observed post-sync ID bytes (PID, parity included). Match on these verbatim. ────────────
    /** Status frame, amp→panel, 8 data bytes. PID `0xB1` = raw 6-bit id `0x31`, parity `0b10`. */
    const val PID_STATUS = 0xB1
    /** Button-response frame, amp polls panel, 8 data bytes. PID `0x39` = raw id `0x39`, parity `0b00`. */
    const val PID_BUTTONS = 0x39

    /** Base (no-button) payload of the [PID_BUTTONS] frame; a byte differing from base = a press. */
    private val BUTTONS_BASE = intArrayOf(0x40, 0x00, 0x00, 0x00, 0x10, 0x90, 0x00, 0x00)

    /** Other IDs seen on this bus in the RE notes; we decode them to [LinSignal.Unknown]. */
    val OBSERVED_OTHER_IDS = intArrayOf(0x32, 0xBA, 0xF5, 0x78, 0x76)

    // ── HVAC air-distribution mode (status byte 2, low nibble). VAL per the RE notes. ───────────
    enum class Mode(val code: Int) {
        FACE(0x1), FACE_FEET(0x2), FEET(0x3), FEET_DEFROST(0x4), DEFROST(0x9), UNKNOWN(-1);
        companion object {
            fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /** A single panel/amp button event decoded out of a [PID_BUTTONS] frame. */
    enum class Button {
        OFF, AUTO,
        AC, ECO, FAN_DOWN, FAN_UP,
        MODE, S_MODE,
        SYNC, FRONT_DEFROST, REAR_DEFROST,
        DRIVER_TEMP_DOWN, DRIVER_TEMP_UP,
        PASSENGER_TEMP_DOWN, PASSENGER_TEMP_UP,
        RECIRC,
    }

    /** Which checksum form validated a frame — see class KDoc. */
    enum class ChecksumKind { ENHANCED, CLASSIC, NONE }

    /**
     * Number of DATA bytes (excluding PID and the trailing checksum byte) for [id], or null if the
     * frame length is unknown. The reader uses null to mean "unknown layout — resync on the next
     * `0x55`" rather than guessing a length and desyncing the stream.
     */
    fun frameDataLenFor(id: Int): Int? = when (id) {
        PID_STATUS -> 8
        PID_BUTTONS -> 8
        else -> null
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // PID / parity helpers
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** The stripped 6-bit LIN frame id (PID with its two parity bits masked off). */
    fun rawId(pid: Int): Int = pid and 0x3F

    /** The two parity bits carried in PID bits 6..7 (bit6 = P0, bit7 = P1). */
    fun parityBits(pid: Int): Int = (pid ushr 6) and 0x3

    /** Compute the full 8-bit LIN PID (id + parity) from a 6-bit frame [id6]. */
    fun computePid(id6: Int): Int {
        val id = id6 and 0x3F
        fun b(n: Int) = (id ushr n) and 1
        val p0 = b(0) xor b(1) xor b(2) xor b(4)
        val p1 = (b(1) xor b(3) xor b(4) xor b(5)) xor 1   // ~(...) in one bit
        return id or (p0 shl 6) or (p1 shl 7)
    }

    /** True if [pid]'s parity bits match those computed from its low 6 bits. */
    fun pidValid(pid: Int): Boolean = computePid(rawId(pid)) == (pid and 0xFF)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Checksums (LIN modulo-255 carry-folded sum, then one's-complement)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Fold a running sum back into 0..0xFF the LIN way (add the carry above 0xFF back in). */
    private fun foldSum(seed: Int, data: ByteArray): Int {
        var sum = seed
        for (byte in data) {
            sum += byte.toInt() and 0xFF
            if (sum > 0xFF) sum -= 0xFF
        }
        return sum
    }

    /**
     * Enhanced (LIN 2.x) checksum over PID + data: `~(fold(pid, data)) & 0xFF`. This is the RE
     * notes' `~(sum of ID + all data)` form, with the standard carry-fold made explicit (for the
     * short frames here it reduces to the plain `~sum & 0xFF` the notes quote).
     */
    fun enhancedChecksum(pid: Int, data: ByteArray): Int =
        foldSum(pid and 0xFF, data).inv() and 0xFF

    /** Classic (LIN 1.x) checksum over the DATA bytes only: `~(fold(0, data)) & 0xFF`. */
    fun classicChecksum(data: ByteArray): Int =
        foldSum(0, data).inv() and 0xFF

    /**
     * Try both checksum forms against an observed trailing [checksum] byte. Returns which one
     * validated (enhanced preferred), or [ChecksumKind.NONE] if neither did.
     */
    fun verifyChecksum(pid: Int, data: ByteArray, checksum: Int): ChecksumKind = when (checksum and 0xFF) {
        enhancedChecksum(pid, data) -> ChecksumKind.ENHANCED
        classicChecksum(data) -> ChecksumKind.CLASSIC
        else -> ChecksumKind.NONE
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Temperature scale — UNCONFIRMED (see class KDoc / LIN_INTEGRATION.md)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Best-guess set-point mapping for a raw temp byte, in °C. **UNCONFIRMED** — pending a
     * toggle-and-diff capture. The example driver/passenger byte is `0x2C` (44); `raw * 0.5` puts
     * that at **22.0 °C**, comfortably inside the RAV4 climate range (~16–30 °C). An alternative
     * seen on the vendor CANBOX `AIR_CONDITIONER` key used a 5..33 set-point range; if the real
     * scale turns out to be a direct code, replace this one function. Callers should also keep the
     * raw byte (they do — see [LinSignal.ClimateStatus.driverTempRaw]) so the UI can show it while
     * the scale is unconfirmed.
     */
    fun setpointGuessC(raw: Int): Double = (raw and 0xFF) * 0.5

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Decode
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Decode one LIN frame's PID + data payload. [id] is the observed post-sync PID byte
     * ([PID_STATUS] / [PID_BUTTONS]); [data] is the frame's data bytes (checksum already stripped by
     * the reader). Returns null for a frame we do not map (cheap drop), or a [LinSignal] otherwise.
     * A too-short [data] yields null rather than throwing, so a mis-sliced frame can't crash the loop.
     */
    fun decode(id: Int, data: ByteArray): LinSignal? = when (id) {
        PID_STATUS -> decodeStatus(data)
        PID_BUTTONS -> decodeButtons(data)
        else -> null
    }

    /** As [decode], but unmapped IDs become [LinSignal.Unknown] (keeps raw bytes for capture). */
    fun decodeOrUnknown(id: Int, data: ByteArray): LinSignal =
        decode(id, data) ?: LinSignal.Unknown(id, rawId(id), data.copyOf())

    private fun u(d: ByteArray, i: Int): Int = if (i in d.indices) d[i].toInt() and 0xFF else 0

    /**
     * 0xB1 status (amp→panel), 8 data bytes. Example `80 0X 13 00 2C 2C 00 81`:
     *   b0 = base 0x80 + ECO bit       b1 = fan speed 0..7
     *   b2 = mode (low nibble)         b3 = rear-defrost + sync bits
     *   b4 = driver temp               b5 = passenger temp
     *   b6 = (unmapped)                b7 = A/C bit (&1) + illumination bit
     * Bit assignments marked UNCONFIRMED are best-guesses (cross-referenced to the 0x39 button
     * codes where possible) and should be toggle-and-diff verified; raw bytes are always exposed.
     */
    private fun decodeStatus(d: ByteArray): LinSignal.ClimateStatus? {
        if (d.size < 8) return null
        val b0 = u(d, 0); val b1 = u(d, 1); val b2 = u(d, 2); val b3 = u(d, 3)
        val b4 = u(d, 4); val b5 = u(d, 5); val b7 = u(d, 7)
        return LinSignal.ClimateStatus(
            fanSpeed = b1 and 0x07,
            modeRaw = b2,
            mode = Mode.from(b2 and 0x0F),
            driverTempRaw = b4,
            passengerTempRaw = b5,
            driverSetpointC = setpointGuessC(b4),
            passengerSetpointC = setpointGuessC(b5),
            acOn = (b7 and 0x01) != 0,
            illumination = (b7 and 0x80) != 0,      // UNCONFIRMED: example b7=0x81 → bit7 set
            eco = (b0 and 0x01) != 0,               // UNCONFIRMED: b0 base 0x80, ECO = low bit
            rearDefrost = (b3 and 0x40) != 0,       // UNCONFIRMED: mirrors 0x39 rear-defrost 0x40
            sync = (b3 and 0x20) != 0,              // UNCONFIRMED: mirrors 0x39 sync 0x20
            raw = d.copyOf(),
        )
    }

    /**
     * 0x39 button response (amp polls panel), 8 data bytes, base `40 00 00 00 10 90 00 00`. Each
     * byte that differs from [BUTTONS_BASE] to a known code is one button event; we collect every
     * match (usually exactly one) so a rare multi-bit frame isn't silently dropped.
     *   b0: OFF 0x42 / AUTO 0x48            b1: AC 0x80 / ECO 0x40 / FAN- 0x3D / FAN+ 0x3C
     *   b2: MODE 0x1C / S-MODE 0x80         b3: SYNC 0x20 / FRONT-DEF 0x80 / REAR-DEF 0x40
     *   b4: DRIVER TEMP -0x0F / +0x11       b5: PASSENGER TEMP -0x8F / +0x91
     *   b6: RECIRC 0xC0
     */
    private fun decodeButtons(d: ByteArray): LinSignal.ClimateButtons? {
        if (d.size < 7) return null
        val pressed = ArrayList<Button>(2)
        fun test(idx: Int, code: Int, btn: Button) { if (u(d, idx) == code) pressed += btn }
        test(0, 0x42, Button.OFF);  test(0, 0x48, Button.AUTO)
        test(1, 0x80, Button.AC);   test(1, 0x40, Button.ECO)
        test(1, 0x3D, Button.FAN_DOWN); test(1, 0x3C, Button.FAN_UP)
        test(2, 0x1C, Button.MODE); test(2, 0x80, Button.S_MODE)
        test(3, 0x20, Button.SYNC); test(3, 0x80, Button.FRONT_DEFROST); test(3, 0x40, Button.REAR_DEFROST)
        test(4, 0x0F, Button.DRIVER_TEMP_DOWN); test(4, 0x11, Button.DRIVER_TEMP_UP)
        test(5, 0x8F, Button.PASSENGER_TEMP_DOWN); test(5, 0x91, Button.PASSENGER_TEMP_UP)
        test(6, 0xC0, Button.RECIRC)
        return LinSignal.ClimateButtons(pressed = pressed, raw = d.copyOf())
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Self-test — hand-built frames from the bitzero examples, plus PID/parity + checksum math.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sanity-check the whole decode path off-device (a debug menu, or `main` when run as pure
     * Kotlin). Returns true on pass; prints each failure. Covers: the 0xB1 status example
     * (fan/mode/temps/AC), a 0x39 fan+ press, PID↔raw-id parity for both observed bytes, and
     * self-consistency of both checksum variants (the example omits the on-wire checksum byte, so
     * we verify a frame built with our own computed checksum round-trips — see class KDoc).
     */
    fun selfTest(): Boolean {
        val results = mutableListOf<Boolean>()
        fun check(name: String, cond: Boolean) { results += cond; if (!cond) println("FAIL: $name") }
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        // 1) 0xB1 status example `80 00 13 00 2C 2C 00 81` (X=0). fan 0, mode feet(low nibble 3),
        //    temps 0x2C, A/C on, illumination on.
        val st = decode(PID_STATUS, bytes(0x80, 0x00, 0x13, 0x00, 0x2C, 0x2C, 0x00, 0x81))
            as LinSignal.ClimateStatus
        check("status.fan=0", st.fanSpeed == 0)
        check("status.mode=FEET", st.mode == Mode.FEET && st.modeRaw == 0x13)
        check("status.driverTempRaw=0x2C", st.driverTempRaw == 0x2C)
        check("status.passTempRaw=0x2C", st.passengerTempRaw == 0x2C)
        check("status.driverSetpoint=22.0", kEq(st.driverSetpointC, 22.0))
        check("status.acOn", st.acOn)
        check("status.illum", st.illumination)

        // 1b) same frame, fan raised to 5 (b1=0x05) and mode face (b2 low nibble 1).
        val st2 = decode(PID_STATUS, bytes(0x80, 0x05, 0x01, 0x00, 0x30, 0x2A, 0x00, 0x80))
            as LinSignal.ClimateStatus
        check("status2.fan=5", st2.fanSpeed == 5)
        check("status2.mode=FACE", st2.mode == Mode.FACE)
        check("status2.acOff", !st2.acOn)

        // 2) 0x39 button: FAN+ press → b1 = 0x3C over the base frame.
        val bt = decode(PID_BUTTONS, bytes(0x40, 0x3C, 0x00, 0x00, 0x10, 0x90, 0x00, 0x00))
            as LinSignal.ClimateButtons
        check("buttons.fan+", bt.pressed == listOf(Button.FAN_UP))
        // 2b) driver temp up (b4 0x11) + a base frame with no press → empty.
        val bt2 = decode(PID_BUTTONS, bytes(0x40, 0x00, 0x00, 0x00, 0x11, 0x90, 0x00, 0x00))
            as LinSignal.ClimateButtons
        check("buttons.driverTemp+", bt2.pressed == listOf(Button.DRIVER_TEMP_UP))
        val bt3 = decode(PID_BUTTONS, BUTTONS_BASE.let { ByteArray(8) { i -> it[i].toByte() } })
            as LinSignal.ClimateButtons
        check("buttons.none", bt3.pressed.isEmpty())

        // 3) PID / parity: 0xB1 is the PID for raw id 0x31; 0x39 is the PID for raw id 0x39.
        check("pid.B1.rawId=0x31", rawId(PID_STATUS) == 0x31)
        check("pid.B1.valid", pidValid(PID_STATUS) && computePid(0x31) == 0xB1)
        check("pid.B1.parity=0b10", parityBits(PID_STATUS) == 0b10)
        check("pid.39.rawId=0x39", rawId(PID_BUTTONS) == 0x39)
        check("pid.39.valid", pidValid(PID_BUTTONS) && computePid(0x39) == 0x39)
        check("pid.39.parity=0b00", parityBits(PID_BUTTONS) == 0b00)

        // 4) Checksum self-consistency (example has no on-wire checksum byte; verify round-trip).
        val statusData = bytes(0x80, 0x00, 0x13, 0x00, 0x2C, 0x2C, 0x00, 0x81)
        val enh = enhancedChecksum(PID_STATUS, statusData)
        val cls = classicChecksum(statusData)
        check("cksum.enh.range", enh in 0..0xFF)
        check("cksum.enh.verify", verifyChecksum(PID_STATUS, statusData, enh) == ChecksumKind.ENHANCED)
        check("cksum.cls.verify", verifyChecksum(PID_STATUS, statusData, cls) in
            listOf(ChecksumKind.CLASSIC, ChecksumKind.ENHANCED)) // enhanced wins if they collide
        check("cksum.bad", verifyChecksum(PID_STATUS, statusData, (enh xor 0xFF)) != ChecksumKind.ENHANCED)

        // 5) Unknown id → null from decode, Unknown from decodeOrUnknown; length map.
        check("unknown-null", decode(0x32, ByteArray(8)) == null)
        check("unknown-var", decodeOrUnknown(0x32, ByteArray(2)).let {
            it is LinSignal.Unknown && it.rawId == 0x32
        })
        check("len.B1=8", frameDataLenFor(PID_STATUS) == 8)
        check("len.unknown=null", frameDataLenFor(0x32) == null)

        return results.all { it }
    }

    private fun kEq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6
}

/**
 * Decoded LIN climate signal. `Unknown` keeps the raw payload so an on-device capture can still log
 * an ID we have not mapped (the winter-package `0x32/0xBA/0xF5/0x78`, unresponsive `0x76`, etc.).
 * Named `LinSignal` to sit beside `RawCanSignal` / `CanSignal` in the same package without collision.
 */
sealed interface LinSignal {

    /** 0xB1 — climate status (amp→panel). Raw bytes retained while bit/temp guesses are unconfirmed. */
    data class ClimateStatus(
        val fanSpeed: Int,
        val mode: LinClimateDecoder.Mode,
        val modeRaw: Int,
        val driverTempRaw: Int,
        val passengerTempRaw: Int,
        /** UNCONFIRMED °C best-guess (`raw*0.5`); pair with the raw byte in the UI. */
        val driverSetpointC: Double,
        val passengerSetpointC: Double,
        val acOn: Boolean,
        val illumination: Boolean,
        val eco: Boolean,
        val rearDefrost: Boolean,
        val sync: Boolean,
        val raw: ByteArray,
    ) : LinSignal {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClimateStatus) return false
            return fanSpeed == other.fanSpeed && mode == other.mode && modeRaw == other.modeRaw &&
                driverTempRaw == other.driverTempRaw && passengerTempRaw == other.passengerTempRaw &&
                acOn == other.acOn && illumination == other.illumination && eco == other.eco &&
                rearDefrost == other.rearDefrost && sync == other.sync && raw.contentEquals(other.raw)
        }
        override fun hashCode(): Int = 31 * (31 * fanSpeed + modeRaw) + raw.contentHashCode()
    }

    /** 0x39 — button response (amp polls panel). [pressed] is the set of buttons this frame reports. */
    data class ClimateButtons(
        val pressed: List<LinClimateDecoder.Button>,
        val raw: ByteArray,
    ) : LinSignal {
        val primary: LinClimateDecoder.Button? get() = pressed.firstOrNull()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClimateButtons) return false
            return pressed == other.pressed && raw.contentEquals(other.raw)
        }
        override fun hashCode(): Int = 31 * pressed.hashCode() + raw.contentHashCode()
    }

    /** Any LIN id we don't map yet; raw payload + stripped 6-bit id preserved for capture/logging. */
    data class Unknown(val pid: Int, val rawId: Int, val payload: ByteArray) : LinSignal {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return pid == other.pid && rawId == other.rawId && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * (31 * pid + rawId) + payload.contentHashCode()
    }
}

/** Run the decoder self-test as a plain Kotlin program (`kotlinc … -include-runtime` then run). */
fun main() {
    val ok = LinClimateDecoder.selfTest()
    println(if (ok) "LinClimateDecoder.selfTest: PASS" else "LinClimateDecoder.selfTest: FAIL")
    if (!ok) kotlin.system.exitProcess(1)
}
