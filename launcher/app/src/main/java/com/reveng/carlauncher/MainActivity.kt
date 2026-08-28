package com.reveng.carlauncher

import android.Manifest // v2.5
import android.content.Intent
import android.content.pm.PackageManager // v2.5
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts // v2.5
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.carlib.RootShell
import com.reveng.carlauncher.data.CarSettingsController // v1.1 settings suite
import com.reveng.carlauncher.data.DayNightMode // v0.6
import com.reveng.carlauncher.data.RadioPresetsStore // v0.9
import com.reveng.carlauncher.data.SettingKeys // v2.5 touch beep
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.data.ThemeStore
import com.reveng.carlauncher.input.LauncherFocus // v0.8 SWC navigation
import com.reveng.carlauncher.input.LocalLauncherFocus // v0.8
import com.reveng.carlauncher.input.NavKey // v0.8
import com.reveng.carlauncher.input.SwcNavigator // v0.8
import com.reveng.carlauncher.media.NowPlayingRepository
import com.reveng.carlauncher.ui.CarFeedback // v2.5
import com.reveng.carlauncher.ui.HomeScreen
import com.reveng.carlauncher.ui.LocalCarFeedback // v2.5
import com.reveng.carlauncher.ui.ParkedOnly // v2.5
import com.reveng.carlauncher.ui.ProvideParkedOnlyLock // v2.5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.reveng.carlauncher.ui.OnboardingScreen // v1.0
import com.reveng.carlauncher.ui.settings.SettingsHost // v1.1 settings suite
import com.reveng.carlauncher.ui.ThemeEditorScreen
import com.reveng.carlauncher.ui.ThemesScreen
import com.reveng.carlauncher.ui.theme.CarLauncherTheme
import com.reveng.carlauncher.ui.theme.CarTheme

/**
 * MainActivity — the companion HOME. See CAR_API §6.
 *
 * Registered with MAIN + HOME + DEFAULT + LAUNCHER (AndroidManifest.xml); the user still
 * selects the default home from the system chooser. singleTask + landscape are fixed for
 * the 1920x720 head unit.
 *
 * v0.5: hosts a plain top-level screen switch (Home ↔ Themes ↔ editor — no nav library)
 * and applies the active [CarTheme] from [ThemeStore], re-themed live on day/night and on
 * theme switch.
 */
class MainActivity : ComponentActivity() {

    private lateinit var carEvents: CarEvents
    private lateinit var carService: CarService
    private lateinit var appRepository: AppRepository
    private lateinit var nowPlaying: NowPlayingRepository
    private lateinit var themeStore: ThemeStore
    private lateinit var settingsStore: SettingsStore // v0.6
    private lateinit var radioPresetsStore: RadioPresetsStore // v0.9
    private lateinit var carSettingsController: CarSettingsController // v1.1 settings suite

    // v0.8: roving focus ring for steering-wheel / DPAD navigation. Held as a field so the
    // key dispatcher below and the Compose tree (via LocalLauncherFocus) share one instance.
    private val launcherFocus = LauncherFocus()

    // v0.8: hoisted top-level screen state so the Back/Home keys (handled outside Compose in
    // dispatchKeyEvent) can return to Home from a sub-screen.
    private val screenState = mutableStateOf<Screen>(Screen.Home)

    // v2.5: eyes-free tap confirmation. Held as a field because SWC keys are handled outside
    // composition (handleNav), and that is the case §1.4 cares about most.
    private lateinit var carFeedback: CarFeedback

