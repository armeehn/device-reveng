package com.ripostelabs.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope // v2.8
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.ripostelabs.carlauncher.ui.theme.carShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect // v4.1
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ripostelabs.carlauncher.AppInfo
import com.ripostelabs.carlauncher.AppRepository
import com.ripostelabs.carlauncher.R
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.data.AppDirectoryStore // v0.4.2 custom app directory
import com.ripostelabs.carlauncher.data.DriverSide // v2.8
import com.ripostelabs.carlauncher.data.LauncherSettings // v0.6
import com.ripostelabs.carlauncher.data.OemApps
import com.ripostelabs.carlauncher.data.Placement // v0.4.2
import com.ripostelabs.carlauncher.data.effectivePlacement // v0.4.2
import com.ripostelabs.carlauncher.data.SettingsStore // v0.6
import com.ripostelabs.carlauncher.input.FocusTarget // v0.8 SWC navigation
import com.ripostelabs.carlauncher.input.LauncherFocus // v0.8
import com.ripostelabs.carlauncher.input.LocalLauncherFocus // v0.8
import com.ripostelabs.carlauncher.input.launcherFocusTarget // v0.8
import com.ripostelabs.carlauncher.media.JellyfinApp // v2.7
import com.ripostelabs.carlauncher.media.MiniScreenController // v4.1
import com.ripostelabs.carlauncher.media.MiniScreenState // v4.1
import com.ripostelabs.carlauncher.media.NowPlayingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home screen for the 1920x720 (~1280x480 dp) landscape head unit — the fixed
 * three-column layout from LAUNCHER_DESIGN §2, which never reflows:
 *
 *   [ StatusBar — full width ]
 *   ┌ LEFT (glance) ┬ CENTER (app grid) ┬ RIGHT (driver thumb) ┐
 *   │ MediaCard     │ AppDrawer 2×N     │ QuickLaunch 3×2 icons │
 *   │ ClimateReadout│ (system folder)   │ RadioCard             │
 *   └───────────────┴───────────────────┴───────────────────────┘
 *
 * The [ReverseOverlay] sits above everything and is toggled by the reverse state from
 * [CarEvents] (CAR_API §1.3), unchanged from v0.2.
 */
