package com.ripostelabs.carlauncher.data

/**
 * RAV4-278: Android's call screen must not cover a CarPlay session. Pure; `MainActivity` polls
 * the top activity while CarPlay is up and brings CarPlay back when this says so.
 *
 * Why it can still appear: HfpYield parks the HF client while CarPlay is up, so Telecom gets no
 * HFP call for Siri (voice recognition on SCO) or FaceTime Audio (a CallKit call the iPhone
 * reports over HFP). A phone that reaches HFP before the park, or a park the stack refuses,
 * still lets Telecom open the Dialer's InCallActivity over CarPlay (owner, 2026-10-02).
 *
 * ```
 *  CarPlay up, call screen appears ──▶ reclaim (once per appearance)
 *  CarPlay up, Dialer main screen  ──▶ leave it: the owner opened it
 *  no session                      ──▶ leave it
 * ```
 */
class CallUiGuard {

    enum class Session { LIVE, NONE }

    private var shown = false

    /** True when [line] (a `topResumedActivity=` line) is a fresh call screen over a [Session.LIVE]. */
    fun onTop(line: String?, session: Session): Boolean {
        val callUi = isCallUi(line)
        val fresh = callUi && !shown
        shown = callUi
        return fresh && session == Session.LIVE
    }

    companion object {
        /** Same read as the nav bar's foreground poll. */
        const val TOP_QUERY = "dumpsys activity activities | grep -m1 topResumedActivity"
        /** The nav bar's cadence: a call screen stays up at most this long. */
        const val POLL_MS = 1_500L

        /** `<package>/<activity>` in the record; a leading dot is package-relative. */
        private val COMPONENT = Regex("""topResumedActivity=.*?\s([a-zA-Z][\w.]*)/([\w.$]+)""")
        /** AOSP and Google Dialer both host the call screen in this package. */
        private const val IN_CALL_UI = "com.android.incallui."

        fun isCallUi(line: String?): Boolean {
            val match = COMPONENT.find(line ?: return false) ?: return false
            val (pkg, cls) = match.destructured
            val name = if (cls.startsWith(".")) pkg + cls else cls
            return name.startsWith(IN_CALL_UI) || name.endsWith(".InCallActivity")
        }
    }
}
