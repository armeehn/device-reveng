package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reveng.carlauncher.AppInfo

/**
 * v2.3 rofi-style full-screen app search. The vendor system IME ignores night mode and covered
 * half the 720px-tall screen, so search no longer uses it at all: this overlay owns the whole
 * view with a big themed query line, a live-filtered result row, and an in-Compose QWERTY —
 * every pixel themes with [MaterialTheme.colorScheme]. Prefix matches rank before substring
 * matches; ⏎ (or tapping a tile) launches, the first result is the ⏎ target and is outlined.
 */
@Composable
fun SearchOverlay(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps
            .filter { it.label.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ !it.label.startsWith(q, ignoreCase = true) }, { it.label.lowercase() }))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
                QueryLine(query = query, onDismiss = onDismiss)

                if (results.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No matching apps",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(
                            results,
                            key = { _, app -> app.packageName + "/" + app.activityName },
                        ) { index, app ->
                            SearchResultTile(
                                app = app,
                                // Outline the ⏎ target while a query narrows the list.
                                highlighted = index == 0 && query.isNotBlank(),
                                onClick = { onLaunch(app) },
                            )
                        }
                    }
                }

                SearchKeyboard(
                    onChar = { query += it },
                    onBackspace = { query = query.dropLast(1) },
                    onEnter = { results.firstOrNull()?.let(onLaunch) },
                )
            }
        }
    }
}

/** The big query line: search icon, typed text (or hint) with a block cursor, close button. */
@Composable
private fun QueryLine(query: String, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.width(14.dp))
        if (query.isEmpty()) {
            Text(
                text = "Type to search…",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = query,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Box(
            Modifier
                .padding(start = 4.dp)
                .width(4.dp)
                .height(34.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close search",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun SearchResultTile(app: AppInfo, highlighted: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(132.dp)
            .clip(shape)
            .then(
                if (highlighted) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Image(
            painter = rememberDrawablePainter(app),
            contentDescription = app.label,
            modifier = Modifier.size(84.dp),
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * In-Compose QWERTY sized for a moving vehicle: 4 rows of ~72dp keys across the full 1920px
 * width. Letters append lowercase (matching is case-insensitive anyway); ⏎ launches the first
 * result. No shift/symbols — app labels only ever need letters, digits and space.
 */
@Composable
private fun SearchKeyboard(
    onChar: (Char) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "1234567890".forEach { c -> SearchKey(c.toString()) { onChar(c) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "QWERTYUIOP".forEach { c -> SearchKey(c.toString()) { onChar(c.lowercaseChar()) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "ASDFGHJKL".forEach { c -> SearchKey(c.toString()) { onChar(c.lowercaseChar()) } }
            SearchKey("⌫", weight = 1.5f, onPress = onBackspace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            "ZXCVBNM".forEach { c -> SearchKey(c.toString()) { onChar(c.lowercaseChar()) } }
            SearchKey("␣", weight = 3f) { onChar(' ') }
            SearchKey("⏎", weight = 1.5f, onPress = onEnter)
        }
    }
}

@Composable
private fun RowScope.SearchKey(
    label: String,
    weight: Float = 1f,
    onPress: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(weight)
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPress),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The tappable search-bar lookalike at the top of the drawer; opens [SearchOverlay]. */
@Composable
fun DrawerSearchTrigger(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Search apps",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
