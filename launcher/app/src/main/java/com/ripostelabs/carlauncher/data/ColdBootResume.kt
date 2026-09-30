package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode
import com.ripostelabs.carlauncher.carlib.McuPort
import kotlinx.coroutines.delay

/**
 * RAV4-170: after a cold boot, reopen the app of the last playable source, as stock's
 * `Sys_Last_Mode` does (EventService.java:3898-3905). The car service keeps the source across
 * boots; this side maps it to a suite app and runs once per boot.
 *
 *     boot ──▶ link Running ──▶ due(BOOT_COUNT)? ──▶ port.resumeMode ──▶ start the app ──▶ play key
 *
 * An ACC wake is not a boot (BOOT_COUNT unchanged), and McuSleepWake already resumes the MCU
 * source there, so a wake never relaunches anything.
 */
object ColdBootResume {

    private const val TAG = "ColdBootResume"
    private const val PREFS = "cold_boot_resume"
    private const val KEY_BOOT = "resumed_boot"
    private const val NO_BOOT = -1

    /** Time for the player to start its media session before the play key reaches it. */
    private const val PLAY_DELAY_MS = 3_000L

    private val APPS = mapOf(
        Mode.RADIO to "com.ripostelabs.radio",
        Mode.MUSIC to "com.ripostelabs.music",
        Mode.BT_MUSIC to "com.ripostelabs.bluetooth",
        Mode.MOVIE to "com.ripostelabs.video",
    )

    fun appFor(mode: Mode?): String? = mode?.let(APPS::get)

    /** True once per boot; false when the boot count is unreadable. */
    fun due(bootCount: Int?, lastResumed: Int): Boolean = bootCount != null && bootCount != lastResumed

    /** The players wait for a play key; the radio plays once its app claims the tuner. */
    fun needsPlay(mode: Mode): Boolean = mode == Mode.MUSIC || mode == Mode.BT_MUSIC

    /** Call once the link runs, so [McuPort.resumeMode] can reach the car service. */
    suspend fun run(context: Context, port: McuPort) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, NO_BOOT)
            .takeIf { it != NO_BOOT }
        if (!due(boot, prefs.getInt(KEY_BOOT, NO_BOOT))) {
            return
        }

        // Recorded before the launch, so a crash in the app cannot loop the launcher into it.
        prefs.edit().putInt(KEY_BOOT, boot ?: NO_BOOT).apply()

        val mode = port.resumeMode ?: return
        val intent = appFor(mode)?.let(context.packageManager::getLaunchIntentForPackage) ?: return
        Log.i(TAG, "cold boot: resuming $mode in ${intent.`package`}")
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        if (!needsPlay(mode)) {
            return
        }

        delay(PLAY_DELAY_MS)
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }
}
