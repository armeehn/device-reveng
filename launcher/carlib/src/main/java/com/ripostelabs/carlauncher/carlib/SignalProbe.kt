package com.ripostelabs.carlauncher.carlib

/**
 * SignalProbe — find which part of the bus answers when a human presses one thing.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────────────────────────
 * Passive capture on this car is exhausted. A parked, still vehicle emits almost no variation:
 * 85 of 111 ids were fully static across a baseline run. Every signal still unattributed needs
 * somebody in the car actuating one control while the bus is watched. That work was done by hand
 * against log files, and the method errors it invited cost more time than the captures did.
 *
 * ── The method, and the two mistakes it is built to prevent ─────────────────────────────────────
 * Compare the **fraction of frames a bit is set** between two windows holding different states.
 * Not distinct-value counts, and not one window read alone.
 *
 *  1. **Never compare distinct-value counts across windows of different lengths.** A longer window
 *     accumulates more values for free, so every id looks like it "gained variance". Fractions are
 *     immune: they are already normalised by frame count.
 *  2. **A pulse is not a state.** The first door candidate ever found here (`0x620` byte 1 bit
 *     `0x80`, 32 clean toggles) is a 0.3 s event pulse. Across a 60 s door-held-open run it was
 *     clear for 47 of them. Holding one state for a long window is what separates them, and this
 *     ranks by how much the fraction MOVED, so a pulse that fires in both windows scores near zero.
 *
 * A third problem solves itself. Checksum and counter bytes — byte 7 on many ids here — churn
 * through every value and masquerade as signal in any test that counts changes. Per-bit fractions
 * put them near 0.5 in both windows, so their delta is ~0 and they sink to the bottom unranked.
 * Nothing has to know which bytes are checksums.
 *
 * ── What it will not do ─────────────────────────────────────────────────────────────────────────
 * It never says a signal was found. It reports how far each candidate moved and how much evidence
 * is behind it; deciding what counts as an answer belongs to [GuidedTest], and confirming it
 * belongs to a second run holding a different state.
 */
class SignalProbe {

    /** Which window a frame belongs to. */
    enum class Phase { BASELINE, ACTION }

    /**
     * One bit of one byte of one id, and how often it was set in each window.
     *
     * [delta] is signed: positive means the action SET the bit, negative means it cleared it.
     * Both are equally good answers, and hiding the sign would lose which way a control moves.
     */
    data class Candidate(
        val id: Int,
        val byteIndex: Int,
        val bitMask: Int,
        val baselineFraction: Double,
        val actionFraction: Double,
    ) {
        val delta: Double get() = actionFraction - baselineFraction
    }

    /**
     * A byte whose values in the two windows share nothing at all.
     *
     * Weaker evidence than a moved bit and reported separately, but it is what found the climate
     * messages: `0x380` byte 2 read `0x00` with climate off and `0x20`/`0xA0` with it on, with no
     * overlap. A multi-valued field like blower duty shows up here and not in the bit ranking.
     */
    data class DisjointByte(
        val id: Int,
        val byteIndex: Int,
        val baselineValues: Set<Int>,
        val actionValues: Set<Int>,
    )

    private class Counts {
        var frames = 0
        val bitsSet = HashMap<Int, Int>()
        val values = HashMap<Int, MutableSet<Int>>()
    }

    private val perPhase = mapOf(
        Phase.BASELINE to HashMap<Int, Counts>(),
        Phase.ACTION to HashMap<Int, Counts>(),
    )

    private var phase = Phase.BASELINE

    fun phase(next: Phase) {
        phase = next
    }

    fun framesIn(which: Phase): Int = perPhase.getValue(which).values.sumOf { it.frames }

    /** Accumulate one frame into the current window. */
    fun accept(id: Int, data: ByteArray) {
        val counts = perPhase.getValue(phase).getOrPut(id) { Counts() }
        counts.frames++

        for (index in 0 until minOf(data.size, MAX_BYTES)) {
            val value = data[index].toInt() and BYTE_MASK
            counts.values.getOrPut(index) { HashSet() }.add(value)

            for (bit in 0 until BITS_PER_BYTE) {
                val mask = 1 shl bit
                if (value and mask == 0) {
                    continue
                }

                val key = index * BITS_PER_BYTE + bit
                counts.bitsSet[key] = (counts.bitsSet[key] ?: 0) + 1
            }
        }
    }

    /**
     * Every bit that moved, biggest movement first.
     *
     * An id is skipped unless BOTH windows saw at least [MIN_FRAMES_PER_ID] of it. Without that
     * floor an id seen twice in one window and never in the other reports a perfect 0-to-1 move
     * on pure noise, and it outranks every real answer.
     */
    fun candidates(): List<Candidate> {
        val base = perPhase.getValue(Phase.BASELINE)
        val action = perPhase.getValue(Phase.ACTION)
        val out = mutableListOf<Candidate>()

        for ((id, actionCounts) in action) {
            val baseCounts = base[id] ?: continue
            if (baseCounts.frames < MIN_FRAMES_PER_ID || actionCounts.frames < MIN_FRAMES_PER_ID) {
                continue
            }

            for (key in (baseCounts.bitsSet.keys + actionCounts.bitsSet.keys)) {
                val baseFraction = (baseCounts.bitsSet[key] ?: 0).toDouble() / baseCounts.frames
                val actionFraction = (actionCounts.bitsSet[key] ?: 0).toDouble() / actionCounts.frames

                out += Candidate(
                    id = id,
                    byteIndex = key / BITS_PER_BYTE,
                    bitMask = 1 shl (key % BITS_PER_BYTE),
                    baselineFraction = baseFraction,
                    actionFraction = actionFraction,
                )
            }
        }

        return out.sortedByDescending { kotlin.math.abs(it.delta) }
    }

    /** Bytes whose two windows share no value at all. Secondary evidence; see [DisjointByte]. */
    fun disjointBytes(): List<DisjointByte> {
        val base = perPhase.getValue(Phase.BASELINE)
        val action = perPhase.getValue(Phase.ACTION)
        val out = mutableListOf<DisjointByte>()

        for ((id, actionCounts) in action) {
            val baseCounts = base[id] ?: continue
            if (baseCounts.frames < MIN_FRAMES_PER_ID || actionCounts.frames < MIN_FRAMES_PER_ID) {
                continue
            }

            for ((index, actionValues) in actionCounts.values) {
                val baseValues = baseCounts.values[index] ?: continue
                if (baseValues.isEmpty() || actionValues.isEmpty()) {
                    continue
                }
                if (baseValues.intersect(actionValues).isNotEmpty()) {
                    continue
                }

                out += DisjointByte(id, index, baseValues.toSet(), actionValues.toSet())
            }
        }

        return out.sortedBy { it.id }
    }

    /** How far a specific bit moved, or null if that id was not seen enough in both windows. */
    fun movementOf(id: Int, byteIndex: Int, bitMask: Int): Candidate? =
        candidates().firstOrNull { it.id == id && it.byteIndex == byteIndex && it.bitMask == bitMask }

    private companion object {
        const val BITS_PER_BYTE = 8
        const val BYTE_MASK = 0xFF
        const val MAX_BYTES = 8

        /**
         * Below this an id has not been sampled enough for a fraction to mean anything. The
         * slowest ids on this bus run at ~1 Hz, so this is a few seconds of a slow signal and a
         * fraction of a second of a fast one.
         */
        const val MIN_FRAMES_PER_ID = 8
    }
}
