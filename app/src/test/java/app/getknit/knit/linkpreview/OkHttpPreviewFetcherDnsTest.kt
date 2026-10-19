package app.getknit.knit.linkpreview

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The private-address guard as the walk meets it (ADR 2026-09.n752): OkHttp connects only to what [OkHttpPreviewFetcher.BoundDns]
 * returned, so a name the resolver refuses opens no socket at all, and a redirect that lands on a refused name is
 * refused on that hop, after the first one was served. Two loopback addresses stand in for a public and a private
 * one — the policy is injected — so the test reaches a real server and never the network.
 */
class OkHttpPreviewFetcherDnsTest {
    private val server = MockWebServer()
    private val publicAddress = InetAddress.getByName("127.0.0.1")
    private val privateAddress = InetAddress.getByName("127.0.0.2")

    private val fetcher =
        OkHttpPreviewFetcher(
            clientFor = {
                OkHttpPreviewFetcher
                    .baseClient()
                    .newBuilder()
                    .dns(
                        OkHttpPreviewFetcher.BoundDns(
                            resolve = { host -> listOf(if (host == "private.test") privateAddress else publicAddress) },
                            isPublic = { address -> address.contentEquals(publicAddress.address) },
                        ),
                    ).build()
            },
            hopPolicy = { it },
        )

    @Before
    fun start() = server.start(publicAddress, 0)

    @After
    fun stop() = server.close()

    private fun url(
        host: String,
        path: String,
    ) = "http://$host:${server.port}$path"

    @Test
    fun aNameThatResolvesPrivatelyIsRefusedWithoutASocket() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<title>Inside</title>")
                .build(),
        )

        assertEquals(PageFetch.Failed("dns"), runBlocking { fetcher.fetchPage(url("private.test", "/admin")) })
        assertEquals(ImageFetch.Failed("dns"), runBlocking { fetcher.fetchImage(url("private.test", "/p.jpg")) })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aRedirectOntoAPrivateNameIsRefusedOnThatHop() {
        // The rebinding shape: a public page that bounces the fetch inward. Every hop re-resolves, so the second fails.
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", url("private.test", "/admin"))
                .build(),
        )

        assertEquals(PageFetch.Failed("dns"), runBlocking { fetcher.fetchPage(url("public.test", "/start")) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aNameThatResolvesPubliclyIsFetched() {
        // The control: the same rig reaches the server when the guard allows it, so the refusals above are the guard's.
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .addHeader("Content-Type", "text/html")
                .body("<title>Out</title>")
                .build(),
        )

        val page = runBlocking { fetcher.fetchPage(url("public.test", "/page")) }

        assertTrue("expected a page, got $page", page is PageFetch.Html)
        assertEquals("<title>Out</title>", String((page as PageFetch.Html).bytes))
    }
}
