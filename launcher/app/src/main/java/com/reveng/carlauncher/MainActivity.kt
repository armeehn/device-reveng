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
import androidx.compose.foundation.layout.Box // v2.8
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect // v2.8
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState // v2.6
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.carlib.GatewayHandshake // v3.0
import com.reveng.carlauncher.carlib.RootShell
import com.reveng.carlauncher.carlib.SysVar // v0.4.9 vendor hidden-apps list
import com.reveng.carlauncher.data.CarSettingsController // v1.1 settings suite
import com.reveng.carlauncher.data.parseVendorHidden // v0.4.9
import com.reveng.carlauncher.data.CrashLog // v0.4.3.7
import com.reveng.carlauncher.data.AppDirectoryStore // v0.4.2
import com.reveng.carlauncher.data.AppOrderStore // v3.0
import com.reveng.carlauncher.data.DriverProfilesStore // v3.0
import com.reveng.carlauncher.data.FavoritesStore // v3.0
import com.reveng.carlauncher.data.IgnitionSession // v0.4.7.1
import com.reveng.carlauncher.data.DayNightMode // v0.6
import com.reveng.carlauncher.data.DriverSide // v2.8
import com.reveng.carlauncher.data.NotificationFilterStore // v2.7
import com.reveng.carlauncher.data.RadioPresetsStore // v0.9
import com.reveng.carlauncher.data.Reachability // v2.8
import com.reveng.carlauncher.data.RootTierController // v2.9
import com.reveng.carlauncher.data.SettingKeys // v2.5 touch beep
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.data.SystemChrome // v2.5
import com.reveng.carlauncher.data.ThemeSnapshotStore
import com.reveng.carlauncher.data.ThemeStore
import com.reveng.carlauncher.data.UpdateController // v0.7 auto-updater
import com.reveng.carlauncher.input.KeyBridge // v2.8
import com.reveng.carlauncher.input.KeyPump // v2.8
import com.reveng.carlauncher.data.WatchHistoryStore // v2.7
import com.reveng.carlauncher.input.LauncherFocus // v0.8 SWC navigation
import com.reveng.carlauncher.input.LocalLauncherFocus // v0.8
import com.reveng.carlauncher.input.NavEvent // v2.8
import com.reveng.carlauncher.input.NavKey // v0.8
import com.reveng.carlauncher.input.SwcNavigator // v0.8
import com.reveng.carlauncher.media.ContinueWatchingRepository // v2.7
import com.reveng.carlauncher.media.MiniScreenController // v4.1
import com.reveng.carlauncher.media.NowPlayingRepository
import com.reveng.carlauncher.notif.NotificationRepository // v2.7
import com.reveng.carlauncher.ui.CarFeedback // v2.5
import com.reveng.carlauncher.ui.DashboardScreen // v3.0
import com.reveng.carlauncher.ui.HomeScreen
import com.reveng.carlauncher.ui.LocalCarFeedback // v2.5
import com.reveng.carlauncher.ui.MediaScreen // v2.6
import com.reveng.carlauncher.ui.ContinueWatchingScreen // v2.7
import com.reveng.carlauncher.ui.NotificationShelfScreen // v2.7
import com.reveng.carlauncher.ui.ParkedOnly // v2.5
import com.reveng.carlauncher.ui.ProfilesScreen // v3.0
import com.reveng.carlauncher.ui.ProvideParkedOnlyLock // v2.5
import com.reveng.carlauncher.ui.ShadeOverlay // v2.5 shade
import com.reveng.carlauncher.ui.RadarSideStrip // v2.8
import com.reveng.carlauncher.ui.RadioScreen // v2.6
import com.reveng.carlauncher.ui.rememberClockNight // v2.7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay // v2.6
import kotlinx.coroutines.flow.combine // v0.4.7.1 muted-aware TTS
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.reveng.carlauncher.ui.OnboardingScreen // v1.0
import com.reveng.carlauncher.ui.settings.SettingsHost // v1.1 settings suite
import com.reveng.carlauncher.ui.settings.SettingsRoute
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
    private lateinit var speechController: com.reveng.carlauncher.media.SpeechController // v0.4.2 TTS
    private lateinit var radioPresetsStore: RadioPresetsStore // v0.9
    private lateinit var carSettingsController: CarSettingsController // v1.1 settings suite
    private lateinit var rootTierController: RootTierController // v2.9
    private lateinit var updateController: UpdateController // v0.7 auto-updater

    // v3.0 cockpit: driver profiles need the stores they write through to, and the gateway
    // handshake needs to outlive any one screen.
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var appOrderStore: AppOrderStore
    private lateinit var appDirectoryStore: AppDirectoryStore
    private lateinit var profilesStore: DriverProfilesStore
    private lateinit var gatewayHandshake: GatewayHandshake

    private lateinit var ignitionSession: IgnitionSession // v0.4.7.1 session timer holder
    private lateinit var watchHistoryStore: WatchHistoryStore // v2.7
    private lateinit var continueWatching: ContinueWatchingRepository // v2.7
    private lateinit var miniScreen: MiniScreenController // v4.1 video mini screen
    private lateinit var notificationFilter: NotificationFilterStore // v2.7

    // v0.8: roving focus ring for steering-wheel / DPAD navigation. Held as a field so the
    // key dispatcher below and the Compose tree (via LocalLauncherFocus) share one instance.
    private val launcherFocus = LauncherFocus()

    // v0.8: hoisted top-level screen state so the Back/Home keys (handled outside Compose in
    // dispatchKeyEvent) can return to Home from a sub-screen.
    private val screenState = mutableStateOf<Screen>(Screen.Home)

    // v2.5: eyes-free tap confirmation. Held as a field because SWC keys are handled outside
    // composition (handleNav), and that is the case §1.4 cares about most.
    private lateinit var carFeedback: CarFeedback

    // v2.8: one press-timing model for both input sources (see KeyPump), and the bridge that
    // carries a decoded key into Compose's focus system on every screen that is not Home.
    private lateinit var keyPump: KeyPump
    private val keyBridge by lazy { KeyBridge(window) }

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
        // v0.4.3.7: arm the crash log before anything else runs, so a failure during the rest of
        // this method is recorded too. Cheap and synchronous — it only reads the current default
        // handler and installs a wrapper around it.
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the head-unit display awake while the launcher is foreground.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        carEvents = CarEvents(applicationContext).also { it.register() }
        // v0.4.7.1: activity-scoped so opening the Dashboard doesn't restart the session timer.
        ignitionSession = IgnitionSession(lifecycleScope, carEvents.accOn)
        carService = CarService(applicationContext).also { it.bind() }
        appRepository = AppRepository(this)
        nowPlaying = NowPlayingRepository(applicationContext).also { it.start(lifecycleScope) }
        themeStore = ThemeStore(applicationContext)
        settingsStore = SettingsStore(applicationContext) // v0.6

        // v2.7: the notification shelf's mute filter. Constructed before the speech controller
        // below, which shares it.
        notificationFilter = NotificationFilterStore(applicationContext, lifecycleScope)

        // v0.4.2: text-to-speech — announces the now-playing track when the user opts in
        // (SettingsStore.readNowPlaying, off by default). The controller no-ops until its engine
        // is ready and stays silent while the toggle is off.
        speechController = com.reveng.carlauncher.media.SpeechController(applicationContext).also {
            it.observeNowPlaying(
                lifecycleScope,
                nowPlaying.state,
                settingsStore.settings.map { s -> s.readNowPlaying },
            )
            // The same mute filter the shelf renders with: TTS was fed the raw repository and
            // read notifications aloud from apps the driver had muted.
            it.observeNotifications(
                lifecycleScope,
                combine(
                    com.reveng.carlauncher.notif.NotificationRepository.items,
                    notificationFilter.muted,
                ) { items, muted -> items.filterNot { n -> n.packageName in muted } },
                settingsStore.settings.map { s -> s.readNotifications },
            )
        }
        radioPresetsStore = RadioPresetsStore(applicationContext, lifecycleScope) // v0.9
        carSettingsController = CarSettingsController(applicationContext, lifecycleScope) // v1.1
        rootTierController = RootTierController(applicationContext, lifecycleScope) // v2.9

        // v0.7: auto-updater. The launch check self-gates (toggle, token, once a day), so on
        // most starts this launches one coroutine that reads a DataStore and stops.
        updateController = UpdateController(applicationContext, lifecycleScope)
        updateController.autoCheckOnLaunch()

        // v3.0: driver profiles + the vendor-gateway UIMODE channel.
        favoritesStore = FavoritesStore(applicationContext, lifecycleScope)
        appOrderStore = AppOrderStore(applicationContext, lifecycleScope)
        // One instance per DataStore file, owned here. The drawer, Home and the app-directory
        // screen used to `remember` their own, and each duplicate starts its own eager collector
        // — a Home <-> Settings round trip re-read three preference files for nothing.
        appDirectoryStore = AppDirectoryStore(applicationContext, lifecycleScope)
        profilesStore = DriverProfilesStore(applicationContext, lifecycleScope)
        gatewayHandshake = GatewayHandshake(applicationContext).also { it.register() }

        // v2.7: the continue-watching shelf rides the media stack we already have — it records
        // Jellyfin sessions as they play, so it must start with the launcher, not with the screen.
        watchHistoryStore = WatchHistoryStore(applicationContext, lifecycleScope)
        continueWatching = ContinueWatchingRepository(applicationContext, watchHistoryStore)
            .also { it.observe(lifecycleScope, nowPlaying.state) }

        // v4.1: the video mini screen (freeform window over the home media card).
        miniScreen = MiniScreenController(applicationContext, lifecycleScope)

        // v2.7: the notification shelf's listener. Root-enable off the main thread — this shells
        // out, and a launcher that blocks its first frame on `su` is a launcher that looks broken.
        lifecycleScope.launch(Dispatchers.IO) {
            NotificationRepository.ensureListenerEnabled(applicationContext)
        }

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

        keyPump = KeyPump(lifecycleScope, ::onNavEvent) // v2.8

        // v0.8: input source (a) — vendor STEER_WHEEL_INFOR broadcasts as CAR_KEY_* codes.
        // v2.8: the broadcast carries the press state, so feed the pump both edges rather than
        // dropping the release — held-repeat and long-press both need to know when it ended.
        lifecycleScope.launch {
            carEvents.swcKeys.collect { key ->
                val nav = SwcNavigator.fromCarKey(key.keyIndex) ?: return@collect
                if (key.down) keyPump.down(nav) else keyPump.up(nav)
            }
        }

        // v0.4.9: the vendor gateway's "open the app drawer" broadcast
        // (ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT, CUSTOMERUI_NOTES §6). Our drawer is the Home
        // centre grid, so the request routes through the same screen switch every other
        // navigation uses.
        lifecycleScope.launch {
            carEvents.openAppList.collect { screenState.value = Screen.Home }
        }

        // v2.8: reverse is an interruption, not navigation. The vendor composites its reverse
        // window over us and takes the screen; when it hands the screen back, put the ring where
        // the driver left it instead of making them find their place again.
        lifecycleScope.launch {
            carEvents.reverse.collect { engaged ->
                keyPump.cancel() // a key held as the window changed has no meaningful release
                if (engaged) launcherFocus.saveForInterruption()
                else launcherFocus.restoreAfterInterruption()
            }
        }

        setContent {
            // Day/night from the vendor illumination broadcast (CAR_API §1.3).
            val dayNight by carEvents.dayNight.collectAsStateWithLifecycle()
            // v0.6: the Settings/QuickControls day-night mode can override the car signal.
            val settings by settingsStore.settings.collectAsStateWithLifecycle()

            // v2.7: the clock stand-in. AUTO only falls back once we can say the car has never
            // spoken — a unit that IS hearing illumination keeps following it, because the car
            // knows about tunnels and the clock does not.
            val illuminationSeen by carEvents.illuminationSeen.collectAsStateWithLifecycle()
            val clockNight by rememberClockNight(settings.nightStartHour, settings.nightEndHour)
            val night = when (settings.dayNightMode) {
                DayNightMode.FORCE_DAY -> false
                DayNightMode.FORCE_NIGHT -> true
                DayNightMode.CLOCK -> clockNight
                DayNightMode.AUTO ->
                    if (settings.clockFallback && !illuminationSeen) clockNight
                    else dayNight == CarEvents.DayNight.NIGHT
            }

            // Mirror our day/night into the SYSTEM night mode. The soft keyboard is a separate
            // system app that themes off system uiMode, not our Compose theme — without this it
            // renders light over a dark drawer. uiMode is in configChanges, so no recreate.
            // v3.0: announce the same mode to the vendor gateway (CAR_API §6.2), which is what
            // makes the vendor stack treat us as *the* launcher rather than an app that happens
            // to be foreground. Fire-and-forget; re-announcing costs nothing.
            LaunchedEffect(night) {
                gatewayHandshake.announceUiMode(night)
            }

            LaunchedEffect(night) {
                withContext(Dispatchers.IO) {
                    RootShell.exec("cmd uimode night " + if (night) "yes" else "no")
                }
            }

            // v2.5: apply the vendor-bar suppression choice. Keyed on the setting so a toggle
            // takes effect at once; also re-asserted on every start because the `cmd statusbar`
            // half of the suppression is runtime-only and lost when SystemUI restarts (reboot).
            LaunchedEffect(settings.replaceSystemBars) {
                withContext(Dispatchers.IO) {
                    SystemChrome.apply(settings.replaceSystemBars)
                }
            }

            val activeTheme by themeStore.activeTheme.collectAsStateWithLifecycle()
            val allThemes by themeStore.allThemes.collectAsStateWithLifecycle()

            var screen by screenState // v0.8: hoisted to a field (Back/Home keys)

            // v1.0: route to onboarding exactly once on genuine first run. firstRun is null
            // until DataStore resolves; we hold a plain themed frame (below) until then so a
            // returning user never flashes the onboarding screen.
            val firstRun by settingsStore.firstRun.collectAsStateWithLifecycle()

            // v1.0: route to onboarding on a genuine first run — derived, not written back.
            // Assigning `screen` (and a `routed` latch) inside the composition wrote state this
            // same composition had already read, so Compose invalidated the scope and the
            // first-run frame composed twice. Onboarding's onFinish clears firstRun, and that is
            // what releases the route; nothing is written during composition any more.
            val shownScreen =
                if (firstRun == true && screen == Screen.Home) Screen.Onboarding else screen

            // v2.5: the parked-only verdict. Gated features block on MOVING only — UNKNOWN
            // fails open, see CarEvents.motion.
            val motion by carEvents.motion.collectAsStateWithLifecycle()
            val parkedOnlyLock =
                settings.motionGateEnabled && motion == CarEvents.Motion.MOVING

            // v2.6: the vendor's current source ("Bluetooth", "USB", …), polled off the main
            // thread — a blocking AIDL read in a composition body once spun a main-thread IPC
            // recomposition loop (see the incident note in RadioSettingsScreen).
            val vendorSource by produceState<String?>(initialValue = null) {
                while (true) {
                    value = withContext(Dispatchers.IO) { carService.getValidModeTitle() }
                    delay(VENDOR_SOURCE_POLL_MS)
                }
            }

            // v2.6: the vendor's own radio presets, raw. Read-only — see RadioScreen for why we
            // never write these back.
            val sysVars by carSettingsController.snapshot.collectAsStateWithLifecycle()
            val vendorPresets = remember(sysVars) {
                (0 until VENDOR_PRESET_SLOTS)
                    .mapNotNull { slot -> sysVars["${SettingKeys.RDO_FAVORITE_PREFIX}$slot"] }
                    .filter { it.isNotBlank() }
            }

            // v2.8: the reachability mirror (LAUNCHER_DESIGN §2.5). Resolved here rather than in
            // HomeScreen because the focus ring lives outside composition and needs it too.
            val driverSide = Reachability.resolve(
                settings.driverSideMode,
                sysVars[SettingKeys.CAR_TYPE],
            )
            SideEffect { launcherFocus.mirrored = driverSide == DriverSide.RIGHT }

            // v2.8: the low-speed maneuvering strips. Hidden in reverse — the vendor owns the
            // screen then, and ReverseOverlay's coexistence rule is that we never contend for it.
            // Hidden too while the decode is unconfirmed: an arc that reads "clear" off a guessed
            // byte offset is a safety claim we have not earned.
            val reverse by carEvents.reverse.collectAsStateWithLifecycle()
            val radar by carEvents.radar.collectAsStateWithLifecycle()
            val speedKmh by carEvents.speedKmh.collectAsStateWithLifecycle()
            val maneuvering = settings.radarLayoutConfirmed &&
                !reverse &&
                speedKmh <= MANEUVER_MAX_KMH &&
                shownScreen != Screen.Onboarding

            // v0.5: republish the palette for the com.reveng.* suite whenever it changes.
            // Keyed on both inputs because a night crossing changes the colours without changing
            // the theme. The write is small and synchronous-to-memory (SharedPreferences.apply),
            // so it stays on the composition's thread rather than racing a coroutine against the
            // frame that shows the new colours.
            LaunchedEffect(activeTheme, night) {
                ThemeSnapshotStore.publish(applicationContext, activeTheme, night)
            }

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
                  // v2.8: the maneuvering strips sit above every screen, so the box wraps the
                  // whole switch rather than living inside Home.
                  Box(modifier = Modifier.fillMaxSize()) {
                    // v1.0: crossfade top-level screen transitions (Home ↔ Themes ↔ Settings ↔
                    // Editor ↔ Onboarding) instead of a hard swap. While firstRun is unresolved
                    // we render nothing but the themed Surface — a fast, jank-free first frame.
                    if (firstRun == null) {
                        // holding frame: just the background Surface
                    } else {
                      // v1.0: the first-run route is already folded into [shownScreen] above, so
                      // a genuine first boot composes straight to Onboarding without the
                      // Crossfade rendering Home for a frame first — the flash the holding frame
                      // is meant to prevent, and which a post-composition LaunchedEffect brought
                      // back when this was tried that way.
                      Crossfade(
                        targetState = shownScreen,
                        animationSpec = tween(durationMillis = 300),
                        label = "screen",
                      ) { s ->
                        when (s) {
                            Screen.Onboarding -> OnboardingScreen(
                                themeStore = themeStore,
                                appRepository = appRepository,
                                favoritesStore = favoritesStore,
                                night = night,
                                onFinish = {
                                    settingsStore.setFirstRunComplete()
                                    screen = Screen.Home
                                },
                            )

                            // v2.5: wrap Home in the launcher's own swipe-from-top shade.
                            Screen.Home -> ShadeOverlay(
                                carService = carService,
                                settingsStore = settingsStore,
                                enabled = settings.shadeEnabled, // v2.5 shade
                            ) {
                                // v0.4.9: the vendor hidden-apps list, read (never written)
                                // out of the SysVar snapshot the settings suite already keeps
                                // live, so a change in the vendor settings screen applies here
                                // without a new observer.
                                val sysVars by carSettingsController.snapshot
                                    .collectAsStateWithLifecycle()
                                val vendorHidden = remember(sysVars) {
                                    parseVendorHidden(sysVars[SysVar.KEY_LAUNCHER_APP_HIDE])
                                }
                                HomeScreen(
                                    carEvents = carEvents,
                                    carService = carService,
                                    appRepository = appRepository,
                                    nowPlaying = nowPlaying,
                                    // The launcher-owned stores, rather than a second eager
                                    // collector per screen on the same DataStore files.
                                    favoritesStore = favoritesStore,
                                    appOrderStore = appOrderStore,
                                    appDirectoryStore = appDirectoryStore,
                                    vendorHidden = vendorHidden, // v0.4.9
                                    onOpenThemes = { screen = Screen.Themes },
                                    // v0.6: wire settings + a Settings-screen entry point.
                                    settingsStore = settingsStore,
                                    onOpenSettings = { screen = Screen.Settings() },
                                    radioPresetsStore = radioPresetsStore, // v0.9 Radio 2.0
                                    // Status-bar power chip deep-links to Power & sleep.
                                    onOpenPowerSettings = {
                                        screen = Screen.Settings(SettingsRoute.Power)
                                    },
                                    // v2.6: the glance cards deep-link into their full screens.
                                    onOpenMedia = { screen = Screen.Media },
                                    // v3.0: cockpit + profiles, two taps from Home.
                                    onOpenDashboard = { screen = Screen.Dashboard },
                                    onOpenProfiles = { screen = Screen.Profiles },
                                    onOpenRadio = { screen = Screen.Radio },
                                    driverSide = driverSide, // v2.8 reachability mirror
                                    // v2.7 shelves
                                    onOpenNotifications = { screen = Screen.Notifications },
                                    onOpenContinueWatching = { screen = Screen.ContinueWatching },
                                    miniScreen = miniScreen, // v4.1 video mini screen
                                )
                            }

                            // v2.6: the full media player (§3.3).
                            Screen.Media -> {
                                val now by nowPlaying.state.collectAsStateWithLifecycle()
                                val sources by nowPlaying.sources.collectAsStateWithLifecycle()
                                MediaScreen(
                                    now = now,
                                    sources = sources,
                                    onPlayPause = nowPlaying::playPause,
                                    onNext = nowPlaying::next,
                                    onPrev = nowPlaying::prev,
                                    onSeek = nowPlaying::seekTo,
                                    onSelectSource = nowPlaying::selectSession,
                                    onBack = { screen = Screen.Home },
                                    vendorSource = vendorSource,
                                )
                            }

                            // v2.6: the full tuner (§3.4).
                            Screen.Radio -> RadioScreen(
                                carService = carService,
                                presetsStore = radioPresetsStore,
                                onBack = { screen = Screen.Home },
                                vendorPresets = vendorPresets,
                            )

                            // v3.0: the cockpit dashboard.
                            Screen.Dashboard -> DashboardScreen(
                                carEvents = carEvents,
                                ignitionSession = ignitionSession, // v0.4.7.1
                                onBack = { screen = Screen.Home },
                            )

                            // v3.0: driver profiles. Applying writes through to the stores that
                            // own each setting, so nothing here becomes a second source of truth.
                            Screen.Profiles -> ProfilesScreen(
                                store = profilesStore,
                                onApply = { profile ->
                                    lifecycleScope.launch {
                                        profilesStore.apply(
                                            profile = profile,
                                            themeStore = themeStore,
                                            favoritesStore = favoritesStore,
                                            appOrderStore = appOrderStore,
                                            settingsStore = settingsStore,
                                        )
                                    }
                                },
                                onCapture = {
                                    lifecycleScope.launch {
                                        profilesStore.captureCurrent(
                                            name = defaultProfileName(),
                                            themeStore = themeStore,
                                            favoritesStore = favoritesStore,
                                            appOrderStore = appOrderStore,
                                            settingsStore = settingsStore,
                                        )
                                    }
                                },
                                onDelete = { profile ->
                                    lifecycleScope.launch { profilesStore.delete(profile.id) }
                                },
                                onRename = { profile, newName ->
                                    // v0.4.2: upsert keys by id, so this replaces the name in place
                                    // and keeps active status and the bundled settings untouched.
                                    lifecycleScope.launch { profilesStore.upsert(profile.copy(name = newName)) }
                                },
                                onBack = { screen = Screen.Home },
                            )

                            // v1.1: full settings suite — categorized, reskinned vendor mirror.
                            is Screen.Settings -> SettingsHost(
                                settingsStore = settingsStore,
                                controller = carSettingsController,
                                carService = carService,
                                carEvents = carEvents,
                                radioPresetsStore = radioPresetsStore,
                                rootTier = rootTierController, // v2.9
                                updater = updateController, // v0.7 auto-updater
                                onExit = { screen = Screen.Home },
                                appDirectoryStore = appDirectoryStore,
                                initialRoute = s.initialRoute,
                            )

                            Screen.Themes -> ThemesScreen(
                                themes = allThemes,
                                activeId = activeTheme.id,
                                night = night,
                                onSetActive = { themeStore.setActive(it.id) },
                                onDuplicate = { themeStore.duplicate(it) },
                                onEdit = { screen = Screen.Editor(it) },
                                onDelete = { themeStore.delete(it.id) },
                                // v2.7: an imported file lands as a new user theme and becomes
                                // active immediately — you imported it to look at it.
                                onImport = {
                                    themeStore.upsert(it)
                                    themeStore.setActive(it.id)
                                },
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

                            // v2.7 §1.4: reading a list of prose off a screen is the textbook
                            // distraction case, so both shelves are parked-only. Gated here, like
                            // the theme editor, so neither screen has to know about motion.
                            Screen.Notifications -> ParkedOnly(
                                feature = "The notification shelf",
                                onBack = { screen = Screen.Home },
                            ) {
                                val items by NotificationRepository.items
                                    .collectAsStateWithLifecycle()
                                val muted by notificationFilter.muted
                                    .collectAsStateWithLifecycle()
                                // A Settings.Secure ContentResolver query — it ran on the
                                // main thread the first time the shelf opened. null = not
                                // resolved yet, read as enabled so the "access is off" warning
                                // never flashes before the answer arrives.
                                val listenerEnabled by produceState<Boolean?>(initialValue = null) {
                                    value = withContext(Dispatchers.IO) {
                                        NotificationRepository.isListenerEnabled(applicationContext)
                                    }
                                }
                                NotificationShelfScreen(
                                    items = items,
                                    muted = muted,
                                    listenerEnabled = listenerEnabled ?: true,
                                    onSetMuted = notificationFilter::setMuted,
                                    onDismiss = NotificationRepository::dismiss,
                                    onOpenApp = {
                                        NotificationRepository.launchSource(this@MainActivity, it)
                                    },
                                    onBack = { screen = Screen.Home },
                                )
                            }

                            Screen.ContinueWatching -> ParkedOnly(
                                feature = "Continue watching",
                                onBack = { screen = Screen.Home },
                            ) {
                                val shelf by continueWatching.shelf.collectAsStateWithLifecycle()
                                // Two PackageManager lookups per candidate package, off the
                                // main thread for the same reason. null is also the screen's
                                // "no client installed" state, which is what it renders for the
                                // frame before the lookups land.
                                val jellyfinLabel by produceState<String?>(initialValue = null) {
                                    value = withContext(Dispatchers.IO) {
                                        continueWatching.jellyfinLabel()
                                    }
                                }
                                ContinueWatchingScreen(
                                    entries = shelf,
                                    jellyfinLabel = jellyfinLabel,
                                    onOpenJellyfin = { continueWatching.openJellyfin() },
                                    onForget = { continueWatching.forget(lifecycleScope, it) },
                                    onBack = { screen = Screen.Home },
                                )
                            }

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

                    // v2.8: proximity rails on the screen edges while creeping forward.
                    if (maneuvering) {
                        RadarSideStrip(state = radar)
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
     * codes surfacing as KeyEvents).
     *
     * v2.8: these now go through [keyPump] like the broadcast keys, which means we consume both
     * edges of any key we recognise and re-emit the paced version ourselves. The system's own
     * auto-repeat (`repeatCount > 0`) is dropped: it fires at the platform's keyboard rate, and
     * the whole point of the pump is that a car's rate is not a keyboard's.
     *
     * A key we don't recognise still falls through untouched, so anything with its own handler
     * (volume, power) is unaffected. BACK is consumed here but handed back to the system in
     * [onNavEvent] when no screen wanted it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val nav = SwcNavigator.fromKeyEvent(event.keyCode) ?: return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keyPump.down(nav)
            // A system-cancelled press (focus loss, gesture takeover) must drop the held key,
            // not fire the deferred short action a normal release would.
            KeyEvent.ACTION_UP ->
                if (event.flags and KeyEvent.FLAG_CANCELED != 0) keyPump.cancel()
                else keyPump.up(nav)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * The pump holds a key across events; a window that loses focus never sees the release, and
     * an unreleased REPEATING key would auto-repeat forever into a screen the driver has left.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::keyPump.isInitialized) {
            keyPump.cancel()
        }
    }

    /**
     * v2.8 — act on one paced press from [keyPump].
     *
     * Feedback fires only on a genuine first press: v2.5 §1.4 wants the driver to feel that a
     * wheel press landed, but confirming every auto-repeat tick would turn holding the tuner into
     * a burst of beeps and buzzes that says nothing.
     */
    private fun onNavEvent(event: NavEvent) {
        val consumed = when (event) {
            is NavEvent.Press -> routeNav(event.key)
            is NavEvent.LongPress -> routeLong(event.key)
        }

        if (consumed) {
            if (event !is NavEvent.Press || !event.repeat) {
                carFeedback.tap()
            }
            return
        }

        // A BACK nothing claimed is the system's — Home, or a screen with nothing left to pop.
        // We consumed the KeyEvent to time it, so we owe the system the press back.
        if (event is NavEvent.Press && event.key == NavKey.BACK) {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    /**
     * v2.8 — long-press secondary actions.
     *
     * BACK is the escape hatch: from anywhere, at any settings depth, one long press is Home.
     * Without it a driver lost four screens deep in the settings suite has to count their way
     * back out. CENTER's secondary action belongs to whatever holds focus, so Home delegates it
     * to [LauncherFocus]; off Home there is no per-item secondary action to delegate to, and
     * inventing one that varies by screen would be worse than the key doing nothing.
     */
    private fun routeLong(nav: NavKey): Boolean = when (nav) {
        NavKey.BACK -> {
            screenState.value = Screen.Home
            launcherFocus.reset()
            true
        }
        NavKey.CENTER -> screenState.value == Screen.Home && launcherFocus.onLongPress()
        else -> false
    }

    private fun routeNav(nav: NavKey): Boolean = when (nav) {
        NavKey.MEDIA_NEXT -> { nowPlaying.next(); true }
        NavKey.MEDIA_PREV -> { nowPlaying.prev(); true }
        NavKey.MEDIA_PLAY_PAUSE -> { nowPlaying.playPause(); true }
        // v2.6: the wheel's source keys open the full screens. A second press of MEDIA while
        // already there toggles playback, so the transport that key used to give is still one
        // press away rather than lost.
        NavKey.OPEN_MEDIA -> {
            if (screenState.value == Screen.Media) {
                nowPlaying.playPause()
            } else {
                screenState.value = Screen.Media
            }
            true
        }
        NavKey.OPEN_RADIO -> { screenState.value = Screen.Radio; true }
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
            is Screen.Settings -> { onBackPressedDispatcher.onBackPressed(); true }
            // Themes / Editor / Media / Radio are flat top-level screens -> straight Home.
            else -> { screenState.value = Screen.Home; true }
        }
        // v2.8: Home keeps its hand-written ring; every other screen is driven through
        // [KeyBridge], which moves Compose's own focus and wraps at the edges.
        NavKey.CENTER, NavKey.UP, NavKey.DOWN, NavKey.LEFT, NavKey.RIGHT -> when {
            screenState.value == Screen.Home -> launcherFocus.onKey(nav)
            nav == NavKey.CENTER -> keyBridge.activate()
            else -> keyBridge.move(nav)
        }
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
        // v4.1: dismissing the video mini screen injects HOME (that is what minimizes a
        // freeform task); that press echoes back here as a HOME intent. It is ours, not the
        // driver's — keep whatever screen they were navigating to.
        if (intent.hasCategory(Intent.CATEGORY_HOME) && miniScreen.consumeHomeInjection()) {
            return
        }
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
        keyPump.cancel() // v2.8: drop any held key and its repeat timer
        gatewayHandshake.unregister() // v3.0
        carEvents.unregister()
        carService.unbind()
        nowPlaying.stop()
        speechController.shutdown() // v0.4.2 TTS
        carSettingsController.release() // v1.1
        themeStore.release()
        settingsStore.release()
    }

    /**
     * v3.0 — the name a freshly captured profile gets.
     *
     * Generated rather than typed: renaming wants a text field, and the themed in-app keyboard
     * that replaces the unthemeable vendor IME lands in v2.7 on a sibling branch. Capturing the
     * setup is the valuable half and works today; naming it properly follows once that keyboard
     * is available on this branch.
     */
    private fun defaultProfileName(): String = "Driver ${profilesStore.profiles.value.size + 1}"

    /** Top-level screens — a simple switch, no nav library (LAUNCHER_DESIGN v0.5). */
    private sealed interface Screen {
        data object Onboarding : Screen // v1.0 first-run flow
        data object Home : Screen
        data object Themes : Screen
        // v0.6; the optional route deep-links into a settings page (status-bar power chip).
        data class Settings(val initialRoute: SettingsRoute? = null) : Screen
        data object Media : Screen // v2.6 full media player (§3.3)
        data object Radio : Screen // v2.6 full tuner (§3.4)
        data object Dashboard : Screen // v3.0 cockpit
        data object Profiles : Screen // v3.0 driver profiles
        data object Notifications : Screen // v2.7
        data object ContinueWatching : Screen // v2.7
        data class Editor(val theme: CarTheme) : Screen
    }
}

/** v2.6 — the vendor source changes only when the driver changes it; polling it is a courtesy. */
private const val VENDOR_SOURCE_POLL_MS = 5_000L

/** The vendor stores exactly six radio favourites: `Rdo_MyFavorite0..5` (CAR_API §2.3). */
private const val VENDOR_PRESET_SLOTS = 6

/**
 * v2.8 — above this the maneuvering strips are noise, not information: the sensors stop reporting
 * anyway and nothing within their range is still a parking obstacle. Walking pace with margin.
 * An unknown speed ([com.reveng.carlauncher.carlib.GpsSpeedSource.SPEED_UNKNOWN], negative) passes,
 * which is deliberate — the multi-storey car park where GPS has no fix is exactly where this helps.
 */
private const val MANEUVER_MAX_KMH = 15
