package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * Applies `Sys_backcar_Video_Type` to the reverse-camera decoder the way the vendor gateway did.
 *
 *     SysVarLocalStore ──onRow──▶ apply(key, value)
 *                                   ├─ no root ──▶ log only; the row still persists
 *                                   ├─ setprop persist.riposte.camera.mode <n> ──▶ init trigger
 *                                   │      (riposte-camera-mode.sh: unlock, then v<n> to the nodes)
 *                                   └─ prop refused ──▶ the same write straight from here
 *
 * Stock: the settings item writes the row and posts BackcarSignalTypeSet
 * (BackcarSignalTypeSet.java:93-94). BackcarEvent then re-opens the camera on channel 0 and
 * writes "v<row>" to the PR2000 decoder node, after unlocking it with `sys.pr2000.writable`
 * (BackcarEvent.java:1371, 1392-1408; CamerasSignalDetection.java:33, 202-219). The landscape
 * settings view writes the same "v<row>" to the decoder under the CCI camera path at once
 * (SignalView.java:87-91). Both guard on the node existing, so a board without it is a no-op.
 *
 * `Sys_6752_Backcar_Video_Type` is declared (SysProviderOpt.java:237) but nothing in the
 * gateway reads it, and `rn6752_mode` (CamerasSignalDetection.java:34) is never written, so
 * that row only persists. XS9922B decoders take `persist.camera.sensorcfg.resolution` instead
 * (BackcarEvent.java:1381-1383, 2232-2238), keyed on `Sys_XS992B_Reverse_Type_Key`, a row this
 * unit has never shown; that branch is not applied here.
 */
object ReverseCameraDecoder {

    /** `PATH_PR2000`, the decoder node the gateway writes (CamerasSignalDetection.java:33). */
    const val PR2000_NODE = "/sys/pr2000/pr2000"

    /** The same decoder under the CCI camera (SignalView.java:91). */
    const val PR2000_CCI_NODE =
        "/sys/devices/platform/soc/5c0c000.qcom,cci/5c0c000.qcom,cci:qcom,camera@0/pr2000"

    /** Riposte OS 0.2: init runs the decoder write on every change of this property. */
    const val MODE_PROP = "persist.riposte.camera.mode"

    /**
     * The vendor picker's rows (BackcarSignalTypeSet.java:61-93), the SIGNAL_* constants of
     * CamerasSignalDetection.java:35-42. Row 0 lets the decoder detect the signal itself.
     */
    val VIDEO_TYPES: List<Pair<Int, String>> = listOf(
        0 to "Auto",
        1 to "CVBS NTSC",
        2 to "CVBS PAL",
        3 to "AHD 720p 25 Hz",
        4 to "AHD 1080p 25 Hz",
        5 to "AHD 720p 60 Hz",
        6 to "AHD 1080p 30 Hz",
        7 to "AHD 720p 30 Hz",
        8 to "CVBS PAL 60 Hz",
    )

    /** Nine boxes on the vendor picker, 0 auto .. 8 PAL 60 (BackcarSignalTypeSet.java:99-100). */
    const val VIDEO_TYPE_MAX = 8

    private const val WRITABLE_PROP = "sys.pr2000.writable"
    private const val TAG = "ReverseCameraDecoder"

    /** The vendor row as a decoder mode (0..8), or null when the gateway applied nothing for it. */
    fun mode(key: String, value: String): Int? {
        if (key != SettingKeys.BACKCAR_VIDEO_TYPE) {
            return null
        }

        val type = value.trim().toIntOrNull() ?: return null
        if (type < 0 || type > VIDEO_TYPE_MAX) {
            return null
        }

        return type
    }

    /** The decoder state a row maps to ("v3"), or null when the gateway applied nothing for it. */
    fun signalState(key: String, value: String): String? = mode(key, value)?.let { "v$it" }

    /**
     * Set the property init watches, and read it back: `setprop` on a label this app may not
     * write fails quietly on some builds, and the read-back is what proves the trigger fired.
     */
    fun modeCommand(mode: Int): String =
        "setprop $MODE_PROP $mode && [ \"\$(getprop $MODE_PROP)\" = $mode ]"

    /**
     * One root shell line: unlock, then write [state] to each node that exists. Runs in a
     * subshell so a failed write exits 1 without touching the shared root session.
     */
    fun command(state: String): String {
        val nodes = listOf(PR2000_NODE, PR2000_CCI_NODE).joinToString(" ") { RootShell.quote(it) }
        val write = "printf %s ${RootShell.quote(state)} > \"\$n\" || exit 1"
        return "(setprop $WRITABLE_PROP 1; for n in $nodes; do [ -e \"\$n\" ] || continue; $write; done)"
    }

    /** Apply a row change; [run] returns the shell result, or null when it could not run. */
    fun apply(
        key: String,
        value: String,
        rootAvailable: Boolean = RootShell.isRootAvailable(),
        run: (String) -> RootShell.Result? = { RootShell.exec(it) },
    ) {
        val mode = mode(key, value) ?: return
        val viaProp = modeCommand(mode)
        val direct = command("v$mode")

        if (!rootAvailable) {
            Log.w(TAG, "no root, decoder stays as is; would run: $viaProp")
            return
        }

        val prop = run(viaProp)
        if (prop?.ok == true) {
            Log.i(TAG, "decoder mode $mode via $MODE_PROP: $viaProp -> ${prop.code}")
            return
        }

        // No init trigger on this build (or the label refused us): write the nodes ourselves.
        Log.w(TAG, "$MODE_PROP refused ($viaProp -> ${prop?.code}), writing the decoder directly")
        val result = run(direct)
        Log.i(TAG, "decoder v$mode: $direct -> ${result?.code}")
    }
}
