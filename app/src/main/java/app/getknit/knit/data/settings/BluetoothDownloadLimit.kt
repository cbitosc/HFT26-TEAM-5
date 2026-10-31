package app.getknit.knit.data.settings

/**
 * The user's "Ask before large Bluetooth downloads" choice (ADR 2026-10.js2p): the declared size above which a
 * received attachment waits for a tap before its fetch may ride Bluetooth. Stored by name; [MB_1] is the default.
 */
enum class BluetoothDownloadLimit(
    /** The limit in bytes (decimal, as `Formatter.formatShortFileSize` prints them), null for never asking. */
    val bytes: Long?,
) {
    NEVER(null),
    MB_1(MEGABYTE),
    MB_5(FIVE_MEGABYTES),
    ;

    companion object {
        val DEFAULT = MB_1

        /** The stored string, read back. Unknown or absent means [DEFAULT]. */
        fun of(stored: String?): BluetoothDownloadLimit = entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}

private const val MEGABYTE = 1_000_000L
private const val FIVE_MEGABYTES = 5_000_000L
