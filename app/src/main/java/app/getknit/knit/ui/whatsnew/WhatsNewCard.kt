package app.getknit.knit.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The chat list's announcement of [entry], the first item in the list rather than the pinned banner band:
 * the band is for states the user has to act on, and this is news. Both the close button and "See what's
 * new" mark the entry seen; left alone it retires itself ([WhatsNewPolicy]).
 */
@Composable
fun WhatsNewCard(
    entry: WhatsNewEntry,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { testTag = "chatlist_whats_new" },
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.whats_new_card_eyebrow, entry.version),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).semantics { testTag = "chatlist_whats_new_dismiss" },
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.whats_new_card_dismiss))
                }
            }
            Column(modifier = Modifier.padding(end = 12.dp)) {
                Text(
                    text = stringResource(entry.headline),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(entry.summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(
                        onClick = onOpen,
                        modifier = Modifier.semantics { testTag = "chatlist_whats_new_open" },
                    ) {
                        Text(stringResource(R.string.whats_new_card_open))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WhatsNewCardPreview() =
    KnitPreview {
        WhatsNewCard(entry = WhatsNew.entries.first(), onOpen = {}, onDismiss = {})
    }
