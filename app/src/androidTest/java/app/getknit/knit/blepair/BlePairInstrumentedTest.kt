package app.getknit.knit.blepair

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.mesh.TransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.File
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Two emulators meshing over **real Bluetooth** and nothing else: each one's host stack drives one of knit-ios's
 * nRF52840 dongles through `scripts/hci-bridge.py --isolate`, which keeps the pair from hearing the lab phones (and
 * them from hearing the pair), so every frame here crossed real air between two real controllers.
 * `scripts/ble-pair-test.sh` boots the pair, installs, and runs this class on both emulators at once.
 *
 * Both sides run the same methods in the same order and each asserts what it sees itself; they meet through the
 * mesh (each waits for the other's message), and every test ends in a [rendezvous], so neither runs ahead into the
 * next one — or out of the class: `am instrument` kills the app when its last test returns, and a side that left
 * first took the link, and the ✓✓ it still owed, with it (the first run, 2026-10-05). The class then lingers
 * [LINGER_MS] before returning, so the last frames either side sent get out. Arguments, from `am instrument -e`:
 * - `knitBlePeer` — the other emulator's node id. Without it the class skips, so the ordinary
 *   `connectedDebugAndroidTest` and Firebase Test Lab runs never wait on a peer that isn't there.
 * - `knitBleRun` — a tag unique to the run, in every body, so a run never matches an older run's rows.
 * - `knitBleBridge` — the guest-side port `adb reverse` maps to this emulator's bridge control; only the cut needs it.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BlePairInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val peer: String = args.getString(ARG_PEER).orEmpty()
    private val run: String = args.getString(ARG_RUN) ?: "adhoc"
    private val bridgePort: Int? = args.getString(ARG_BRIDGE)?.toIntOrNull()

    private val mesh: MeshController by lazy { GlobalContext.get().get() }
    private val messages: MessageRepository by lazy { GlobalContext.get().get() }
    private val blobs: BlobRepository by lazy { GlobalContext.get().get() }
    private val attachments: AttachmentStore by lazy { GlobalContext.get().get() }
    private val identity: Identity by lazy { GlobalContext.get().get() }

    @get:Rule
    val name = TestName()

    private lateinit var self: String
    private val watch = CoroutineScope(Dispatchers.Default + Job())
    private val strangers = ConcurrentHashMap.newKeySet<String>()

    @Before
    fun startTheMesh() {
        assumeTrue("not a BLE pair run (no $ARG_PEER argument)", peer.isNotEmpty())
        assumeFalse("a seeded build fakes its peers", BuildConfig.SEED_DEMO)
        // `am instrument` restarts the process, so the service the launch started is gone; the runner exempts the
        // battery optimisation (ble-pair-test.sh), which is what lets an off-screen start claim the foreground.
        assertTrue("MeshService.start refused (is Knit battery-exempt?)", MeshService.start(context))
        self = runBlocking { identity.nodeId() }
        // "Nobody else" holds for the whole test, not one instant of it: anyone besides the peer the mesh ever
        // counts as nearby fails it in [noStrangerWasNearby].
        watch.launch { mesh.neighbors.collect { set -> set.forEach { if (it.nodeId != peer) strangers += it.nodeId } } }
    }

    @After
    fun meetThePeerThenCheckNoStrangerWasNearby() {
        try {
            if (::self.isInitialized) runBlocking { rendezvous("${name.methodName} end") }
        } finally {
            watch.cancel()
        }
        assertTrue("the pair heard a node besides its peer; isolation leaked: $strangers", strangers.isEmpty())
    }

    @Test
    fun a_linksThePeerOverBluetooth() =
        runBlocking<Unit> {
            val started = System.currentTimeMillis()
            awaitLink()
            note("linked $peer in ${System.currentTimeMillis() - started} ms")
            val statuses = mesh.transportStatuses.value
            assertTrue(
                "a link to the peer without a Bluetooth one: $statuses",
                statuses.any { it.kind == TransportKind.Bluetooth && it.linked > 0 },
            )
            assertEquals(
                "a link on a plane an emulator has no radio for: $statuses",
                0,
                statuses.filter { it.kind != TransportKind.Bluetooth }.sumOf { it.linked },
            )
        }

    @Test
    fun b_aDmCrossesBothWaysAndItsTickComesHome() =
        runBlocking<Unit> {
            awaitLink()
            val mine = body("dm from $self")
            assertTrue("sendChat refused the DM", mesh.sendChat(mine, recipientId = peer))
            awaitRow(peer, DM_TIMEOUT_MS, "the peer's DM") { it.senderId == peer && it.body == body("dm from $peer") }
            awaitRow(peer, DM_TIMEOUT_MS, "the ✓✓ on our DM") { it.senderId == self && it.body == mine && it.received }
        }

    @Test
    fun c_aNearbyPostCrossesBothWays() =
        runBlocking<Unit> {
            awaitLink()
            assertTrue("sendChat refused the post", mesh.sendChat(body("post from $self"), recipientId = null))
            awaitRow(Conversations.NEARBY, DM_TIMEOUT_MS, "the peer's Nearby post") {
                it.senderId == peer && it.body == body("post from $peer")
            }
        }

    @Test
    fun d_aPhotoCrossesBothWays() =
        runBlocking<Unit> {
            awaitLink()
            val picked = attachments.ingest(Uri.fromFile(noisePhoto()))
            assertTrue("ingest refused the photo: $picked", picked is AttachmentStore.IngestResult.Success)
            val ingested = (picked as AttachmentStore.IngestResult.Success).ingested
            note("sending a ${ingested.mime} photo ${ingested.hash}")
            assertTrue(
                "sendChat refused the photo",
                mesh.sendChat(body("photo from $self"), attachment = ingested, recipientId = peer),
            )
            val started = System.currentTimeMillis()
            val theirs =
                awaitRow(peer, PHOTO_TIMEOUT_MS, "the peer's photo message") {
                    it.senderId == peer && it.body == body("photo from $peer") && it.attachmentHash != null
                }
            val hash = checkNotNull(theirs.attachmentHash)
            withTimeout(PHOTO_TIMEOUT_MS) { while (!blobs.exists(hash)) delay(POLL_MS) }
            note("held the peer's photo $hash ${System.currentTimeMillis() - started} ms after sending ours")
        }

    @Test
    fun e_aCutLinkComesBackAndCarriesADm() =
        runBlocking<Unit> {
            assumeTrue("no bridge control ($ARG_BRIDGE)", bridgePort != null)
            awaitLink()
            // One side cuts (the smaller id, which never dials, so the relink is the other side's ordinary dial); the
            // other learns of it from the controller's LL terminate. Each then needs the link back to deliver.
            if (self < peer) {
                val cutAt = System.currentTimeMillis()
                val cut = bridge(JSONObject().put("cmd", "cut")).getJSONArray("cut")
                assertTrue("the bridge had no link to cut", cut.length() > 0)
                val relinkMs = awaitBridgeRelink(cutAt)
                note("relinked $relinkMs ms after the cut")
                val mine = body("after the cut from $self")
                assertTrue("sendChat refused the DM", mesh.sendChat(mine, recipientId = peer))
                awaitRow(peer, DM_TIMEOUT_MS, "the peer's post-cut DM") {
                    it.senderId == peer && it.body == body("after the cut from $peer")
                }
            } else {
                awaitRow(peer, RELINK_TIMEOUT_MS + DM_TIMEOUT_MS, "the cutter's post-cut DM") {
                    it.senderId == peer && it.body == body("after the cut from $peer")
                }
                val mine = body("after the cut from $self")
                assertTrue("sendChat refused the DM", mesh.sendChat(mine, recipientId = peer))
                awaitRow(peer, DM_TIMEOUT_MS, "the ✓✓ on our post-cut DM") {
                    it.senderId == self && it.body == mine && it.received
                }
            }
        }

    private fun body(what: String) = "$TAG $run $what"

    /** The peer nearby and a Bluetooth link up: the mesh can carry a frame to it now. */
    private suspend fun awaitLink() {
        runCatching {
            withTimeout(LINK_TIMEOUT_MS) {
                mesh.neighbors.first { set -> set.any { it.nodeId == peer } }
                mesh.transportStatuses.first { s -> s.any { it.kind == TransportKind.Bluetooth && it.linked > 0 } }
            }
        }.onFailure {
            throw AssertionError(
                "no Bluetooth link to $peer within $LINK_TIMEOUT_MS ms; nearby ${mesh.neighbors.value}, " +
                    "radios ${mesh.transportStatuses.value}",
                it,
            )
        }
    }

    /** The first row of [conversation] (its newest window) that [match] accepts, within [timeoutMs]. */
    private suspend fun awaitRow(
        conversation: String,
        timeoutMs: Long,
        what: String,
        match: (MessageEntity) -> Boolean,
    ): MessageEntity {
        val found =
            runCatching {
                withTimeout(timeoutMs) {
                    messages.observeNewestMessages(conversation, WINDOW).first { rows -> rows.any(match) }.first(match)
                }
            }
        return found.getOrElse {
            val rows = messages.observeNewestMessages(conversation, WINDOW).first()
            val seen = rows.joinToString("\n") { "  ${it.senderId.take(8)} received=${it.received} ${it.body}" }
            throw AssertionError("no $what within $timeoutMs ms; $conversation holds:\n$seen", it)
        }
    }

    /** Both sides past [step]: each says so in the DM thread and waits to hear the other say it. */
    private suspend fun rendezvous(step: String) {
        assertTrue("sendChat refused the rendezvous", mesh.sendChat(body("$step done by $self"), recipientId = peer))
        awaitRow(peer, DM_TIMEOUT_MS, "the peer at '$step'") { it.senderId == peer && it.body == body("$step done by $peer") }
    }

    /** How long after [cutAt] the bridge saw a link come up again — the controller's word, not the app's. */
    private suspend fun awaitBridgeRelink(cutAt: Long): Long =
        withTimeout(RELINK_TIMEOUT_MS) {
            while (!bridgeLinkedSince(cutAt)) delay(POLL_MS)
            System.currentTimeMillis() - cutAt
        }

    /** A link younger than the time since [cutAt]: ages are the bridge's, so the host and guest clocks never meet. */
    private suspend fun bridgeLinkedSince(cutAt: Long): Boolean {
        val sinceCutS = (System.currentTimeMillis() - cutAt) / MS_PER_S
        val links = bridge(JSONObject().put("cmd", "links")).getJSONArray("links")
        return (0 until links.length()).any { links.getJSONObject(it).getDouble("ageS") < sinceCutS }
    }

    /** One request to this emulator's bridge control (a JSON line each way), over the port `adb reverse` maps. */
    private suspend fun bridge(request: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            Socket("127.0.0.1", checkNotNull(bridgePort)).use { socket ->
                socket.soTimeout = BRIDGE_TIMEOUT_MS
                socket.getOutputStream().write("$request\n".toByteArray())
                JSONObject(socket.getInputStream().bufferedReader().readLine())
            }
        }

    /** A photo of noise, so the transcode cannot shrink it to nothing: a few tens of KB over the air. */
    private fun noisePhoto(): File {
        val random = Random(run.hashCode() xor self.hashCode())
        val bitmap = Bitmap.createBitmap(PHOTO_SIDE, PHOTO_SIDE, Bitmap.Config.ARGB_8888)
        for (y in 0 until PHOTO_SIDE) {
            for (x in 0 until PHOTO_SIDE) bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
        }
        val file = File(context.cacheDir, "ble-pair-$run.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return file
    }

    private fun note(line: String) = Log.i(TAG, "$run ${self.take(8)}: $line")

    companion object {
        /** Keeps the process, so the mesh, up past the last rendezvous until its ✓✓ and frames are out. */
        @JvmStatic
        @AfterClass
        fun lingerForTheLastFrames() {
            if (InstrumentationRegistry.getArguments().getString(ARG_PEER) != null) runBlocking { delay(LINGER_MS) }
        }

        private const val TAG = "BlePair"
        private const val ARG_PEER = "knitBlePeer"
        private const val ARG_RUN = "knitBleRun"
        private const val ARG_BRIDGE = "knitBleBridge"
        private const val WINDOW = 50
        private const val MS_PER_S = 1000.0
        private const val POLL_MS = 500L
        private const val BRIDGE_TIMEOUT_MS = 5_000
        private const val LINK_TIMEOUT_MS = 180_000L
        private const val DM_TIMEOUT_MS = 120_000L
        private const val PHOTO_TIMEOUT_MS = 240_000L
        private const val RELINK_TIMEOUT_MS = 120_000L
        private const val PHOTO_SIDE = 160
        private const val JPEG_QUALITY = 90
        private const val LINGER_MS = 10_000L
    }
}
