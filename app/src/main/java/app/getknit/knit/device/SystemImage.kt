package app.getknit.knit.device

/**
 * Whether this phone runs a **generic system image over an older vendor image**: a GSI (Project Treble) such as
 * TrebleDroid's, flashed over the vendor partition an older Android shipped with. The framework is new and the
 * vendor's HALs are not, and two of them fail in ways the framework does not survive:
 *
 * - **Wi-Fi Direct hosting.** A client joining a group this phone hosts crashes `system_server` (an NPE in
 *   `WifiP2pGroup.setClientInterfaceMacAddress` from `GroupCreatedState`: the old supplicant HAL reports no client
 *   interface address). Joining someone else's group works (ADR 2026-10.w3dw).
 * - **Voice capture for a call.** `VOICE_COMMUNICATION` in a call's audio mode opens the vendor's VoIP record route,
 *   which the old HAL does not have (`unable to find path 'audio-record-voip'`, `start_input_stream: cannot open
 *   device`), so the call's microphone hands WebRTC nothing but zeros. `VOICE_RECOGNITION` records normally (ADR
 *   2026-10.kw29).
 *
 * Read from two build fingerprints (`brand/product/device:release/id/incremental:type/tags`): the system image's
 * (`Build.FINGERPRINT`) and the vendor partition's (`Build.getFingerprintedPartitions()`, public since API 29). Both
 * must say so. The system image's product or device names a GSI, which an OEM build never does: a Qualcomm OEM's
 * system partition can be a `qssi` single system image with names of its own, which "names differ" alone would catch.
 * And the vendor's Android release is older than the system's, so a GSI on a vendor of its own release, whose HALs
 * the framework was built against, is left alone. The lab's Moto G Power reads
 * `google/lineage_arm64_bgN/tdgsi_arm64_ab:14/…` over `motorola/sofia_retail/sofia:11/…`.
 *
 * Pure, so its test needs no Robolectric: the two strings are read at wiring.
 */
object SystemImage {
    /** True when [system] is a GSI's fingerprint and [vendor]'s Android release is older than [system]'s. */
    fun isGenericOnOlderVendor(
        system: String?,
        vendor: String?,
    ): Boolean {
        val sys = system?.let(::parse) ?: return false
        val ven = vendor?.let(::parse) ?: return false
        val sysRelease = sys.release ?: return false
        val venRelease = ven.release ?: return false
        return sys.namesGsi() && venRelease < sysRelease
    }

    /** One fingerprint's fields; [release] is its Android release's major number, null when it has none. */
    internal data class Print(
        val brand: String,
        val product: String,
        val device: String,
        val release: Int?,
    ) {
        fun namesGsi(): Boolean = GSI_MARK in product.lowercase() || GSI_MARK in device.lowercase()
    }

    /** Splits a build fingerprint, or null when it is not one. */
    internal fun parse(fingerprint: String): Print? {
        val parts = fingerprint.trim().split(':')
        if (parts.size < COLON_FIELDS) return null
        val names = parts[0].split('/')
        if (names.size != NAME_FIELDS || names.any { it.isEmpty() }) return null
        val release = parts[1].substringBefore('/').takeWhile(Char::isDigit).toIntOrNull()
        return Print(brand = names[0], product = names[1], device = names[2], release = release)
    }

    /**
     * What every known GSI's names carry: Google's (`gsi_arm64`), phh's (`phhgsi_arm64_ab`) and TrebleDroid's
     * (`tdgsi_arm64_ab`).
     */
    private const val GSI_MARK = "gsi"
    private const val NAME_FIELDS = 3

    /** `names:release/id/incremental:type/tags`. */
    private const val COLON_FIELDS = 3
}

/**
 * The device facts read once at wiring and shared by everything that works around this phone's image: whether it is
 * a generic system image over an older vendor ([SystemImage]). Hosting a Wi-Fi Direct group (ADR 2026-10.w3dw) and a
 * call's microphone (ADR 2026-10.kw29) both read it.
 */
data class DeviceImage(
    val genericOnOlderVendor: Boolean,
) {
    companion object {
        /**
         * This phone's facts, from its build fingerprints: the system partition's and `Build.FINGERPRINT` (either may
         * name the GSI; a GSI can dress one up as a stock phone's), against the vendor partition's.
         */
        fun read(
            fingerprint: String?,
            partitions: Map<String, String>,
        ): DeviceImage {
            val vendor = partitions[PARTITION_VENDOR]
            val generic =
                listOf(partitions[PARTITION_SYSTEM], fingerprint).distinct().any { SystemImage.isGenericOnOlderVendor(it, vendor) }
            return DeviceImage(genericOnOlderVendor = generic)
        }

        const val PARTITION_SYSTEM = "system"
        const val PARTITION_VENDOR = "vendor"
    }
}
