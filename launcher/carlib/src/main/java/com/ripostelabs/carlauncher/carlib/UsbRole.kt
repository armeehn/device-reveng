package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * The role of the GT6's ONE USB controller (`4e00000.dwc3`, "ssusb").
 *
 * A single node picks which way the port faces:
 *
 *     HOST        the car's USB sockets work: CANable, USB media, wired CarPlay
 *     PERIPHERAL  the unit is a gadget: adb over the 4PIN pigtail; the car sockets are dead
 *
 * Stock switched it by writing `host` or `peripheral` to [MODE_NODE] (eventcenter
 * AccEvent/Utils.java:47, :192-197 `openAdb(z ? "peripheral" : "host")`; the settings app's
 * AdbHelps.java:5-13 does the same from its hidden "USB debugging" tap). Stock only ever wrote
 * `peripheral` from Java (a `usbdebug.txt` on a stick, EventService.java:11125, or that tap,
 * PortraitScreenFragment.java:51); `host` is what the vendor init leaves the port in
 * (os/overlay/system/etc/init/riposte.rc:13-15). Riposte OS 0.2 without this class wrote
 * nothing, so a bench image stayed peripheral in the car and the car's sockets were dead.
 *
 * The node is root-only, so every read and write goes through [RootShell]. The choice is also
 * kept in [ROLE_PROP], which init replays at boot (riposte-usb-role.sh, the #279 pattern).
 */
enum class UsbRole(val wire: String) {
    HOST("host"),
    PERIPHERAL("peripheral"),

    /** The node could not be read, or said something else ("none"). Never written. */
    UNKNOWN("");

    /** What one [apply] did. */
    enum class Switch { UNCHANGED, WRITTEN, FAILED, NO_ROOT, REFUSED }

    companion object {
        /** `usb_otg_switch` (Utils.java:47; AdbHelps.java:5): the dwc3 role node. */
        const val MODE_NODE = "/sys/devices/platform/soc/4e00000.ssusb/mode"

        /** Riposte OS 0.2: init re-applies this at boot and on every change (riposte.rc). */
        const val ROLE_PROP = "persist.riposte.usb.role"

        /** Set by `os/build.sh --bench` (overlay/props): the pigtail must keep adb from boot. */
        const val BENCH_PROP = "ro.riposte.os.bench"

        /** riposte-usbadb.sh:7 asks for the adb gadget before the switch; so does this. */
        private const val USB_CONFIG_PROP = "sys.usb.config"
        private const val ADB_CONFIG = "adb"
        private const val BENCH_ON = "1"
        private const val TAG = "UsbRole"

        /** What the node says, or [UNKNOWN] for anything but the two wire words. */
        fun parse(text: String?): UsbRole {
            val word = text?.trim() ?: return UNKNOWN

            return entries.firstOrNull { it != UNKNOWN && it.wire == word } ?: UNKNOWN
        }

        /** A stored name back to a choice; junk, absent or UNKNOWN is no choice at all. */
        fun choice(name: String?): UsbRole? =
            entries.firstOrNull { it != UNKNOWN && it.name == name }

        /**
         * The role a fresh image starts in. A bench image is updated over the pigtail from zero,
         * so it keeps adb; a car image faces the car, where the sockets carry the CANable.
         */
        fun default(benchProp: String?): UsbRole =
            if (benchProp?.trim() == BENCH_ON) PERIPHERAL else HOST

        /** The driver's stored choice when there is one, else the image default. */
        fun resolve(stored: UsbRole?, benchProp: String?): UsbRole =
            stored?.takeIf { it != UNKNOWN } ?: default(benchProp)

        fun readCommand(): String = "cat ${RootShell.quote(MODE_NODE)}"

        /** The node's current role; a shell that fails or is absent answers [UNKNOWN]. */
        fun read(run: (String) -> RootShell.Result?): UsbRole {
            val result = run(readCommand()) ?: return UNKNOWN
            if (!result.ok) {
                return UNKNOWN
            }

            return parse(result.stdout)
        }

        /**
         * One root shell line: remember the choice for boot, ask for the adb gadget when going
         * peripheral, write the node, and read it back so the exit code is the proof. A missing
         * node (not this board) exits 2 without a write.
         */
        fun switchCommand(role: UsbRole): String {
            val node = RootShell.quote(MODE_NODE)
            val word = RootShell.quote(role.wire)
            val gadget = if (role == PERIPHERAL) "setprop $USB_CONFIG_PROP $ADB_CONFIG; " else ""

            return "(setprop $ROLE_PROP ${role.wire}; [ -e $node ] || exit 2; " +
                "${gadget}printf %s $word > $node && [ \"\$(cat $node)\" = $word ])"
        }

        /**
         * Put the port in [wanted]. Nothing is written when the node already says so ([current],
         * read through [run] when not given), when [wanted] is [UNKNOWN], or without root. Every
         * switch is logged with its exit code.
         */
        fun apply(
            wanted: UsbRole,
            rootAvailable: Boolean = RootShell.isRootAvailable(),
            current: UsbRole? = null,
            run: (String) -> RootShell.Result? = { RootShell.exec(it) },
        ): Switch {
            if (wanted == UNKNOWN) {
                Log.w(TAG, "refused: nothing to write for UNKNOWN")
                return Switch.REFUSED
            }
            if (!rootAvailable) {
                Log.w(TAG, "no root, port stays as is; wanted ${wanted.wire}")
                return Switch.NO_ROOT
            }

            val now = current ?: read(run)
            if (now == wanted) {
                Log.i(TAG, "port already ${wanted.wire}")
                return Switch.UNCHANGED
            }

            val command = switchCommand(wanted)
            val result = run(command)
            val code = result?.code
            if (result?.ok != true) {
                Log.e(TAG, "switch ${now.name} -> ${wanted.wire} FAILED: $command -> $code ${result?.err}")
                return Switch.FAILED
            }

            Log.i(TAG, "switch ${now.name} -> ${wanted.wire}: $command -> $code")
            return Switch.WRITTEN
        }
    }
}
