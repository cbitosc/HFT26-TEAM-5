package app.getknit.knit.transfer

import app.getknit.knit.crash.ProcessExitEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** Whether this phone may host a Wi-Fi Direct group, and why not (ADR 2026-10.w3dw). */
sealed interface HostVerdict {
    data object Allowed : HostVerdict

    /**
     * A generic system image over an older vendor image (`device/SystemImage`): a client joining a group this phone
     * hosts crashes `system_server`. Known before the first try, so it never has to happen.
     */
    data object GenericImage : HostVerdict

    /** Knit died while hosting, with no cause of its own, [DirectHostPolicy.MAX_FAILS] times running; [at] is when. */
    data class Latched(
        val at: Long,
    ) : HostVerdict
}

/**
 * What survives a process death about hosting a group.
 *
 * @param stamp the app version + OS build this record was written under; a change discards it, so a new build of
 *   either gets another try
 * @param since when a group this phone hosts went up and was not yet released (epoch millis; 0 = none up). Found
 *   set on a later start, it means the process died with a group of ours on air.
 * @param sinceElapsed `SystemClock.elapsedRealtime()` at [since]. A smaller clock on a later start means the phone
 *   rebooted in between (a dead battery, a restart); a `system_server` crash restarts the framework and leaves that
 *   clock running.
 * @param fails consecutive deaths while hosting that nothing else explains; a clean release resets it
 * @param latchedAt when hosting was turned off (0 = not)
 */
data class DirectHostRecord(
    val stamp: String,
    val since: Long,
    val sinceElapsed: Long,
    val fails: Int,
    val latchedAt: Long,
) {
    companion object {
        val NONE = DirectHostRecord(stamp = "", since = 0L, sinceElapsed = 0L, fails = 0, latchedAt = 0L)
    }
}

/**
 * The two-method store the guard needs (`data/settings/DirectHostStore`). [write] is the durability barrier, as
 * `ModelLoadJournal.setModelLoadState` is: DataStore's `edit {}` fsyncs and renames before it resumes, so a group
 * raised right after it is on disk before any client can join it.
 */
interface DirectHostJournal {
    suspend fun read(): DirectHostRecord

    suspend fun write(record: DirectHostRecord)
}

/** What the radio lease asks before it raises a group of ours, and tells once the group is down again. */
interface HostGate {
    /** Whether this phone may host at all — a snapshot, cheap, for a caller deciding what to offer. */
    fun mayHost(): Boolean

    /** Called just before a group of ours goes up: false refuses it; true has recorded that one is up. */
    suspend fun hosting(): Boolean

    /** Called once a group [hosting] let up is down again. */
    suspend fun hosted()

    /** Every phone may host: what a test and a build without a guard get. */
    object Open : HostGate {
        override fun mayHost() = true

        override suspend fun hosting() = true

        override suspend fun hosted() = Unit
    }
}

/**
 * The guard's reasoning, pure. The marker means "the process died with a group of ours up", and only an unexplained
 * death in the same boot counts against hosting: a reboot (the clock went back), or a death the platform names a
 * cause for that is not the Wi-Fi Direct service's (a Java or native crash of ours, an ANR, the low-memory killer, the
 * user, an update — `crash/ProcessExitReasons`), is cleared without counting. A `system_server` crash takes this
 * process down with it and leaves no record of its own: that is what counts.
 */
object DirectHostPolicy {
    /**
     * Two, not one: transfers ship in every build, and a wrong latch silently costs a phone its direct sending. A
     * reboot is already told apart by the clock, so two unexplained deaths running while hosting, with no clean
     * hosting between them, is evidence enough. The known case (a GSI) is vetoed before its first try.
     */
    const val MAX_FAILS = 2

    /** Settles what the previous process left in [stored], at a start [nowMs] / [elapsedMs]; [exit] is read on demand. */
    fun settle(
        stored: DirectHostRecord,
        stamp: String,
        nowMs: Long,
        elapsedMs: Long,
        exit: () -> ProcessExitEvidence?,
    ): DirectHostRecord {
        if (stored.stamp != stamp) return DirectHostRecord.NONE.copy(stamp = stamp)
        if (stored.since == 0L) return stored
        val cleared = stored.copy(since = 0L, sinceElapsed = 0L)
        if (stored.latchedAt > 0L || elapsedMs < stored.sinceElapsed) return cleared
        if (explains(exit(), stored.since)) return cleared
        val fails = stored.fails + 1
        return if (fails >= MAX_FAILS) cleared.copy(fails = fails, latchedAt = nowMs) else cleared.copy(fails = fails)
    }

