package com.ripostelabs.carlauncher.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ripostelabs.carlauncher.data.BindStage
import com.ripostelabs.carlauncher.data.HomeWidgetFlow
import com.ripostelabs.carlauncher.data.HomeWidgetHost
import com.ripostelabs.carlauncher.data.Outcome
import com.ripostelabs.carlauncher.data.WidgetChoice
import com.ripostelabs.carlauncher.ui.settings.OptionPickerDialog
import com.ripostelabs.carlauncher.ui.theme.carCard

/**
 * RAV4-199 — one Android AppWidget on Home, in the glance column.
 *
 * Empty, the card offers "Add a widget". Filled, it draws the provider's view with a small
 * edit button in the corner that reopens the picker (another widget, or remove). The widget
 * is an ordinary view inside Home, so reverse, the call card, the volume bar and the nav bar
 * all still draw over it.
 */
@Composable
fun HomeWidgetCard(host: HomeWidgetHost, modifier: Modifier = Modifier) {
    val widgetId by host.widgetId.collectAsStateSafe(initial = null)
    var picking by remember { mutableStateOf(false) }
    val adder = rememberWidgetAdder(host)

    // Provider updates flow only while Home is started.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) {
                host.startListening()
            }
            if (e == Lifecycle.Event.ON_STOP) {
                host.stopListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            host.stopListening()
        }
    }

    // An uninstalled provider leaves an id with no info: show the empty card again.
    val id = widgetId
    val info = id?.let(host::info)

    Card(
        modifier = modifier.carCard(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (id == null || info == null) {
            EmptySlot(onAdd = { picking = true })
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx -> host.createView(ctx, id, info) },
                    modifier = Modifier.fillMaxSize().padding(WIDGET_PADDING),
                )
                IconButton(
                    onClick = { picking = true },
                    modifier = Modifier.align(Alignment.TopEnd).size(EDIT_BUTTON),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Change widget",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (picking) {
        WidgetPicker(
            host = host,
            shown = info?.provider?.let { "${it.packageName}/${it.className}" },
            onPick = { choice ->
                picking = false
                adder(choice)
            },
            onRemove = {
                picking = false
                host.remove()
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun EmptySlot(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().clickable(onClick = onAdd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Add a widget",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WidgetPicker(
    host: HomeWidgetHost,
    shown: String?,
    onPick: (WidgetChoice) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = remember { host.choices() }

    // Keys are "pkg/cls". The empty key is "Remove", offered only when a widget is shown.
    val options = buildList {
        if (shown != null) {
            add(REMOVE_KEY to "Remove widget")
        }
        choices.forEach { add("${it.pkg}/${it.cls}" to "${it.label} · ${it.app}") }
    }
    OptionPickerDialog(
        title = "Home widget",
        options = options,
        current = shown ?: REMOVE_KEY,
        onSelect = { key ->
            if (key == REMOVE_KEY) {
                onRemove()
                return@OptionPickerDialog
            }
            choices.firstOrNull { "${it.pkg}/${it.cls}" == key }?.let(onPick)
        },
        onDismiss = onDismiss,
    )
}

/**
 * The add flow: bind, else the system grant dialog, then the widget's own setup screen.
 * [HomeWidgetFlow] decides each step; this only launches the screens it names.
 */
@Composable
private fun rememberWidgetAdder(host: HomeWidgetHost): (WidgetChoice) -> Unit {
    var pendingId by remember { mutableStateOf<Int?>(null) }
    var pendingChoice by remember { mutableStateOf<WidgetChoice?>(null) }
    var advance: (BindStage, Outcome) -> Unit = { _, _ -> }

    val grant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        advance(BindStage.GRANT, outcomeOf(it.resultCode))
    }
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        advance(BindStage.CONFIGURE, outcomeOf(it.resultCode))
    }

    advance = advance@{ stage, outcome ->
        val id = pendingId ?: return@advance
        val choice = pendingChoice ?: return@advance

        when (HomeWidgetFlow.next(stage, outcome, host.setup(id))) {
            BindStage.GRANT -> grant.launch(host.grantIntent(id, choice))
            BindStage.CONFIGURE -> {
                // A setup screen we may not start (not exported) leaves the widget on defaults.
                val intent = host.configureIntent(id)
                val started = intent != null && runCatching { configure.launch(intent) }.isSuccess
                if (!started) {
                    host.commit(id)
                    pendingId = null
                }
            }
            BindStage.DONE -> {
                host.commit(id)
                pendingId = null
            }
            BindStage.CANCELLED -> {
                host.discard(id)
                pendingId = null
            }
            BindStage.BIND -> Unit
        }
    }

    return { choice ->
        val id = host.allocate()
        pendingId = id
        pendingChoice = choice
        val bound = if (host.bind(id, choice)) Outcome.OK else Outcome.REFUSED
        advance(BindStage.BIND, bound)
    }
}

private fun outcomeOf(resultCode: Int): Outcome =
    if (resultCode == Activity.RESULT_OK) Outcome.OK else Outcome.REFUSED

private const val REMOVE_KEY = ""
private val WIDGET_PADDING = 8.dp
private val EDIT_BUTTON = 36.dp
