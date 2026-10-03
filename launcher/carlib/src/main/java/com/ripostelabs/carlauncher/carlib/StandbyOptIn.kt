package com.ripostelabs.carlauncher.carlib

/** Settings → Power & sleep → "Standby when the car is off". On unless turned off. */
enum class StandbyMode {
    OFF,
    ON;

    companion object {
        /** A stored name; anything unknown or unset is ON. A failed wake stores OFF ([StandbyFallback]). */
        fun of(name: String?): StandbyMode = entries.firstOrNull { it.name == name } ?: ON
    }
}

/**
 * StandbyOptIn — the one switch in front of [McuSleepWake] for ACC standby.
 *
 * Standby suspends the SoC. The first deep suspend never woke at ACC on (car, vc946): the owner
 * heard the tuner over a black panel, with only RST to recover. The image now suspends to idle
 * as stock does (#404), so standby is on by default, and [StandbyFallback] turns it off after one
 * wake that ends in RST. With [StandbyMode.OFF] the unit behaves as it did before standby:
 *
 *     CarAcc ──▶ acc() ──▶ McuSleepWake, DozeGuard      OFF: reads null, so no ACC-off sleep,
 *                                                            no BT 0, no port close, and
 *                                                            DozeGuard keeps the panel lit
 *     AccStandby ◀── standby() ◀── McuSleepWake         OFF: enter, darken, leave do nothing
 *
 * The MCU then cuts B+ and the next start is a cold boot. The POWER key path keeps its port
 * sleep, as before standby. [StandbyMode.ON] passes everything through unchanged.
 *
 * The mode is read at [McuSleepWake.Standby.enter] and held until leave, so a standby entered
 * is always left, even if the setting flips while asleep.
 */
class StandbyOptIn(private val mode: () -> StandbyMode) {

    @Volatile
    private var armed = false

    private fun active(): Boolean = armed || mode() == StandbyMode.ON

    /** ACC as the machine sees it: unreadable while standby is off, like the GSI's empty property. */
    fun acc(inner: McuSleepWake.AccSource): McuSleepWake.AccSource = object : McuSleepWake.AccSource {
        override fun read(): McuSleepWake.Acc? {
            if (!active()) {
                return null
            }

            return inner.read()
        }
    }

    /** [AccStandby] behind the switch; enter decides, darken and leave follow that decision. */
    fun standby(inner: McuSleepWake.Standby): McuSleepWake.Standby = object : McuSleepWake.Standby {
        override fun enter() {
            armed = mode() == StandbyMode.ON
            if (!armed) {
                return
            }
            inner.enter()
        }

        override fun darken() {
            if (!armed) {
                return
            }
            inner.darken()
        }

        override fun leave() {
            if (!armed) {
                return
            }
            armed = false
            inner.leave()
        }
    }
}
