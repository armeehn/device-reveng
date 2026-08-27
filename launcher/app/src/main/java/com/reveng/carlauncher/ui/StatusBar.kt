package com.reveng.carlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reveng.carlauncher.carlib.CarEvents
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top status bar: live clock + car status chips (ACC power, day/night).
 * Data comes from [CarEvents] state flows (CAR_API §1.3, §6.3).
 */
@Composable
fun StatusBar(
    carEvents: CarEvents,
    modifier: Modifier = Modifier,
) {
    val accOn by carEvents.accOn.collectAsStateSafe(initial = true)
    val dayNight by carEvents.dayNight.collectAsStateSafe(initial = CarEvents.DayNight.DAY)

    val time by produceState(initialValue = nowString()) {
        while (true) {
            value = nowString()
            delay(1_000)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (dayNight == CarEvents.DayNight.NIGHT)
                    Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Illumination mode",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "ACC power",
                tint = if (accOn) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private fun nowString(): String =
    SimpleDateFormat("EEE  HH:mm", Locale.getDefault()).format(Date())
