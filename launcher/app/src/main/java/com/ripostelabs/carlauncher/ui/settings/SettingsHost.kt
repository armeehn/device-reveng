package com.ripostelabs.carlauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.ui.theme.carShape
import kotlinx.coroutines.delay
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.AppDirectoryStore
import com.ripostelabs.carlauncher.data.CarSettingsController
import com.ripostelabs.carlauncher.data.RadioPresetsStore
import com.ripostelabs.carlauncher.data.RootTierController // v2.9
import com.ripostelabs.carlauncher.data.SettingsStore
import com.ripostelabs.carlauncher.data.UpdateController // v0.7 auto-updater
import com.ripostelabs.carlauncher.ui.ParkedOnly // v2.5

/**
 * v1.1 — the Settings app's own navigation host.
 *
 * The launcher's top-level [com.ripostelabs.carlauncher.MainActivity] still owns a flat screen switch;
 * everything *inside* Settings is a self-contained back-stack lived here so MainActivity doesn't
 * need to learn about every category. The hub ([SettingsHub]) lists categories; tapping one pushes
 * its screen; Back (button, gesture, or a steering-wheel BACK routed by MainActivity) pops it, and
 * popping the hub itself returns to the launcher via [onExit].
 *
 * This mirrors how the vendor settings nest (a top menu of categories, each opening a detail page),
 * reskinned with our [com.ripostelabs.carlauncher.ui.theme.CarTheme].
 */
