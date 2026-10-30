package app.getknit.knit.ui.components

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.getknit.knit.R
import app.getknit.knit.badge.Badges
import app.getknit.knit.badge.VerifiedBadge
import app.getknit.knit.ui.preview.KnitPreview

/**
 * How a badge id draws (docs/SUPPORTER_BADGE.md §7, ADR 2026-10.r99s): its artwork and its one-word name. Keyed by id,
 * so a future badge is a row here; an id with no row is never drawn, which the verifier already guarantees.
 */
object BadgeVisuals {
    /**
     * [badge]'s artwork for a mark [markSize] across, drawn in its own colours (C-7-4). The supporter badge is the
     * Yarnworld logo, and below [SMALL_MARK] its simplified form: the full logo's stitching and islands blur there.
     */
    @DrawableRes
    fun iconFor(
        badge: String,
        markSize: Dp,
    ): Int? =
        when (badge) {
            Badges.SUPPORTER -> if (markSize < SMALL_MARK) R.drawable.ic_badge_supporter_small else R.drawable.ic_badge_supporter
            else -> null
        }

    @StringRes
    fun labelFor(badge: String): Int? =
        when (badge) {
            Badges.SUPPORTER -> R.string.badge_supporter
            else -> null
        }

    /** The mark's accessible name, which says what it is rather than repeating the pill's one word. */
    @StringRes
    fun markLabelFor(badge: String): Int? =
        when (badge) {
            Badges.SUPPORTER -> R.string.badge_supporter_mark
            else -> null
        }
}

/**
 * Every verified badge by node id ([app.getknit.knit.badge.PeerBadges.shown]), provided once at the app's root. Empty in
 * previews and tests, so a screen draws no mark unless one is handed down.
 */
val LocalPeerBadges = compositionLocalOf<Map<String, List<VerifiedBadge>>> { emptyMap() }

/** The first badge [nodeId] shows, by its own order, or null: what an avatar passes as its `badge`. */
@Composable
fun peerBadge(nodeId: String?): String? = nodeId?.let { LocalPeerBadges.current[it]?.firstOrNull()?.b }

/**
 * The small mark a badge puts on an avatar of diameter [avatarSize]: the badge's artwork, untinted, ringed in the
 * surface colour, so it reads against a photo and against the tinted initial alike, in light and dark. Sized to the
 * avatar, with a floor that keeps the 36 dp chat header's mark legible. Tappable only when [onClick] is set.
 */
@Composable
fun BadgeMark(
    badge: String,
    avatarSize: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val size = markSize(avatarSize)
    val icon = BadgeVisuals.iconFor(badge, size) ?: return
    val label = BadgeVisuals.markLabelFor(badge)?.let { stringResource(it) }
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                // The surface shows as a ring round the inset artwork, parting it from the avatar.
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).semantics { label?.let { contentDescription = it } },
        contentAlignment = Alignment.Center,
    ) {
        // A logo in its own colours, never a tinted glyph: no theme or wallpaper recolours it.
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size - RING * 2),
        )
    }
}

/** The mark's diameter for an avatar of [avatarSize]: about a third of it, never under 14 dp nor over 28 dp. */
internal fun markSize(avatarSize: Dp): Dp = (avatarSize * MARK_FRACTION).coerceIn(MIN_MARK, MAX_MARK)

private const val MARK_FRACTION = 0.36f
private val MIN_MARK = 14.dp

// A mark narrower than this draws the badge's simplified artwork: avatars under about 44 dp, and the profile pill.
private val SMALL_MARK = 16.dp
private val MAX_MARK = 28.dp
private val RING = 1.5.dp

/** Every avatar size the mark rides, with and without it, light and dark (docs/SUPPORTER_BADGE.md §7). */
@Preview(showBackground = true)
@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AvatarBadgeSizesPreview() =
    KnitPreview {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp)) {
            for (size in listOf(30.dp, 36.dp, 40.dp, 48.dp, 52.dp, 96.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(avatarHash = null, name = "Ada", nodeId = "ada", size = size)
                    Avatar(avatarHash = null, name = "Ada", nodeId = "ada", size = size, badge = Badges.SUPPORTER)
                }
            }
        }
    }
