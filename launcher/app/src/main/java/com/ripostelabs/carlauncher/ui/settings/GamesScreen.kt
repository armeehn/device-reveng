package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ripostelabs.carlauncher.carlib.GameApp
import com.ripostelabs.carlauncher.carlib.GameApps
import com.ripostelabs.carlauncher.carlib.Rom
import com.ripostelabs.carlauncher.data.GamesRepository
import com.ripostelabs.carlauncher.ui.ParkedOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Emulator frontends, launched from the head unit, and the ROM library that plays through them.
 *
 * Wrapped in [ParkedOnly]. Games are the clearest case the launcher's own design rules already
 * cover: a screen a driver must watch continuously, with no reason to be usable in motion. The
 * gate fails open when speed is unknown, which is a deliberate property of that gate and not
 * something this screen re-decides.
 *
 * Which frontends exist and what they are called is [com.ripostelabs.carlauncher.carlib.GameApps],
 * so the interesting rule — exact package matching, because RetroArch's ABI builds differ by a
 * suffix — is unit-tested rather than eyeballed here. Likewise which files are ROMs and which core
 * runs each is [com.ripostelabs.carlauncher.carlib.GameLibrary].
 */
@Composable
fun GamesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val games = remember { GamesRepository.installed(context) }
    val frontend = remember(games) { GameApps.libretroFrontend(games) }

    // File access + the ROM list, re-read on resume (after the grant screen, or a fresh push of
    // ROMs). The directory walk is disk I/O, so it runs off main as DisplaySettingsScreen does
    // for its permission probe. null = not resolved yet, so the section says nothing wrong.
    var fileAccess by remember { mutableStateOf<Boolean?>(null) }
    var library by remember { mutableStateOf<List<Rom>>(emptyList()) }
    var probe by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                probe++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(probe) {
        val granted = GamesRepository.hasFileAccess()
        val roms = if (granted) withContext(Dispatchers.IO) { GamesRepository.library() } else emptyList()
        fileAccess = granted
        library = roms
    }

    SettingsScaffold(
        title = "Games",
        subtitle = "Emulator frontends installed on this unit",
        onBack = onBack,
    ) {
        ParkedOnly(feature = "Games") {
            if (games.isEmpty()) {
                SettingsSection(title = "Nothing installed") {
                    Text(
                        text = "No emulator frontend was found. RetroArch ships separate packages " +
                            "per ABI and they are not interchangeable, so install the build that " +
                            "matches this unit rather than the first result.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@ParkedOnly
            }

            SettingsSection(title = "Installed") {
                games.forEach { game ->
                    ActionRow(
                        label = game.label,
                        description = game.packageName,
                        onClick = { GamesRepository.launch(context, game.packageName) },
                    )
                }
            }

            // Without RetroArch nothing can take a ROM on its launch intent, so no Library section.
            if (frontend == null) {
                return@ParkedOnly
            }

            LibrarySection(frontend = frontend, fileAccess = fileAccess, library = library)
        }
    }
}

@Composable
private fun LibrarySection(frontend: GameApp, fileAccess: Boolean?, library: List<Rom>) {
    val context = LocalContext.current

    SettingsSection(title = "Library") {
        when {
            fileAccess == null -> Unit

            !fileAccess -> ActionRow(
                label = "Allow file access",
                description = "Android hides the ROM folder until the launcher holds " +
                    "\"All files access\". Granted once; opens the system page.",
                onClick = { GamesRepository.requestFileAccess(context) },
            )

            library.isEmpty() -> Text(
                text = "No ROMs found. Copy them to ${GamesRepository.ROMS_DIR.path}/<system>/ " +
                    "(nes, snes, gb, gbc, gba, genesis).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> library.forEach { rom ->
                ActionRow(
                    label = rom.title,
                    description = rom.system.label,
                    onClick = { GamesRepository.play(context, frontend, rom) },
                )
            }
        }
    }
}
