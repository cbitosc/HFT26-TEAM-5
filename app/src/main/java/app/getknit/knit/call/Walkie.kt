package app.getknit.knit.call

import app.getknit.knit.data.VoiceAudio

/** How [CallManager.start] places a call (§3.11): [AUTO] picks; [LIVE] and [WALKIE] force one (the debug bridge). */
enum class CallKind { AUTO, LIVE, WALKIE }

/**
 * The walkie-talkie's seam into [CallManager] (docs/VOICE_CALLS.md §3.11, ADR 2026-10.79yb): whether this build runs
 * one at all, whether a peer does (`Protocol.CAP_WALKIE`), and whether a live call to it looks possible. Late-bound
 * over the mesh like [CallDirect]; [None] is a build without the walkie, whose calls behave exactly as before.
 */
interface CallWalkie {
    val enabled: Boolean

    suspend fun capable(peerId: String): Boolean

    /**
     * Whether a live call to [peerId] could connect: an Internet route here, or a Wi-Fi Direct leg to a neighbour. A
     * guess either way — a live call that turns out to have no path still drops to a walkie (§3.11).
     */
    suspend fun liveLikely(peerId: String): Boolean

    object None : CallWalkie {
        override val enabled = false

        override suspend fun capable(peerId: String) = false

        override suspend fun liveLikely(peerId: String) = true
    }
}

/** One stored message as [WalkiePolicy] reads it: the receiver's [arrivedAt], the sender's [sentAt]. */
data class BurstRow(
    val id: String,
    val senderId: String,
    val mime: String?,
    val name: String?,
    // Set once the clip is held and described, so it can play now.
    val durationMs: Int?,
    val sentAt: Long,
    val arrivedAt: Long,
)

/**
 * What a walkie session does with each message its thread shows (§3.11). A burst is an ordinary voice note; this
 * decides which ones play by themselves. Pure, so every clause is pinned by `WalkiePolicyTest`.
 */
object WalkiePolicy {
    /** A burst's AAC rate: half a voice note's, so a minute stays under the bulk-link threshold (128 KiB). */
    const val BIT_RATE = 16_000

    /** Shorter than this is a slipped thumb, not a burst: nothing is sent. */
    const val MIN_BURST_MS = 500L

    /** A held button is released for the user after this long. */
    const val MAX_BURST_MS = 60_000L

    /** A burst that spent longer than this between its sender and our store (custody, a far hop) is history. */
    const val MAX_LAG_MS = 60_000L

    /** A burst whose clip took longer than this to arrive after its frame is history too. */
    const val MAX_WAIT_MS = 90_000L

    /** A session with no burst either way for this long hangs up on its own. */
    const val IDLE_END_MS = 10 * 60_000L

    enum class Verdict {
        // Plays now.
        PLAY,

        // A burst for this session whose clip is still on its way.
        WAIT,

        // A burst for this session that came too late to play: it stays a voice note in the thread.
        STALE,

        // Not a burst of this session: ours, someone else's, not a voice note, older than the session, or played.
        NONE,
    }

    /**
     * [row]'s fate in the session with [peerId] that connected at [connectedAt]; [now], [connectedAt] and
     * `arrivedAt` are this phone's clock, so only the lag reads the sender's.
     */
    @Suppress("LongParameterList") // the rule's whole input, primitive on purpose
    fun verdict(
        row: BurstRow,
        peerId: String,
        me: String,
        connectedAt: Long,
        now: Long,
        played: Set<String>,
    ): Verdict =
        when {
            row.senderId != peerId || row.senderId == me -> Verdict.NONE

            // A named audio file is a file (AttachmentKind's order), never a burst.
            row.name != null || !VoiceAudio.isVoice(row.mime) -> Verdict.NONE

            row.arrivedAt < connectedAt || row.id in played -> Verdict.NONE

            row.arrivedAt - row.sentAt > MAX_LAG_MS || now - row.arrivedAt > MAX_WAIT_MS -> Verdict.STALE

            row.durationMs == null -> Verdict.WAIT

            else -> Verdict.PLAY
        }
}
