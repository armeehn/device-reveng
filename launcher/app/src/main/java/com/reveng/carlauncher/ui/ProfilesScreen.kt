package com.reveng.carlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.data.DriverProfile
import com.reveng.carlauncher.data.DriverProfilesStore
import com.reveng.carlauncher.ui.theme.carShape

/**
 * v3.0 — driver profiles: named bundles of theme, favourites, quick-launch order and
 * reachability, switched in two taps from Home (status-bar chip, then a row here).
 *
 * Two people share this car, and every one of those four settings is a matter of taste rather
 * than of the vehicle. Switching them one at a time through four settings screens is enough
 * friction that nobody does it, so they get bundled.
 *
 * Creating a profile is parked-only: it needs a name, and naming needs the keyboard. Applying
 * one is not gated — it is a single tap on a named row, no more distracting than changing the
 * radio station, and being unable to restore your own layout while moving would be a worse
 * outcome than the tap itself.
 */
@Composable
fun ProfilesScreen(
    store: DriverProfilesStore,
    onApply: (DriverProfile) -> Unit,
    onCapture: () -> Unit,
    onDelete: (DriverProfile) -> Unit,
    onBack: () -> Unit,
) {
    val profiles by store.profiles.collectAsStateSafe(initial = emptyList())
    val active by store.activeProfile.collectAsStateSafe(initial = null)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(48.dp)
                    .clip(carShape(12.dp))
                    .clickable(onClick = withTapFeedback(onBack))
                    .padding(8.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Driver profiles",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            ParkedOnlySaveButton(onCapture = onCapture)
        }

        if (profiles.isEmpty()) {
            EmptyProfiles()
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
                ProfileRow(
                    profile = profile,
                    active = profile.id == active?.id,
                    onApply = { onApply(profile) },
                    onDelete = { onDelete(profile) },
                )
            }
        }
    }
}

/**
 * "Save current setup" needs a name, and naming needs a keyboard, so it follows the v2.5 gate.
 * Shown disabled rather than hidden so the affordance doesn't vanish and reappear at every
 * traffic light.
 */
@Composable
private fun ParkedOnlySaveButton(onCapture: () -> Unit) {
    val locked = LocalParkedOnlyLock.current
    val alpha = if (locked) DISABLED_ALPHA else 1f
    Text(
        text = if (locked) "Save — when parked" else "Save current setup",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha),
        modifier = Modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            .clickable(enabled = !locked, onClick = withTapFeedback(onCapture))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun ProfileRow(
    profile: DriverProfile,
    active: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT_DP.dp)
            .clip(carShape(16.dp))
            .background(bg)
            .clickable(onClick = withTapFeedback(onApply))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Active profile",
                tint = fg,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${profile.favorites.size} favourites · ${profile.driverSide.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.Delete,
            contentDescription = "Delete ${profile.name}",
            tint = fg,
            modifier = Modifier
                .size(44.dp)
                .clip(carShape(10.dp))
                .clickable(onClick = withTapFeedback(onDelete))
                .padding(8.dp),
        )
    }
}

@Composable
private fun EmptyProfiles() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No profiles yet.\nArrange the launcher how you like it, then Save current setup.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val ROW_HEIGHT_DP = 88
private const val DISABLED_ALPHA = 0.38f
