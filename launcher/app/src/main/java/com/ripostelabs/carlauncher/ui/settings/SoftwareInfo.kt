package com.ripostelabs.carlauncher.ui.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/**
 * RAV4-193: the rows of stock's software version page (`GlobalDataStatic.getDataSoftVersion`,
 * `data/GlobalDataStatic.java:256`) that Android itself can answer: build, RAM, storage and
 * serial. The MCU and CAN box rows come from the owner's frames instead.
 */
object SoftwareInfo {
    const val UNKNOWN = "—"

    private const val KIB = 1024L
    private const val MIB = KIB * KIB
    private const val GIB = MIB * KIB

    /** The first non-blank [candidates], most trusted first; [UNKNOWN] when none is known. */
    fun firstKnown(vararg candidates: String?): String =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: UNKNOWN

    /** Binary units, as Android's own storage page: `512 MB`, `2.0 GB`. */
    fun size(bytes: Long): String {
        if (bytes < GIB) {
            return "${bytes / MIB} MB"
        }
        return String.format(Locale.US, "%.1f GB", bytes.toDouble() / GIB)
    }

    fun capacity(total: Long, free: Long): String = "${size(free)} free of ${size(total)}"

    /** `ActivityManager.MemoryInfo`: what the kernel reports, not the chip's label. */
    fun ram(context: Context): String {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return UNKNOWN
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return capacity(info.totalMem, info.availMem)
    }

    /** The data partition, where apps and media live. */
    fun storage(): String = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        capacity(stat.totalBytes, stat.availableBytes)
    }.getOrDefault(UNKNOWN)

    /**
     * Stock's barcode row. `Build.getSerial` needs READ_PRIVILEGED_PHONE_STATE, which only a
     * platform-signed launcher holds, so an unsigned build shows [UNKNOWN] rather than failing.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun serial(): String = runCatching { Build.getSerial() }
        .getOrNull()
        ?.takeUnless { it == Build.UNKNOWN }
        ?: UNKNOWN

    /** Stock's OS and framework rows: the Android release and the image's build id. */
    fun android(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun build(): String = firstKnown(Build.DISPLAY)

    fun model(): String = firstKnown("${Build.MANUFACTURER} ${Build.MODEL}")
}
