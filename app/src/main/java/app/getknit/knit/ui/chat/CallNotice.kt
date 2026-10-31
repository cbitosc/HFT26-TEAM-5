package app.getknit.knit.ui.chat

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.PhoneDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.data.message.CallOutcome
import app.getknit.knit.data.message.CallRecord
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.ui.preview.KnitPreview
import java.util.Locale

/**
 * The sentence for a call's line, as a string resource id (docs/VOICE_CALLS.md §3.12). Written from this phone's
 * side, like a transfer's: a call the peer turned down reads "They declined your call" here and "You declined a call"
 * on their phone — which is why the chat list puts no "You: " in front of one.
 */
@StringRes
fun callNoticeText(record: CallRecord): Int =
    when (record.outcome) {
        CallOutcome.Completed -> {
            completedText(record)
        }

        CallOutcome.Dropped -> {
            R.string.chat_call_record_dropped
        }

        CallOutcome.Missed -> {
            if (record.walkie) R.string.chat_call_record_missed_mesh else R.string.chat_call_record_missed
        }

        CallOutcome.Busy -> {
            R.string.chat_call_record_busy
        }

        CallOutcome.Declined -> {
            R.string.chat_call_record_declined
        }

        CallOutcome.NoAnswer -> {
            R.string.chat_call_record_no_answer
        }

        CallOutcome.Cancelled -> {
            R.string.chat_call_record_cancelled
        }

        CallOutcome.DeclinedByUs -> {
            R.string.chat_call_record_declined_by_you
        }

        CallOutcome.Unreachable -> {
            R.string.chat_call_record_unreachable
        }

        CallOutcome.Failed -> {
            R.string.chat_call_record_failed
        }
    }

/** A connected call's sentence: its direction, and whether it was a walkie (a "mesh call", as the chat names one). */
@StringRes
private fun completedText(record: CallRecord): Int =
    when {
        record.outgoing && record.walkie -> R.string.chat_call_record_outgoing_mesh
        record.outgoing -> R.string.chat_call_record_outgoing
        record.walkie -> R.string.chat_call_record_incoming_mesh
        else -> R.string.chat_call_record_incoming
    }

/** A connected call's length as a phone app writes it: `m:ss`, or `h:mm:ss` past the hour. */
fun callDuration(ms: Long): String {
    val total = ms / 1_000L
    val hours = total / 3_600L
    val minutes = total % 3_600L / 60L
    val seconds = total % 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** The sentence, with the duration after it for a call that connected. */
fun callSentence(
    context: Context,
    record: CallRecord,
): String {
    val sentence = context.getString(callNoticeText(record))
    val duration = record.durationMs ?: return sentence
    return context.getString(R.string.chat_call_record_with_detail, sentence, callDuration(duration))
}

/**
 * A call row's one-line stand-in in the chat list, or null when its body is not a record this build reads. A call is
 * the second notice kind the list shows, for a transfer's reason: one of the two people did something to the other.
 */
fun callPreview(
    context: Context,
    row: MessageEntity,
): String? = CallRecord.decode(row.body)?.let { callSentence(context, it) }

/**
 * A call's line in the thread: centred and muted like a status notice, with an arrow for its direction (a missed one
 * in the error colour) and the time it was placed, since "when" is half of what a call record is for.
 */
@Composable
fun CallNotice(
    record: CallRecord,
    sentAt: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sentence = callSentence(context, record)
    val time = DateUtils.formatDateTime(context, sentAt, DateUtils.FORMAT_SHOW_TIME)
    val line = stringResource(R.string.chat_call_record_with_detail, sentence, time)
    val missed = record.outcome == CallOutcome.Missed
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = callIcon(record),
            contentDescription = null,
            modifier = Modifier.padding(end = 6.dp).size(16.dp),
            tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = line,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun callIcon(record: CallRecord): ImageVector =
    when (record.outcome) {
        CallOutcome.Missed -> Icons.AutoMirrored.Filled.CallMissed
        CallOutcome.Failed, CallOutcome.Dropped, CallOutcome.Unreachable -> Icons.Filled.PhoneDisabled
        else -> if (record.outgoing) Icons.AutoMirrored.Filled.CallMade else Icons.AutoMirrored.Filled.CallReceived
    }

@Preview(showBackground = true)
@Composable
fun CallNoticePreview() {
    KnitPreview {
        Column {
            CallNotice(
                CallRecord(id = "a", outgoing = true, reason = CallPayload.REASON_USER, connectedAt = 0L, endedAt = 222_000L),
                sentAt = PREVIEW_AT,
            )
            CallNotice(
                CallRecord(id = "d", outgoing = true, reason = CallPayload.REASON_NO_ROUTE, connectedAt = 0L, endedAt = 95_000L),
                sentAt = PREVIEW_AT,
            )
            CallNotice(CallRecord(id = "b", outgoing = false, missed = true), sentAt = PREVIEW_AT)
            CallNotice(CallRecord(id = "c", outgoing = true, reason = CallPayload.REASON_BUSY, byPeer = true), sentAt = PREVIEW_AT)
        }
    }
}

// 2026-10-12 14:02 UTC: the screenshot render pins UTC/en-US, so the line reads the same everywhere.
private const val PREVIEW_AT = 1_791_813_720_000L
