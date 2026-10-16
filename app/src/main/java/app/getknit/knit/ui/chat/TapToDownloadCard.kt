package app.getknit.knit.ui.chat

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.ui.preview.KnitPreview

/**
 * The stand-in for a received attachment over the user's "Ask before large Bluetooth downloads" limit (ADR
 * 2026-10.js2p): its name or kind, its declared size, and a tap that lets its fetch ride Bluetooth. Shown only while
 * no bytes are arriving — over Wi-Fi Aware the attachment fetches with no tap, and the bubble's progress ring takes
 * this card's place by itself.
 *
 * One card for a photo, a voice note and a file alike: until the bytes land there is nothing kind-specific to draw,
 * and the label says which it is. [declaredSize] is the sender's claim, the only size there is before the fetch.
 */
@Composable
fun TapToDownloadCard(
    label: String,
    declaredSize: Long,
    onDownload: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val detail = stringResource(R.string.chat_attachment_tap_to_download, Formatter.formatShortFileSize(context, declaredSize))
    val hint = stringResource(R.string.chat_attachment_tap_hint)
    // The card paints its own container, so it carries the matching content colour (FileAttachmentBubble's reason).
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                modifier
                    .testTag("chat_attachment_tap_to_download")
                    .padding(vertical = 2.dp)
                    .width(CARD_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.chat_attachment_download_action),
                        role = Role.Button,
                        onClick = onDownload,
                        onLongClick = onLongClick,
                    ).padding(10.dp)
                    // One node, one sentence — "report.pdf, 4.2 MB · Tap to download, …" — with the click above it.
                    .clearAndSetSemantics { contentDescription = listOf(label, detail, hint).joinToString(", ") },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(ICON_SLOT)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(start = 10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val CARD_WIDTH = 220.dp
private val ICON_SLOT = 36.dp

/** A 4.2 MB file only Bluetooth could bring, waiting for the tap. */
@Preview(showBackground = true)
@Composable
fun TapToDownloadFilePreview() =
    KnitPreview {
        TapToDownloadCard(label = "Survey map.pdf", declaredSize = 4_200_000, onDownload = {}, onLongClick = {})
    }

/** A large photo, named by its kind. */
@Preview(showBackground = true)
@Composable
fun TapToDownloadPhotoPreview() =
    KnitPreview {
        TapToDownloadCard(label = "Photo", declaredSize = 1_800_000, onDownload = {}, onLongClick = {})
    }
