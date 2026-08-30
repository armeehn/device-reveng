package com.reveng.carlauncher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import com.reveng.carlauncher.ui.theme.carShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import com.reveng.carlauncher.AppInfo
import com.reveng.carlauncher.AppRepository
import com.reveng.carlauncher.HomeRole
import com.reveng.carlauncher.data.FavoritesStore
import com.reveng.carlauncher.data.ThemeStore
import com.reveng.carlauncher.ui.theme.CarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.0 — one-shot first-run onboarding. Three short, fully skippable steps:
 *
 *   1. Pick a theme  — reuses the built-in presets + [ThemePreviewSwatch] live swatch.
 *   2. Pick favourites — tap a few apps to pin them (persisted via [FavoritesStore], the same
 *      store the drawer's favourites row reads).
 *   3. Set as default home — explains companion-vs-default and opens the platform picker
 *      ([HomeRole.requestSetDefaultHome]); shows a live "you're all set" state once we're default.
 *
 * Gated by SettingsStore.firstRun and shown exactly once; [onFinish] marks it complete (called
 * from both Finish and Skip). It loads apps itself; the [FavoritesStore] is handed in so the
 * screen writes through the launcher's instance rather than opening a second one.
 */
@Composable
fun OnboardingScreen(
    themeStore: ThemeStore,
    appRepository: AppRepository,
    night: Boolean,
    onFinish: () -> Unit,
    // Null falls back to a local instance, which keeps previews working.
    favoritesStore: FavoritesStore? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themes by themeStore.allThemes.collectAsStateSafe(initial = emptyList())
    val activeTheme by themeStore.activeTheme.collectAsStateSafe(initial = com.reveng.carlauncher.ui.theme.BuiltInThemes.DEFAULT)

    val favStore = favoritesStore ?: remember { FavoritesStore(context.applicationContext, scope) }
    val favorites by favStore.favorites.collectAsStateSafe(initial = emptySet())

    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appRepository.loadApps() }.filter { !it.isSystem }
    }

    // Live default-home status for step 3, refreshed on resume (return from the picker). The probe
    // is a PackageManager.resolveActivity binder round-trip, so it runs on Dispatchers.IO and the
    // state is seeded false rather than resolved in the composable body.
    var isDefaultHome by remember { mutableStateOf(false) }
    var homeProbe by remember { mutableStateOf(0) }
    LaunchedEffect(homeProbe) {
        isDefaultHome = withContext(Dispatchers.IO) { HomeRole.isDefaultHome(context) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) homeProbe++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var step by remember { mutableStateOf(0) }
    val lastStep = 3 // v0.4.2: theme, favourites, permissions, default-home

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        // ---- Header: step dots + Skip ------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Welcome to Car Launcher",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            StepDots(current = step, total = lastStep + 1)
            Spacer(Modifier.width(20.dp))
            PillButton(label = "Skip", primary = false, onClick = onFinish)
        }

        Spacer(Modifier.height(20.dp))

        // ---- Step content (animated) -------------------------------------
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(tween(320)) { w -> dir * w / 6 } + fadeIn(tween(320)))
                        .togetherWith(
                            slideOutHorizontally(tween(320)) { w -> -dir * w / 6 } + fadeOut(tween(220)),
                        )
                        .using(SizeTransform(clip = false))
                },
                label = "onboarding-step",
            ) { s ->
                when (s) {
                    0 -> ThemeStep(
                        themes = themes,
                        activeId = activeTheme.id,
                        night = night,
                        onSelect = { themeStore.setActive(it.id) },
                    )
                    1 -> FavoritesStep(
                        apps = apps,
                        favorites = favorites,
                        onToggle = { app -> scope.launch { favStore.toggle(app.packageName) } },
                    )
                    2 -> PermissionsStep() // v0.4.2
                    else -> DefaultHomeStep(
                        isDefaultHome = isDefaultHome,
                        onSetDefault = { HomeRole.requestSetDefaultHome(context) },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Footer: Back / Next|Done ------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0) {
                PillButton(
                    label = "Back",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    primary = false,
                    onClick = { step-- },
                )
            }
            Spacer(Modifier.weight(1f))
            PillButton(
                label = if (step == lastStep) "Finish" else "Next",
                primary = true,
                onClick = { if (step == lastStep) onFinish() else step++ },
            )
        }
    }
}

