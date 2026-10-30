package app.getknit.knit.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A generic system image over an older vendor, told from the build fingerprints (ADRs 2026-10.w3dw and 2026-10.kw29). */
class SystemImageTest {
    @Test
    fun theLabMotoIsAGsiOverAnOlderVendor() {
        assertTrue(SystemImage.isGenericOnOlderVendor(MOTO_SYSTEM, MOTO_VENDOR))
    }

    @Test
    fun aStockPhoneWhosePartitionsShareOneBuildIsNot() {
        assertFalse(SystemImage.isGenericOnOlderVendor(PIXEL, PIXEL))
    }

    @Test
    fun aQualcommSingleSystemImageOverAnOlderVendorIsNot() {
        // An OEM's `qssi` system partition carries names of its own, and a frozen vendor can be older: neither is a GSI.
        assertFalse(SystemImage.isGenericOnOlderVendor(QSSI_SYSTEM, XIAOMI_VENDOR))
    }

    @Test
    fun aGsiOverAVendorOfItsOwnReleaseIsLeftAlone() {
        val vendor14 = "motorola/sofia_retail/sofia:14/U1RPS34.1/abcd:user/release-keys"
        assertFalse(SystemImage.isGenericOnOlderVendor(MOTO_SYSTEM, vendor14))
    }

    @Test
    fun googlesAndPhhsGsisAreNamedToo() {
        assertTrue(SystemImage.isGenericOnOlderVendor("google/gsi_arm64/gsi_arm64:14/UP1A/1:userdebug/test-keys", MOTO_VENDOR))
        assertTrue(SystemImage.isGenericOnOlderVendor("phh/phhgsi_arm64_ab/phhgsi_arm64_ab:13/TQ3A/1:user/test-keys", MOTO_VENDOR))
    }

    @Test
    fun anythingMissingOrMalformedIsNotGeneric() {
        assertFalse(SystemImage.isGenericOnOlderVendor(null, MOTO_VENDOR))
        assertFalse(SystemImage.isGenericOnOlderVendor(MOTO_SYSTEM, null))
        assertFalse(SystemImage.isGenericOnOlderVendor("not a fingerprint", MOTO_VENDOR))
        assertFalse(SystemImage.isGenericOnOlderVendor(MOTO_SYSTEM, "motorola/sofia_retail/sofia:Tiramisu/x/y:user/release-keys"))
        assertNull(SystemImage.parse("a/b:14/x:user/keys"))
    }

    @Test
    fun theReleaseIsItsMajorNumber() {
        assertEquals(12, SystemImage.parse("google/x/y:12L/SP2A/1:user/release-keys")?.release)
    }

    @Test
    fun eitherSystemFingerprintMayNameTheGsi() {
        // A GSI dressed up as a stock phone in ro.build.fingerprint still names itself on the system partition.
        val partitions = mapOf(DeviceImage.PARTITION_SYSTEM to MOTO_SYSTEM, DeviceImage.PARTITION_VENDOR to MOTO_VENDOR)
        assertTrue(DeviceImage.read(fingerprint = PIXEL, partitions = partitions).genericOnOlderVendor)
        assertTrue(DeviceImage.read(MOTO_SYSTEM, mapOf(DeviceImage.PARTITION_VENDOR to MOTO_VENDOR)).genericOnOlderVendor)
        assertFalse(DeviceImage.read(MOTO_SYSTEM, emptyMap()).genericOnOlderVendor)
    }

    private companion object {
        // Read off the lab's Moto G Power (2026-10-08 logcat): TrebleDroid's LineageOS GSI over the stock Android 11 vendor.
        const val MOTO_SYSTEM =
            "google/lineage_arm64_bgN/tdgsi_arm64_ab:14/AP2A.240905.003/eng.crossg.20250621.234934:userdebug/release-keys"
        const val MOTO_VENDOR = "motorola/sofia_retail/sofia:11/rpms31.q1-54-13-11/e171d1:user/release-keys"
        const val PIXEL = "google/tokay/tokay:16/BP2A.250805.005/13691446:user/release-keys"
        const val QSSI_SYSTEM = "qti/qssi/qssi:13/TKQ1.221114.001/V14.0.3.0:user/release-keys"
        const val XIAOMI_VENDOR = "Xiaomi/alioth_global/alioth:11/RKQ1.200826.002/V12.0.4.0:user/release-keys"
    }
}
