package com.ripostelabs.carlauncher.carlib

/**
 * AccessorySequence — lights and servos, choreographed.
 *
 * A sequence is an ordered list of steps; each step sends one command to one accessory and then
 * holds for a while before the next. "Welcome": light bar on, hold 400 ms, spot on, hold 2 s,
 * both to 30 %. "Stow the antenna": servo to 0, hold until it has travelled, cut its power.
 *
 * ── It is driven by ticks, not by sleeping ──────────────────────────────────────────────────────
 * [SequenceRunner.tick] is called with the current time and advances whatever is due. That keeps
 * the whole thing testable with an injected clock, and it keeps a sequence cancellable between
 * steps rather than mid-`Thread.sleep`.
 *
 * ── A failed step aborts, it does not continue ──────────────────────────────────────────────────
 * If step 2 of "stow the antenna" is refused, running step 3 — cut the servo's power — leaves the
 * antenna wherever it stopped. A sequence is a plan whose later steps assume the earlier ones
 * happened; the moment one did not, the plan is void. [SequenceState.Aborted] carries which step
 * and why, so the screen can say so rather than show the sequence as finished.
 */
data class SequenceStep(
    val accessoryId: String,
    val command: AccessoryCommand,

    /** How long to wait after this step before the next one. Zero means straight on. */
    val holdMs: Long = 0L,
)

data class AccessorySequence(
    val id: String,
    val name: String,
    val steps: List<SequenceStep>,
) {
    init {
        require(steps.isNotEmpty()) { "a sequence needs at least one step" }
        require(steps.all { it.holdMs >= 0 }) { "a hold cannot be negative" }
    }
}

sealed interface SequenceState {
    object Idle : SequenceState

    data class Running(val sequence: AccessorySequence, val nextStep: Int, val dueAt: Long) : SequenceState

    data class Finished(val sequence: AccessorySequence, val atMs: Long) : SequenceState

    /** Stopped before the end. [step] is the index that failed, or -1 for a cancel. */
    data class Aborted(val sequence: AccessorySequence, val step: Int, val reason: String) : SequenceState
}

/**
 * Runs one sequence at a time against an [AccessoryController].
 *
 * One at a time is deliberate. Two sequences addressing the same light would interleave their
 * steps and produce a third choreography nobody wrote. Starting a sequence while another runs
 * cancels the first, explicitly and observably, rather than letting them fight.
 */
class SequenceRunner(private val controller: AccessoryController) {

    var state: SequenceState = SequenceState.Idle
        private set

    /** Begin [sequence]. Any running sequence is cancelled first. The first step runs now. */
    fun start(sequence: AccessorySequence, now: Long) {
        if (state is SequenceState.Running) {
            cancel(now)
        }

        state = SequenceState.Running(sequence, nextStep = 0, dueAt = now)
        tick(now)
    }

    /** Stop whatever is running. Steps already sent stay sent; nothing is undone. */
    fun cancel(now: Long) {
        val running = state as? SequenceState.Running ?: return
        state = SequenceState.Aborted(running.sequence, step = CANCELLED, reason = "cancelled")
    }

    /**
     * Advance every step that is due as of [now]. Safe to call as often as you like; a runner
     * with nothing due does nothing. Several steps with zero hold run in one tick.
     */
    fun tick(now: Long) {
        while (true) {
            val running = state as? SequenceState.Running ?: return
            if (now < running.dueAt) {
                return
            }

            val step = running.sequence.steps[running.nextStep]
            val result = controller.send(step.accessoryId, step.command, now)
            if (result != CommandResult.APPLIED) {
                state = SequenceState.Aborted(
                    running.sequence,
                    step = running.nextStep,
                    reason = "${step.accessoryId}: $result",
                )
                return
            }

            val last = running.nextStep == running.sequence.steps.lastIndex
            state = if (last) {
                SequenceState.Finished(running.sequence, now)
            } else {
                SequenceState.Running(running.sequence, running.nextStep + 1, dueAt = now + step.holdMs)
            }
        }
    }

    private companion object {
        const val CANCELLED = -1
    }
}