@Composable
fun SettingsHost(
    settingsStore: SettingsStore,
    controller: CarSettingsController,
    carService: CarService,
    carEvents: CarEvents,
    radioPresetsStore: RadioPresetsStore,
    rootTier: RootTierController, // v2.9
    updater: UpdateController, // v0.7 auto-updater
    onExit: () -> Unit,
    // The launcher-owned instance, so the app-directory screen and the drawer read one store.
    appDirectoryStore: AppDirectoryStore? = null,
    // Optional deep link: open with this route pushed above the hub, so Back still pops
    // to the hub (status-bar power chip → Power & sleep).
    initialRoute: SettingsRoute? = null,
) {
    val backStack = remember {
        mutableStateListOf<SettingsRoute>(SettingsRoute.Hub).also { stack ->
            initialRoute?.let { stack.add(it) }
        }
    }

    fun push(route: SettingsRoute) { backStack.add(route) }
    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else onExit()
    }

    // Intercept system/gesture Back for the whole Settings subtree.
    BackHandler(enabled = true) { pop() }

    val current = backStack.last()

    // v0.4.7.1: the one collector of the controller's write results. A failed SysVar persist
    // rolls the control back (CarSettingsController) — without this, that snap-back had no
    // explanation anywhere on screen.
    var failedWriteKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller) {
        controller.writeEvents.collect { event ->
            if (!event.ok) {
                failedWriteKey = event.key
            }
        }
    }
    LaunchedEffect(failedWriteKey) {
        if (failedWriteKey != null) {
            delay(WRITE_FAILURE_TOAST_MS)
            failedWriteKey = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      AnimatedContent(
        targetState = current,
        transitionSpec = {
            (fadeIn(tween(220))) togetherWith (fadeOut(tween(180)))
        },
        label = "settings-route",
    ) { route ->
        when (route) {
            SettingsRoute.Hub -> SettingsHub(
                controller = controller,
                onOpen = ::push,
                onBack = onExit,
            )

            SettingsRoute.LauncherPrefs -> LauncherPrefsScreen(
                settingsStore = settingsStore,
                onBack = ::pop,
                carEvents = carEvents, // v2.5 live speed / motion readout
                controller = controller, // v2.8 raw Sys_CarType next to the mirror override
            )

            // v0.4.2: custom app directory — per-app Home/System/Hidden placement over the drawer.
            SettingsRoute.AppDirectory -> AppDirectoryScreen(
                onBack = ::pop,
                directoryStore = appDirectoryStore,
            )

            // v0.4.2: setup doctor — probes the grants a reinstall drops and repairs them (root).
            SettingsRoute.SetupDoctor -> SetupDoctorScreen(
                controller = controller,
                onBack = ::pop,
                settingsStore = settingsStore,
                carService = carService,
            )

            // v0.4.2: back up / restore the whole launcher state (DataStore file snapshot).
            SettingsRoute.Backup -> BackupSettingsScreen(
                onBack = ::pop,
            )

            SettingsRoute.Display -> DisplaySettingsScreen(
                controller = controller,
                carService = carService,
                onBack = ::pop,
            )

            SettingsRoute.ReverseCamera -> ReverseCameraSettingsScreen(
                controller = controller,
                onBack = ::pop,
            )

            SettingsRoute.Radar -> RadarSettingsScreen(
                controller = controller,
                carEvents = carEvents,
                settingsStore = settingsStore, // v2.8 layout-confirmed flag
                onOpenCapture = { push(SettingsRoute.RadarCapture) }, // v2.8
                onBack = ::pop,
            )

            // v2.8: the instrument that makes the guessed radar byte layout verifiable.
            SettingsRoute.RadarCapture -> RadarCaptureScreen(
                carEvents = carEvents,
                settingsStore = settingsStore,
                onBack = ::pop,
            )

            // v0.4.3: the instrument that confirms the CAN bulk-frame action + payload layout,
            // the route to a real speed reading (README "Known TODOs").
            SettingsRoute.CanCapture -> CanCaptureScreen(
                carEvents = carEvents,
                onBack = ::pop,
            )

            // v0.4.3: radio broadcast sniffer -- the route to a station name.
            SettingsRoute.RadioInfoCapture -> RadioInfoCaptureScreen(
                carEvents = carEvents,
                onBack = ::pop,
            )

            // v0.4.3: generic sniffer over the confirmed-but-undecoded CAN event cluster.
            SettingsRoute.VehicleDataCapture -> VehicleDataCaptureScreen(
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.Audio -> AudioSettingsScreen(
                controller = controller,
                carService = carService,
                onBack = ::pop,
            )

            SettingsRoute.Climate -> ClimateSettingsScreen(
                controller = controller,
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.Radio -> RadioSettingsScreen(
                controller = controller,
                carService = carService,
                radioPresetsStore = radioPresetsStore,
                onBack = ::pop,
            )

            SettingsRoute.SteeringWheel -> SteeringWheelSettingsScreen(
                controller = controller,
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.WheelGestures -> WheelGesturesSettingsScreen(
                settingsStore = settingsStore,
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.Power -> PowerSettingsScreen(
                controller = controller,
                carEvents = carEvents,
                onBack = ::pop,
            )

            // v2.9: root-only capabilities, including the one destructive action in the app.
            SettingsRoute.RootTier -> RootTierSettingsScreen(
                controller = controller,
                rootTier = rootTier,
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.System -> SystemSettingsScreen(
                controller = controller,
                carService = carService,
                onBack = ::pop,
            )

            // v2.5 §1.4: the raw SysVar browser is 455 keys of free-text editing over live
            // vehicle config — the most attention-hungry screen in the app, and parked-only.
            // v0.4.7: dump the live SysVar table to JSON for offline RE.
            SettingsRoute.SysVarExport -> SysVarExportScreen(
                controller = controller,
                onBack = ::pop,
            )

            // v0.7: pull-from-GitHub auto-updater.
            SettingsRoute.Updates -> UpdatesScreen(
                updater = updater,
                onBack = ::pop,
            )

            SettingsRoute.Advanced -> ParkedOnly(
                feature = "The SysVar browser",
                onBack = ::pop,
            ) {
                AdvancedSettingsScreen(
                    controller = controller,
                    onBack = ::pop,
                )
            }
        }
      }

      failedWriteKey?.let { key -> WriteFailureToast(key = key) }
    }
}

/** Small themed notice naming the SysVar whose persist failed and visibly snapped back. */
@Composable
private fun WriteFailureToast(key: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 24.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = carShape(14.dp),
        ) {
            Text(
                text = "Couldn't save $key — the value was rolled back",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** Long enough to read one line, short enough not to nag on a rootless unit. */
private const val WRITE_FAILURE_TOAST_MS = 4_000L

/** The routes inside the Settings subtree. */
sealed interface SettingsRoute {
    data object Hub : SettingsRoute
    data object LauncherPrefs : SettingsRoute
    data object AppDirectory : SettingsRoute // v0.4.2
    data object SetupDoctor : SettingsRoute // v0.4.2
    data object Backup : SettingsRoute // v0.4.2
    data object Display : SettingsRoute
    data object ReverseCamera : SettingsRoute
    data object Radar : SettingsRoute
    data object RadarCapture : SettingsRoute // v2.8
    data object CanCapture : SettingsRoute // v0.4.3
    data object RadioInfoCapture : SettingsRoute // v0.4.3
    data object VehicleDataCapture : SettingsRoute // v0.4.3
    data object Audio : SettingsRoute
    data object Climate : SettingsRoute
    data object Radio : SettingsRoute
    data object SteeringWheel : SettingsRoute
    data object WheelGestures : SettingsRoute
    data object Power : SettingsRoute
    data object RootTier : SettingsRoute // v2.9
    data object System : SettingsRoute
    data object Advanced : SettingsRoute
    data object SysVarExport : SettingsRoute // v0.4.7
    data object Updates : SettingsRoute // v0.7 auto-updater
}
