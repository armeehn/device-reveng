package com.ripostelabs.carlauncher.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ripostelabs.carlauncher.data.GamesRepository
import com.ripostelabs.carlauncher.ui.ParkedOnly

/**
 * Emulator frontends, launched from the head unit.
 *
 * Wrapped in [ParkedOnly]. Games are the clearest case the launcher's own design rules already
 * cover: a screen a driver must watch continuously, with no reason to be usable in motion. The
 * gate fails open when speed is unknown, which is a deliberate property of that gate and not
 * something this screen re-decides.
 *
 * Which frontends exist and what they are called is [com.ripostelabs.carlauncher.carlib.GameApps],
 * so the interesting rule — exact package matching, because RetroArch's ABI builds differ by a
 * suffix — is unit-tested rather than eyeballed here.
 */
@Composable
fun GamesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val games = remember { GamesRepository.installed(context) }

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
        }
    }
}
