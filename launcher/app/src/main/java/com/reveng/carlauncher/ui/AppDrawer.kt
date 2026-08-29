package com.reveng.carlauncher.ui

import android.os.SystemClock
import com.reveng.carlauncher.ui.theme.carCard
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.data.AppOrderStore
import com.reveng.carlauncher.data.FavoritesStore
import com.reveng.carlauncher.input.GridFocus // v0.8 SWC navigation
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * App-drawer grid — v0.4 "App Drawer 2.0". On top of the original scrollable grid + "System"
 * folder (vendor/engineering apps behind one tile), this adds:
 *
 *  - **Search**: v2.3 — a rofi-style full-screen [SearchOverlay] with its own in-Compose
 *    keyboard (the vendor IME ignores night mode and ate half the screen); the bar at the top
 *    of the drawer ([DrawerSearchTrigger]) just opens it.
 *  - **Favorites**: long-press-then-release an app to (un)favorite it; favorites are pinned in a
 *    horizontal row above the grid. Persisted via [FavoritesStore] (DataStore Preferences).
 *  - **Drag-to-reorder**: long-press-drag a tile to reorder the main grid; the custom order is
 *    persisted via [AppOrderStore]. Apps with no saved position sort after, alphabetically.
 *
 * Gestures on a grid tile: short tap = launch, long-press + release (no move) = toggle favorite,
 * long-press + drag = reorder. Reordering is disabled while a search query is active (the grid is
 * then a filtered subset). Big tap targets for a moving vehicle (CAR_API §6.3, "App list").
 *
 * All colours come from [MaterialTheme.colorScheme] so the sibling theming pass applies.
 */
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    systemApps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 0, // v0.6: 0 = adaptive sizing; >0 = fixed column count (SettingsStore)
    gridFocus: GridFocus? = null, // v0.8: roving focus over grid tiles (null = touch-only)
    // The launcher-owned stores. Constructing them here instead opened a second eager collector
    // on each DataStore file, so the drawer re-read them on every Home <-> Settings round trip.
    // Null falls back to local instances, which keeps previews and standalone use working.
    favoritesStore: FavoritesStore? = null,
    appOrderStore: AppOrderStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favStore = favoritesStore ?: remember { FavoritesStore(context.applicationContext, scope) }
    val ordStore = appOrderStore ?: remember { AppOrderStore(context.applicationContext, scope) }

    val favorites by favStore.favorites.collectAsStateSafe(initial = emptySet())
    val savedOrder by ordStore.order.collectAsStateSafe(initial = emptyList())
    var showSearch by remember { mutableStateOf(false) } // v2.3 full-screen search overlay
    var showSystem by remember { mutableStateOf(false) }

    // Apply the user's saved order first (by package), everything else alphabetically after.
    val orderedApps = remember(apps, savedOrder) {
        val rank = savedOrder.withIndex().associate { (i, pkg) -> pkg to i }
        apps.sortedWith(
            compareBy({ rank[it.packageName] ?: Int.MAX_VALUE }, { it.label.lowercase() }),
        )
    }
    val favoriteApps = orderedApps.filter { it.packageName in favorites }

    val toggleFavorite: (AppInfo) -> Unit = { app -> scope.launch { favStore.toggle(app.packageName) } }

    Column(modifier = modifier.fillMaxSize()) {
        DrawerSearchTrigger(
            onClick = withTapFeedback { showSearch = true }, // v2.5
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )

        if (favoriteApps.isNotEmpty()) {
            FavoritesRow(
                apps = favoriteApps,
                onLaunch = onLaunch,
                onToggleFavorite = toggleFavorite,
            )
        }

        ReorderableAppGrid(
            apps = orderedApps,
            favorites = favorites,
            systemApps = systemApps,
            reorderEnabled = true,
            onLaunch = onLaunch,
            onToggleFavorite = toggleFavorite,
            onReorder = { newOrder -> scope.launch { ordStore.setOrder(newOrder.map { it.packageName }) } },
            onOpenSystem = { showSystem = true },
            columns = columns, // v0.6 density
            gridFocus = gridFocus, // v0.8
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }

    if (showSearch) {
        SearchOverlay(
            apps = orderedApps,
            onLaunch = { onLaunch(it); showSearch = false },
            onDismiss = { showSearch = false },
        )
    }

    if (showSystem) {
        SystemFolderDialog(
            systemApps = systemApps,
            onLaunch = { onLaunch(it); showSystem = false },
            onDismiss = { showSystem = false },
        )
    }
}

