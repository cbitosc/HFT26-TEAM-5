package app.getknit.knit.mesh.protocol

import app.getknit.knit.mesh.crypto.ratchet.GroupKeySendState
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetStore

/**
 * [GroupRatchetStore] in memory, with `GroupRatchetRepository`'s row semantics over `GroupRatchetDao`: send chains
 * upsert on `(groupId, epoch)` and read newest-epoch-first (the re-serve list without retired rows, ADR
 * 2026-10.xgwj), receive chains upsert on `(groupId, senderId, epoch, mintedAt)` and read newest-mint-first, skipped
 * keys insert-or-ignore on that plus `idx` and are consumed only for the mint the open names (#177), and the outbox
 * keeps its epochs monotone.
 */
internal class MemoryGroupRatchetStore : GroupRatchetStore {
    private data class RecvId(
        val groupId: String,
        val senderId: String,
        val epoch: Int,
        val mintedAt: Long,
    )

    private data class SkippedId(
        val recv: RecvId,
        val idx: Int,
    )

    private val send = LinkedHashMap<Pair<String, Int>, GroupRatchetEngine.SendChain>()
    private val recv = LinkedHashMap<RecvId, GroupRatchetEngine.RecvChain>()
    private val skipped = LinkedHashMap<SkippedId, Pair<String, GroupRatchetEngine.SkippedKey>>()
    private val outbox = LinkedHashMap<Pair<String, String>, GroupKeySendState>()

    /** Every send row for [groupId], retired ones included, newest epoch first. */
    fun allSendChains(groupId: String): List<GroupRatchetEngine.SendChain> =
        send.values.filter { it.groupId == groupId }.sortedByDescending { it.epoch }

    override suspend fun sendChain(groupId: String): GroupRatchetEngine.SendChain? = allSendChains(groupId).firstOrNull()

    override suspend fun sendChains(groupId: String): List<GroupRatchetEngine.SendChain> = allSendChains(groupId).filterNot { it.isRetired }

    override suspend fun commitSend(chain: GroupRatchetEngine.SendChain) {
        send[chain.groupId to chain.epoch] = chain
    }

    override suspend fun retireSendChains(groupId: String) {
        val newest = sendChain(groupId) ?: return
        send.keys.removeAll { it.first == groupId }
        commitSend(newest.retired())
    }

    override suspend fun recvChains(
        groupId: String,
        senderId: String,
        epoch: Int,
    ): List<GroupRatchetEngine.RecvChain> =
        recv.values.filter { it.groupId == groupId && it.senderId == senderId && it.epoch == epoch }.sortedByDescending { it.mintedAt }

    /** Every receive chain for [groupId], by sender, epoch and mint. */
    fun allRecvChains(groupId: String): List<GroupRatchetEngine.RecvChain> =
        recv.values.filter { it.groupId == groupId }.sortedWith(compareBy({ it.senderId }, { it.epoch }, { it.mintedAt }))

    override suspend fun skippedKeys(
        groupId: String,
        senderId: String,
        epoch: Int,
        idx: Int,
    ): List<GroupRatchetEngine.SkippedKey> =
        skipped
            .filterKeys { it.recv.groupId == groupId && it.recv.senderId == senderId && it.recv.epoch == epoch && it.idx == idx }
            .values
            .map { it.second }
            .sortedByDescending { it.mintedAt }

    /** Every skipped key for [groupId] as `(senderId, key)`, by sender, epoch, mint and index. */
    fun allSkipped(groupId: String): List<Pair<String, GroupRatchetEngine.SkippedKey>> =
        skipped
            .filterKeys { it.recv.groupId == groupId }
            .values
            .sortedWith(compareBy({ it.first }, { it.second.epoch }, { it.second.mintedAt }, { it.second.idx }))

    override suspend fun insertRecvChain(chain: GroupRatchetEngine.RecvChain) {
        recv[RecvId(chain.groupId, chain.senderId, chain.epoch, chain.mintedAt)] = chain
    }

    override suspend fun applyOpen(
        groupId: String,
        senderId: String,
        delta: GroupRatchetEngine.OpenDelta,
        headerSe: Int,
        headerN: Int,
    ) {
        delta.recvChain?.let { insertRecvChain(it) }
        for (key in delta.skippedInserts) {
            skipped.putIfAbsent(SkippedId(RecvId(groupId, senderId, key.epoch, key.mintedAt), key.idx), senderId to key)
        }
        delta.consumedSkippedMintedAt?.let { skipped.remove(SkippedId(RecvId(groupId, senderId, headerSe, it), headerN)) }
    }

    override suspend fun purgeGroup(groupId: String) {
        retireSendChains(groupId)
        recv.keys.removeAll { it.groupId == groupId }
        skipped.keys.removeAll { it.recv.groupId == groupId }
        outbox.keys.removeAll { it.first == groupId }
    }

    override suspend fun keySend(
        groupId: String,
        memberId: String,
    ): GroupKeySendState? = outbox[groupId to memberId]

    override suspend fun keySends(groupId: String): List<GroupKeySendState> = outboxOf(groupId)

    /** The outbox rows for [groupId], by member. */
    fun outboxOf(groupId: String): List<GroupKeySendState> = outbox.values.filter { it.groupId == groupId }.sortedBy { it.memberId }

    override suspend fun markKeySent(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    ) {
        val existing = outbox[groupId to memberId]
        outbox[groupId to memberId] =
            GroupKeySendState(
                groupId = groupId,
                memberId = memberId,
                sentEpoch = maxOf(epoch, existing?.sentEpoch ?: 0),
                sentAt = at,
                ackedEpoch = existing?.ackedEpoch ?: 0,
                ackedAt = existing?.ackedAt ?: 0L,
            )
    }

    override suspend fun markKeyAcked(
        groupId: String,
        memberId: String,
        epoch: Int,
        at: Long,
    ) {
        val existing = outbox[groupId to memberId] ?: return
        if (epoch <= existing.ackedEpoch) return
        outbox[groupId to memberId] = GroupKeySendState(groupId, memberId, existing.sentEpoch, existing.sentAt, epoch, at)
    }

    override suspend fun deleteKeySend(
        groupId: String,
        memberId: String,
    ) {
        outbox.remove(groupId to memberId)
    }

    // No transcript sweeps (see MemoryRatchetStore.sweep).
    override suspend fun sweep(now: Long): Unit = throw UnsupportedOperationException("no transcript sweeps yet")
}
