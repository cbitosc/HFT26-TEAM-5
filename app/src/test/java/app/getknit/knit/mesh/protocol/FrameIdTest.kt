package app.getknit.knit.mesh.protocol

import app.getknit.knit.identity.NodeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id shapes every inbound door admits (#178): what [FrameId.new] mints, a profile's `profile-<sender>-<stamp>`, and a
 * walkie chunk's short prefix. Android's database drops a leading U+FEFF from every id it binds and byte-swaps the rest
 * after a leading U+FFFE, so an id the gate lets through must be one no table can confuse with another.
 */
class FrameIdTest {
    private val alice = NodeId.derive("alice")
    private val bob = NodeId.derive("bob")

    private fun env(
        id: String,
        type: String = FrameType.CHAT,
        senderId: String = alice,
        sentAt: Long = 1_700_000_000_000L,
    ) = RelayEnvelope(type = type, id = id, senderId = senderId, sentAt = sentAt, payload = ByteArray(0))

    @Test
    fun `every id the app mints is admitted`() {
        assertTrue(FrameId.admits(env(FrameId.new())))
        assertTrue(FrameId.admits(env(FrameId.forMeshPost(0x1234abcd, 9911))))
        assertTrue(FrameId.admits(env(FrameId.new(), type = FrameType.RECEIPT)))
        assertTrue(FrameId.admits(env(FrameId.new().take(FrameId.TALK_LENGTH), type = FrameType.TALK)))
        val stamp = 1_700_000_000_123L
        assertTrue(FrameId.admits(env(FrameId.profileId(alice, stamp), type = FrameType.PROFILE, sentAt = stamp)))
    }

    @Test
    fun `a profile id is its sender and stamp spelled out, so a stamp of zero or below is admitted too`() {
        assertEquals("profile-$alice-42", FrameId.profileId(alice, 42))
        assertTrue(FrameId.admits(env("profile-$alice-0", type = FrameType.PROFILE, sentAt = 0)))
        assertTrue(FrameId.admits(env("profile-$alice--5", type = FrameType.PROFILE, sentAt = -5)))
    }

    @Test
    fun `an unknown type is held to the minted shape`() {
        assertTrue(FrameId.admits(env(FrameId.new(), type = "future")))
        assertFalse(FrameId.admits(env("m1", type = "future")))
    }

    @Test
    fun `a leading byte-order mark or its swapped twin is refused on every type`() {
        val id = FrameId.new()
        val swapped = id.map { Char(((it.code and 0xFF) shl 8) or (it.code ushr 8)) }.joinToString("")
        for (crafted in listOf("﻿$id", "￾$swapped", "﻿" + id.drop(1))) {
            assertFalse(crafted, FrameId.admits(env(crafted)))
        }
        val talk = id.take(FrameId.TALK_LENGTH)
        assertFalse(FrameId.admits(env("﻿" + talk.drop(1), type = FrameType.TALK)))
        val profile = "﻿" + FrameId.profileId(alice, 7)
        assertFalse(FrameId.admits(env(profile, type = FrameType.PROFILE, sentAt = 7)))
    }

    @Test
    fun `a frame id must round-trip, not merely look like one`() {
        val id = FrameId.new()
        assertFalse(FrameId.admits(env(id.dropLast(1))))
        assertFalse(FrameId.admits(env(id + "A")))
        assertFalse(FrameId.admits(env("$id==")))
        assertFalse(FrameId.admits(env("+" + id.drop(1))))
        assertFalse(FrameId.admits(env("/" + id.drop(1))))
        // The last character of a 16-byte id carries two padding bits, which must be zero: only A, Q, g and w round-trip.
        val loose = id.dropLast(1) + "B"
        assertFalse(FrameId.admits(env(loose)))
        assertFalse(FrameId.admits(env("")))
    }

    @Test
    fun `a profile id is bound to its sender and stamp`() {
        val stamp = 1_700_000_000_123L
        assertFalse(FrameId.admits(env(FrameId.profileId(bob, stamp), type = FrameType.PROFILE, sentAt = stamp)))
        assertFalse(FrameId.admits(env(FrameId.profileId(alice, stamp + 1), type = FrameType.PROFILE, sentAt = stamp)))
        assertFalse(FrameId.admits(env("profile-$alice-007", type = FrameType.PROFILE, sentAt = 7)))
        assertFalse(FrameId.admits(env("profile-$alice", type = FrameType.PROFILE, sentAt = 0)))
        assertFalse(FrameId.admits(env(FrameId.new(), type = FrameType.PROFILE)))
        // A sender that is not a node id cannot have a profile id, whatever the id spells.
        assertFalse(FrameId.admits(env("profile-alice-7", type = FrameType.PROFILE, senderId = "alice", sentAt = 7)))
        val upper = alice.uppercase()
        assertFalse(FrameId.admits(env("profile-$upper-7", type = FrameType.PROFILE, senderId = upper, sentAt = 7)))
    }

    @Test
    fun `a talk id is exactly the short prefix`() {
        val id = FrameId.new()
        assertFalse(FrameId.admits(env(id, type = FrameType.TALK)))
        assertFalse(FrameId.admits(env(id.take(FrameId.TALK_LENGTH + 1), type = FrameType.TALK)))
        assertFalse(FrameId.admits(env(id.take(FrameId.TALK_LENGTH - 1), type = FrameType.TALK)))
        assertFalse(FrameId.admits(env("abc.efgh+jk", type = FrameType.TALK)))
        // The short prefix is a talk id only: on any other type it is refused.
        assertFalse(FrameId.admits(env(id.take(FrameId.TALK_LENGTH))))
    }
}
