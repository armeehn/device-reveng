package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.fold
import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.foldRaw
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VehicleState — the live [VehicleSnapshot], accumulated from whatever the car has said so far.
 *
 * [VehicleSnapshot.fold] already knows how to absorb one signal, and [VehicleTiles] already knows
 * how to present a snapshot, but nothing held one between frames. Each opcode arrives in its own
 * frame, so a screen reading only the newest frame would show one reading and blank everything
 * else. This is the thing in the middle that remembers.
 *
 *     MCU frame ──> HiworldCanDecoder ──> CanSignal ──> fold ──> VehicleSnapshot ──> tiles
 *                                                        └ here
 *
 * Staleness is not handled here on purpose. The snapshot already timestamps every field and
 * [VehicleSnapshot.raw] drops anything too old at read time, so a value expires by being read
 * late rather than by a timer running in this class. That keeps the whole path free of clocks
 * and testable without sleeping.
 */
class VehicleState(
    /**
     * Where speed candidates go. The snapshot itself never holds a speed; the calibration does,
     * paired with the ECU's answer, until a scale is actually established.
     */
    private val calibration: SpeedCalibration? = null,
) {

    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    /**
     * Absorb one framed MCU message. Anything the decoder does not recognise leaves the snapshot
     * exactly as it was: a frame we cannot read is not evidence that a previously good reading
     * has changed, and blanking the dashboard on an unknown opcode would be a lie.
     */
    fun onFrame(framed: ByteArray, atMs: Long) {
        val signal = HiworldCanDecoder.decodeFrame(framed) ?: return
        onSignal(signal, atMs)
    }

    /** Absorb one already-decoded signal. */
    fun onSignal(signal: CanSignal, atMs: Long) {
        calibration?.onCandidate(signal, atMs)
        _snapshot.value = _snapshot.value.fold(signal, atMs)
    }

    /**
     * Absorb one frame from the **raw bus** (the CANable tap), as opposed to the vendor MCU.
     *
     * Both sources land in the same fields, so the screen above does not need to know which one
     * a reading came from. An id the decoder does not handle leaves the snapshot untouched, for
     * the same reason an unreadable MCU frame does: silence is not evidence of change, and the
     * raw bus carries 111 ids of which only a few are decoded.
     */
    fun onRawFrame(id: Int, data: ByteArray, atMs: Long) {
        val signal = RawCanDecoder.decode(id, data) ?: return
        _snapshot.value = _snapshot.value.foldRaw(signal, atMs)
    }

    /**
     * The same, straight off the adapter. [SlcanFrame] carries unsigned byte values so equality
     * works; the decoder wants a ByteArray and masks each byte back to 0..255 itself, so a value
     * of 0x80 survives the round trip through a signed Kotlin Byte.
     */
    fun onRawFrame(frame: SlcanFrame, atMs: Long) {
        onRawFrame(frame.id, ByteArray(frame.data.size) { frame.data[it].toByte() }, atMs)
    }

    /** The tiles to draw right now. [now] is passed in so staleness stays testable. */
    fun tiles(now: Long): List<VehicleTiles.Tile> = VehicleTiles.tilesFor(_snapshot.value, now)

    /**
     * Whether any field has a live source. False means there is no car to talk to — an emulator,
     * the engine off, or the MCU link down — and a caller must say so rather than draw an empty
     * dashboard that reads as "everything is zero".
     */
    fun hasData(now: Long): Boolean = _snapshot.value.hasAnySource(now)
}
