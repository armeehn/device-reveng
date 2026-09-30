package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.Intent

/**
 * RAV4-176: what the wheel voice key opens, as stock's `sys_set_customized_voice_key`
 * (ItemTextRightCheckBoxView.java:196-199). Blank is the system assistant (Google's, through
 * ACTION_VOICE_COMMAND); a package opens that app. With no activity for either, the caller
 * falls back to the vendor voice key.
 */
object VoiceKey {

    sealed interface Target {
        data object Assistant : Target

        data class App(val pkg: String) : Target
    }

    fun target(pkg: String): Target = if (pkg.isBlank()) Target.Assistant else Target.App(pkg)

    /** The intent to start, or null when nothing on the unit answers it. */
    fun intent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        val intent = when (val t = target(pkg)) {
            Target.Assistant -> Intent(Intent.ACTION_VOICE_COMMAND).takeIf { it.resolveActivity(pm) != null }
            is Target.App -> pm.getLaunchIntentForPackage(t.pkg)
        }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
