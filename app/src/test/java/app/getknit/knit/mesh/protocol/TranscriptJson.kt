package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/** The JSON a [Transcript] step records: the header, the actor's state digest, a ctl content, the floors. */
internal object TranscriptJson {
    fun sha256(bytes: ByteArray?): String? = bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).toHex() }

    fun header(r: RatchetHeader) =
        buildJsonObject {
            put("se", r.se)
            put("ek", r.ek.toHex())
            put("pe", r.pe)
            put("n", r.n)
            put("flags", r.flags)
            val init = r.init
            if (init == null) {
                put("init", JsonNull)
            } else {
                putJsonObject("init") {
                    put("eph", init.eph.toHex())
                    put("pkid", init.pkid)
                    put("at", init.at)
                }
            }
        }

    fun state(
        who: TranscriptParty,
        peer: TranscriptParty,
    ): JsonObject =
        buildJsonObject {
            put("of", who.name)
            put("peer", peer.name)
            put("session", who.store.sessionOf(peer.nodeId)?.let(::session) ?: JsonNull)
            putJsonArray("recvEpochs") {
                for (row in who.store.recvOf(peer.nodeId)) {
                    add(
                        buildJsonObject {
                            put("epoch", row.epoch)
                            put("rootTag", "%016x".format(row.rootTag))
                            put("next", row.next)
                            put("chainKeySha256", sha256(row.chainKey))
                            put("lastUsedAt", row.lastUsedAt)
                        },
                    )
                }
            }
            val skipped = who.store.skippedOf(peer.nodeId)
            put("skippedKeyCount", skipped.size)
            put("skippedKeys", skippedRuns(skipped))
            putJsonArray("localEpochs") {
                for (epoch in who.store.localEpochsOf(peer.nodeId)) {
                    add(
                        buildJsonObject {
                            put("epoch", epoch.epoch)
                            put("createdAt", epoch.createdAt)
                            put("pub", epoch.pub.toHex())
                        },
                    )
                }
            }
        }

    private fun session(s: RatchetEngine.SessionState) =
        buildJsonObject {
            put("rootSha256", sha256(s.root))
            put("confirmed", s.confirmed)
            put("weAreInitiator", s.weAreInitiator)
            put("establishedAt", s.establishedAt)
            put("initEphPub", s.initEphPub?.toHex())
            put("initPkid", s.initPkid)
            put("peerInitEphPub", s.peerInitEphPub?.toHex())
            put("peerBasePub", s.peerBasePub?.toHex())
            put("peerBaseEpoch", s.peerBaseEpoch)
            put("sendEpoch", s.sendEpoch)
            put("sendEpochPub", s.sendEpochPub?.toHex())
            put("sendChainKeySha256", sha256(s.sendChainKey))
            put("sendCount", s.sendCount)
            put("sendEpochStartedAt", s.sendEpochStartedAt)
            put("sendEpochBaseEpoch", s.sendEpochBaseEpoch)
            put("sendEpochExportSha256", sha256(s.sendEpochExport))
            put("highestPeAcked", s.highestPeAcked)
            put("prevRootSha256", sha256(s.prevRoot))
            put("prevRootWeAreInitiator", s.prevRootWeAreInitiator)
            put("prevRootExpiresAt", s.prevRootExpiresAt)
            put("lastResetSentAt", s.lastResetSentAt)
        }

    /** Skipped keys as contiguous runs per `(epoch, rootTag)`: a 196-key gap is one entry, not 196. */
    private fun skippedRuns(keys: List<RatchetEngine.SkippedKey>): JsonArray =
        buildJsonArray {
            for ((group, members) in keys.groupBy { it.epoch to it.rootTag }) {
                var start = members.first().idx
                var last = start

                fun flush() =
                    add(
                        buildJsonObject {
                            put("epoch", group.first)
                            put("rootTag", "%016x".format(group.second))
                            put("fromIdx", start)
                            put("toIdx", last)
                        },
                    )
                for (key in members.drop(1)) {
                    if (key.idx != last + 1) {
                        flush()
                        start = key.idx
                    }
                    last = key.idx
                }
                flush()
            }
        }

    /** The machinery fields of [content] — null for an ordinary DM with nothing beyond its body. */
    fun content(content: MessageContent): JsonObject? {
        if (content.ctl == null && content.acks == null) return null
        return buildJsonObject {
            put("ctl", content.ctl)
            put("ack", content.ack)
            put("acks", content.acks?.let { ids -> buildJsonArray { ids.forEach { add(it) } } } ?: JsonNull)
            content.rp?.let { rp ->
                putJsonObject("rp") {
                    put("messageId", rp.messageId)
                    put("emoji", rp.emoji)
                }
            }
            content.pr?.let { pr ->
                putJsonObject("pr") {
                    put("name", pr.name)
                    put("status", pr.status)
                    put("avatarHash", pr.avatarHash)
                    put("version", pr.version)
                    put("openToChat", pr.openToChat)
                    put("loraNode", pr.loraNode)
                    put("loraKey", pr.loraKey)
                }
            }
        }
    }

    /**
     * The in-memory floors [who]'s `RatchetSessions` holds for [peer] — read by reflection, because they are private
     * to the facade and production never needs them outside it. Null where the map has no entry. A restart forgets
     * all four. `lastResetSentAtInMemory` is the no-session fallback only `noteUndecryptable` reads (as the max with
     * the persisted stamp); `sealResetDm`'s plan reads the persisted `state.session.lastResetSentAt`, never this.
     */
    fun floors(
        who: TranscriptParty,
        peer: TranscriptParty,
    ): JsonObject {
        fun entry(field: String): Any? {
            val map =
                RatchetSessions::class.java
                    .getDeclaredField(field)
                    .apply { isAccessible = true }
                    .get(who.sessions) as Map<*, *>
            return synchronized(map) { map[peer.nodeId] }
        }
        return buildJsonObject {
            put("lastReplacementAt", entry("lastReplacementAt") as Long?)
            put("confirmedAt", entry("confirmedAt") as Long?)
            put("lastResetSentAtInMemory", entry("lastResetSentAt") as Long?)
            put("undecryptableIds", (entry("undecryptable") as Set<*>?)?.size ?: 0)
        }
    }
}
