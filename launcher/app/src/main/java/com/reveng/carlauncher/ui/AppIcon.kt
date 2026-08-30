package com.reveng.carlauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.CurrencyExchange
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GetApp
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.ui.theme.carShape

/**
 * The rewritten OEM apps (com.reveng.*) all ship the stock template launcher icon, so the
 * drawer showed two dozen identical tiles. This maps each rewrite to a Material glyph on its
 * own colored tile so every app reads at a glance; anything unmapped (including non-reveng
 * apps) keeps its PackageManager icon.
 */
private data class RewrittenIcon(val glyph: ImageVector, val tile: Color)

/** Keyed by the com.reveng.<suffix>; `.debug` variants resolve to the same entry. */
private val rewrittenIcons: Map<String, RewrittenIcon> = mapOf(
    "bluetooth" to RewrittenIcon(Icons.Rounded.Bluetooth, Color(0xFF1976D2)),
    "browser" to RewrittenIcon(Icons.Rounded.Language, Color(0xFF1E88E5)),
    "calculator" to RewrittenIcon(Icons.Rounded.Calculate, Color(0xFF455A64)),
    "calendar" to RewrittenIcon(Icons.Rounded.CalendarMonth, Color(0xFFD81B60)),
    "clock" to RewrittenIcon(Icons.Rounded.Schedule, Color(0xFF3949AB)),
    "compass" to RewrittenIcon(Icons.Rounded.Explore, Color(0xFF00897B)),
    "contacts" to RewrittenIcon(Icons.Rounded.Contacts, Color(0xFF5E35B1)),
    "converter" to RewrittenIcon(Icons.Rounded.SwapHoriz, Color(0xFF6D4C41)),
    "currency" to RewrittenIcon(Icons.Rounded.CurrencyExchange, Color(0xFF2E7D32)),
    "deviceinfo" to RewrittenIcon(Icons.Rounded.Memory, Color(0xFF546E7A)),
    "files" to RewrittenIcon(Icons.Rounded.Folder, Color(0xFFF4511E)),
    "gps" to RewrittenIcon(Icons.Rounded.GpsFixed, Color(0xFF039BE5)),
    "installer" to RewrittenIcon(Icons.Rounded.GetApp, Color(0xFF00ACC1)),
    "level" to RewrittenIcon(Icons.Rounded.Straighten, Color(0xFF7CB342)),
    "music" to RewrittenIcon(Icons.Rounded.MusicNote, Color(0xFFC2185B)),
    "news" to RewrittenIcon(Icons.Rounded.Newspaper, Color(0xFFEF6C00)),
    "notes" to RewrittenIcon(Icons.Rounded.Description, Color(0xFFF57F17)),
    "photos" to RewrittenIcon(Icons.Rounded.Photo, Color(0xFF43A047)),
    "radio" to RewrittenIcon(Icons.Rounded.Radio, Color(0xFFE53935)),
    "recorder" to RewrittenIcon(Icons.Rounded.Mic, Color(0xFF8D6E63)),
    "sketch" to RewrittenIcon(Icons.Rounded.Brush, Color(0xFF7B1FA2)),
    "soundmeter" to RewrittenIcon(Icons.Rounded.GraphicEq, Color(0xFF00838F)),
    "speedometer" to RewrittenIcon(Icons.Rounded.Speed, Color(0xFFC62828)),
    "tasks" to RewrittenIcon(Icons.Rounded.DoneAll, Color(0xFF388E3C)),
    "video" to RewrittenIcon(Icons.Rounded.Movie, Color(0xFF3F51B5)),
    "weather" to RewrittenIcon(Icons.Rounded.WbSunny, Color(0xFFFFA000)),
)

private fun rewrittenIconFor(packageName: String): RewrittenIcon? {
    val pkg = packageName.removeSuffix(".debug")
    if (!pkg.startsWith("com.reveng.")) return null
    return rewrittenIcons[pkg.removePrefix("com.reveng.")]
}

/**
 * The one place an app's icon is drawn: the rewrite tile above, or the PackageManager
 * drawable rasterized via [rememberDrawablePainter] for everything else.
 */
@Composable
internal fun AppIcon(app: AppInfo, size: Dp, modifier: Modifier = Modifier) {
    val custom = rewrittenIconFor(app.packageName)
    if (custom != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(carShape(size * 0.22f))
                .background(custom.tile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = custom.glyph,
                contentDescription = app.label,
                tint = Color.White,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    } else {
        Image(
            painter = rememberDrawablePainter(app),
            contentDescription = app.label,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
    }
}
