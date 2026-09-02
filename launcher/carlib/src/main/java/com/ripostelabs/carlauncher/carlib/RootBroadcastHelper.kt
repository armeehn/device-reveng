package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper

/**
 * v2.9 — the root-uid half of the protected-broadcast capture. **This class does not run inside
 * the launcher process.** [RootBroadcastBridge] starts it as a bare `app_process` under `su`:
 *
 * ```
 * CLASSPATH=<our apk> app_process /system/bin com.ripostelabs.carlauncher.carlib.RootBroadcastHelper
 * ```
 *
 * ### Why a separate process at all
 *
 * The gateway sends `STEER_WHEEL_INFOR` and the day/night backlight events with
 * `com.szchoiceway.permission.broadcast` (CAR_API §1.1). That permission is almost certainly
 * `signature`, and the vendor platform key is confirmed unobtainable, so the launcher can never
 * hold it — [CarEvents] has been living on the weaker unprotected fallbacks instead. But AMS's
 * `checkComponentPermission` short-circuits to GRANTED for uid 0 and uid 1000 before it ever looks
 * at granted permissions, so a process running as root receives permission-guarded broadcasts
 * without holding anything. Root is the capability the platform key was going to buy us.
 *
 * ### Why not parse logcat instead
 *
 * The obvious rootless-looking alternative — stream `logcat` and read the dispatch lines — cannot
 * work for the event we actually need. AOSP's event log records broadcast *discards* and receiver
 * *finishes*, never a dispatch carrying extras, and an SWC press is nothing but its extras
 * (`LPARAM` key index, `WPARAM` up/down, `VOLTAGE`). Recovering those would mean betting on
 * undocumented vendor debug lines whose format we have never seen, and silently mis-decoding a
 * steering-wheel key is worse than not having it. This path gets the real Intent.
 *
 * ### Transport
 *
 * One line per event on stdout, read by the parent from the `su` process's stdout. A pipe rather
 * than a socket because the parent already owns the process: no port to pick, no bind permission,
 * and the channel dies exactly when the helper does.
 *
 *     RDY
 *     EVT<TAB>com.choiceway...STEER_WHEEL_INFOR<TAB>EventUtils.STEER_WHEEL_INFOR_LPARAM=5<TAB>…
 *
 * Only int extras are carried: every protected action we capture either has none or has only ints
 * (CAR_API §1.3). Anything richer would need a real Parcel over a real IPC channel, which is not
 * worth it for events that are three ints wide.
 *
 * Keep rules for this class live in `consumer-rules.pro` — R8 must not rename or drop `main`,
 * because `app_process` looks it up by name.
 */
object RootBroadcastHelper {

    /** Emitted once the receiver is registered, so the parent can tell "running" from "started". */
    const val READY_LINE = "RDY"

    /** Leading field of an event line. */
    const val EVENT_PREFIX = "EVT"

    /** Field separator. Tab, because no action string or extra name contains one. */
    const val SEP = "\t"

    /**
     * The protected actions worth a root process, mapped to the int extras to carry back.
     *
     * Deliberately *only* the protected ones. Everything unprotected already reaches the launcher's
     * own receiver, and duplicating it here would double-deliver for no gain.
     */
    private val CAPTURED: Map<String, Array<String>> = mapOf(
        CarEvents.ACTION_BACKCAR_START to emptyArray(),
        CarEvents.ACTION_BACKCAR_END to emptyArray(),
        CarEvents.ACTION_DAY_BACKLIGHT_CHANGED to emptyArray(),
        CarEvents.ACTION_NIGHT_BACKLIGHT_CHANGED to emptyArray(),
        CarEvents.STEER_WHEEL_INFOR to arrayOf(
            CarEvents.EXTRA_SWC_LPARAM,
            CarEvents.EXTRA_SWC_WPARAM,
            CarEvents.EXTRA_SWC_VOLTAGE,
        ),
    )

    /** Sentinel for an absent int extra, so a real 0 stays distinguishable from "not present". */
    private const val EXTRA_ABSENT = Int.MIN_VALUE

    @JvmStatic
    fun main(args: Array<String>) {
        // A bare app_process has no looper and no Context. ActivityThread.systemMain() builds the
        // system context the platform's own shell tools use; it must run after the main looper
        // exists, and it is reflective because it is a hidden API. Hidden-API enforcement is
        // installed by the zygote for *app* processes, so a process exec'd straight from a shell
        // is not subject to it — [inferred] for this vendor build, which is why every failure
        // below exits quietly and the launcher simply keeps its unprotected fallbacks.
        Looper.prepareMainLooper()

        val context = systemContext() ?: return
        val filter = IntentFilter().apply { CAPTURED.keys.forEach { addAction(it) } }

        val ok = runCatching {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }.isSuccess
        if (!ok) {
            return
        }

        emit(READY_LINE)
        Looper.loop()
    }

    private val receiver = object : BroadcastReceiver() {
        /**
         * v0.4.7 — same guard as CarEvents' in-process receiver: reading any extra force-unparcels
         * the whole Bundle, and a vendor Parcelable under an unrelated key throws
         * BadParcelableException. Unguarded, that kills this process and the bridge respawns it
         * every 2 s forever. Emit nothing on failure.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val wanted = CAPTURED[action] ?: return

            runCatching {
                intent.setExtrasClassLoader(javaClass.classLoader)

                val line = StringBuilder(EVENT_PREFIX).append(SEP).append(action)
                for (extra in wanted) {
                    val value = intent.getIntExtra(extra, EXTRA_ABSENT)
                    if (value == EXTRA_ABSENT) {
                        continue
                    }
                    line.append(SEP).append(extra).append('=').append(value)
                }

                emit(line.toString())
            }
        }
    }

    private fun systemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrNull()

    /**
     * Flush every line. Without this the parent sees nothing until the 8 KB pipe buffer fills,
     * which for three-int SWC events is minutes of latency on a control path that must feel
     * instant.
     */
    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }
}
