package app.getknit.knit.mesh.spool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The one place the key swap speaks OkHttp (`rules/mesh.md`, ADR 2026-10.3sk7): a single `POST <origin>/api/v1/ticket`
 * with `Authorization: Bearer <key>` and an empty body, read into [IssuerAnswer.read].
 *
 * - **Bound to the validated network**, as the link-preview fetch is: [clientFor] hands back a client whose sockets and
 *   lookups belong to the default network `net/AndroidInternetGate` vouched for, or null when there is none
 *   ([IssuerAnswer.NoVerdict] `offline`, no socket). Unlike the preview fetch there is no private-address guard: the
 *   origin is one the person confirmed on the invite sheet, as they confirmed the relay beside it, and the spool dialer
 *   applies none to that either. A debug build's bench issuer on a LAN or behind `adb reverse` needs exactly that.
 * - **No redirect is followed.** The key is a bearer credential tied to the origin it arrived with (C-7.6-29), and
 *   OkHttp would carry the `Authorization` header to a same-host redirect target; a 3xx is just another status.
 * - **Short timeouts, no retries, no cache** (none is configured; every answer is `no-store` anyway), and the body is
 *   read through a [MAX_BODY_BYTES] cap — an honest answer is a few hundred bytes.
 * - **Nothing secret is logged or thrown.** Failures become a short reason word; the key, the header and the body
 *   never reach one.
 *
 * The cleartext rule is enforced here as well as by [TicketRefresher]: with [allowCleartext] off, an `http://` origin
 * is refused before any socket opens.
 */
class OkHttpTicketIssuer(
    private val clientFor: () -> OkHttpClient?,
    private val allowCleartext: Boolean,
) : TicketIssuer {
    override suspend fun swap(
        origin: String,
        key: String,
    ): IssuerAnswer {
        if (!IssuerOrigin.isAcceptable(origin, allowCleartext)) return IssuerAnswer.NoVerdict("refused origin")
        val client = clientFor() ?: return IssuerAnswer.NoVerdict("offline")
        val request =
            Request
                .Builder()
                .url(IssuerOrigin.ticketUrl(origin))
                .header(AUTHORIZATION, "Bearer $key")
                .header(ACCEPT, JSON)
                .post(ByteArray(0).toRequestBody(null))
                .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = readCapped(response) ?: return@use IssuerAnswer.NoVerdict("too large")
                    IssuerAnswer.read(response.code, body)
                }
            } catch (_: UnknownHostException) {
                IssuerAnswer.NoVerdict("dns")
            } catch (_: InterruptedIOException) {
                IssuerAnswer.NoVerdict("timeout")
            } catch (e: IOException) {
                IssuerAnswer.NoVerdict(e.javaClass.simpleName)
            }
        }
    }

    /** The body, or null when it is past [MAX_BODY_BYTES]: read one byte past the cap so a body that fits is told apart. */
    private fun readCapped(response: Response): ByteArray? {
        val source = response.body.source()
        val buffer = Buffer()
        var remaining = MAX_BODY_BYTES + 1
        while (remaining > 0) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        return buffer.readByteArray().takeIf { it.size <= MAX_BODY_BYTES }
    }

    companion object {
        /** Room for the 200's six fields and a ~200-byte ticket many times over. */
        const val MAX_BODY_BYTES = 8L * 1024
        const val CONNECT_TIMEOUT_S = 10L
        const val READ_TIMEOUT_S = 10L
        const val CALL_TIMEOUT_S = 20L

        private const val AUTHORIZATION = "Authorization"
        private const val ACCEPT = "Accept"
        private const val JSON = "application/json"

        /** The client every ask starts from: short timeouts, no redirects, no retries, no cache, no cookies. */
        fun baseClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()

        /**
         * [base] bound to [network]: its sockets come from the network's own factory and its lookups from the network's
         * resolver, so an ask can never ride the Wi-Fi Aware link. OkHttp keys pooled connections by address — socket
         * factory and resolver included — so a connection made for one network is never reused for another.
         */
        fun bound(
            base: OkHttpClient,
            network: android.net.Network,
        ): OkHttpClient =
            base
                .newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .build()
    }
}
