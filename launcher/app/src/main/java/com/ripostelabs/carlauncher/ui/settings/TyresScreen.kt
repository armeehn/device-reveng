package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.carlib.CanSignal
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.TpmsAlert

/**
 * Tyres: the box's 0x48 report as the stock TPMS page shows it.
 *
 *     open ──▶ 03 6A 05 01 48 ──▶ box ──▶ 0x48 ──▶ status + 5 pressures
 *
 * Status first (TPMS valid, pressure normal or abnormal), then the four wheels and the spare in
 * kPa. A dash is a sensor with no reading. The normal/abnormal line is the car's own bit; like
 * stock it shows only while the car says its TPMS is valid.
 */
@Composable
fun TyresScreen(
    carService: CarService,
    carEvents: CarEvents,
    onBack: () -> Unit,
) {
    val report by carEvents.tpms.collectAsStateWithLifecycle()

    // The stock page asks on every open; sensors that are asleep answer with 0xFE.
    LaunchedEffect(Unit) { carService.requestTpms() }

    SettingsScaffold(
        title = "Tyres",
        subtitle = "Tyre pressures as the car reports them",
        onBack = onBack,
    ) {
        val r = report
        if (r == null) {
            SettingsSection(title = "No tyre report") {
                Text(
                    text = "The car has not sent a tyre report yet. It answers when this page " +
                        "opens if the CAN box is connected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@SettingsScaffold
        }

        SettingsSection(title = "Status") {
            InfoRow("TPMS", if (r.valid) "Valid" else "Invalid")
            if (r.valid) {
                InfoRow("Tyre pressure", if (r.abnormal) "Abnormal" else "Normal")
            }
        }

        SettingsSection(title = "Pressure") {
            InfoRow("Front left", kpa(r.frontLeftKpa))
            InfoRow("Front right", kpa(r.frontRightKpa))
            InfoRow("Rear left", kpa(r.rearLeftKpa))
            InfoRow("Rear right", kpa(r.rearRightKpa))
            InfoRow("Spare", kpa(r.spareKpa))
        }
    }
}

/**
 * The tyre popup, over the launcher. Raised once when the car's abnormal bit rises
 * ([TpmsAlert]); dismissing it waits for the next rise.
 */
@Composable
fun TyreWarningPopup(carEvents: CarEvents) {
    val report by carEvents.tpms.collectAsStateWithLifecycle()
    val alert = remember { TpmsAlert() }
    var shown by remember { mutableStateOf<CanSignal.Tpms?>(null) }

    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        if (alert.onReport(r)) {
            shown = r
        }
    }

    val r = shown ?: return
    ConfirmDialog(
        title = "Tyre pressure abnormal",
        message = "The car reports an abnormal tyre pressure.\n" +
            "Front ${kpa(r.frontLeftKpa)} / ${kpa(r.frontRightKpa)}\n" +
            "Rear ${kpa(r.rearLeftKpa)} / ${kpa(r.rearRightKpa)}\n" +
            "Spare ${kpa(r.spareKpa)}",
        confirmLabel = "OK",
        onConfirm = { shown = null },
        onDismiss = { shown = null },
    )
}

private fun kpa(v: Int?): String = v?.let { "$it kPa" } ?: "—"
