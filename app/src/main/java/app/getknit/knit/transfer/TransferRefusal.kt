package app.getknit.knit.transfer

/** Why a transfer could not be offered or accepted right now; the UI maps each to a sentence. */
enum class TransferRefusal {
    /** No Wi-Fi Direct on this device, or the platform refused the group outright. */
    NoWifiDirect,
    WifiOff,
    Permission,

    /** Another transfer is still in progress on this device. */
    Busy,
    Unreadable,
    TooLarge,
    NotNearby,

    /** The offer could not be sealed to the peer (no pinned key / session). */
    NoSession,
    NoSpace,

    /** Something else — a hotspot, screen mirroring, a foreign group — already holds Wi-Fi Direct. */
    Hotspot,

    /**
     * The platform refused because Knit is not on screen. Below API 33 Wi-Fi Direct is gated on the
     * *location* AppOp, which a foreground-only grant does not satisfy from the background.
     */
    Background,

    /** The offer being answered is no longer open (expired, cancelled, already answered). */
    Gone,

    /**
     * This phone does not host Wi-Fi Direct groups (ADR 2026-10.w3dw): hosting takes it down — a generic system image
     * over an older vendor, or a latch after it died hosting. It can still receive, which only joins. Local only.
     */
    CannotHost,
}
