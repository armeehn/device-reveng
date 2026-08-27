package com.reveng.carlauncher.ui.theme

import androidx.compose.ui.graphics.Color

// Dark "car" palette — high-contrast, low-glare for a head unit at night.
val CarBackground = Color(0xFF0B0E11)
val CarSurface = Color(0xFF161B22)
val CarSurfaceVariant = Color(0xFF1F2630)
val CarAccent = Color(0xFF2F81F7)
val CarAccentMuted = Color(0xFF1B3A5B)
val CarOnSurface = Color(0xFFE6EDF3)
val CarOnSurfaceMuted = Color(0xFF8B98A5)
val CarError = Color(0xFFE5534B)

// Night variant — dimmer surfaces + softer text/accent to cut glare while driving at
// night. Still fully dark (a head unit must never flash white). Driven by the vendor
// day/night backlight broadcast via CarEvents.dayNight.
val CarBackgroundNight = Color(0xFF05070A)
val CarSurfaceNight = Color(0xFF0D1117)
val CarAccentNight = Color(0xFF1F5FB0)
val CarOnSurfaceDim = Color(0xFF5B6672)
