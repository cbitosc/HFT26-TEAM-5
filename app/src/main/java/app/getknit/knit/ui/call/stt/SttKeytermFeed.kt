package app.getknit.knit.ui.call.stt

import app.getknit.knit.call.CallState
import app.getknit.knit.call.SttKeytermPolicy
import app.getknit.knit.call.sttKeyterms
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.group.GroupDao
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageDao
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Gives the transcript's model the names a call is likely to carry (C-3.11-9, `sttKeyterms`): read once as a call
 * begins — the peer's and this phone's names, the latest threads' and the thread with the peer — and cleared when it
 * ends. Everything stays on the phone, in memory: the list goes to the model and nowhere else, and is never logged.
 */
internal class SttKeytermFeed(
    private val calls: StateFlow<CallState?>,
    private val speech: MoonshineSpeech,
    private val peers: PeerRepository,
    private val groups: GroupDao,
    private val messages: MessageDao,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    fun start() {
        scope.launch {
            calls
                .map { call -> call?.let { it.id to it.peerId } }
                .distinctUntilChanged()
                .collectLatest { call ->
                    speech.keyterms = call?.let { (_, peerId) -> gather(peerId) }.orEmpty()
                    if (call != null) log("walkie stt: ${speech.keyterms.size} key terms for call ${call.first}")
                }
        }
    }

    private suspend fun gather(peerId: String): List<String> {
        val threads =
            messages
                .observeNewestPerConversation(listOf(MessageEntity.KIND_NORMAL), emptyList())
                .first()
                .map { it.conversationId }
                .take(SttKeytermPolicy.RECENT_THREADS)
                .mapNotNull { id ->
                    when (Conversations.kindFor(id)) {
                        ConversationKind.DM -> peers.find(id)?.name
                        ConversationKind.GROUP -> groups.findById(id)?.name
                        else -> null
                    }
                }
        val texts =
            messages
                .observeNewestForConversation(peerId, SttKeytermPolicy.RECENT_MESSAGES)
                .first()
                .filter { it.kind == MessageEntity.KIND_NORMAL }
                .map { it.body }
        return sttKeyterms(
            peerName = peers.find(peerId)?.name,
            myName = settings.displayName.first(),
            threadNames = threads,
            recentTexts = texts,
        )
    }
}
