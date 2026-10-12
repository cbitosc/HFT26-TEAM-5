package app.getknit.knit.call

import app.getknit.knit.data.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Writes each call's line into its DM thread (docs/VOICE_CALLS.md §3.12): the one collector of [CallManager.logged].
 * Apart from the manager on purpose — [CallManager.onSignal] runs on the inbound path and never waits on the
 * database, so a record is handed over on a channel and saved here, on [scope].
 *
 * [messages] is resolved at the first record rather than at start: the call graph starts with the process, and the
 * encrypted database may not be open yet (or may refuse, ADR 2026-10.47rw). A save that fails loses that one line and
 * nothing else.
 */
class CallLog(
    private val calls: CallManager,
    private val messages: () -> MessageRepository,
    private val selfId: suspend () -> String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    fun start(): Job =
        scope.launch {
            calls.logged.collect { entry ->
                try {
                    messages().save(entry.record.toEntity(peerId = entry.peerId, selfId = selfId(), startedAt = entry.startedAt))
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log("call ${entry.record.id}: its record was not saved (${e.javaClass.simpleName})")
                }
            }
        }
}
