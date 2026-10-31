package app.getknit.knit.call

/**
 * A terminal phase this phone saw for the call [id] (C-3.4-3): the phase [ph] and reason [rsn] that ended it, at [at] on
 * this phone's clock. What [CallTombJournal] keeps, so a late OFFER for a call that already ended still never rings, nor
 * comes back as a second missed call, after the process that saw the end has died.
 */
class CallTomb(
    val id: String,
    val ph: Int,
    val rsn: Int?,
    val at: Long,
)

/**
 * Where [CallManager]'s tombstones outlive the process (C-3.4-3, ADR 2026-10.3tee's fifth amendment). The manager reads
 * it once as it starts and rewrites it whole, off the inbound path, each time it adds one. A journal that cannot be read
 * reads as empty; one that cannot be written keeps what it had. Either way the tombstones in memory still hold for the
 * life of the process.
 */
interface CallTombJournal {
    suspend fun load(): List<CallTomb>

    suspend fun save(tombs: List<CallTomb>)

    /** Keeps nothing: the tombstones last as long as the process. */
    object None : CallTombJournal {
        override suspend fun load(): List<CallTomb> = emptyList()

        override suspend fun save(tombs: List<CallTomb>) = Unit
    }
}
