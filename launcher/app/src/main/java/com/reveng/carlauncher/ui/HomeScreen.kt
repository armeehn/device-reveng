package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.reveng.carlauncher.data.LauncherSettings // v0.6
import com.reveng.carlauncher.data.SettingsStore // v0.6
import com.reveng.carlauncher.input.FocusTarget // v0.8 SWC navigation
import com.reveng.carlauncher.input.LauncherFocus // v0.8
import com.reveng.carlauncher.input.LocalLauncherFocus // v0.8
import com.reveng.carlauncher.input.launcherFocusTarget // v0.8
import com.reveng.carlauncher.media.JellyfinApp // v2.7
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
    onOpenNotifications: (() -> Unit)? = null, // v2.7
    onOpenContinueWatching: (() -> Unit)? = null, // v2.7
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
    val userApps = apps.filter { !it.isSystem }
    val systemApps = apps.filter { it.isSystem }

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
                is FocusTarget.Media -> nowPlaying.playPause()
                is FocusTarget.Grid -> focus.grid.launch(target.index)
                is FocusTarget.Quick -> quickApps.getOrNull(target.index)?.let(appRepository::launch)
                else -> {} // Climate / Nav / Radio are glanceable, no primary action
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
                onOpenNotifications = onOpenNotifications, // v2.7
                onOpenContinueWatching = onOpenContinueWatching, // v2.7
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusGroup(), // v0.8: one focus group for the whole Home layout
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ---- LEFT: media over climate (glance zone) --------------------
                Column(
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight()
                        .focusGroup(),
                ) {
                    // v0.6: media/climate cards are individually toggleable in Settings.
                    // v0.8: wrap card call-sites in a focus-ring highlight (cards untouched).
                    if (settings.showMedia) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .launcherFocusTarget(focus, FocusTarget.Media),
                        ) {
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

                // ---- CENTER: the app-drawer grid (widest) ----------------------
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
                    )
                }

                // ---- RIGHT: quick-launch column + radio (driver thumb zone) -----
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
                                .launcherFocusTarget(focus, FocusTarget.Radio),
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(carShape(15.dp))
            .clickable(onClick = onClick)
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
