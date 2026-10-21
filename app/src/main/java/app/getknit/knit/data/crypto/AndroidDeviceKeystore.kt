package app.getknit.knit.data.crypto

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.getknit.knit.mesh.spool.DeviceKeystore
import java.security.InvalidAlgorithmParameterException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The Android Keystore under `mesh/spool/DeviceKeys` (docs/VOICE_CALLS.md C-7.6-39, ADR 2026-10.wk3m): an ECDSA P-256
 * signing pair per customer key, non-exportable, StrongBox-backed where the phone has StrongBox
 * ([PackageManager.FEATURE_STRONGBOX_KEYSTORE]) and a plain Keystore key otherwise. P-256 rather than Ed25519 because
 * the Keystore has Ed25519 only from API 33. No user-authentication or unlocked-device requirement: the ticket refresher
 * asks screen-off.
 *
 * It answers what the Keystore said and decides nothing: [look] uses `getKey`, which answers null only for
 * KEY_NOT_FOUND and throws for every backend error (never `getEntry` or `containsAlias`, which read a busy keystore2 as
 * no key, ADR 2026-10.47rw); a throw is [DeviceKeystore.Look.Refused], and only the classes that say the phone cannot
 * make the key at all are [DeviceKeystore.Made.Unsupported]. `generate` is only ever handed a new alias, so it never
 * replaces a pair. Nothing it touches is logged.
 */
class AndroidDeviceKeystore(
    packageManager: PackageManager,
) : DeviceKeystore {
    private val strongBox = packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Suppress("TooGenericExceptionCaught") // every Keystore failure is a refusal here; the caller decides on it
    override fun look(alias: String): DeviceKeystore.Look =
        try {
            val store = keyStore()
            when (val key = store.getKey(alias, null)) {
                null -> {
                    DeviceKeystore.Look.Absent
                }

                is PrivateKey -> {
                    // No certificate, and no throw: a husk the process died inside generating, or keystore2's null for a busy backend.
                    val certificate = store.getCertificate(alias) ?: return DeviceKeystore.Look.Broken
                    certificate.publicKey
                        ?.encoded
                        ?.let { DeviceKeystore.Look.Present(Pair(key, it)) }
                        ?: DeviceKeystore.Look.Refused
                }

                else -> {
                    DeviceKeystore.Look.Refused
                }
            }
        } catch (_: Exception) {
            DeviceKeystore.Look.Refused
        }

    @Suppress("TooGenericExceptionCaught")
    override fun generate(alias: String): DeviceKeystore.Made =
        try {
            // StrongBox first where the phone has it; any failure there falls back to the TEE under the same new alias,
            // which nothing else holds, so a StrongBox that always fails never leaves the phone without a pair.
            val pair =
                if (strongBox) {
                    try {
                        generate(alias, strongBox = true)
                    } catch (_: Exception) {
                        runCatching { keyStore().deleteEntry(alias) }
                        generate(alias, strongBox = false)
                    }
                } else {
                    generate(alias, strongBox = false)
                }
            DeviceKeystore.Made.Pair(Pair(pair.private, pair.public.encoded))
        } catch (e: Exception) {
            when (e) {
                is NoSuchAlgorithmException, is NoSuchProviderException, is InvalidAlgorithmParameterException,
                is UnsupportedOperationException,
                -> DeviceKeystore.Made.Unsupported

                else -> DeviceKeystore.Made.Refused
            }
        }

    @Suppress("TooGenericExceptionCaught")
    override fun delete(alias: String): Boolean =
        try {
            keyStore().deleteEntry(alias)
            true
        } catch (_: Exception) {
            false
        }

    @Suppress("TooGenericExceptionCaught")
    override fun aliases(prefix: String): List<String>? =
        try {
            keyStore().aliases().toList().filter { it.startsWith(prefix) }
        } catch (_: Exception) {
            null
        }

    private fun generate(
        alias: String,
        strongBox: Boolean,
    ): java.security.KeyPair {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyPairGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
    }

    /** One pair's handle: the non-exportable private key and the public key's SPKI DER. Names neither. */
    private class Pair(
        private val privateKey: PrivateKey,
        private val spki: ByteArray,
    ) : DeviceKeystore.RawPair {
        override fun publicKey(): ByteArray = spki.copyOf()

        @Suppress("TooGenericExceptionCaught")
        override fun sign(message: ByteArray): DeviceKeystore.Signed =
            try {
                val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply { initSign(privateKey) }
                signature.update(message)
                DeviceKeystore.Signed.Signature(signature.sign())
            } catch (e: Exception) {
                if (KeystoreFailure.classify(e) == Loss.KEY_INVALIDATED) DeviceKeystore.Signed.Lost else DeviceKeystore.Signed.Refused
            }

        override fun toString(): String = "DevicePair"
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val EC_CURVE = "secp256r1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}