// ---- Steps -----------------------------------------------------------------

@Composable
private fun ThemeStep(
    themes: List<CarTheme>,
    activeId: String,
    night: Boolean,
    onSelect: (CarTheme) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading("Pick a theme", "You can fine-tune or add your own later in Themes.")
        Spacer(Modifier.height(16.dp))
        if (themes.isEmpty()) {
            EmptyHint("Loading themes…")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(themes, key = { it.id }) { theme ->
                    val active = theme.id == activeId
                    Column(
                        modifier = Modifier
                            .clip(carShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                if (active) 3.dp else 1.dp,
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                carShape(18.dp),
                            )
                            .clickable { onSelect(theme) }
                            .padding(14.dp),
                    ) {
                        ThemePreviewSwatch(
                            colors = theme.variant(night),
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = theme.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (active) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesStep(
    apps: List<AppInfo>,
    favorites: Set<String>,
    onToggle: (AppInfo) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading(
            "Choose favourite apps",
            "Tap a few to pin them to the top of the drawer. Optional — skip if you like.",
        )
        Spacer(Modifier.height(16.dp))
        if (apps.isEmpty()) {
            EmptyHint("No apps found yet — they'll appear here once loaded.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(apps, key = { it.packageName + "/" + it.activityName }) { app ->
                    AppPickTile(
                        app = app,
                        selected = app.packageName in favorites,
                        onClick = { onToggle(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPickTile(app: AppInfo, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(carShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                carShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            AppIcon(app = app, size = 56.dp)
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        AutoSizeText(
            text = app.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * v0.4.2 — first-run permissions step. Requests the runtime grants the normal way (the system
 * dialog, not the root/adb path the Setup Doctor uses post-install), and links out to the
 * notification-access screen for the listener grants that have no runtime-request API. Live status
 * comes from [SetupDoctor], re-probed whenever we resume (returning from a dialog or Settings). All
 * optional — the footer Next/Skip always proceeds; Settings ▸ Setup doctor repeats this later.
 */
@Composable
private fun PermissionsStep() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doctor = remember { com.reveng.carlauncher.data.SetupDoctor(context.applicationContext, scope) }
    val checks by doctor.checks.collectAsStateSafe(initial = emptyList())

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { doctor.refresh() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) doctor.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading(
            "Grant permissions",
            "So the launcher's features work. All optional — you can do this any time in " +
                "Settings ▸ Setup doctor.",
        )
        Spacer(Modifier.height(16.dp))
        checks.forEach { check ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (check.ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (check.ok) "Granted" else "Not granted",
                    tint = if (check.ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = check.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                label = "Grant location & Bluetooth",
                primary = true,
                onClick = {
                    // Derive the request set from the doctor's checks so the required runtime
                    // permissions are defined once (in SetupDoctor), not re-hardcoded here.
                    val perms = checks.mapNotNull { it.runtimePermission }.toTypedArray()
                    if (perms.isNotEmpty()) permissionLauncher.launch(perms)
                },
            )
            PillButton(
                label = "Notification access",
                primary = false,
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun DefaultHomeStep(isDefaultHome: Boolean, onSetDefault: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (isDefaultHome) Icons.Filled.CheckCircle else Icons.Filled.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (isDefaultHome) {
            StepHeadingCentered(
                "You're all set",
                "Car Launcher is your default home. Press Finish to get started.",
            )
        } else {
            StepHeadingCentered(
                "Set as default home",
                "Car Launcher works as a companion even if it isn't the default. To make it come " +
                    "up on Home, set it as the default launcher — then choose \"Car Launcher\".",
            )
            Spacer(Modifier.height(24.dp))
            PillButton(
                label = "Set as default home",
                icon = Icons.Filled.Home,
                primary = true,
                onClick = onSetDefault,
            )
        }
    }
}

// ---- Building blocks -------------------------------------------------------

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepHeadingCentered(title: String, subtitle: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val on = i == current
            Box(
                modifier = Modifier
                    .height(10.dp)
                    .width(if (on) 26.dp else 10.dp)
                    .clip(carShape(5.dp))
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}

/** Large-target pill button. Height 56dp for easy in-car tapping. */
@Composable
private fun PillButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(carShape(28.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
    }
}
