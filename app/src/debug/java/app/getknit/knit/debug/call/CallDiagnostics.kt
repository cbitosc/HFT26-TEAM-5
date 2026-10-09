package app.getknit.knit.debug.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.getknit.knit.call.WalkiePolicy
import app.getknit.knit.call.WalkieStats
import java.util.Locale

/**
 * The call screen's diagnostics card (experimental, debug only): the live numbers of the media — round trip, jitter,
 * loss, both bitrates, the jitter buffer — over the path, the codec, the DTLS-SRTP suite and the ICE candidates. Drawn
 * for demos and for a tester's eye; nothing here is a call decision.
 */
@Composable
internal fun CallDiagnostics(
    stats: CallStats,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Call diagnostics", "LIVE")
            Row(Modifier.fillMaxWidth()) {
                Metric("Round trip", ms(stats.rttMs), Modifier.weight(1f))
                Metric("Jitter", ms(stats.jitterMs), Modifier.weight(1f))
                Metric("Loss", percent(stats.lossPercent), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("↑ Send", kbps(stats.sendKbps), Modifier.weight(1f))
                Metric("↓ Receive", kbps(stats.receiveKbps), Modifier.weight(1f))
                Metric("Buffer", ms(stats.jitterBufferMs), Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Path", pathOf(stats))
                Detail("Codec", codecOf(stats))
                Detail("DTLS", dtlsOf(stats))
                Detail("SRTP", stats.srtpCipher ?: NONE)
                Detail("ICE", "${stats.localCandidates} local · ${stats.remoteCandidates} remote candidates")
            }
        }
    }
}

/**
 * The diagnostics card of a walkie session (§3.11), laid out as [CallDiagnostics] is: the chunks' transit, jitter and
 * loss, the frames' rate each way over the latest burst and the player's start-up buffer, over the path a chunk takes,
 * the codec, the chunks and the frames. Transit is read off two phones' clocks; the rest is not.
 */
@Composable
internal fun WalkieDiagnostics(
    stats: WalkieStats,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Walkie diagnostics", "MESH")
            Row(Modifier.fillMaxWidth()) {
                Metric("Transit", ms(stats.transitMs), Modifier.weight(1f))
                Metric("Jitter", ms(stats.jitterMs), Modifier.weight(1f))
                Metric("Loss", percent(stats.lossPercent), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Metric("↑ Send", kbps(stats.sendKbps), Modifier.weight(1f))
                Metric("↓ Receive", kbps(stats.receiveKbps), Modifier.weight(1f))
                Metric("Buffer", ms(stats.bufferMs), Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail("Path", stats.path ?: NONE)
                Detail("Codec", WALKIE_CODEC)
                Detail("Chunks", chunksOf(stats))
                Detail("Frame", framesOf(stats))
                Detail("Bursts", "${stats.burstsOut} sent · ${stats.burstsIn} heard")
            }
        }
    }
}

/** The card's title, with its live marker at the end. */
@Composable
private fun Title(
    title: String,
    marker: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Outlined.Insights,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Text(marker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Detail(
    label: String,
    value: String,
) {
    Row(Modifier.clearAndSetSemantics { contentDescription = "$label: $value" }) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DetailLabelWidth = 56.dp
private const val NONE = "—"
private const val FINE_BELOW = 10.0
private const val HZ_PER_KHZ = 1_000
private const val BPS_PER_KBPS = 1_000
private const val WALKIE_PACKET_MS = 20

/** "Opus · 8 kbps · 16 kHz · mono · 20 ms": what a walkie burst is recorded as (C-3.11-5). */
private val WALKIE_CODEC =
    "Opus · ${WalkiePolicy.BIT_RATE / BPS_PER_KBPS} kbps · ${WalkiePolicy.SAMPLE_RATE / HZ_PER_KHZ} kHz · mono · $WALKIE_PACKET_MS ms"

/** "480 ms · 42 out, 1 failed · 40 in, 2 lost": the chunks' length and how many went and came. */
internal fun chunksOf(stats: WalkieStats): String {
    val out = "${stats.chunksOut} out" + if (stats.chunksFailed > 0) ", ${stats.chunksFailed} failed" else ""
    val inbound = "${stats.chunksIn} in" + if (stats.chunksLost > 0) ", ${stats.chunksLost} lost" else ""
    return "${WalkiePolicy.CHUNK_MS} ms · $out · $inbound"
}

/** "712 B out · 708 B in · handed over in 12 ms": the average frame each way, and how long the radio took one. */
internal fun framesOf(stats: WalkieStats): String =
    listOfNotNull(
        stats.frameOut?.let { "$it B out" },
        stats.frameIn?.let { "$it B in" },
        stats.handMs?.let { "handed over in ${ms(it)}" },
    ).joinToString(" · ").ifEmpty { NONE }

internal fun ms(value: Double?): String = value?.let { "${decimal(it)} ms" } ?: NONE

internal fun kbps(value: Double?): String = value?.let { "${decimal(it)} kbps" } ?: NONE

internal fun percent(value: Double?): String = value?.let { "%.1f %%".format(Locale.ROOT, it) } ?: NONE

/** One decimal under ten, whole numbers above: "2.4 ms", "48 ms". */
private fun decimal(value: Double): String =
    if (value <
        FINE_BELOW
    ) {
        "%.1f".format(Locale.ROOT, value)
    } else {
        "%.0f".format(Locale.ROOT, value)
    }

/** "relay (TURN/UDP) ↔ srflx · UDP · Wi-Fi": the pair's types, its transport and the local network's kind. */
internal fun pathOf(stats: CallStats): String {
    val local = stats.localType ?: return NONE
    val turn = stats.relayProtocol?.let { " (TURN/${it.uppercase(Locale.ROOT)})" }.orEmpty()
    val network =
        when {
            stats.wifiDirect -> "Wi-Fi Direct"
            stats.networkType == "wifi" -> "Wi-Fi"
            stats.networkType == "cellular" -> "Cellular"
            stats.networkType == "ethernet" -> "Ethernet"
            stats.networkType == "vpn" -> "VPN"
            else -> stats.networkType
        }
    return listOfNotNull("$local$turn ↔ ${stats.remoteType ?: "?"}", stats.protocol?.uppercase(Locale.ROOT), network)
        .joinToString(" · ")
}

/** "Opus · 48 kHz · 2 ch · FEC". */
internal fun codecOf(stats: CallStats): String {
    val name = stats.codec ?: return NONE
    return listOfNotNull(
        name.replaceFirstChar { it.titlecase(Locale.ROOT) },
        stats.clockRate?.let { "${it / HZ_PER_KHZ} kHz" },
        stats.channels?.let { "$it ch" },
        "FEC".takeIf { stats.fec },
    ).joinToString(" · ")
}

/** "1.2 · ECDHE-ECDSA / AES-128-GCM-SHA256": the version and the suite, shortened the way OpenSSL spells it. */
internal fun dtlsOf(stats: CallStats): String {
    val suite =
        stats.dtlsCipher
            ?.removePrefix("TLS_")
            ?.replace("_WITH_", " / ")
            ?.replace('_', '-')
    return listOfNotNull(stats.dtlsVersion, suite).joinToString(" · ").ifEmpty { NONE }
}
