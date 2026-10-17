package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.crypto.scope.SpoolPow
import app.getknit.knit.mesh.spool.AccountAnswer
import app.getknit.knit.mesh.spool.AccountIssuer
import app.getknit.knit.mesh.spool.AccountStamp
import app.getknit.knit.mesh.spool.IssuerAnswer
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.TicketIssuer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A free issuer in the lab (docs/VOICE_CALLS.md §2.6): the account route and the key swap answering as knit-spool-
 * coordinator's do (`feat/free-tickets` @ `5883035`), for one audience. A stamp is checked for real; a ticket is the
 * fixture's, unsigned, with `fl` 2 and this issuer's `ab` and `am`, which [app.getknit.knit.mesh.spool.FakeSpool]
 * accepts on its claims. A node gets it through `MeshLab.node(freeIssuer = …)`.
 */
class LabFreeIssuer(
    val aud: String,
    private val clock: () -> Long,
    private val am: Long? = 262_144L,
    private val ab: Long? = 1_310_720L,
    private val bits: Int = 4,
) : TicketIssuer,
    AccountIssuer {
    val origin = "https://free.lab.example"

    /** How many accounts the route made, and how many swaps it answered with a ticket. */
    val accounts = AtomicInteger()
    val tickets = AtomicInteger()

    private val keys = ConcurrentHashMap.newKeySet<String>()

    override suspend fun accountParams(
        origin: String,
        aud: String,
    ): AccountAnswer = if (aud == this.aud) AccountAnswer.Params(aud, bits, SpoolPow.utcDay(clock())) else AccountAnswer.Unavailable(null)

    override suspend fun createAccount(
        origin: String,
        aud: String,
        day: Long,
        nonce: ByteArray,
        n: Long,
    ): AccountAnswer {
        if (aud != this.aud) return AccountAnswer.Unavailable(null)
        if (!AccountStamp.verify(day, nonce, n, bits)) return AccountAnswer.BadStamp(bits, SpoolPow.utcDay(clock()))
        val key = "kak1_" + accounts.incrementAndGet().toString().padStart(KEY_CHARS, 'A')
        keys += key
        return AccountAnswer.Created(key, aud)
    }

    override suspend fun swap(
        origin: String,
        key: String,
    ): IssuerAnswer {
        if (key !in keys) return IssuerAnswer.UnknownKey
        val nowS = clock() / MS_PER_S
        val ticket =
            SpoolTicketsTest.ticket(
                aud = aud,
                nbf = nowS,
                exp = nowS + TICKET_S,
                fl = SpoolTicket.FL_FILES,
                sigByte = tickets.incrementAndGet().toByte(),
                ab = ab,
                am = am,
            )
        return IssuerAnswer.Issued(ticket.text, clock() + ENDS_MS)
    }

    private companion object {
        const val KEY_CHARS = 43
        const val MS_PER_S = 1_000L
        const val TICKET_S = 25 * 3_600L
        const val ENDS_MS = 30 * 86_400_000L
    }
}