/**
 * The main scrollable app grid with long-press drag-to-reorder. The drag detector lives on the
 * grid container; it hit-tests [androidx.compose.foundation.lazy.grid.LazyGridState.layoutInfo]
 * to find the dragged tile and the tile currently under the finger, swapping them live in a local
 * copy and persisting via [onReorder] on release. A long-press that ends without meaningful
 * movement is treated as a favorite toggle instead. The "System" folder tile (when present) is
 * appended last and is never draggable.
 */
@Composable
private fun ReorderableAppGrid(
    apps: List<AppInfo>,
    favorites: Set<String>,
    systemApps: List<AppInfo>,
    reorderEnabled: Boolean,
    onLaunch: (AppInfo) -> Unit,
    onToggleFavorite: (AppInfo) -> Unit,
    onReorder: (List<AppInfo>) -> Unit,
    onOpenSystem: () -> Unit,
    columns: Int = 0, // v0.6: 0=adaptive, >0=fixed
    gridFocus: GridFocus? = null, // v0.8
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    // Live, locally-reorderable copy; resets whenever the source list changes (order persisted
    // -> flow re-emits -> new `apps`).
    var items by remember(apps) { mutableStateOf(apps) }
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var totalDrag by remember { mutableStateOf(Offset.Zero) }
    // A long-press-release toggles the favorite via the grid drag detector; without this guard
    // the tile's combinedClickable ALSO fires onClick on that same release and launches the app.
    // draggingIndex is still >= 0 when the child click fires (child-first Main pass); the
    // timestamp covers the release having already reset draggingIndex.
    var suppressTapUntil by remember { mutableStateOf(0L) }

    // v0.8: publish the displayed grid (count, resolved columns, launch-by-index) to the focus
    // ring so the SWC key dispatcher navigates the exact tiles the drawer shows. The System
    // folder tile (when present) is the last focusable index.
    val hasSystem = systemApps.isNotEmpty()
    val effectiveColumns = if (columns > 0) columns else 3
    if (gridFocus != null) {
        SideEffect {
            gridFocus.count = items.size + if (hasSystem) 1 else 0
            gridFocus.columns = effectiveColumns
            gridFocus.launch = { i ->
                when {
                    i < items.size -> onLaunch(items[i])
                    hasSystem -> onOpenSystem()
                }
            }
            // v2.8: long CENTER from the wheel does what a touch long-press-release does — toggle
            // the favourite. The System folder tile has no favourite, so it is left alone.
            gridFocus.longPress = { i ->
                if (i < items.size) {
                    onToggleFavorite(items[i])
                }
            }
        }
    }
    val focusedIndex = gridFocus?.focusedIndex

    val dragModifier = if (reorderEnabled) {
        Modifier.pointerInput(items.size) {
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                        pos.x >= info.offset.x && pos.x < info.offset.x + info.size.width &&
                            pos.y >= info.offset.y && pos.y < info.offset.y + info.size.height
                    }
                    if (hit != null && hit.index < items.size) {
                        draggingIndex = hit.index
                        dragOffset = Offset.Zero
                        totalDrag = Offset.Zero
                    } else {
                        draggingIndex = -1
                    }
                },
                onDrag = { change, delta ->
                    change.consume()
                    if (draggingIndex >= 0) {
                        dragOffset += delta
                        totalDrag += delta
                        val dragged = gridState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == draggingIndex }
                        if (dragged != null) {
                            val cx = dragged.offset.x + dragged.size.width / 2f + dragOffset.x
                            val cy = dragged.offset.y + dragged.size.height / 2f + dragOffset.y
                            val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                info.index != draggingIndex && info.index < items.size &&
                                    cx >= info.offset.x && cx < info.offset.x + info.size.width &&
                                    cy >= info.offset.y && cy < info.offset.y + info.size.height
                            }
                            if (target != null) {
                                val next = items.toMutableList()
                                next.add(target.index, next.removeAt(draggingIndex))
                                items = next
                                draggingIndex = target.index
                                dragOffset = Offset.Zero
                            }
                        }
                    }
                },
                onDragEnd = {
                    val idx = draggingIndex
                    val moved = hypot(totalDrag.x, totalDrag.y) > 24f
                    if (idx in items.indices) {
                        if (moved) onReorder(items) else onToggleFavorite(items[idx])
                    }
                    draggingIndex = -1
                    dragOffset = Offset.Zero
                    totalDrag = Offset.Zero
                    suppressTapUntil = SystemClock.uptimeMillis() + 250
                },
                onDragCancel = {
                    draggingIndex = -1
                    dragOffset = Offset.Zero
                    totalDrag = Offset.Zero
                    suppressTapUntil = SystemClock.uptimeMillis() + 250
                },
            )
        }
    } else {
        Modifier
    }

    LazyVerticalGrid(
        state = gridState,
        columns = if (columns > 0) GridCells.Fixed(columns) else GridCells.Adaptive(minSize = 140.dp),
        modifier = modifier.then(dragModifier),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(
            items,
            key = { _, app -> app.packageName + "/" + app.activityName },
        ) { index, app ->
            val dragging = index == draggingIndex
            // v2.5: feedback wraps the launch, not the tap — a tap swallowed by the drag guard
            // below did nothing, and confirming nothing is its own kind of lie.
            val launch = withTapFeedback { onLaunch(app) }
            AppTile(
                app = app,
                onClick = {
                    // Ignore the click that rides along with a long-press-release (favorite toggle)
                    // or that lands just after a drag ends.
                    if (draggingIndex < 0 && SystemClock.uptimeMillis() >= suppressTapUntil) launch()
                },
                favorite = app.packageName in favorites,
                focused = index == focusedIndex, // v0.8 focus ring
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                            scaleX = 1.08f
                            scaleY = 1.08f
                        }
                    },
            )
        }
        if (hasSystem) {
            item(key = "__system_folder__") {
                SystemFolderTile(
                    count = systemApps.size,
                    onClick = withTapFeedback(onOpenSystem), // v2.5
                    focused = focusedIndex == items.size, // v0.8: folder is the last index
                )
            }
        }
    }
}

