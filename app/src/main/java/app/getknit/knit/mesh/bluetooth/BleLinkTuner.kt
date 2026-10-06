package app.getknit.knit.mesh.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Asks one Bluetooth link's controller for a PHY, a connection priority or an ATT MTU, and reads back what the link
 * runs on (#120). The drain trial's lever: a GATT client attached to the link's own ACL is the only handle Android
 * gives on a link's parameters — the L2CAP socket has none — so this follows [BlePhyControl]'s lifecycle: [isLive]
 * re-checked just before `connectGatt` (on a dead ACL the call would dial), a 2 s attach window, no service discovery
 * (its unlock re-sends the central's first parameters, ADR 2026-09.dqvb), and the client closed with the link.
 *
 * It has no policy: each [tune] is one ask the debug bridge made, served in order by one coroutine, each step waiting
 * a bounded time for the controller's report. A request the controller leaves unanswered (a priority the link already
 * has reports nothing) is not an error; the status says what was heard. Device-verified only — there is no host GATT
 * stack.
 */
@SuppressLint("MissingPermission")
internal class BleLinkTuner(
    private val context: Context,
    private val device: BluetoothDevice,
    private val nodeId: String,
    private val scope: CoroutineScope,
    private val isLive: () -> Boolean,
    private val now: () -> Long,
) {
    private class Ask(
        val request: LinkTuneRequest,
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val asks = Channel<Ask>(Channel.UNLIMITED)
    private val attachedSignal = CompletableDeferred<Boolean>()
    private val phyReports = Channel<Int>(Channel.CONFLATED)
    private val paramReports = Channel<Boolean>(Channel.CONFLATED)
    private val mtuReports = Channel<Int>(Channel.CONFLATED)
    private val rssiReads = Channel<Int>(Channel.CONFLATED)
    private var job: Job? = null

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var attached = false

    @Volatile private var disconnected = false

    @Volatile private var txPhy: LinkPhy? = null

    @Volatile private var rxPhy: LinkPhy? = null

    @Volatile private var params: LinkParams? = null

    @Volatile private var paramsStatus: Int? = null

    @Volatile private var mtu: Int? = null

    @Volatile private var mtuAsked: Int? = null

    @Volatile private var rssi: Int? = null

    /** Whether this tuner can still serve an ask: started, and neither closed nor cut off from the link. */
    val alive: Boolean get() = job?.isActive == true && !disconnected

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** Stop, and let the GATT client go. What was asked stays on the link until the link or the other side moves it. */
    fun close() {
        job?.cancel()
    }

    /** Asks for what [request] names and returns the status once the asks are answered or given up on. */
    suspend fun tune(request: LinkTuneRequest): LinkTuneStatus {
        val ask = Ask(request)
        if (asks.trySend(ask).isSuccess) {
            // stretches: the bridge's own give-up on one ask; a sleep only returns the status read so far.
            withTimeoutOrNull(TUNE_TIMEOUT_MS) { ask.done.await() }
        }
        return status()
    }

    fun status(): LinkTuneStatus =
        LinkTuneStatus(
            nodeId = nodeId,
            address = device.address,
            attached = attached,
            txPhy = txPhy,
            rxPhy = rxPhy,
            params = params,
            paramsStatus = paramsStatus,
            mtu = mtu,
            mtuAsked = mtuAsked,
            rssi = rssi,
        )

    @Suppress("DEPRECATION") // compileSdk 37.1 deprecates connectGatt(Context, …); see BleDoorbell.Client.open
    private suspend fun loop() {
        try {
            if (!isLive()) return
            val started = now()
            val g = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            if (g == null) {
                Log.i(TAG, "bt tune $nodeId: connectGatt refused")
                return
            }
            gatt = g
            // stretches: give-up on a GATT attach over the link's own ACL; a sleep only delays closing the client.
            if (withTimeoutOrNull(ATTACH_TIMEOUT_MS) { attachedSignal.await() } != true) {
                Log.i(TAG, "bt tune $nodeId: attach failed")
                return
            }
            attached = true
            Log.i(TAG, "bt tune $nodeId attached (${device.address}) in ${now() - started}ms")
            for (ask in asks) {
                if (!disconnected) serve(g, ask.request)
                ask.done.complete(Unit)
            }
        } finally {
            attached = false
            asks.close()
            while (true) {
                asks
                    .tryReceive()
                    .getOrNull()
                    ?.done
                    ?.complete(Unit) ?: break
            }
            gatt?.let {
                // disconnect() drops this client's hold on the link (the channel keeps the ACL); close() unregisters it.
                runCatching { it.disconnect() }
                runCatching { it.close() }
            }
            gatt = null
        }
    }

    private suspend fun serve(
        g: BluetoothGatt,
        request: LinkTuneRequest,
    ) {
        request.phy?.let { askPhy(g, it) }
        request.priority?.let { askPriority(g, it) }
        request.mtu?.let { askMtu(g, it) }
        if (request.read) read(g)
    }

    private suspend fun askPhy(
        g: BluetoothGatt,
        phy: TunePhy,
    ) {
        val mask =
            when (phy) {
                TunePhy.ONE_M -> BluetoothDevice.PHY_LE_1M_MASK
                TunePhy.TWO_M -> BluetoothDevice.PHY_LE_2M_MASK
                TunePhy.ONE_M_2M -> BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
            }
        phyReports.tryReceive()
        val asked = runCatching { g.setPreferredPhy(mask, mask, BluetoothDevice.PHY_OPTION_NO_PREFERRED) }.isSuccess
        Log.i(TAG, "bt tune $nodeId ask phy=${phy.name} requested=$asked")
        // stretches: give-up on one PHY update; a sleep only ends the wait for a report that came or will not.
        if (asked) withTimeoutOrNull(REPORT_TIMEOUT_MS) { phyReports.receive() }
    }

    private suspend fun askPriority(
        g: BluetoothGatt,
        priority: TunePriority,
    ) {
        val code =
            when (priority) {
                TunePriority.HIGH -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
                TunePriority.BALANCED -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                TunePriority.LOW_POWER -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
            }
        paramReports.tryReceive()
        val asked = runCatching { g.requestConnectionPriority(code) }.getOrDefault(false)
        Log.i(TAG, "bt tune $nodeId ask priority=${priority.name} requested=$asked")
        // stretches: give-up on one parameter update; a link already on the asked values reports nothing.
        if (asked) withTimeoutOrNull(REPORT_TIMEOUT_MS) { paramReports.receive() }
    }

    private suspend fun askMtu(
        g: BluetoothGatt,
        size: Int,
    ) {
        mtuReports.tryReceive()
        mtuAsked = size
        val asked = runCatching { g.requestMtu(size) }.getOrDefault(false)
        Log.i(TAG, "bt tune $nodeId ask mtu=$size requested=$asked")
        // stretches: give-up on one ATT MTU exchange; a sleep only ends the wait for its answer.
        if (asked) withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuReports.receive() }
    }

    private suspend fun read(g: BluetoothGatt) {
        phyReports.tryReceive()
        if (runCatching { g.readPhy() }.isSuccess) {
            // stretches: give-up on one PHY read.
            withTimeoutOrNull(READ_TIMEOUT_MS) { phyReports.receive() }
        }
        rssiReads.tryReceive()
        if (runCatching { g.readRemoteRssi() }.getOrNull() == true) {
            // stretches: give-up on one RSSI read.
            withTimeoutOrNull(READ_TIMEOUT_MS) { rssiReads.receive() }?.let { rssi = it }
        }
    }

    private fun onPhy(
        tx: Int,
        rx: Int,
        status: Int,
        what: String,
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            txPhy = phyOf(tx)
            rxPhy = phyOf(rx)
        }
        Log.i(TAG, "bt tune $nodeId phy tx=${phyOf(tx)} rx=${phyOf(rx)} status=$status ($what)")
        phyReports.trySend(status)
    }

    private val callback =
        object : LinkParamsCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        attachedSignal.complete(true)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        disconnected = true
                        attachedSignal.complete(false)
                    }
                }
            }

            override fun onPhyRead(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onPhy(txPhy, rxPhy, status, "read")

            override fun onPhyUpdate(
                g: BluetoothGatt,
                txPhy: Int,
                rxPhy: Int,
                status: Int,
            ) = onPhy(txPhy, rxPhy, status, "update")

            override fun onLinkParams(
                params: LinkParams,
                ok: Boolean,
                status: Int,
            ) {
                // A colliding update (0x2A) reports values that are not the link's (dqvb's put-back amendment).
                if (ok) this@BleLinkTuner.params = params
                paramsStatus = status
                Log.i(
                    TAG,
                    "bt tune $nodeId params interval=${params.intervalUs}us latency=${params.latency} " +
                        "timeout=${params.timeoutMs}ms status=$status",
                )
                paramReports.trySend(ok)
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                // The size is what the exchange settled on whatever the status says (as MeshtasticGatt reads it).
                this@BleLinkTuner.mtu = mtu
                Log.i(TAG, "bt tune $nodeId mtu=$mtu asked=$mtuAsked status=$status")
                mtuReports.trySend(mtu)
            }

            override fun onReadRemoteRssi(
                g: BluetoothGatt,
                rssi: Int,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) rssiReads.trySend(rssi)
            }
        }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG

        // As BleDoorbell's: an attach to a live link answers in milliseconds; past this the call is dialing instead.
        const val ATTACH_TIMEOUT_MS = 2_000L

        // A PHY or parameter update is one LL procedure, a few connection events; one not back by now is not coming.
        const val REPORT_TIMEOUT_MS = 3_000L

        // MeshtasticGatt's: an ATT MTU exchange over a busy link can wait behind its traffic.
        const val MTU_TIMEOUT_MS = 10_000L

        // A PHY or RSSI read is one HCI command.
        const val READ_TIMEOUT_MS = 1_000L

        // Every step of one ask, with room to spare; the bridge's reply waits no longer.
        const val TUNE_TIMEOUT_MS = 20_000L

        fun phyOf(phy: Int): LinkPhy =
            when (phy) {
                BluetoothDevice.PHY_LE_1M -> LinkPhy.ONE_M
                BluetoothDevice.PHY_LE_2M -> LinkPhy.TWO_M
                BluetoothDevice.PHY_LE_CODED -> LinkPhy.CODED
                else -> LinkPhy.UNKNOWN
            }
    }
}
