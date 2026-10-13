package app.getknit.knit.call

/**
 * One place a call's audio can go (docs/VOICE_CALLS.md §3.13): an endpoint the system offers the call, which carries
 * both directions — a headset's microphone with its earpieces. [key] is the system's identifier for it, stable while it
 * stays available; [name] is the system's own label ("Pixel Buds Pro", "Earpiece").
 */
data class CallAudioRoute(
    val kind: Kind,
    val name: String,
    val key: String,
) {
    enum class Kind { EARPIECE, SPEAKER, WIRED, BLUETOOTH, OTHER }

    /** Something the user wears: it takes a call of either kind, microphone included. */
    val headset: Boolean get() = kind == Kind.WIRED || kind == Kind.BLUETOOTH
}

/** Where the call's audio goes now ([current], null until the system says), and where else it could. */
data class CallRouting(
    val current: CallAudioRoute?,
    val available: List<CallAudioRoute>,
)

/**
 * The route rules both call kinds share (§3.13). The system (Telecom) picks a call's route and follows a headset that
 * comes or goes; these say only where a walkie differs, and what the notification's one route action does.
 */
object CallAudioPolicy {
    /**
     * The route a call asks to start on: the loudspeaker for a walkie session with no headset available — a walkie is
     * heard aloud — and the system's own choice (null) otherwise, which is the earpiece for a live call and a headset
     * whenever one is connected.
     */
    fun startRoute(
        walkie: Boolean,
        available: List<CallAudioRoute>,
    ): CallAudioRoute? =
        if (!walkie || available.any { it.headset }) null else available.firstOrNull { it.kind == CallAudioRoute.Kind.SPEAKER }

    /**
     * What a walkie session asks for when its route has moved to the earpiece without the user asking — a headset
     * unplugged or out of range, or a start the system did not honour — since nobody holds a walkie to their ear: the
     * loudspeaker. Null when nothing needs asking: the route is not the earpiece, the user picked it ([askedFor]), or
     * there is no loudspeaker. Live calls are never corrected.
     */
    fun walkieCorrection(
        routing: CallRouting,
        askedFor: CallAudioRoute?,
    ): CallAudioRoute? {
        val current = routing.current ?: return null
        if (current.kind != CallAudioRoute.Kind.EARPIECE || askedFor?.key == current.key) return null
        return routing.available.firstOrNull { it.kind == CallAudioRoute.Kind.SPEAKER }
    }

    /**
     * The notification's one route action: onto the loudspeaker, or off it to the best other route — a Bluetooth
     * headset, then a wired one, then the earpiece. Null when there is nowhere to go.
     */
    fun notificationTarget(routing: CallRouting): CallAudioRoute? =
        if (routing.current?.kind == CallAudioRoute.Kind.SPEAKER) {
            routing.available.filter { it.kind != CallAudioRoute.Kind.SPEAKER }.minByOrNull { OFF_SPEAKER.indexOf(it.kind) }
        } else {
            routing.available.firstOrNull { it.kind == CallAudioRoute.Kind.SPEAKER }
        }

    // Off the speaker, best first.
    private val OFF_SPEAKER =
        listOf(CallAudioRoute.Kind.BLUETOOTH, CallAudioRoute.Kind.WIRED, CallAudioRoute.Kind.EARPIECE, CallAudioRoute.Kind.OTHER)
}