    /** Whether [exit] is this process's own death after [since]: a crash of ours, an ANR, the system or the user. */
    private fun explains(
        exit: ProcessExitEvidence?,
        since: Long,
    ): Boolean = exit != null && exit.at >= since && (exit.explained || exit.nativeFault)

    /** [stored] with a group of ours going up at [nowMs] / [elapsedMs]. */
    fun hosting(
        stored: DirectHostRecord,
        stamp: String,
        nowMs: Long,
        elapsedMs: Long,
    ): DirectHostRecord =
        if (stored.stamp == stamp) {
            stored.copy(since = nowMs, sinceElapsed = elapsedMs)
        } else {
            DirectHostRecord.NONE.copy(stamp = stamp, since = nowMs, sinceElapsed = elapsedMs)
        }

    /** [stored] once our group came down cleanly: no group up, and the run of unexplained deaths is over. */
    fun released(stored: DirectHostRecord): DirectHostRecord = stored.copy(since = 0L, sinceElapsed = 0L, fails = 0)

    fun verdictOf(
        record: DirectHostRecord,
        stamp: String,
        genericImage: Boolean,
    ): HostVerdict =
        when {
            genericImage -> HostVerdict.GenericImage
            record.stamp == stamp && record.latchedAt > 0L -> HostVerdict.Latched(record.latchedAt)
            else -> HostVerdict.Allowed
        }
}

/**
 * Keeps this phone from hosting a Wi-Fi Direct group where hosting takes the phone down (ADR 2026-10.w3dw). A call's
 * caller and a transfer's sender host; the other side joins, which this never refuses. Two sources, one verdict:
 * [genericImage], known at start, and a latch for every other ROM, shaped like `moderation/ModelLoadGuard` (ADR 037):
 * a durable marker while a group of ours is up, settled at the next start by [DirectHostPolicy].
 *
 * **Fails open.** A store that cannot be read or written means "not latched, no marker", never "assume the worst":
 * it guards against a crash, and must not become one. Pure: [stamp], [exits] and the clocks are handed in.
 */
class DirectHostGuard(
    private val journal: DirectHostJournal,
    private val exits: () -> ProcessExitEvidence?,
    private val stamp: String,
    private val genericImage: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long,
    private val log: (String) -> Unit = {},
) : HostGate {
    private val loading = AtomicBoolean(false)
    private val loaded = CompletableDeferred<Unit>()
    private val _verdict = MutableStateFlow(DirectHostPolicy.verdictOf(DirectHostRecord.NONE, stamp, genericImage))

    /** The verdict now. Allowed until [load] has read a latch, unless the image is generic. */
    val verdict: StateFlow<HostVerdict> = _verdict.asStateFlow()

    /** Settles what the previous process left. Once per process; a second caller waits for the first. */
    suspend fun load() {
        if (!loading.compareAndSet(false, true)) return loaded.await()
        try {
            val stored = tolerating { journal.read() } ?: return
            val settled = DirectHostPolicy.settle(stored, stamp, now(), elapsed(), exits)
            if (settled != stored) tolerating { journal.write(settled) }
            if (settled.fails > stored.fails) log("direct host: died hosting, unexplained (${settled.fails}/${DirectHostPolicy.MAX_FAILS})")
            _verdict.value = DirectHostPolicy.verdictOf(settled, stamp, genericImage)
            if (_verdict.value != HostVerdict.Allowed) log("direct host: off (${_verdict.value})")
        } finally {
            loaded.complete(Unit)
        }
    }

    override fun mayHost(): Boolean = _verdict.value == HostVerdict.Allowed

    override suspend fun hosting(): Boolean {
        load()
        if (!mayHost()) return false
        val stored = tolerating { journal.read() } ?: return true
        tolerating { journal.write(DirectHostPolicy.hosting(stored, stamp, now(), elapsed())) }
        return true
    }

    override suspend fun hosted() {
        withContext(NonCancellable) {
            val stored = tolerating { journal.read() } ?: return@withContext
            tolerating { journal.write(DirectHostPolicy.released(stored)) }
        }
    }

    /** The Diagnostics reset: lets this phone host again. A generic image stays off; that is no latch to clear. */
    suspend fun reset() {
        tolerating { journal.write(DirectHostRecord.NONE.copy(stamp = stamp)) }
        _verdict.value = DirectHostPolicy.verdictOf(DirectHostRecord.NONE, stamp, genericImage)
    }

    /** A journal call reporting failure as null; never swallows cancellation (see the fail-open note). */
    private suspend fun <T> tolerating(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
}
