package app.getknit.knit.call

import app.getknit.knit.mesh.protocol.CallPayload

/** The mesh seam of a voice call: one sealed `CTL_CALL` DM to [peerId]. False when nothing could be sealed. */
fun interface CallSignals {
    suspend fun sendCallSignal(
        peerId: String,
        payload: CallPayload,
    ): Boolean
}
