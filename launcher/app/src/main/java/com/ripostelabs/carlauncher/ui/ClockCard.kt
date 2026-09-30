package com.ripostelabs.carlauncher.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ripostelabs.carlauncher.data.ClockStyle
import com.ripostelabs.carlauncher.data.Sky
import com.ripostelabs.carlauncher.data.WeatherFeed
import com.ripostelabs.carlauncher.data.WeatherNow
import com.ripostelabs.carlauncher.ui.theme.carCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Analog hand angles in degrees clockwise from twelve. */
internal object ClockHands {
    private const val DEG_PER_HOUR = 30f
    private const val DEG_PER_MINUTE = 6f
    private const val HOURS_ON_FACE = 12

    /** 10:30 → 315: the hour hand moves half a degree per minute, like a real movement. */
    fun hour(hour: Int, minute: Int): Float =
        (hour % HOURS_ON_FACE) * DEG_PER_HOUR + minute * (DEG_PER_HOUR / 60f)

    fun minute(minute: Int, second: Int): Float = minute * DEG_PER_MINUTE + second * (DEG_PER_MINUTE / 60f)
}

/** The wall clock, ticking once a second. Shared by the home card and the screensaver. */
@Composable
fun rememberNow(): State<Calendar> = produceState(initialValue = Calendar.getInstance()) {
    while (true) {
        value = Calendar.getInstance()
        delay(TICK_MS)
    }
}

/**
 * The time in the chosen [style]. Digital is 24 h to match the status bar; analog is a plain
 * dial with hour ticks and no numerals, which reads at a glance from the driver's seat.
 */
@Composable
fun ClockFace(
    style: ClockStyle,
    now: Calendar,
    modifier: Modifier = Modifier,
    digitalSize: Int = DIGITAL_SP,
    analogSize: Dp = ANALOG_DP,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (style == ClockStyle.ANALOG) {
        val accent = MaterialTheme.colorScheme.primary
        Canvas(modifier = modifier.size(analogSize)) { drawDial(now, color, accent) }
        return
    }
    Text(
        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time),
        fontSize = digitalSize.sp,
        fontWeight = FontWeight.Light,
        color = color,
        modifier = modifier,
    )
}

private fun DrawScope.drawDial(now: Calendar, ink: Color, accent: Color) {
    val r = size.minDimension / 2f
    val c = center

    // Twelve ticks, the quarters longer.
    for (i in 0 until 12) {
        rotate(i * 30f, c) {
            val long = i % 3 == 0
            drawLine(
                color = ink.copy(alpha = if (long) 1f else 0.5f),
                start = Offset(c.x, c.y - r),
                end = Offset(c.x, c.y - r * if (long) 0.78f else 0.86f),
                strokeWidth = r * 0.05f,
                cap = StrokeCap.Round,
            )
        }
    }

    val h = now.get(Calendar.HOUR_OF_DAY)
    val m = now.get(Calendar.MINUTE)
    val s = now.get(Calendar.SECOND)
    hand(ClockHands.hour(h, m), r * 0.5f, r * 0.09f, ink)
    hand(ClockHands.minute(m, s), r * 0.75f, r * 0.06f, ink)
    drawCircle(color = accent, radius = r * 0.08f, center = c)
}

private fun DrawScope.hand(degrees: Float, length: Float, width: Float, color: Color) {
    rotate(degrees, center) {
        drawLine(color, center, Offset(center.x, center.y - length), width, StrokeCap.Round)
    }
}

/**
 * RAV4-196 — the home clock and weather card (stock `TimeCard` + `WeatherInfoView`).
 *
 * ```
 *   ┌──────────────────────────────────────┐
 *   │ 14:05          ☁  15°C   18° / 6°    │
 *   │ Wed 30 Sep        Kelowna  AQI 42    │
 *   └──────────────────────────────────────┘
 * ```
 *
 * The weather half appears only with a fresh reading and opens the weather app on tap. With the
 * clock off it is the weather alone; with neither there is no card (the caller skips it).
 */
@Composable
fun HomeClockCard(
    style: ClockStyle,
    weather: WeatherNow?,
    onOpenWeather: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val now by rememberNow()

    Card(
        modifier = modifier.carCard(accent = MaterialTheme.colorScheme.secondary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (style != ClockStyle.OFF) {
                ClockBlock(style, now)
            }
            if (weather != null) {
                WeatherBlock(weather, onOpenWeather)
            }
        }
    }
}

@Composable
private fun ClockBlock(style: ClockStyle, now: Calendar) {
    // Two short lines, so the weather beside it keeps its width.
    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(now.time)
    val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(now.time)

    // Analog: dial, then the digits small beside it. Digital: the digits are the face.
    Row(verticalAlignment = Alignment.CenterVertically) {
        ClockFace(style = style, now = now)
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.Center) {
            if (style == ClockStyle.ANALOG) {
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (style == ClockStyle.DIGITAL) {
                Text(
                    text = day,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (style == ClockStyle.DIGITAL) date else "$day $date",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeatherBlock(w: WeatherNow, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = withTapFeedback(onOpen)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = skyIcon(WeatherFeed.sky(w.code)),
            contentDescription = WeatherFeed.sky(w.code).name.lowercase().replaceFirstChar { it.uppercase() },
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = WeatherFeed.degrees(w.temp) + w.unit.code,
                fontSize = 24.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val hiLo = if (w.high != null && w.low != null) {
                "${WeatherFeed.degrees(w.high)} / ${WeatherFeed.degrees(w.low)}"
            } else {
                w.place
            }
            Text(
                text = hiLo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            w.aqi?.let {
                Text(
                    text = "AQI $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun skyIcon(sky: Sky): ImageVector = when (sky) {
    Sky.CLEAR -> Icons.Filled.WbSunny
    Sky.CLOUDY -> Icons.Filled.Cloud
    Sky.FOG -> Icons.Filled.Dehaze
    Sky.RAIN -> Icons.Filled.Umbrella
    Sky.SNOW -> Icons.Filled.AcUnit
    Sky.STORM -> Icons.Filled.Thunderstorm
}

/**
 * The weather app's last fresh reading, or null. Re-read when the provider says it changed and
 * on a slow poll, so a reading ages out even when nothing new arrives.
 */
@Composable
fun rememberWeather(enabled: Boolean): State<WeatherNow?> {
    val context = LocalContext.current.applicationContext
    val poke = remember { mutableIntStateOf(0) }
    DisposableEffect(enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose {}
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                poke.intValue++
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(WeatherFeed.URI, false, observer)
        }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return produceState<WeatherNow?>(initialValue = null, enabled, poke.intValue) {
        while (enabled) {
            value = readFresh(context)
            delay(WEATHER_POLL_MS)
        }
        value = null
    }
}

private suspend fun readFresh(context: Context): WeatherNow? = withContext(Dispatchers.IO) {
    WeatherFeed.read(context)?.takeIf { WeatherFeed.isFresh(it, System.currentTimeMillis()) }
}

private const val TICK_MS = 1_000L
private const val WEATHER_POLL_MS = 10L * 60_000L
private const val DIGITAL_SP = 44
private val ANALOG_DP = 72.dp
