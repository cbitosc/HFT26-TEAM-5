package app.getknit.knit.mesh.spool

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** A device key in software, for the JVM tests: the Keystore is not there. [refuse] makes every call a refusal. */
class SoftwareDeviceSigner(
    val pair: KeyPair = newPair(),
    var refuse: Boolean = false,
) : DeviceSigner {
    var signed = 0

    override suspend fun publicKey(): ByteArray? = if (refuse) null else pair.public.encoded

    override suspend fun sign(message: ByteArray): ByteArray? {
        if (refuse) return null
        signed++
        return Signature
            .getInstance("SHA256withECDSA")
            .apply {
                initSign(pair.private)
                update(message)
            }.sign()
    }

    companion object {
        fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        /** The fixed pair `DeviceProofVectorTest` pins, from its PKCS#8 and SPKI DER. */
        fun fixedPair(): KeyPair {
            val factory = KeyFactory.getInstance("EC")
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(DeviceProofVectorTest.SPKI.hex())),
                factory.generatePrivate(PKCS8EncodedKeySpec(DeviceProofVectorTest.PKCS8.hex())),
            )
        }

        fun publicKeyOf(spki: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

        /** Whether [signature] (DER) verifies over [message] under [spki]. */
        fun verifies(
            spki: ByteArray,
            message: ByteArray,
            signature: ByteArray,
        ): Boolean =
            Signature
                .getInstance("SHA256withECDSA")
                .apply {
                    initVerify(publicKeyOf(spki))
                    update(message)
                }.verify(signature)

        fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * A Keystore that answers from a script: the pairs it holds by alias, what given aliases' lookups say, and what the next
 * listings, generations and signatures say. Records what it was asked, so a test can say nothing was made over a pair
 * or deleted.
 */
class FakeDeviceKeystore : DeviceKeystore {
    val pairs = linkedMapOf<String, SoftwarePair>()

    // Alias → what its lookups say instead of the honest answer.
    val lookAs = mutableMapOf<String, DeviceKeystore.Look>()
    val makes = ArrayDeque<DeviceKeystore.Made>()
    var signs: () -> DeviceKeystore.Signed? = { null }
    var listRefused = false

    // Aliases a listing leaves out though the pair is there: the legacy keystore's dead daemon.
    val hidden = mutableSetOf<String>()

    var generated = 0
    var overwrote = false
    val deleted = mutableListOf<String>()

    override fun look(alias: String): DeviceKeystore.Look =
        lookAs[alias] ?: pairs[alias]?.let { DeviceKeystore.Look.Present(it) } ?: DeviceKeystore.Look.Absent

    override fun generate(alias: String): DeviceKeystore.Made {
        makes.removeFirstOrNull()?.let { return it }
        if (alias in pairs) overwrote = true
        generated++
        val pair = SoftwarePair()
        pairs[alias] = pair
        return DeviceKeystore.Made.Pair(pair)
    }

    override fun delete(alias: String): Boolean {
        deleted += alias
        pairs.remove(alias)
        lookAs.remove(alias)
        return true
    }

    override fun aliases(prefix: String): List<String>? =
        if (listRefused) null else (pairs.keys + lookAs.keys).distinct().filter { it.startsWith(prefix) && it !in hidden }

    inner class SoftwarePair : DeviceKeystore.RawPair {
        val pair: KeyPair = SoftwareDeviceSigner.newPair()

        override fun publicKey(): ByteArray = pair.public.encoded

        override fun sign(message: ByteArray): DeviceKeystore.Signed =
            signs() ?: DeviceKeystore.Signed.Signature(
                Signature
                    .getInstance("SHA256withECDSA")
                    .apply {
                        initSign(pair.private)
                        update(message)
                    }.sign(),
            )
    }
}

/** [DeviceKeyMarks] in memory; [broken] makes every call throw, as a DataStore that cannot write would. */
class MemoryDeviceKeyMarks : DeviceKeyMarks {
    val map = linkedMapOf<String, String>()
    var broken = false

    override suspend fun accepted(): Map<String, String> {
        check(!broken) { "datastore" }
        return map.toMap()
    }

    override suspend fun putAccepted(
        slot: String,
        id: String,
    ) {
        check(!broken) { "datastore" }
        map[slot] = id
    }

    override suspend fun removeAccepted(slot: String) {
        check(!broken) { "datastore" }
        map.remove(slot)
    }
}

/**
 * A keyring that hands out one software signer per (host, key) under the id [ID], or what [next] says; records what it
 * was asked.
 */
class FakeDeviceKeyring : DeviceKeyring {
    val signers = linkedMapOf<Pair<String, String>, SoftwareDeviceSigner>()
    var next: ((Set<String>) -> DeviceKey)? = null
    val created = mutableListOf<Pair<String, String>>()
    val avoided = mutableListOf<Set<String>>()
    val acceptedIds = mutableListOf<String>()
    var retained = 0

    override suspend fun create(
        host: String,
        key: String,
    ) {
        created += host to key
    }

    override suspend fun forKey(
        host: String,
        key: String,
        avoid: Set<String>,
    ): DeviceKey {
        avoided += avoid
        return next?.invoke(avoid) ?: DeviceKey.Ready(signers.getOrPut(host to key) { SoftwareDeviceSigner() }, ID)
    }

    override suspend fun accepted(
        host: String,
        key: String,
        id: String,
    ) {
        acceptedIds += id
    }

    override suspend fun retain() {
        retained++
    }

    companion object {
        const val ID = "pair-a"
    }
}
