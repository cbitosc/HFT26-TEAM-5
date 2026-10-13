package app.getknit.knit.debug.call

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.getknit.knit.call.CallAudioRoute
import app.getknit.knit.call.CallAudioRoute.Kind
import app.getknit.knit.call.CallRouting

/**
 * Where a call's audio goes, on the call screen of either kind (docs/VOICE_CALLS.md §3.13). With only the earpiece and
 * the loudspeaker it is the loudspeaker's toggle every phone's call screen has; once a headset is there too it names
 * the route the call is on, and a tap lists every route the system offers. Before the system holds the call there is
 * nowhere to choose, and it is drawn disabled.
 */
@Composable
internal fun AudioRouteButton(
    routing: CallRouting?,
    onRoute: (CallAudioRoute) -> Unit,
) {
    val available = routing?.available.orEmpty()
    val speaker = available.firstOrNull { it.kind == Kind.SPEAKER }
    if (available.size <= 2) {
        val other = available.firstOrNull { it.kind != Kind.SPEAKER }
        CallToggle(
            label = "Speaker",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            checked = routing?.current?.kind == Kind.SPEAKER,
            onCheckedChange = { on -> (if (on) speaker else other)?.let(onRoute) },
            enabled = speaker != null && other != null,
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    val current = routing?.current
    val label = current?.let(::routeLabel) ?: "Audio"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = { open = true }, modifier = Modifier.size(ToggleSize), shape = CircleShape) {
            Icon(
                routeIcon(current?.kind),
                contentDescription = "Audio: $label. Choose where the call plays",
                modifier = Modifier.size(28.dp),
            )
        }
        // A headset's own name can be long; the sheet has room for all of it.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 104.dp).clearAndSetSemantics {},
        )
    }
    if (open && routing != null) {
        RouteSheet(
            routing = routing,
            onPick = {
                open = false
                onRoute(it)
            },
            onDismiss = { open = false },
        )
    }
}

/** Every route the system offers the call, the one it is on checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteSheet(
    routing: CallRouting,
    onPick: (CallAudioRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Audio",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            for (route in routing.available) {
                val selected = route.key == routing.current?.key
                ListItem(
                    headlineContent = { Text(route.name.ifBlank { routeLabel(route) }) },
                    leadingContent = { Icon(routeIcon(route.kind), contentDescription = null) },
                    trailingContent = { if (selected) Icon(Icons.Filled.Check, contentDescription = null) },
                    modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) { onPick(route) },
                )
            }
        }
    }
}

/** A route as a button, a notification action or a log line names it: the headset's own name for a Bluetooth one. */
internal fun routeLabel(route: CallAudioRoute): String =
    when (route.kind) {
        Kind.EARPIECE -> "Earpiece"
        Kind.SPEAKER -> "Speaker"
        Kind.WIRED -> "Headset"
        Kind.BLUETOOTH, Kind.OTHER -> route.name.ifBlank { if (route.kind == Kind.BLUETOOTH) "Bluetooth" else "Audio" }
    }

private fun routeIcon(kind: Kind?): ImageVector =
    when (kind) {
        Kind.EARPIECE -> Icons.Filled.PhoneInTalk
        Kind.SPEAKER -> Icons.AutoMirrored.Filled.VolumeUp
        Kind.WIRED -> Icons.Filled.Headset
        Kind.BLUETOOTH -> Icons.Filled.BluetoothAudio
        Kind.OTHER, null -> Icons.Filled.SettingsVoice
    }
