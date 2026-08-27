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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ---- LEFT: media over climate (glance zone) --------------------
                Column(
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight(),
                ) {
                    // v0.6: media/climate cards are individually toggleable in Settings.
                    if (settings.showMedia) {
                        MediaCard(
                            now = media,
                            onPlayPause = nowPlaying::playPause,
                            onNext = nowPlaying::next,
                            onPrev = nowPlaying::prev,
                            onSeek = nowPlaying::seekTo,          // v0.9 Media 2.0
                            onCycleSource = nowPlaying::cycleSession, // v0.9 Media 2.0
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                    if (settings.showClimate) {
                        if (settings.showMedia) Spacer(Modifier.height(16.dp))
                        ClimateReadout(
                            carService = carService,
                            carEvents = carEvents,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp),
                        )
                    }
                }

                // ---- CENTER: the app-drawer grid (widest) ----------------------
                Column(
                    modifier = Modifier
                        .weight(0.40f)
                        .fillMaxHeight(),
                ) {
                    // v0.7: navigation tile (turn-by-turn/ETA + parking sensors) at top of center.
                    NavCard(
                        carEvents = carEvents,
                        radar = radar,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                    )
                    Spacer(Modifier.height(16.dp))
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
                    )
                }

                // ---- RIGHT: quick-launch column + radio (driver thumb zone) -----
                Column(
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxHeight(),
                ) {
                    QuickLaunchColumn(
                        apps = userApps.take(4),
                        onLaunch = appRepository::launch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    // v0.6: radio card is toggleable in Settings.
                    if (settings.showRadio) {
                        Spacer(Modifier.height(16.dp))
                        RadioCard(
                            carService = carService,
                            presetsStore = radioPresetsStore, // v0.9 Radio 2.0
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                }
            }
        }

        // v0.9: minimal, transparent reverse overlay that COEXISTS with the vendor reverse
        // window (radar bars + optional static guide lines are now owned by ReverseOverlay).
        ReverseOverlay(visible = reverse, radar = radar, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A compact quick-launch column of the driver's most-used apps (LAUNCHER_DESIGN §2.4).
 * Row-per-app icon + label with large tap targets; lives in the closest-reach column.
 */
@Composable
private fun QuickLaunchColumn(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
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
        apps.forEach { app ->
            QuickLaunchRow(app = app, onClick = { onLaunch(app) })
        }
    }
}

@Composable
private fun QuickLaunchRow(app: AppInfo, onClick: () -> Unit) {
    val bmp = remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 108, height = 108).asImageBitmap()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
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
