package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.reveng.carlauncher.ui.theme.carShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.AppRepository
import com.reveng.carlauncher.R
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.carlib.CarService
import com.reveng.carlauncher.data.AppDirectoryStore // v0.4.2 custom app directory
import com.reveng.carlauncher.data.DriverSide // v2.8
import com.reveng.carlauncher.data.LauncherSettings // v0.6
import com.reveng.carlauncher.data.Placement // v0.4.2
import com.reveng.carlauncher.data.effectivePlacement // v0.4.2
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.input.FocusTarget // v0.8 SWC navigation
import com.reveng.carlauncher.input.LauncherFocus // v0.8
import com.reveng.carlauncher.input.LocalLauncherFocus // v0.8
import com.reveng.carlauncher.input.launcherFocusTarget // v0.8
import com.reveng.carlauncher.media.JellyfinApp // v2.7
import com.reveng.carlauncher.media.MiniScreenController // v4.1
import com.reveng.carlauncher.media.MiniScreenState // v4.1
import com.reveng.carlauncher.media.NowPlayingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home screen for the 1920x720 (~1280x480 dp) landscape head unit — the fixed
 * three-column layout from LAUNCHER_DESIGN §2, which never reflows:
 *
 *   [ StatusBar — full width ]
 *   ┌ LEFT (glance) ┬ CENTER (app grid) ┬ RIGHT (driver thumb) ┐
 *   │ MediaCard     │ AppDrawer 2×N     │ QuickLaunch column    │
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
    radioPresetsStore: com.reveng.carlauncher.data.RadioPresetsStore? = null, // v0.9 Radio 2.0
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
    favoritesStore: com.reveng.carlauncher.data.FavoritesStore? = null,
    appOrderStore: com.reveng.carlauncher.data.AppOrderStore? = null,
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

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appRepository.loadApps() }
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
    // v2.7: the Jellyfin quick-launch preset. Ordering only — the tile was already in the drawer,
    // this just puts it in the driver's thumb column instead of alphabetically wherever it fell.
    val quickApps = JellyfinApp.pinFirst(userApps) { it.packageName }.take(QUICK_LAUNCH_SLOTS)
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
                                carService = carService,
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
                                radar = radar,
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
                    QuickLaunchColumn(
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
        ReverseOverlay(visible = reverse, radar = radar, modifier = Modifier.fillMaxSize())
    }
}

/** How many tiles the right-hand thumb column holds before the RadioCard claims the rest. */
private const val QUICK_LAUNCH_SLOTS = 4

/**
 * A compact quick-launch column of the driver's most-used apps (LAUNCHER_DESIGN §2.4).
 * Row-per-app icon + label with large tap targets; lives in the closest-reach column.
 *
 * The rows live in a [LazyColumn]: when the RadioCard below squeezes the column, rows that
 * don't fit are scrollable instead of clipped. SWC focus moves keep the focused row visible
 * by scrolling to it.
 */
@Composable
private fun QuickLaunchColumn(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    focus: LauncherFocus? = null, // v0.8: draw the focus ring on the focused row
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
        val listState = rememberLazyListState()
        val focusedQuick = (focus?.current as? FocusTarget.Quick)?.index
        LaunchedEffect(focusedQuick) {
            if (focusedQuick != null && focusedQuick in apps.indices) {
                listState.animateScrollToItem(focusedQuick)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(
                apps,
                key = { _, app -> app.packageName + "/" + app.activityName },
            ) { index, app ->
                val rowModifier = if (focus != null) {
                    Modifier.launcherFocusTarget(focus, FocusTarget.Quick(index), cornerRadiusDp = 15)
                } else {
                    Modifier
                }
                QuickLaunchRow(app = app, onClick = { onLaunch(app) }, modifier = rowModifier)
            }
        }
    }
}

@Composable
private fun QuickLaunchRow(app: AppInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bmp = remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 108, height = 108).asImageBitmap()
    }
    val press = withTapFeedback(onClick) // v2.5
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(carShape(15.dp))
            .clickable(onClick = press)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = bmp,
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
    }
}
