package dev.kcoap.edhoc

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Collects the intermediate values a state machine records, by RFC 9529 name. */
internal class RecordedValues : IntermediateValues {
    private val values = mutableMapOf<String, ByteArray>()

    override fun record(name: String, value: ByteArray) {
        values[name] = value
    }

    operator fun get(name: String): ByteArray = values[name] ?: fail("$name was not recorded; recorded: ${values.keys}")

    fun has(name: String): Boolean = name in values

    /** Asserts that [name] was recorded with the value [expectedHex]. */
    fun assertValue(name: String, expectedHex: String) {
        assertEquals(expectedHex, get(name).toHex(), name)
    }
}

internal fun assertHex(expectedHex: String, actual: ByteArray, message: String? = null) {
    assertEquals(expectedHex, actual.toHex(), message)
}

internal fun String.hex(): ByteArray = hexToBytes()

/** Hands out the given key pairs in order, checking each is of the requested type. */
internal fun sequenceOfKeys(vararg pairs: suspend (CryptoBackend) -> KeyPair): EphemeralKeySource {
    var next = 0
    return EphemeralKeySource { backend, type ->
        val pair = pairs.getOrNull(next++)?.invoke(backend) ?: fail("No more ephemeral keys")
        assertEquals(type, pair.type, "ephemeral key type")
        pair
    }
}

/** An ephemeral key pair from fixed bytes, imported freshly each time it is used. */
internal fun fixedKey(type: KeyType, privateHex: String, publicHex: String): suspend (CryptoBackend) -> KeyPair =
    { backend -> backend.importKeyPair(type, privateHex.hex(), publicHex.hex()) }

/** Hands out the given connection identifiers in order. */
internal fun sequenceOfIdentifiers(vararg identifiers: ConnectionIdentifier): ConnectionIdentifierProvider {
    var next = 0
    return ConnectionIdentifierProvider { _, _ ->
        identifiers.getOrNull(next++) ?: fail("No more connection identifiers")
    }
}

internal fun cid(hex: String): ConnectionIdentifier = ConnectionIdentifier(hex.hex())

/**
 * Lets a trace with signatures be replayed byte for byte on every backend.
 *
 * Ed25519 is deterministic in RFC 8032, but a platform may hedge it with randomness (Apple's CryptoKit
 * does), which is allowed and interoperates. Where the backend signs the same data twice alike, its own
 * signature is used, so the trace checks it. Where it does not, this checks that the backend's signature
 * verifies and then returns the trace's [expected] signature over the same data, so every other byte of
 * the trace is still compared.
 */
internal class TraceSignatures(
    private val backend: CryptoBackend,
    private val publicKeys: List<ByteArray>,
    private val expected: List<ByteArray>,
) : CryptoBackend by backend {
    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray {
        val own = backend.sign(algorithm, privateKey, data)
        if (own contentEquals backend.sign(algorithm, privateKey, data)) return own
        val publicKey = publicKeys.firstOrNull { backend.verify(algorithm, it, data, own) }
        assertTrue(publicKey != null, "the backend's own ${algorithm.name} signature must verify")
        return expected.firstOrNull { backend.verify(algorithm, publicKey, data, it) } ?: own
    }
}
