package app.getknit.knit.call

import app.getknit.knit.call.WalkiePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of a walkie session's messages play by themselves (docs/VOICE_CALLS.md §3.11): one case per clause. */
class WalkiePolicyTest {
    private fun burst(
        id: String = "m1",
        senderId: String = PEER,
        mime: String? = "audio/aac",
        name: String? = null,
        durationMs: Int? = 2_400,
        sentAt: Long = NOW - 5_000,
        arrivedAt: Long = NOW - 2_000,
    ) = BurstRow(id, senderId, mime, name, durationMs, sentAt, arrivedAt)

    private fun verdict(
        row: BurstRow,
        played: Set<String> = emptySet(),
        connectedAt: Long = CONNECTED,
    ) = WalkiePolicy.verdict(row, peerId = PEER, me = ME, connectedAt = connectedAt, now = NOW, played = played)

    @Test
    fun aFreshHeldBurstFromThePeerPlays() = assertEquals(Verdict.PLAY, verdict(burst()))

    @Test
    fun oneWhoseClipIsStillComingWaits() = assertEquals(Verdict.WAIT, verdict(burst(durationMs = null)))

    @Test
    fun oursAndAnyoneElsesAreNotBursts() {
        assertEquals(Verdict.NONE, verdict(burst(senderId = ME)))
        assertEquals(Verdict.NONE, verdict(burst(senderId = "someone-else")))
    }

    @Test
    fun onlyAnUnnamedVoiceNoteIsABurst() {
        assertEquals(Verdict.NONE, verdict(burst(mime = "image/jpeg")))
        assertEquals(Verdict.NONE, verdict(burst(mime = null)))
        // A named audio file is a file, as the thread draws it.
        assertEquals(Verdict.NONE, verdict(burst(name = "song.mp3", mime = "audio/mpeg")))
    }

    @Test
    fun whatArrivedBeforeTheSessionOrAlreadyPlayedIsLeftAlone() {
        val connected = NOW - 10_000
        assertEquals(Verdict.NONE, verdict(burst(arrivedAt = connected - 1, sentAt = connected - 2), connectedAt = connected))
        assertEquals(Verdict.PLAY, verdict(burst(arrivedAt = connected, sentAt = connected - 2), connectedAt = connected))
        assertEquals(Verdict.NONE, verdict(burst(), played = setOf("m1")))
    }

    @Test
    fun aBurstThatSatTooLongOnTheWayIsStale() {
        val arrived = NOW - 1_000
        assertEquals(Verdict.PLAY, verdict(burst(sentAt = arrived - WalkiePolicy.MAX_LAG_MS, arrivedAt = arrived)))
        assertEquals(Verdict.STALE, verdict(burst(sentAt = arrived - WalkiePolicy.MAX_LAG_MS - 1, arrivedAt = arrived)))
        // Stale whether or not its clip is in yet: it will never play.
        assertEquals(Verdict.STALE, verdict(burst(sentAt = arrived - WalkiePolicy.MAX_LAG_MS - 1, arrivedAt = arrived, durationMs = null)))
    }

    @Test
    fun aSenderClockAheadOfOursIsNoLag() = assertEquals(Verdict.PLAY, verdict(burst(sentAt = NOW + 30_000)))

    @Test
    fun aClipThatTookTooLongToArriveIsStale() {
        val arrived = NOW - WalkiePolicy.MAX_WAIT_MS
        assertEquals(Verdict.PLAY, verdict(burst(sentAt = arrived, arrivedAt = arrived)))
        assertEquals(Verdict.STALE, verdict(burst(sentAt = arrived - 1, arrivedAt = arrived - 1)))
    }

    private companion object {
        const val PEER = "peer"
        const val ME = "me"
        const val NOW = 1_800_000_000_000L
        const val CONNECTED = NOW - 300_000
    }
}
