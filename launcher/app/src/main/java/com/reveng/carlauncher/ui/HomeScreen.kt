package com.reveng.carlauncher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.AppRepository
import com.reveng.carlauncher.R
import com.reveng.carlauncher.carlib.CarEvents
import com.reveng.carlauncher.media.NowPlayingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home screen for the 1920x720 landscape head unit.
 *
 * Layout: a full-width [StatusBar] on top; below it a two-pane row — the app-drawer grid
 * on the left (~62%) and a column of car widgets (media card, room for climate/radio) on
 * the right. The [ReverseOverlay] sits above everything and is toggled by the reverse
 * state from [CarEvents] (CAR_API §1.3).
 */
@Composable
fun HomeScreen(
    carEvents: CarEvents,
    appRepository: AppRepository,
    nowPlaying: NowPlayingRepository,
) {
    val context = LocalContext.current
    val reverse by carEvents.reverse.collectAsStateSafe(initial = false)
    val media by nowPlaying.state.collectAsStateSafe(initial = null)

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appRepository.loadApps() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(carEvents = carEvents)

            Row(modifier = Modifier.fillMaxSize()) {
                // Left: app drawer.
                Column(
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight(),
                ) {
                    Text(
                        text = stringResource(R.string.app_drawer_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
                    )
                    AppDrawer(
                        apps = apps.filter { !it.isSystem },
                        systemApps = apps.filter { it.isSystem },
                        onLaunch = appRepository::launch,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Right: car widgets column.
                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                        .padding(end = 24.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    MediaCard(
                        now = media,
                        onPlayPause = nowPlaying::playPause,
                        onNext = nowPlaying::next,
                        onPrev = nowPlaying::prev,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    // TODO: climate card (CarAirState / getAirData) and radio card
                    // (ZXW_RADIO_INFO_EVT / getRadioFreq) go here — CAR_API §6.3.
                }
            }
        }

        // Full-screen reverse camera overlay, above the home content.
        ReverseOverlay(visible = reverse, modifier = Modifier.fillMaxSize())
    }
}
