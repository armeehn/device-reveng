package com.reveng.carlauncher

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.DayNightMode // v0.6
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.data.ThemeStore
import com.reveng.carlauncher.media.NowPlayingRepository
import com.reveng.carlauncher.ui.HomeScreen
import com.reveng.carlauncher.ui.SettingsScreen // v0.6
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

            val activeTheme by themeStore.activeTheme.collectAsStateWithLifecycle()
            val allThemes by themeStore.allThemes.collectAsStateWithLifecycle()

            var screen by remember { mutableStateOf<Screen>(Screen.Home) }

            CarLauncherTheme(theme = activeTheme, night = night) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (val s = screen) {
                        Screen.Home -> HomeScreen(
                            carEvents = carEvents,
                            carService = carService,
                            appRepository = appRepository,
                            nowPlaying = nowPlaying,
                            onOpenThemes = { screen = Screen.Themes },
                            // v0.6: wire settings + a Settings-screen entry point.
                            settingsStore = settingsStore,
                            onOpenSettings = { screen = Screen.Settings },
                        )

                        // v0.6: launcher Settings screen.
                        Screen.Settings -> SettingsScreen(
                            settingsStore = settingsStore,
                            onBack = { screen = Screen.Home },
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

                        is Screen.Editor -> ThemeEditorScreen(
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

    override fun onDestroy() {
        super.onDestroy()
        carEvents.unregister()
        carService.unbind()
        nowPlaying.stop()
    }

    /** Top-level screens — a simple switch, no nav library (LAUNCHER_DESIGN v0.5). */
    private sealed interface Screen {
        data object Home : Screen
        data object Themes : Screen
        data object Settings : Screen // v0.6
        data class Editor(val theme: CarTheme) : Screen
    }
}
