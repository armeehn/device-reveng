package com.ripostelabs.carlauncher.data

/**
 * v2.9 — the two pure rules first run turns on, kept out of the UI so they are testable.
 *
 * Both exist because the first screens a driver ever sees were judged on the farm for the
 * first time on 2026-09-22 and both were wrong there:
 *
 *   - the launcher fired its own location dialog from onCreate, so a genuine first boot asked
 *     "Allow location?" on top of the welcome screen, before a word of it had been read;
 *   - the permissions step listed the Setup Doctor's inventory row ("Rewritten app suite") as
 *     if it were a grant, which is not something a driver can answer.
 */
object FirstRunGate {

    /**
     * May the launcher raise its own runtime-permission dialog on this start? Not on a first
     * run: onboarding's permissions step is the ask then. `null` is "the flag has not been read
     * off disk yet", which is also a no: prompting then is the same race.
     */
    fun mayPrompt(firstRun: Boolean?): Boolean = firstRun == false

    /** The checks onboarding offers to fix: grants only, never a row that merely reports. */
    fun grants(checks: List<DoctorCheck>): List<DoctorCheck> =
        checks.filter { it.kind == CheckKind.GRANT }
}