    /**
     * v2.5: the location grant that lets [com.reveng.carlauncher.carlib.GpsSpeedSource] read road
     * speed. [CarEvents.register] runs before the user can answer, so its first attempt finds no
     * permission; this callback starts the source once the answer is yes. A denial is not fatal —
     * speed stays unknown and the parked-only gate fails open by design.
     */
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // The isInitialized guard is load-bearing. This launcher is registered during field
        // initialisation, i.e. before super.onCreate(), and ActivityResultRegistry re-delivers a
        // pending result from inside super.onCreate() — before [carEvents] is assigned below. So
        // if the activity is killed while the permission dialog is up and the user then answers
        // it, this fires with carEvents still unset and would crash on exactly the path where
        // permission was just granted. Skipping is safe: register() starts the source itself, and
        // by then the grant is in place.
        if (granted && ::carEvents.isInitialized) {
            carEvents.startSpeedSource()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the head-unit display awake while the launcher is foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        carEvents = CarEvents(applicationContext).also { it.register() }
        carService = CarService(applicationContext).also { it.bind() }
        appRepository = AppRepository(this)
        nowPlaying = NowPlayingRepository(applicationContext).also { it.start(lifecycleScope) }
        themeStore = ThemeStore(applicationContext)
        settingsStore = SettingsStore(applicationContext) // v0.6
        radioPresetsStore = RadioPresetsStore(applicationContext, lifecycleScope) // v0.9
        carSettingsController = CarSettingsController(applicationContext, lifecycleScope) // v1.1

        // v2.5: beep follows the vendor Set_TouchBeep SysVar, read fresh at each tap so a change
        // in the settings suite applies without rebuilding anything.
        carFeedback = CarFeedback(
            view = window.decorView,
            carService = carService,
            scope = lifecycleScope,
            beepEnabled = { carSettingsController.getBoolean(SettingKeys.TOUCH_BEEP, false) },
        )

        // v2.5: ask once for the location permission behind the parked-only gate.
        requestLocationPermissionIfNeeded()

        // v0.8: input source (a) — vendor STEER_WHEEL_INFOR broadcasts as CAR_KEY_* codes.
        lifecycleScope.launch {
            carEvents.swcKeys.collect { key ->
                SwcNavigator.fromSwc(key)?.let { handleNav(it) }
            }
        }

        setContent {
            // Day/night from the vendor illumination broadcast (CAR_API §1.3).
            val dayNight by carEvents.dayNight.collectAsStateWithLifecycle()
            // v0.6: the Settings/QuickControls day-night mode can override the car signal.
            val settings by settingsStore.settings.collectAsStateWithLifecycle()
            val night = when (settings.dayNightMode) {
                DayNightMode.FORCE_DAY -> false
                DayNightMode.FORCE_NIGHT -> true
                DayNightMode.AUTO -> dayNight == CarEvents.DayNight.NIGHT
            }

            // Mirror our day/night into the SYSTEM night mode. The soft keyboard is a separate
            // system app that themes off system uiMode, not our Compose theme — without this it
            // renders light over a dark drawer. uiMode is in configChanges, so no recreate.
            LaunchedEffect(night) {
                withContext(Dispatchers.IO) {
                    RootShell.exec("cmd uimode night " + if (night) "yes" else "no")
                }
            }

            val activeTheme by themeStore.activeTheme.collectAsStateWithLifecycle()
            val allThemes by themeStore.allThemes.collectAsStateWithLifecycle()

            var screen by screenState // v0.8: hoisted to a field (Back/Home keys)

            // v1.0: route to onboarding exactly once on genuine first run. firstRun is null
            // until DataStore resolves; we hold a plain themed frame (below) until then so a
            // returning user never flashes the onboarding screen.
            val firstRun by settingsStore.firstRun.collectAsStateWithLifecycle()
            var routed by remember { mutableStateOf(false) }

            // v2.5: the parked-only verdict. Gated features block on MOVING only — UNKNOWN
            // fails open, see CarEvents.motion.
            val motion by carEvents.motion.collectAsStateWithLifecycle()
            val parkedOnlyLock =
                settings.motionGateEnabled && motion == CarEvents.Motion.MOVING

            CarLauncherTheme(theme = activeTheme, night = night) {
              CompositionLocalProvider(
                  LocalLauncherFocus provides launcherFocus,
                  LocalCarFeedback provides carFeedback, // v2.5
              ) {
               ProvideParkedOnlyLock(locked = parkedOnlyLock) { // v2.5
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // v1.0: crossfade top-level screen transitions (Home ↔ Themes ↔ Settings ↔
                    // Editor ↔ Onboarding) instead of a hard swap. While firstRun is unresolved
                    // we render nothing but the themed Surface — a fast, jank-free first frame.
                    if (firstRun == null) {
                        // holding frame: just the background Surface
                    } else {
                      // v1.0: apply first-run routing once, DURING composition and before the
                      // Crossfade's first pass, so a genuine first boot composes straight to
                      // Onboarding. Doing this in a post-composition LaunchedEffect made the
                      // Crossfade render Home for a frame and then animate a 300ms crossfade into
                      // Onboarding — the exact flash the holding frame is meant to prevent.
                      if (!routed) {
                          if (firstRun == true && screen == Screen.Home) screen = Screen.Onboarding
                          routed = true
                      }
                      Crossfade(
                        targetState = screen,
                        animationSpec = tween(durationMillis = 300),
                        label = "screen",
                      ) { s ->
                        when (s) {
                            Screen.Onboarding -> OnboardingScreen(
                                themeStore = themeStore,
                                appRepository = appRepository,
                                night = night,
                                onFinish = {
                                    settingsStore.setFirstRunComplete()
                                    screen = Screen.Home
                                },
                            )

                            Screen.Home -> HomeScreen(
                                carEvents = carEvents,
                                carService = carService,
                                appRepository = appRepository,
                                nowPlaying = nowPlaying,
                                onOpenThemes = { screen = Screen.Themes },
                                // v0.6: wire settings + a Settings-screen entry point.
                                settingsStore = settingsStore,
                                onOpenSettings = { screen = Screen.Settings },
                                radioPresetsStore = radioPresetsStore, // v0.9 Radio 2.0
                            )

                            // v1.1: full settings suite — categorized, reskinned vendor mirror.
                            Screen.Settings -> SettingsHost(
                                settingsStore = settingsStore,
                                controller = carSettingsController,
                                carService = carService,
                                carEvents = carEvents,
                                radioPresetsStore = radioPresetsStore,
                                onExit = { screen = Screen.Home },
                            )

                            Screen.Themes -> ThemesScreen(
                                themes = allThemes,
                                activeId = activeTheme.id,
                                night = night,
                                onSetActive = { themeStore.setActive(it.id) },
                                onDuplicate = { themeStore.duplicate(it) },
                                onEdit = { screen = Screen.Editor(it) },
                                onDelete = { themeStore.delete(it.id) },
                                onNew = {
                                    // An unsaved draft off the active theme; persisted only on Save.
                                    val draft = activeTheme.copy(
                                        id = "user.${System.currentTimeMillis()}",
                                        name = "New theme",
                                        isBuiltIn = false,
                                    )
                                    screen = Screen.Editor(draft)
                                },
                                onBack = { screen = Screen.Home },
                            )

                            // v2.5 §1.4: colour-picking is fine-grained and attention-heavy —
                            // parked-only. Gated here rather than inside the editor so the
                            // editor keeps knowing nothing about motion.
                            is Screen.Editor -> ParkedOnly(
                                feature = "The theme editor",
                                onBack = { screen = Screen.Themes },
                            ) {
                                ThemeEditorScreen(
                                    source = s.theme,
                                    night = night,
                                    onSave = {
                                        themeStore.upsert(it)
                                        themeStore.setActive(it.id)
                                        screen = Screen.Themes
                                    },
                                    onCancel = { screen = Screen.Themes },
                                )
                            }
                        }
                      }
                    }
                }
               }
              }
            }
        }
    }

    /**
     * v2.5: ask for the location permission that backs the parked-only gate, once, and only when
     * it is actually missing. Android stops showing the prompt after repeated denials, which is
     * the correct outcome: the gate then fails open and the launcher stays fully usable.
     */
    private fun requestLocationPermissionIfNeeded() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            return
        }
        locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * v0.8: input source (b) — real Android [KeyEvent]s (DPAD / ENTER / MEDIA_* and any vendor
     * codes surfacing as KeyEvents). Consumed only when we actually act, so system Back on Home
     * and dialog Back still work.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val nav = SwcNavigator.fromKeyEvent(event.keyCode)
        if (nav != null && event.action == KeyEvent.ACTION_DOWN && handleNav(nav)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Route a decoded [NavKey]. Media transport reaches the active MediaController regardless of
     * focus; directional / CENTER drive the focus ring while on Home; Back/Home return Home from
     * a sub-screen. Returns true when the key was consumed.
     */
    private fun handleNav(nav: NavKey): Boolean {
        val consumed = routeNav(nav)
        // v2.5 §1.4: a wheel press is the one input the driver makes without looking, so confirm
        // it. Only on consumption — buzzing for a key we ignored would report a lie.
        if (consumed) {
            carFeedback.tap()
        }
        return consumed
    }

    private fun routeNav(nav: NavKey): Boolean = when (nav) {
        NavKey.MEDIA_NEXT -> { nowPlaying.next(); true }
        NavKey.MEDIA_PREV -> { nowPlaying.prev(); true }
        NavKey.MEDIA_PLAY_PAUSE -> { nowPlaying.playPause(); true }
        NavKey.HOME -> {
            screenState.value = Screen.Home
            launcherFocus.reset()
            true
        }
        NavKey.BACK -> when (screenState.value) {
            Screen.Home -> false // let the system / a dialog handle Back on Home
            // v1.1: Settings owns its own back-stack (SettingsHost). Route Back through the
            // OnBackPressedDispatcher so its BackHandler pops one level (and exits to Home only
            // from the hub) instead of jumping straight Home from a deep settings screen.
            Screen.Settings -> { onBackPressedDispatcher.onBackPressed(); true }
            else -> { screenState.value = Screen.Home; true } // Themes / Editor -> Home
        }
        NavKey.CENTER, NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT ->
            if (screenState.value == Screen.Home) launcherFocus.onKey(nav) else false
    }

    /**
     * v1.0: HOME intent handling. Because MainActivity is singleTask, pressing HOME (or the
     * system relaunching us as the HOME app after the vendor idle/screensaver dismisses)
     * delivers here instead of recreating the activity. Behave like a launcher should: pop back
     * to the top-level Home surface and reset the focus ring. We never fight the vendor
     * com.android.atslcarconsole overlay — we simply re-assert Home cleanly when we regain focus.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME) && screenState.value != Screen.Onboarding) {
            screenState.value = Screen.Home
            launcherFocus.reset()
        }
    }

    /**
     * v1.0: re-assert our display flags on resume. The vendor idle/screensaver can clear
     * FLAG_KEEP_SCREEN_ON while it owns the screen; re-adding it here (idempotent) keeps the
     * head unit awake once we're foreground again, without polling or contending with the
     * vendor overlay.
     */
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        carEvents.unregister()
        carService.unbind()
        nowPlaying.stop()
        carSettingsController.release() // v1.1
        themeStore.release()
        settingsStore.release()
    }

    /** Top-level screens — a simple switch, no nav library (LAUNCHER_DESIGN v0.5). */
    private sealed interface Screen {
        data object Onboarding : Screen // v1.0 first-run flow
        data object Home : Screen
        data object Themes : Screen
        data object Settings : Screen // v0.6
        data class Editor(val theme: CarTheme) : Screen
    }
}
