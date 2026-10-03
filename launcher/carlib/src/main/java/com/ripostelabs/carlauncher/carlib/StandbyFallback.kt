package com.ripostelabs.carlauncher.carlib

/**
 * StandbyFallback — standby is on by default; one failed wake turns it off again.
 *
 * A wake that fails leaves a black panel over the tuner, and the owner's only way out is RST.
 * At the next launcher start two facts tell that apart from a normal cold boot:
 *
 *     AccStandby marker   set: standby was entered and never left
 *     PMIC power-on       "Hard Reset" = RST    "KPD" = the MCU powering up after a B+ cut
 *                         "SMPL" = a crank brown-out (car dmesg, 2026-10-01/02, 30 boots)
 *
 *     marker set + Hard Reset ──▶ failed wake ──▶ standby OFF, cold boots as before
 *     marker set + KPD / SMPL ──▶ a long park or a crank, standby stays ON
 *
 * So the worst a failed wake costs is one RST.
 */
object StandbyFallback {

    /** Whether the last standby was left ([AccStandby.recover]). */
    enum class Left { YES, NEVER }

    /** The PMIC's power-on reason, from qpnp-power-on's boot line. */
    enum class PowerOn(private val trigger: String?) {
        KEY("KPD"),
        RESET("Hard Reset"),
        POWER_LOSS("SMPL"),
        UNKNOWN(null);

        companion object {
            private const val MARKER = "Power-on reason: Triggered from "

            /** e.g. `PMIC@SID0: Power-on reason: Triggered from Hard Reset and 'cold' boot`. */
            fun parse(dmesg: List<String>): PowerOn {
                val line = dmesg.firstOrNull { it.contains(MARKER) } ?: return UNKNOWN
                val cause = line.substringAfter(MARKER)
                return entries.firstOrNull { it.trigger != null && cause.startsWith(it.trigger) } ?: UNKNOWN
            }

            /** The kernel log through the root shell; UNKNOWN when it has rolled past boot. */
            fun read(shell: (String) -> RootShell.Result = { RootShell.exec(it) }): PowerOn =
                parse(shell(DMESG).out)

            private const val DMESG = "dmesg | grep -m 1 'Power-on reason'"
        }
    }

    fun failedWake(left: Left, powerOn: PowerOn): Boolean = left == Left.NEVER && powerOn == PowerOn.RESET
}