/** Pinned horizontal row of favorite apps above the grid. Long-press a chip to unfavorite. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesRow(
    apps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onToggleFavorite: (AppInfo) -> Unit,
) {
    Column {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(apps, key = { "fav_" + it.packageName + "/" + it.activityName }) { app ->
                FavoriteChip(
                    app = app,
                    onClick = withTapFeedback { onLaunch(app) }, // v2.5
                    onLongClick = { onToggleFavorite(app) },
                )
            }
        }
    }
}

/**
 * Compact icon + label chip for the Favorites row. The full [AppTile] (72dp icon, label
 * underneath) is too tall for the center column — NavCard + search + favorites + grid must
 * share ~400dp, so the tile's label ended up clipped off the bottom of the screen. The chip
 * keeps the name beside the icon so it is always visible, and returns ~60dp to the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteChip(
    app: AppInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bmp = remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
    }
    Row(
        modifier = Modifier
            .clip(carShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            bitmap = bmp,
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    focused: Boolean = false, // v0.8: SWC/DPAD focus ring
) {
    val shape = carShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Image(
                painter = rememberDrawablePainter(app),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(72.dp),
            )
            if (favorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The "System" folder entry — a folder-styled tile showing an apps icon + count. */
@Composable
private fun SystemFolderTile(count: Int, onClick: () -> Unit, focused: Boolean = false) {
    val shape = carShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(carShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = "System apps",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = "System ($count)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Dialog housing the system/vendor apps grid. */
@Composable
private fun SystemFolderDialog(
    systemApps: List<AppInfo>,
    onLaunch: (AppInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = carShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .carCard(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "System apps",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(systemApps, key = { it.packageName + "/" + it.activityName }) { app ->
                        AppTile(app = app, onClick = withTapFeedback { onLaunch(app) }) // v2.5
                    }
                }
            }
        }
    }
}

/**
 * Convert a launcher [android.graphics.drawable.Drawable] icon into a Compose Painter by
 * rasterizing to a bitmap. Kept simple (no Coil dependency). Internal: [SearchOverlay]'s
 * result tiles reuse it.
 */
@Composable
internal fun rememberDrawablePainter(app: AppInfo): Painter {
    val bmp = remember(app.packageName + app.activityName) {
        app.icon.toBitmap(width = 144, height = 144).asImageBitmap()
    }
    return BitmapPainter(bmp)
}
