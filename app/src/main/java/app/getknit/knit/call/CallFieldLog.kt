package app.getknit.knit.call

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.protocol.CallPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/*
 * What the calls write into the field log (ADR 2026-10.a239's 2026-10-18 amendment, docs/FIELD_LOG.md): the field log
 * takes no strings, so every word a call's code or libwebrtc spells is turned into one of the constants below first.
 */

/** Why a call ended, as the field log names it: [CallPayload]'s reasons, and the offers that never became a call here. */
enum class CallEndReason {
    USER,
    BUSY,
    TIMEOUT,
    NO_ROUTE,
    MEDIA,
    ERROR,

    /** An offer of a kind the person switched off (C-3.3-7), answered "no answer" without ringing. */
    OFF,

    /** An offer that never rang: outside `sentAt ± 60 s` (C-3.4-1), or after its call ended (C-3.4-3). */
    STALE,

    /** Our own offer, dropped without a word for the peer's that crossed it (C-3.5-1); the peer's goes on as the call. */
    GLARE,
    OTHER,
    ;

    companion object {
        fun of(rsn: Int?): CallEndReason =
            when (rsn) {
                CallPayload.REASON_USER -> USER
                CallPayload.REASON_BUSY -> BUSY
                CallPayload.REASON_TIMEOUT -> TIMEOUT
                CallPayload.REASON_NO_ROUTE -> NO_ROUTE
                CallPayload.REASON_MEDIA -> MEDIA
                CallPayload.REASON_ERROR -> ERROR
                else -> OTHER
            }
    }
}

/** An ICE candidate's type (RFC 8445), from a `candidate:` line's `typ` or a stats report's `candidateType`. */
enum class IceType {
    HOST,
    SRFLX,
    PRFLX,
    RELAY,
    OTHER,
    ;

    companion object {
        /** Null for none; [OTHER] for a type this build does not know. */
        fun of(type: String?): IceType? =
            type?.let { t ->
                entries.firstOrNull { it != OTHER && it.name.equals(t, ignoreCase = true) }
                    ?: OTHER
            }
    }
}

/** A candidate's transport, or a relay candidate's leg to its TURN server. */
enum class IceProtocol {
    UDP,
    TCP,
    TLS,
    OTHER,
    ;

    companion object {
        fun of(protocol: String?): IceProtocol? =
            protocol?.let { p -> entries.firstOrNull { it != OTHER && it.name.equals(p, ignoreCase = true) } ?: OTHER }

        /** The transport a `candidate:` line names: its third field (`candidate:1 1 udp 2122260223 …`). */
        fun ofLine(line: String): IceProtocol? = of(line.removePrefix("a=").split(' ').getOrNull(2))
    }
}

/**
 * The network a candidate sits on, from a stats report's `networkType` (`wifi`, `cellular`, …) or libwebrtc's adapter
 * type by name (`WIFI`, `CELLULAR_4G`, …). The call's Wi-Fi Direct group reports as [ETHERNET] (§3.10).
 */
enum class IceNetwork {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    LOOPBACK,
    BLUETOOTH,
    UNKNOWN,
    OTHER,
    ;

    companion object {
        fun of(name: String?): IceNetwork? {
            val n = name?.lowercase() ?: return null
            return when {
                n.startsWith("cellular") -> CELLULAR
                n == "unknown" || n == "adapter_type_any" -> UNKNOWN
                else -> entries.firstOrNull { it != OTHER && it.name.equals(n, ignoreCase = true) } ?: OTHER
            }
        }
    }
}

/** How a call's Wi-Fi Direct leg ended (§3.10), for [FieldEvent.CallDirectLeg]. */
enum class DirectLegEnd {
    /** The call ended, or moved off the leg, and gave the radio back. */
    CALL,

    /** The peer's end said bye down the lane. */
    BYE,

    /** The lane closed under it, or a read failed. */
    CLOSED,

    /** The lane heard nothing for its silence window. */
    SILENT,

    /** The host listened its window out and no joiner's lane came. */
    NO_LANE,

    /** Wi-Fi Direct refused or failed: the group never came up, or a join gave up. */
    RADIO,

    /** The lane could not be dialled, or its handshake failed. */
    LANE_FAILED,
}

/** A walkie session's totals for the field log, rounded to whole milliseconds. */
fun WalkieStats.fieldEvent(): FieldEvent.WalkieEnd =
    FieldEvent.WalkieEnd(
        burstsOut = burstsOut,
        burstsIn = burstsIn,
        chunksOut = chunksOut,
        chunksFailed = chunksFailed,
        chunksIn = chunksIn,
        chunksLost = chunksLost,
        transitMs = transitMs?.roundToLong(),
        jitterMs = jitterMs?.roundToLong(),
        handMs = handMs?.roundToLong(),
        frameOut = frameOut,
        frameIn = frameIn,
    )

/**
 * Writes a live call's media into the field log while it is up (ADR 2026-10.a239's amendment): one reading [firstMs]
 * after the media connects, then one every [everyMs], through a reconnect too, until the call ends or drops to a
 * walkie. [read] is the media's own reading (`WebRtcCallMedia.fieldSample`), on a stats reader of its own so the call
 * screen's card keeps its own deltas, and null where there is none; one that fails costs that reading only.
 */
class CallMediaSampler(
    private val calls: StateFlow<CallState?>,
    private val read: suspend (reconnecting: Boolean) -> FieldEvent?,
    private val field: FieldRecorder,
    private val scope: CoroutineScope,
    private val firstMs: Long = FIRST_MS,
    private val everyMs: Long = EVERY_MS,
    private val log: (String) -> Unit = {},
) {
    fun start(): Job =
        scope.launch {
            calls
                .map { it?.takeIf { s -> !s.walkie && s.phase in MEDIA_UP }?.id }
                .distinctUntilChanged()
                .collectLatest { id -> if (id != null) sample() }
        }

    private suspend fun sample() {
        delay(firstMs)
        while (true) {
            val reconnecting = calls.value?.phase == CallPhase.Reconnecting
            try {
                read(reconnecting)?.let(field::record)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a reading never ends the call or the sampling
            ) {
                // That reading is lost; the next one comes on time.
                log("call media reading failed: ${e.javaClass.simpleName}")
            }
            delay(everyMs)
        }
    }

    companion object {
        /** The first reading: the pair is chosen and the first RTCP is in by now. */
        const val FIRST_MS = 5_000L

        /** Every reading after it: a short call still gets a few, an hour of calling about 120 lines. */
        const val EVERY_MS = 30_000L

        private val MEDIA_UP = setOf(CallPhase.Connected, CallPhase.Reconnecting)
    }
}
