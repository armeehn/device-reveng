package com.reveng.carlauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.CarSettingsController
import com.reveng.carlauncher.data.RadioPresetsStore
import com.reveng.carlauncher.data.SettingsStore

/**
 * v1.1 — the Settings app's own navigation host.
 *
 * The launcher's top-level [com.reveng.carlauncher.MainActivity] still owns a flat screen switch;
 * everything *inside* Settings is a self-contained back-stack lived here so MainActivity doesn't
 * need to learn about every category. The hub ([SettingsHub]) lists categories; tapping one pushes
 * its screen; Back (button, gesture, or a steering-wheel BACK routed by MainActivity) pops it, and
 * popping the hub itself returns to the launcher via [onExit].
 *
 * This mirrors how the vendor settings nest (a top menu of categories, each opening a detail page),
 * reskinned with our [com.reveng.carlauncher.ui.theme.CarTheme].
 */
@Composable
fun SettingsHost(
    settingsStore: SettingsStore,
    controller: CarSettingsController,
    carService: CarService,
    carEvents: CarEvents,
    radioPresetsStore: RadioPresetsStore,
    onExit: () -> Unit,
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

            SettingsRoute.Power -> PowerSettingsScreen(
                controller = controller,
                carEvents = carEvents,
                onBack = ::pop,
            )

            SettingsRoute.System -> SystemSettingsScreen(
                controller = controller,
                carService = carService,
                onBack = ::pop,
            )

            SettingsRoute.Advanced -> AdvancedSettingsScreen(
                controller = controller,
                onBack = ::pop,
            )
        }
    }
}

/** The routes inside the Settings subtree. */
sealed interface SettingsRoute {
    data object Hub : SettingsRoute
    data object LauncherPrefs : SettingsRoute
    data object Display : SettingsRoute
    data object ReverseCamera : SettingsRoute
    data object Radar : SettingsRoute
    data object Audio : SettingsRoute
    data object Climate : SettingsRoute
    data object Radio : SettingsRoute
    data object SteeringWheel : SettingsRoute
    data object Power : SettingsRoute
    data object System : SettingsRoute
    data object Advanced : SettingsRoute
}
