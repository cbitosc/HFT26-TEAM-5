package app.getknit.knit

import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.wifiaware.NanCueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [NanCueCodec] — the pure coordination-plane cue / SSI-digest codec. */
class NanCueCodecTest {
    @Test
    fun encodeParseCueRoundTrips() {
        val cue = NanCueCodec.parseCue(NanCueCodec.encodeCue(ID, 42L))
        assertEquals(NanCueCodec.Cue(ID, 42L), cue)
    }

    @Test
    fun aCueWithASecondSeparatorNamesNoPeer() {
        // A canonical node id never holds a separator, and a version that does is not a number (#199).
        assertNull(NanCueCodec.parseCue("a|$ID|42".encodeToByteArray()))
        assertNull(NanCueCodec.parseCue("$ID|a|42".encodeToByteArray()))
    }

    @Test
    fun parseCueRejectsMalformedPayloads() {
        assertNull("empty bytes", NanCueCodec.parseCue(ByteArray(0)))
        assertNull("no separator", NanCueCodec.parseCue("nosep".encodeToByteArray()))
        assertNull("separator at 0 (empty nodeId)", NanCueCodec.parseCue("|42".encodeToByteArray()))
        assertNull("non-numeric version", NanCueCodec.parseCue("$ID|abc".encodeToByteArray()))
        assertNull("malformed bytes decode to a separatorless string", NanCueCodec.parseCue(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun aCueWhoseNodeIdIsNotCanonicalNamesNoPeer() {
        // #199: SQLite drops a leading U+FEFF from a UTF-16 bind, so a marked twin noted reachable apart from the
        // real id would still share its peers row.
        assertNull("BOM-led", NanCueCodec.parseCue("\uFEFF$ID|42".encodeToByteArray()))
        assertNull("uppercase", NanCueCodec.parseCue("${ID.uppercase()}|42".encodeToByteArray()))
        assertNull("legacy 8-char id", NanCueCodec.parseCue("node8id0|42".encodeToByteArray()))
    }

    @Test
    fun encodeParseSsiDigestRoundTrips() {
        for (v in listOf(0L, 1L, 42L, Long.MAX_VALUE)) {
            assertEquals(v, NanCueCodec.parseSsiDigest(NanCueCodec.encodeSsiDigest(v)))
        }
    }

    @Test
    fun parseSsiDigestFindsTheTrailingSegmentInAFullAdvert() {
        // The digest rides as the fourth |-segment of a many-|-separated publish SSI; parsing must pick the
        // trailing |d segment out of the whole advert string.
        val ssi = "knit|3|node8id0" + NanCueCodec.encodeSsiDigest(777L)
        assertEquals(777L, NanCueCodec.parseSsiDigest(ssi))
    }

    @Test
    fun parseSsiDigestPicksTheLastPrefixOccurrence() {
        assertEquals(2L, NanCueCodec.parseSsiDigest("a|d1|d2"))
    }

    @Test
    fun parseSsiDigestRejectsMissingOrNonNumericSegment() {
        assertNull("no |d segment (older build)", NanCueCodec.parseSsiDigest("knit|3|node8id0"))
        assertNull("non-numeric digest", NanCueCodec.parseSsiDigest("knit|dabc"))
    }

    private companion object {
        val ID = NodeId.derive("nan-cue-test")
    }
}
