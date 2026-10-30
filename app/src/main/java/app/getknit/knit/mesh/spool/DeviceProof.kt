package app.getknit.knit.mesh.spool

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/**
 * The device proof a customer key's every request carries (docs/VOICE_CALLS.md C-7.6-40, C-7.6-41, ADR 2026-10.wk3m;
 * the issuer's half is knit-spool-coordinator ADR 2026-10.473c): the device key's SPKI DER, the time, and an ECDSA
 * signature over [message], the two binary values in unpadded base64url. A free key (C-7.6-37) carries none.
 */
object DeviceProof {
    const val LABEL = "knit/issuer/v1/device"

    const val KEY_HEADER = "Knit-Device-Key"
    const val TIME_HEADER = "Knit-Device-Time"
    const val SIGNATURE_HEADER = "Knit-Device-Signature"

    /** C-7.6-43: a `bad-proof` whose `Date` is further than this from the phone's clock is re-signed once on the issuer's. */
    const val MAX_SKEW_MS = 60_000L

    /** `m = LABEL ‖ 0x00 ‖ path ‖ 0x00 ‖ u64be(t) ‖ SHA-256(k)`, the label, [path] and [key] in ASCII with no terminator. */
    fun message(
        path: String,
        t: Long,
        key: String,
    ): ByteArray {
        val label = LABEL.toByteArray(Charsets.US_ASCII)
        val route = path.toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII))
        return ByteBuffer
            .allocate(label.size + 1 + route.size + 1 + Long.SIZE_BYTES + digest.size)
            .put(label)
            .put(0)
            .put(route)
            .put(0)
            .putLong(t)
            .put(digest)
            .array()
    }

    /** Unpadded base64url, the headers' encoding. */
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * One customer key's device key, as a request signs with it: a Keystore pair in production (`AndroidDeviceKeystore`),
 * a software pair in the JVM tests. Neither call throws; a null is a refusal, and the request it was for is not sent
 * (ADR 2026-10.wk3m: an unsigned request to a bound account would be answered 409 and park the key). Its [toString]
 * names nothing.
 */
interface DeviceSigner {
    /** The public key's SPKI DER, or null when the Keystore refused it. */
    suspend fun publicKey(): ByteArray?

    /** ECDSA over SHA-256 of [message], DER, or null when the Keystore refused. */
    suspend fun sign(message: ByteArray): ByteArray?
}

/** What a request may do with a customer key's device key (docs/VOICE_CALLS.md C-7.6-45 as ADR 2026-10.wk3m amends it). */
sealed interface DeviceKey {
    /**
     * Sign with [signer], the pair whose [id] (its alias's suffix, never logged) a 409 or an issuer's 200 is recorded
     * against.
     */
    class Ready(
        val signer: DeviceSigner,
        val id: String,
    ) : DeviceKey {
        override fun toString(): String = "Ready"
    }

    /** This phone holds no pair for the key and cannot make one: the request goes without the three headers. */
    data object Unmade : DeviceKey

    /** The Keystore refused, and a pair this phone made may still be there: ask nothing now, back off as no verdict. */
    data object Refused : DeviceKey
}

/**
 * The device keys this phone holds, one or more pairs per customer key (C-7.6-39). Implemented by [DeviceKeys] over the
 * Keystore; a build without Knit Anywhere has none, and a request then carries no proof. No call throws.
 */
interface DeviceKeyring {
    /** Makes [key]'s pair for [host] if it holds none, when the key arrives (C-7.6-39). A failure is left for [forKey]. */
    suspend fun create(
        host: String,
        key: String,
    )

    /**
     * The pair to sign [key]'s next request for [host] with: the one the issuer last accepted, else the oldest, past any
     * in [avoid] (pairs a 409 already refused) while another is usable. Made now when none is held (C-7.6-46).
     */
    suspend fun forKey(
        host: String,
        key: String,
        avoid: Set<String> = emptySet(),
    ): DeviceKey

    /** The issuer answered a proof from pair [id] with a 200: it is the bound one, and the key's other pairs go. */
    suspend fun accepted(
        host: String,
        key: String,
        id: String,
    )

    /** Deletes every pair whose customer key is no longer held: a replaced key, a removed relay, a restore. */
    suspend fun retain()
}
