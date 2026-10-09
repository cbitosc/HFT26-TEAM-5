package app.getknit.knit.call

import java.io.ByteArrayOutputStream
import java.util.TreeMap

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

/**
 * Where a walkie chunk to a peer would go now (§3.11), for the call screen's notice: over a live link of this phone's
 * own ([DIRECT]); through other phones, with recent evidence a path exists ([MESH]: the peer's own frames reached us
 * through one); or nowhere this phone can see ([NONE]).
 */
enum class TalkLink {
    DIRECT,
    MESH,
    NONE,
    ;

    companion object {
        /** [peerId]'s link, given this phone's live [neighbours] and the peers [heardIndirectly] through one of them. */
        fun of(
            peerId: String,
            neighbours: Set<String>,
            heardIndirectly: Set<String>,
        ): TalkLink =
            when {
                peerId in neighbours -> DIRECT
                neighbours.isNotEmpty() && peerId in heardIndirectly -> MESH
                else -> NONE
            }
    }
}

/** A walkie session's numbers (§3.11). */
object WalkiePolicy {
    /** Opus's rate for a burst: speech at about 1 KB/s, so a burst fits Coded PHY's 2.1 KB/s with its framing. */
    const val BIT_RATE = 8_000

    /** What the microphone is captured at: Opus's wideband, enough for speech. */
    const val SAMPLE_RATE = 16_000

    /** A held button is released for the user after this long. */
    const val MAX_BURST_MS = 60_000L

    /** A session with no burst either way for this long hangs up on its own. */
    const val IDLE_END_MS = 10 * 60_000L

    /**
     * How often a walkie call asks for a Wi-Fi Aware link to its peer, from the ring to the hang-up: inside the radio's
     * 25 s bulk-want window, so the ask never lapses while the call lasts.
     */
    const val LINK_ASK_MS = 10_000L

    /** A chunk goes out once this much audio is in hand: 24 Opus packets, about 0.5 KB before its framing. */
    const val CHUNK_MS = 480L

    /** The player starts a burst once it holds two chunks, or its last, or its first has waited this long. */
    const val PREBUFFER_MS = 400L

    /** A missing chunk is waited for this long while a later one is held, then skipped. */
    const val GAP_MS = 500L

    /** A burst with nothing held and no new chunk for this long is over: its last chunk was lost. */
    const val END_MS = 4_000L
}

/** One chunk of a burst: chunk [q] (from 0) of the sender's burst [b], Opus packets [a] ([OpusPackets]), [e] on its last. */
class TalkChunk(
    val b: Int,
    val q: Int,
    val a: ByteArray,
    val e: Boolean = false,
)

/**
 * A chunk's plaintext, sealed into a `talk` frame by the mesh (§3.11). Binary, because every byte rides Coded PHY twice
 * a second:
 *
 * ```
 * flags (bit 0: the burst's last) · call tag (2 bytes, big-endian) · varint b · varint q · packets
 * ```
 *
 * The tag ([tagOf]) ties a chunk to its session, so a late one from a session just ended never plays in the next.
 * Pure (`WalkieStreamTest`).
 */
object TalkWire {
    private const val END = 0x01
    private const val TAG_BYTES = 2
    private const val VARINT_BITS = 7
    private const val VARINT_MORE = 0x80
    private const val VARINT_MASK = 0x7F
    private const val VARINT_MAX_BYTES = 5
    private const val BYTE = 0xFF
    private const val BYTE_BITS = 8
    private const val TAG_MASK = 0xFFFF

    /** The 16-bit tag of the call [callId]: the same on both phones, since `String.hashCode` is specified. */
    fun tagOf(callId: String): Int = callId.hashCode() and TAG_MASK

    fun encode(
        tag: Int,
        chunk: TalkChunk,
    ): ByteArray {
        val out = ByteArrayOutputStream(1 + TAG_BYTES + 2 * VARINT_MAX_BYTES + chunk.a.size)
        out.write(if (chunk.e) END else 0)
        out.write((tag shr BYTE_BITS) and BYTE)
        out.write(tag and BYTE)
        writeVarint(out, chunk.b)
        writeVarint(out, chunk.q)
        out.write(chunk.a)
        return out.toByteArray()
    }

