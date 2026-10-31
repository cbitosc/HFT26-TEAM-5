package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A [CompositeMeshTransport] child Knit can switch off while the app runs: low-power mode (`LowPowerSource`'s
 * posture, ADR 2026-10.d44b) in every build, and in a debug build also the Diagnostics "Wi-Fi Aware (debug)" switch
 * and the bridge's `NANOFF` (`SettingsStore.debugNanOff`), so a trial can run the mesh on Bluetooth alone without
 * touching the phone's Wi-Fi. Wired around the Wi-Fi Aware child; [off] is whichever of them asks.
 *
 * Off stops [inner] outright — its links, responder and session go, and its [neighbors]/[reachable] empty — and on
 * starts it again, but only while the mesh itself runs: [inner] is up exactly when [start] has been called without
 * a [stop] since **and** the switch reads on. A [start] that lands before the switch's first read waits for it, so
 * an off phone never attaches and detaches again at launch. A [pause] (the Wi-Fi Direct hand-over) is remembered
 * across an off spell and re-applied when [inner] comes back. While [inner] is down every send is dropped and
 * [expectBulkTransfer] declines, so the composite's file route falls straight to the link holder instead of waiting
 * out the bulk grace for a link that cannot come; [health] reads [TransportHealth.Unavailable], which the
 * composite's merge already treats as a radio that is off, and [switchedOff] says it was Knit that turned it off,
 * so no surface asks the user to turn on Wi-Fi. Everything else — the cross-plane hints, the initiator hold — passes
 * through untouched.
 *
 * A switch-driven start waits out [restartFloorMs] since [inner] last stopped, and a switch-driven stop waits out
 * [upFloorMs] since it last started. Low-power mode moves this switch on Battery Saver and charger edges, which can
 * flap, and Wi-Fi Aware's stop and start race each other inside those windows: a start before the NAN-down that
 * follows a stop's responder unregister, or a stop while an attach is in flight, whose late `onAttached` keeps a
 * session nothing closes (the advisory review of ADR 2026-10.d44b's Phase 1). The mesh's own [start] and [stop] are
 * never floored. A [resume] always reaches [inner], up or not: the hand-over hold outlives [inner]'s stop, and only
 * its resume lifts it.
 */
@Suppress("TooManyFunctions") // the gated half of the radio seam: lifecycle, sends and the bulk arm
class SwitchableTransport(
    private val inner: MeshTransport,
    off: Flow<Boolean>,
    private val scope: CoroutineScope,
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
    // A monotonic millisecond clock for the floors; tests pass the scheduler's virtual time.
    private val clock: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
    private val restartFloorMs: Long = RESTART_FLOOR_MS,
    private val upFloorMs: Long = UP_FLOOR_MS,
) : MeshTransport by inner {
    private val lock = Any()

    // Guarded by [lock]: the mesh's own start/stop, the switch (null until its first read), and the hand-over pause.
    private var running = false
    private var offRead: Boolean? = null
    private var paused = false

    // Guarded by [lock]: when [inner] last started and stopped, on [clock], for the floors; and the floored reconcile.
    private var lastStartAt: Long? = null
    private var lastStopAt: Long? = null
    private var floored: Job? = null

    // Guarded by [lock]: a switch-driven start waiting out [restartFloorMs]; the plane is still Knit's to keep off.
    private var startDeferred = false

    // Written under [lock]; read bare by the send gates, which only need a recent answer.
    @Volatile private var innerUp = false

    // What actually happened, never what is wanted: [inner] is down and Knit is keeping it so (the switch reads off, or
    // a switched start waits out its floor). A deferred stop leaves it false while [inner] still runs and sends.
    private val offState = MutableStateFlow(false)

    override val health: StateFlow<TransportHealth> =
        combine(inner.health, offState) { health, isOff -> if (isOff) TransportHealth.Unavailable else health }
            .stateIn(scope, SharingStarted.Eagerly, inner.health.value)

    override val switchedOff: StateFlow<Boolean> = offState.asStateFlow()

    init {
        scope.launch {
            off.distinctUntilChanged().collect { value ->
                synchronized(lock) {
                    offRead = value
                    reconcileSwitched()
                }
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            running = true
            if (offRead == true) log("debug switch: ${inner.kind} is off — not starting")
            reconcile()
            publish()
        }
    }

    override fun stop() {
        synchronized(lock) {
            running = false
            reconcile()
            publish()
        }
    }

    override fun heal() {
        if (innerUp) inner.heal()
    }

    override fun pause() {
        synchronized(lock) {
            paused = true
            if (innerUp) inner.pause()
        }
    }

    override fun resume() {
        synchronized(lock) {
            paused = false
            // Down or up: a hand-over that outlived the inner stop is lifted only by its resume.
            inner.resume()
        }
    }

    override fun expectBulkTransfer(nodeId: String): Boolean = innerUp && inner.expectBulkTransfer(nodeId)

    override suspend fun send(
        wire: WireEnvelope,
        to: Peer?,
    ) {
        if (innerUp) inner.send(wire, to)
    }

    override fun fastFanout(wire: WireEnvelope) {
        if (innerUp) inner.fastFanout(wire)
    }

    override fun fastSend(
        wire: WireEnvelope,
        to: Peer,
    ) {
        if (innerUp) inner.fastSend(wire, to)
    }

    override suspend fun sendFile(
        file: File,
        to: Peer,
        meta: FileMeta,
    ): Boolean = innerUp && inner.sendFile(file, to, meta)

    override suspend fun sendDigest(
        to: Peer,
        ids: List<String>,
    ) {
        if (innerUp) inner.sendDigest(to, ids)
    }

    /**
     * A switch edge: [reconcile], unless that would start [inner] inside [restartFloorMs] of its last stop or stop it
     * inside [upFloorMs] of its last start, in which case it runs again once the floor is out (and then asks what the
     * switch wants by then). Caller holds [lock].
     */
    private fun reconcileSwitched() {
        val want = running && offRead == false
        val wait = floorLeft(want)
        floored?.cancel()
        floored = null
        startDeferred = wait > 0 && want
        if (wait > 0) {
            log("debug switch: ${inner.kind} ${if (want) "on" else "off"} in ${wait}ms (floor)")
            floored =
                scope.launch {
                    // stretches: a floor on awake time is what the race needs. A stop deferred into a doze waits for
                    // the next wake (the heartbeat at worst); a start deferred into one is the safe direction.
                    delay(wait)
                    synchronized(lock) {
                        floored = null
                        reconcileSwitched()
                    }
                }
            publish()
            return
        }
        val wasUp = innerUp
        reconcile()
        publish()
        if (wasUp != innerUp) log("debug switch: ${inner.kind} ${if (innerUp) "on → started" else "off → stopped"}")
    }

    /** How long the switch's [want] must still wait for its floor; 0 when it may apply now. Caller holds [lock]. */
    private fun floorLeft(want: Boolean): Long {
        val since =
            if (want && !innerUp) {
                lastStopAt
            } else if (!want && innerUp) {
                lastStartAt
            } else {
                null
            }
        val floor = if (want) restartFloorMs else upFloorMs
        return since?.let { floor - (clock() - it) } ?: 0L
    }

    /** Publishes [switchedOff] (and with it [health]) from what [inner] is actually doing. Caller holds [lock]. */
    private fun publish() {
        offState.value = !innerUp && (offRead == true || startDeferred)
    }

    /** Brings [inner] to what the mesh and the switch want. Caller holds [lock]. */
    private fun reconcile() {
        val want = running && offRead == false
        if (want && !innerUp) {
            inner.start()
            innerUp = true
            lastStartAt = clock()
            // The transport's own stop() forgot the hand-over; a pause still in force goes back on.
            if (paused) inner.pause()
        } else if (!want && innerUp) {
            inner.stop()
            innerUp = false
            lastStopAt = clock()
        }
    }

    companion object {
        /**
         * The least time between [inner] stopping and a switch starting it again: Wi-Fi Aware's own session-cycle
         * settle (`WifiAwareTransport.SESSION_CYCLE_SETTLE_MS`, 2 s, the NAN-down that wipes the stop's responder
         * ghost lands 29-49 ms after the close) with room to spare.
         */
        const val RESTART_FLOOR_MS = 5_000L

        /** The least time between [inner] starting and a switch stopping it: an attach and its publish settle first. */
        const val UP_FLOOR_MS = 10_000L
    }
}