@Composable
fun HomeScreen(
    carEvents: CarEvents,
    carService: CarService,
    appRepository: AppRepository,
    nowPlaying: NowPlayingRepository,
    onOpenThemes: () -> Unit = {},
    // v0.6: launcher settings (grid density + widget visibility) + Settings nav.
    settingsStore: SettingsStore? = null,
    onOpenSettings: () -> Unit = {},
    radioPresetsStore: com.ripostelabs.carlauncher.data.RadioPresetsStore? = null, // v0.9 Radio 2.0
    onOpenPowerSettings: () -> Unit = {},
    // v2.6: the glance cards deep-link into their full screens (§3.3, §3.4).
    onOpenMedia: () -> Unit = {},
    onOpenRadio: () -> Unit = {},
    // v3.0: cockpit dashboard + driver profiles, reached from the status bar.
    onOpenDashboard: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    // v2.8: reachability mirror (LAUNCHER_DESIGN §2.5). LHD is the default everywhere.
    driverSide: DriverSide = DriverSide.LEFT,
    onOpenNotifications: (() -> Unit)? = null, // v2.7
    onOpenContinueWatching: (() -> Unit)? = null, // v2.7
    miniScreen: MiniScreenController? = null, // v4.1 video mini screen (null keeps previews)
    // The launcher-owned DataStore-backed stores, passed straight through to the drawer. Null
    // falls back to local instances (previews); see AppDrawer for why duplicates cost reads.
    favoritesStore: com.ripostelabs.carlauncher.data.FavoritesStore? = null,
    appOrderStore: com.ripostelabs.carlauncher.data.AppOrderStore? = null,
    appDirectoryStore: AppDirectoryStore? = null,
    // v0.4.9: packages the VENDOR settings hide (SysVar SYS_LAUNCHER_APP_HIDE_KEY, read-only).
    vendorHidden: Set<String> = emptySet(),
) {
    val reverse by carEvents.reverse.collectAsStateSafe(initial = false)
    val media by nowPlaying.state.collectAsStateSafe(initial = null)
    val radar by carEvents.radar.collectAsStateSafe(initial = null) // v0.7 parking sensors
    // v0.6: observe launcher settings (null store -> defaults, keeps previews working).
    val settings by (settingsStore?.settings?.collectAsStateSafe(initial = LauncherSettings())
        ?: remember { mutableStateOf(LauncherSettings()) })

    // v0.4.7 — the radar byte decode is GUESSED; same gate as MainActivity's maneuvering strips:
    // until the layout is confirmed, no Home or reverse surface renders a radar claim.
    val shownRadar = if (settings.radarLayoutConfirmed) radar else null

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    // Reload when the OEM-shadow toggles change, so an un-shadowed app reappears at once.
    val shadowPolicy = remember(settings) { OemApps.ShadowPolicy.from(settings) }
    LaunchedEffect(shadowPolicy) {
        apps = withContext(Dispatchers.IO) { appRepository.loadApps(shadowPolicy) }
    }

    // v0.4.2 custom app directory: the user's per-app placement overrides the built-in
    // user/system classification — pull a misclassified app to Home, tuck one into System, or
    // hide it entirely. Absent override falls back to AppInfo.isSystem.
    val appContext = LocalContext.current.applicationContext
    val dirScope = rememberCoroutineScope()
    val directoryStore = appDirectoryStore ?: remember { AppDirectoryStore(appContext, dirScope) }
    val placements by directoryStore.placements.collectAsStateSafe(initial = emptyMap())
    // One pass: resolve each app's effective placement once and group. HIDDEN apps land in neither
    // list, so they simply fall out. v0.4.9: vendor-hidden packages are unioned in as HIDDEN.
    val byPlacement = remember(apps, placements, vendorHidden) {
        apps.groupBy { it.effectivePlacement(placements, vendorHidden) }
    }
    val userApps = byPlacement[Placement.HOME].orEmpty()
    val systemApps = byPlacement[Placement.SYSTEM].orEmpty()

    // v0.8: the roving focus ring shared with the SWC / DPAD key dispatcher (MainActivity).
    val focus = LocalLauncherFocus.current
    // v0.4.7: pinned quick-launch slots, resolved directly through the repository so Claude
    // still shows up even though loadApps hides it from the drawer. The remaining grid slots
    // fill from the Home apps with the Jellyfin client hoisted first (v2.7).
    var quickPins by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        quickPins = withContext(Dispatchers.IO) {
            QUICK_LAUNCH_PINNED.mapNotNull(appRepository::resolveApp)
        }
    }
    val quickApps = remember(quickPins, userApps) {
        val pinned = quickPins.mapTo(HashSet()) { it.packageName }
        val fill = JellyfinApp.pinFirst(userApps) { it.packageName }
            .filterNot { it.packageName in pinned }
        (quickPins + fill).take(QUICK_LAUNCH_SLOTS)
    }
    SideEffect {
        // Keep the focus model's view of the layout in sync so navigation skips hidden regions.
        focus.showMedia = settings.showMedia
        focus.showClimate = settings.showClimate
        focus.showRadio = settings.showRadio
        focus.showNav = settings.showNav
        focus.quickCount = quickApps.size
        // CENTER activation for the focused region (grid tiles launch via GridFocus).
        focus.onActivate = { target ->
            when (target) {
                // v2.6: CENTER on a card opens its full screen, matching the touch deep-link.
                // Play/pause is still one press away there, and stays on the wheel's own keys.
                is FocusTarget.Media -> onOpenMedia()
                is FocusTarget.Radio -> onOpenRadio()
                is FocusTarget.Grid -> focus.grid.launch(target.index)
                is FocusTarget.Quick -> quickApps.getOrNull(target.index)?.let(appRepository::launch)
                else -> {} // Climate / Nav are glanceable, no primary action
            }
        }
        // v2.8: long CENTER. Each case mirrors the touch long-press that already exists, so the
        // wheel gains no gesture the screen doesn't have. Media has no touch long-press, and
        // play/pause is the one thing a driver wants from the card without opening it.
        focus.onSecondary = { target ->
            when (target) {
                is FocusTarget.Grid -> { focus.grid.longPress(target.index); true }
                is FocusTarget.Media -> { nowPlaying.playPause(); true }
                else -> false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(
                carEvents = carEvents,
                onOpenThemes = onOpenThemes,
                // v0.6: pass Settings nav + services so the status bar can host the gear
                // and the Quick Controls pull-down.
                onOpenSettings = onOpenSettings,
                carService = carService,
                settingsStore = settingsStore,
                onOpenPowerSettings = onOpenPowerSettings,
                onOpenDashboard = onOpenDashboard, // v3.0
                onOpenProfiles = onOpenProfiles, // v3.0
                onOpenNotifications = onOpenNotifications, // v2.7
                onOpenContinueWatching = onOpenContinueWatching, // v2.7
            )

            // v2.8: the two side columns are declared once and *ordered* by the reachability
            // mirror (LAUNCHER_DESIGN §2.5). Declaring them as composable lambdas rather than
            // duplicating the Row for each orientation keeps one definition of each column, so a
            // change to the media card can't silently apply to only one kind of car.
            val glanceColumn: @Composable RowScope.() -> Unit = {
                // ---- media over climate (glance zone) --------------------------
                Column(
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight()
                        .focusGroup(),
                ) {
                    // v0.6: media/climate cards are individually toggleable in Settings.
                    // v0.8: wrap card call-sites in a focus-ring highlight (cards untouched).
                    if (settings.showMedia) {
                        // v4.1: while a video session is on screen (and the car is parked), the
                        // media card's slot hosts the video mini screen instead — a freeform
                        // window positioned over the card by MiniScreenController.
                        val parkedLock = LocalParkedOnlyLock.current
                        val miniState by (miniScreen?.state?.collectAsStateSafe(
                            initial = MiniScreenState.Hidden,
                        ) ?: remember { mutableStateOf(MiniScreenState.Hidden) })
                        val userClosed by (miniScreen?.userClosed?.collectAsStateSafe(initial = null)
                            ?: remember { mutableStateOf<String?>(null) })
                        val videoNow = media?.takeIf { it.isVideo && it.sourcePackage != null }
                        val miniWanted = settings.videoMiniScreen &&
                            miniScreen != null &&
                            videoNow != null &&
                            !parkedLock &&
                            userClosed != videoNow.sourcePackage
                        var miniBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }

                        // Launch once bounds are known; re-runs on session change. show() is
                        // idempotent per package, so layout-settling re-reports are harmless.
                        LaunchedEffect(miniWanted, videoNow?.sourcePackage, miniBounds) {
                            val b = miniBounds
                            val pkg = videoNow?.sourcePackage
                            if (miniWanted && pkg != null && b != null) miniScreen?.show(pkg, b)
                        }
                        // Conditions dropped (moving, video ended, toggle off) -> take it down.
                        LaunchedEffect(miniWanted, miniState) {
                            if (!miniWanted && miniState is MiniScreenState.Active) {
                                miniScreen?.dismiss()
                            }
                        }
                        // Session moved on or stopped -> a Close from the driver stops binding.
                        LaunchedEffect(videoNow?.sourcePackage) {
                            if (videoNow == null) miniScreen?.clearUserClosed()
                        }
                        // Leaving Home (sub-screen, widget toggled off) always removes the window.
                        DisposableEffect(Unit) {
                            onDispose { miniScreen?.dismiss() }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .launcherFocusTarget(focus, FocusTarget.Media)
                                // v2.6: tapping the card body opens the full player. The card's
                                // own transport buttons consume their taps first, so this only
                                // catches the art / title area.
                                .clickable(onClick = withTapFeedback(onOpenMedia)), // v2.5
                        ) {
                            if (miniWanted && videoNow != null) {
                                VideoMiniCard(
                                    now = videoNow,
                                    state = miniState,
                                    onSlotPositioned = { miniBounds = it },
                                    onExpand = { miniScreen?.expand() },
                                    onClose = { miniScreen?.dismiss(userClosed = true) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                MediaCard(
                                    now = media,
                                    onPlayPause = nowPlaying::playPause,
                                    onNext = nowPlaying::next,
                                    onPrev = nowPlaying::prev,
                                    onSeek = nowPlaying::seekTo,
                                    onCycleSource = nowPlaying::cycleSession,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    if (settings.showClimate) {
                        if (settings.showMedia) Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .launcherFocusTarget(focus, FocusTarget.Climate),
                        ) {
                            ClimateReadout(
                                carEvents = carEvents,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // ---- CENTER: the app-drawer grid (widest). Symmetric, so it never moves. -------
            val centerColumn: @Composable RowScope.() -> Unit = {
                Column(
                    modifier = Modifier
                        .weight(0.40f)
                        .fillMaxHeight()
                        .focusGroup(),
                ) {
                    // v0.7: navigation tile (turn-by-turn/ETA + parking sensors) at top of center.
                    // Honors the Settings > Launcher > Home-widgets "Navigation" toggle, like the
                    // media/climate/radio widgets do.
                    if (settings.showNav) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .launcherFocusTarget(focus, FocusTarget.Nav),
                        ) {
                            NavCard(
                                carEvents = carEvents,
                                radar = shownRadar,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        text = stringResource(R.string.app_drawer_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                    )
                    AppDrawer(
                        apps = userApps,
                        systemApps = systemApps,
                        onLaunch = appRepository::launch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f), // v0.7: take remaining height below NavCard
                        columns = settings.gridColumns, // v0.6: grid density from Settings
                        gridFocus = focus.grid, // v0.8: drive/highlight tile focus
                        favoritesStore = favoritesStore,
                        appOrderStore = appOrderStore,
                    )
                }
            }

            // ---- quick-launch column + radio (driver thumb zone) --------------------------
            val thumbColumn: @Composable RowScope.() -> Unit = {
                Column(
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight()
                        .focusGroup(),
                ) {
                    QuickLaunchGrid(
                        apps = quickApps,
                        onLaunch = appRepository::launch,
                        focus = focus, // v0.8
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    // v0.6: radio card is toggleable in Settings.
                    if (settings.showRadio) {
                        Spacer(Modifier.height(16.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .launcherFocusTarget(focus, FocusTarget.Radio)
                                .clickable(onClick = withTapFeedback(onOpenRadio)), // v2.6, v2.5
                        ) {
                            RadioCard(
                                carService = carService,
                                presetsStore = radioPresetsStore,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusGroup(), // v0.8: one focus group for the whole Home layout
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // v2.8: RHD puts the thumb column on the left so it stays under the driver's hand.
                if (driverSide == DriverSide.RIGHT) {
                    thumbColumn()
                    centerColumn()
                    glanceColumn()
                } else {
                    glanceColumn()
                    centerColumn()
                    thumbColumn()
                }
            }
        }

        // v0.9: minimal, transparent reverse overlay that COEXISTS with the vendor reverse
        // window (radar bars + optional static guide lines are now owned by ReverseOverlay).
        ReverseOverlay(
            visible = reverse,
            // shownRadar, not radar: the decode is GUESSED, so the bars stay hidden until a
            // capture confirms the byte layout. Never widen this back to the raw flow.
            radar = shownRadar,
            // the guide-lines choice is persisted, not per-reversal.
            guideLines = settings.reverseGuideLines,
            onToggleGuideLines = { on -> settingsStore?.setReverseGuideLines(on) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Quick-launch pins, in slot order, always offered ahead of the Home-apps fill. CarPlay comes
 * first — it must stay one thumb-tap away, and alphabetical luck used to decide whether it
 * appeared at all. Claude's only surface is this grid (AppRepository hides it from the drawer).
 */
private val QUICK_LAUNCH_PINNED = listOf(
    "com.zjinnova.zlink",   // CarPlay (the Zlink receiver keeps one protocol alias enabled)
    "com.ripostelabs.claudecar", // Claude
    "org.linphone",         // VoIP dialer
)

/** The quick-launch grid is a fixed 3×2: six tiles sharing the space above the RadioCard. */
private const val QUICK_LAUNCH_SLOTS = 6
private const val QUICK_LAUNCH_COLUMNS = 3

/**
 * A compact 3×2 icon-only quick-launch grid (LAUNCHER_DESIGN §2.4) in the closest-reach
 * column. The rows split the available height evenly, so all six tiles always fit with no
 * scrolling whether or not the RadioCard below is enabled. Tiles show just the app icon —
 * labels never fit three-across, and these six are the apps the driver knows by glyph.
 *
 * SWC focus order is row-major: the existing linear Quick ring in SwcNavigator steps through
 * tiles in reading order, so every tile stays reachable with no navigator changes.
 */
@Composable
private fun QuickLaunchGrid(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    focus: LauncherFocus? = null, // v0.8: draw the focus ring on the focused tile
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Quick launch",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            apps.chunked(QUICK_LAUNCH_COLUMNS).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { colIndex, app ->
                        val index = rowIndex * QUICK_LAUNCH_COLUMNS + colIndex
                        val tileModifier = if (focus != null) {
                            Modifier
                                .weight(1f)
                                .launcherFocusTarget(focus, FocusTarget.Quick(index), cornerRadiusDp = 15)
                        } else {
                            Modifier.weight(1f)
                        }
                        QuickLaunchTile(app = app, onClick = { onLaunch(app) }, modifier = tileModifier)
                    }
                    // Pad a short last row so its tiles keep the same width as full rows.
                    repeat(QUICK_LAUNCH_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Icon-only quick-launch tile. [AppIcon] carries the app label as its contentDescription,
 * so TalkBack/SWC announcements are unchanged from the old icon+label row. The icon scales
 * with the tile (RadioCard off = taller rows = bigger icons), capped so PackageManager
 * bitmaps rasterized at 144px don't blur.
 */
@Composable
private fun QuickLaunchTile(app: AppInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val press = withTapFeedback(onClick) // v2.5
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .clip(carShape(15.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = press),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = (min(maxWidth, maxHeight) * 0.62f).coerceIn(48.dp, 88.dp)
        AppIcon(app = app, size = iconSize)
    }
}