    /** The tag and chunk [bytes] carries, or null when they are not one. */
    fun decode(bytes: ByteArray): Pair<Int, TalkChunk>? {
        if (bytes.size < 1 + TAG_BYTES) return null
        val flags = bytes[0].toInt() and BYTE
        val tag = ((bytes[1].toInt() and BYTE) shl BYTE_BITS) or (bytes[2].toInt() and BYTE)
        val (b, afterB) = readVarint(bytes, 1 + TAG_BYTES) ?: return null
        val (q, afterQ) = readVarint(bytes, afterB) ?: return null
        return tag to TalkChunk(b = b, q = q, a = bytes.copyOfRange(afterQ, bytes.size), e = flags and END != 0)
    }

    private fun writeVarint(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        var v = value
        while (v >= VARINT_MORE) {
            out.write((v and VARINT_MASK) or VARINT_MORE)
            v = v ushr VARINT_BITS
        }
        out.write(v)
    }

    private fun readVarint(
        bytes: ByteArray,
        from: Int,
    ): Pair<Int, Int>? {
        var value = 0L
        var at = from
        for (i in 0 until VARINT_MAX_BYTES) {
            if (at >= bytes.size) return null
            val b = bytes[at++].toInt() and BYTE
            value = value or ((b and VARINT_MASK).toLong() shl (VARINT_BITS * i))
            if (b and VARINT_MORE == 0) return value.takeIf { it <= Int.MAX_VALUE }?.let { it.toInt() to at }
        }
        return null
    }
}

/**
 * Opus packets as a burst's chunks carry them (§3.11): each behind a one-byte length, so a chunk is self-delimiting
 * without a container. A packet past 255 bytes is not one a speech rate makes, and is dropped at the join. Pure
 * (`WalkieStreamTest`).
 */
@Suppress("MagicNumber") // the RFC 6716 TOC table
object OpusPackets {
    private const val MAX_PACKET = 255

    /** [packets] behind their lengths, those too long for one dropped. */
    fun join(packets: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in packets) {
            if (p.isEmpty() || p.size > MAX_PACKET) continue
            out.write(p.size)
            out.write(p)
        }
        return out.toByteArray()
    }

    /** The packets [bytes] holds, up to the first length that runs off its end. */
    fun split(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < bytes.size) {
            val size = bytes[at].toInt() and 0xFF
            if (size == 0 || at + 1 + size > bytes.size) break
            out += bytes.copyOfRange(at + 1, at + 1 + size)
            at += 1 + size
        }
        return out
    }

    /** How long [packet] plays, in microseconds, from its TOC byte (RFC 6716 §3.1); 0 for one too short to say. */
    fun durationUs(packet: ByteArray): Long {
        if (packet.isEmpty()) return 0L
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frameUs =
            when {
                config < 12 -> longArrayOf(10_000, 20_000, 40_000, 60_000)[config and 3]
                config < 16 -> longArrayOf(10_000, 20_000)[config and 1]
                else -> longArrayOf(2_500, 5_000, 10_000, 20_000)[config and 3]
            }
        val frames =
            when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 0
            }
        return frameUs * frames
    }
}

/** Gathers one burst's Opus packets into chunks of at least [chunkMs] of audio (§3.11). Not thread-safe. */
class TalkChunker(
    private val chunkMs: Long = WalkiePolicy.CHUNK_MS,
) {
    private val held = mutableListOf<ByteArray>()
    private var heldUs = 0L
    private var totalUs = 0L

    /** Audio this burst has taken in so far, sent or not. */
    val totalMs: Long get() = totalUs / US_PER_MS

    /** Takes one packet; the chunk's audio it completes, or null while the chunk is still short. */
    fun add(packet: ByteArray): ByteArray? {
        held += packet
        val us = OpusPackets.durationUs(packet)
        heldUs += us
        totalUs += us
        return if (heldUs >= chunkMs * US_PER_MS) flush() else null
    }

    /** Whatever is held, possibly nothing: the burst's last chunk. */
    fun flush(): ByteArray =
        OpusPackets.join(held).also {
            held.clear()
            heldUs = 0L
        }

    private companion object {
        const val US_PER_MS = 1_000L
    }
}

