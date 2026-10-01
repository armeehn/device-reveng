package com.ripostelabs.carlauncher.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One activity another app exports: an intent action and the category its filter needs. */
internal data class IntentTarget(val action: String, val category: String)

/** The launcher's seam to PackageManager and startActivity, so the link runs on the JVM. */
internal interface ActivityPort {
    fun resolves(target: IntentTarget): Boolean
    fun start(target: IntentTarget): Boolean
}

/** What the row shows: enabled with its subtitle, or greyed with the install hint. */
internal enum class MicRow(val subtitle: String, val enabled: Boolean) {
    READY(MicSettingsLink.SUBTITLE, enabled = true),
    MISSING(MicSettingsLink.INSTALL_HINT, enabled = false),
}

/**
 * RAV4-255: the RNNoise switch lives in the suite Projection app (MicSettingsActivity), behind
 * a small "Mic" button on that app's own page. The driver looks for it under call audio, so
 * the Phone page links there. No Projection app: the row stays, greyed, and says what to fit.
 */
internal class MicSettingsLink(private val port: ActivityPort) {

    fun row(): MicRow = if (port.resolves(TARGET)) MicRow.READY else MicRow.MISSING

    /** False when the app is gone or refused, so a tap never crashes the settings page. */
    fun open(): Boolean {
        if (!port.resolves(TARGET)) {
            return false
        }
        return port.start(TARGET)
    }

    companion object {
        const val LABEL = "Microphone"
        const val SUBTITLE = "Noise reduction for Siri and CarPlay calls"
        const val INSTALL_HINT = "Install the Projection app"

        // The exported filter in rav4-apps apps/com.ripostelabs.projection/AndroidManifest.xml.
        val TARGET = IntentTarget(
            action = "com.ripostelabs.projection.MIC_SETTINGS",
            category = Intent.CATEGORY_DEFAULT,
        )
    }
}

/** [ActivityPort] over the real PackageManager; the launcher holds QUERY_ALL_PACKAGES. */
private class AndroidActivityPort(private val context: Context) : ActivityPort {

    override fun resolves(target: IntentTarget): Boolean =
        context.packageManager.resolveActivity(intent(target), 0) != null

    override fun start(target: IntentTarget): Boolean {
        // Uninstalled between the probe and the tap, or not exported after an update.
        return try {
            context.startActivity(intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private fun intent(target: IntentTarget) = Intent(target.action).addCategory(target.category)
}

/** The "Microphone" row; the probe is a binder round-trip, so it runs off the main thread. */
@Composable
internal fun MicSettingsRow() {
    val context = LocalContext.current
    val link = remember(context) { MicSettingsLink(AndroidActivityPort(context)) }
    var row by remember { mutableStateOf(MicRow.MISSING) }

    LaunchedEffect(link) {
        row = withContext(Dispatchers.IO) { link.row() }
    }

    SettingRow(
        label = MicSettingsLink.LABEL,
        description = row.subtitle,
        enabled = row.enabled,
        onClick = {
            if (!link.open()) {
                row = MicRow.MISSING
            }
        },
    ) {
        if (row.enabled) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
