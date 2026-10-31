package app.getknit.knit.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.getknit.knit.emergency.EmergencyKind
import app.getknit.knit.emergency.EmergencyPayload
import app.getknit.knit.emergency.EmergencySeverity
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Readable presentation for an emergency payload already admitted by the authenticated projection. */
@Composable
internal fun EmergencyMessageCard(
    payload: EmergencyPayload,
    senderName: String,
    modifier: Modifier = Modifier,
) {
    val accent =
        when (payload.severity) {
            EmergencySeverity.CRITICAL -> MaterialTheme.colorScheme.error
            EmergencySeverity.URGENT -> MaterialTheme.colorScheme.tertiary
            EmergencySeverity.ASSISTANCE, null -> MaterialTheme.colorScheme.primary
        }
    Column(
        modifier = modifier.fillMaxWidth().testTag("chat_emergency_card"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = if (payload.kind == EmergencyKind.SOS) Icons.Filled.Warning else Icons.Filled.Lock,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = if (payload.kind == EmergencyKind.SOS) "Authenticated SOS" else "Authenticated acknowledgement",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        HorizontalDivider(color = accent.copy(alpha = 0.35f))
        Text("Sender: $senderName", style = MaterialTheme.typography.labelLarge)
        payload.severity?.let { severity ->
            Text("Severity: ${severity.label}", style = MaterialTheme.typography.labelLarge, color = accent)
        }
        Text("Emergency message", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(payload.message, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Timestamp: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(payload.createdAt))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        payload.location?.let { location ->
            Spacer(Modifier.height(2.dp))
            Text("Location shared by sender", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(
                String.format(
                    Locale.US,
                    "%.5f, %.5f · approximately ±%d m",
                    location.latitude,
                    location.longitude,
                    location.accuracyMeters.toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private val EmergencySeverity.label: String
    get() =
        when (this) {
            EmergencySeverity.CRITICAL -> "Critical"
            EmergencySeverity.URGENT -> "Urgent"
            EmergencySeverity.ASSISTANCE -> "Assistance"
        }
