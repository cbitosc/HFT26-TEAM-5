@file:Suppress("MatchingDeclarationName") // the walkie transcript card and the state it draws

package app.getknit.knit.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.TranscriptLine
import app.getknit.knit.ui.theme.LocalReduceMotion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the transcript card draws: the session's bursts, and where the speech model stands on this phone. */
internal data class TranscriptUi(
    val lines: List<TranscriptLine>,
    val model: SttModelState,
    // What the model costs to fetch, and whether the route a fetch would take now is mobile data or under Data Saver.
    val modelBytes: Long,
    val metered: Boolean,
)

/**
 * The walkie session's transcript (docs/VOICE_CALLS.md C-3.11-9), in the call screen's card slot while the header's
 * diagnostics toggle is off: each burst from [name] as text while it plays, settled once it has ended, and each burst
 * spoken here as the model heard it from the microphone, before Opus — newest at the bottom — and while the speech
 * model is not on the
 * phone, what it costs, the button that fetches it and Dismiss ([onDismiss]: the offer is closed for good, the caller
 * draws no card while the model is absent, and the download stays on the Calls screen). Experimental, debug only:
 * a spoken line set beside the other phone's for the same burst shows what the codec cost the model. Nothing here
 * leaves the phone.
 */
@Composable
internal fun WalkieTranscript(
    ui: TranscriptUi,
    name: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val ready = ui.model == SttModelState.Ready
    val list = rememberLazyListState()
    val reduceMotion = LocalReduceMotion.current
    // The newest burst in view as it comes, as its words stream in and as its text lands — once the model is in. Before
    // that the panel at the top is what matters, and a short screen would scroll it away.
    LaunchedEffect(ui.lines.size, ui.lines.lastOrNull()?.state, ui.lines.lastOrNull()?.live, ready) {
        if (!ready || ui.lines.isEmpty()) return@LaunchedEffect
        val last = ui.lines.lastIndex
        if (reduceMotion) list.scrollToItem(last) else list.animateScrollToItem(last)
    }
    // The configuration's locale, read so a change of language redraws the times.
    val locale = LocalConfiguration.current.locales[0]
    val times = remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale).withZone(zone) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(top = 16.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { Title("Transcript", "ON DEVICE", Icons.Outlined.ClosedCaption) }
            // Everything under the title is one list, so a short screen scrolls the card rather than clipping it.
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                transcriptItems(ui, name, ready, times, onDownload, onCancel, onDismiss)
            }
        }
    }
}

private fun LazyListScope.transcriptItems(
    ui: TranscriptUi,
    name: String,
    ready: Boolean,
    times: DateTimeFormatter,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!ready) {
        item(key = "model") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelPanel(ui, onDownload, onCancel, onDismiss)
                if (ui.lines.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } else if (ui.lines.isEmpty()) {
        item(key = "empty") {
            Text(
                "What you and ${name.ifBlank { "the other phone" }} say shows here as text, as you talk.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(ui.lines, key = { "${if (it.mine) "s" else "h"}${it.burst}" }) { line ->
        Line(line, if (line.mine) "You" else name.ifBlank { "Them" }, times.format(Instant.ofEpochMilli(line.heardAt)))
    }
}

@Composable
private fun Line(
    line: TranscriptLine,
    who: String,
    time: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(TimeWidth).padding(top = 3.dp),
        )
        Column(Modifier.weight(1f)) {
            // Who spoke; a line of this phone's own is the microphone's audio, before Opus.
            Text(
                if (line.mine) "$who · mic, before Opus" else who,
                style = MaterialTheme.typography.labelMedium,
                color = if (line.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Said(line)
        }
    }
}

@Composable
private fun Said(line: TranscriptLine) {
    val state = line.state
    // A line still listening or finishing carries the words so far; a settled one, none.
    val streaming = state is TranscriptLine.State.Listening || state is TranscriptLine.State.Pending
    when {
        state is TranscriptLine.State.Text -> {
            // Read out as it lands, for a user who cannot hear the burst; their own words they know.
            Text(
                state.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (line.mine) Modifier else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        // The words so far, while the burst is still coming or being finished: dimmed, since they may yet change, and
        // never read out — the settled text is, once.
        streaming && line.live.isNotBlank() -> {
            Text(
                line.live + "…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> {
            Text(
                noteOf(state, line.mine),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// What a line without text says.
private fun noteOf(
    state: TranscriptLine.State,
    mine: Boolean,
): String =
    when (state) {
        TranscriptLine.State.Listening -> if (mine) "Recording…" else "Listening…"
        TranscriptLine.State.Pending -> "Transcribing…"
        TranscriptLine.State.NoSpeech -> "No speech heard"
        TranscriptLine.State.NoModel -> "Not transcribed: the speech model isn't on this phone"
        TranscriptLine.State.Failed -> "Couldn't transcribe this one"
        is TranscriptLine.State.Text -> state.text
    }

/**
 * Where the speech model stands, with the one action that moves it on and, beside a Download, Dismiss: only the offer
 * closes, since once the model is in the Calls screen's switch is the way out.
 */
@Composable
private fun ModelPanel(
    ui: TranscriptUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val model = ui.model) {
            // The card draws no panel then.
            SttModelState.Ready -> {}

            SttModelState.Absent -> {
                Text(
                    "Turn the bursts you hear and speak into text, on this phone. The speech model is a one-time ${mb(ui.modelBytes)} " +
                        "download from getknit.app, here or any time in Settings › Calls.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.metered) MeteredNote()
                OfferButtons("Download", onDownload, onDismiss)
            }

            is SttModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { if (model.total > 0) (model.done.toFloat() / model.total).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Downloading the speech model · ${mb(model.done)} of ${mb(model.total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }

            is SttModelState.Failed -> {
                Text(
                    "The speech model didn't download. ${model.reason}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (ui.metered) MeteredNote()
                OfferButtons("Try again", onDownload, onDismiss)
            }
        }
    }
}

@Composable
private fun MeteredNote() {
    Text(
        "You're on mobile data or Data Saver now.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OfferButtons(
    label: String,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onDownload, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text(label, Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        TextButton(onClick = onDismiss, modifier = Modifier.testTag("walkie_transcript_dismiss")) { Text("Dismiss") }
    }
}

/** "44 MB": whole megabytes (10^6 bytes, as a download size is read). */
internal fun mb(bytes: Long): String = "${(bytes + BYTES_PER_MB / 2) / BYTES_PER_MB} MB"

private const val BYTES_PER_MB = 1_000_000L
private val TimeWidth = 64.dp