/**
 * The peer's chunks put back in order for the player (§3.11). Bursts play one after another in the order they were
 * spoken, each in chunk order once it holds two chunks (or its last, or [prebufferMs] has passed since its first came).
 * A missing chunk is waited for [gapMs] while a later one is held, then skipped; a burst ends on its last chunk, or
 * after [endMs] with nothing held and nothing new. Pure and clocked by the caller (`WalkieStreamTest`); not thread-safe.
 */
class TalkQueue(
    private val prebufferMs: Long = WalkiePolicy.PREBUFFER_MS,
    private val gapMs: Long = WalkiePolicy.GAP_MS,
    private val endMs: Long = WalkiePolicy.END_MS,
) {
    sealed interface Step {
        /** Play [audio] (joined Opus packets, maybe none) of [burst]; [first] opens it, [last] closes it. */
        class Play(
            val burst: Int,
            val audio: ByteArray,
            val first: Boolean,
            val last: Boolean,
        ) : Step

        /** Nothing to play for [ms], unless a chunk comes first. */
        data class Wait(
            val ms: Long,
        ) : Step

        /** Nothing held: wait for a chunk. */
        data object Idle : Step
    }

    private class Burst(
        val firstAt: Long,
    ) {
        val chunks = TreeMap<Int, TalkChunk>()
        var lastAt = firstAt
    }

    private val bursts = TreeMap<Int, Burst>()

    // The newest burst played to its end or given up on; nothing at or below it plays again.
    private var done = -1
    private var playing: Int? = null
    private var next = 0
    private var started = false
    private var stalledAt: Long? = null

    /** Chunks skipped over because they never came. */
    var skipped = 0
        private set

    /** Takes a chunk that came at [at]; false when it is too late (its burst is over or past it) or a duplicate. */
    fun offer(
        chunk: TalkChunk,
        at: Long,
    ): Boolean {
        if (chunk.b <= done || (chunk.b == playing && chunk.q < next)) return false
        val burst = bursts.getOrPut(chunk.b) { Burst(at) }
        if (burst.chunks.putIfAbsent(chunk.q, chunk) != null) return false
        burst.lastAt = maxOf(burst.lastAt, at)
        // A peer that keeps opening bursts it never ends loses the oldest it is not playing.
        while (bursts.size > MAX_BURSTS) bursts.remove(bursts.keys.first { it != playing })
        return true
    }

    /** What to do at [now]. */
    @Suppress("ReturnCount") // a ladder: start a burst, play, skip a gap, give the burst up, wait
    fun poll(now: Long): Step {
        while (true) {
            val id = playing ?: (startable(now) ?: return waitToStart(now))
            val burst = bursts.getValue(id)
            val chunk = burst.chunks.remove(next)
            if (chunk != null) {
                next++
                stalledAt = null
                val first = !started
                started = true
                if (chunk.e) finish(id)
                return Step.Play(id, chunk.a, first, chunk.e)
            }
            val later = burst.chunks.higherKey(next)
            if (later != null) {
                val since = stalledAt ?: now.also { stalledAt = it }
                if (now - since < gapMs) return Step.Wait(since + gapMs - now)
                skipped += later - next
                next = later
                stalledAt = null
                continue
            }
            if (now - burst.lastAt < endMs) return Step.Wait(burst.lastAt + endMs - now)
            // Its last chunk never came: close what played, and move on.
            val opened = started
            finish(id)
            if (opened) return Step.Play(id, ByteArray(0), first = false, last = true)
        }
    }

    // The oldest held burst, made the playing one, when it may start now.
    private fun startable(now: Long): Int? {
        val (id, burst) = bursts.firstEntry() ?: return null
        val ready = burst.chunks.size >= 2 || burst.chunks.values.any { it.e } || now - burst.firstAt >= prebufferMs
        if (!ready) return null
        playing = id
        next = 0
        started = false
        stalledAt = null
        return id
    }

    private fun waitToStart(now: Long): Step {
        val burst = bursts.firstEntry()?.value ?: return Step.Idle
        return Step.Wait(burst.firstAt + prebufferMs - now)
    }

    private fun finish(id: Int) {
        done = maxOf(done, id)
        playing = null
        bursts.headMap(id, true).clear()
    }

    private companion object {
        const val MAX_BURSTS = 8
    }
}
