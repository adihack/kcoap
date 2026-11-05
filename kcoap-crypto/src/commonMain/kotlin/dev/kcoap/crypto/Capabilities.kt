package dev.kcoap.crypto

// Capability checks: whether a backend runs an algorithm on the platform it is running on.
//
// Platforms differ in what their cryptography offers (WebCrypto has no ChaCha20/Poly1305, older browsers
// no X25519, a secure element perhaps only P-256), and a CryptoBackend reports what it cannot do with
// UnsupportedAlgorithmException when the algorithm is asked for. These checks ask in exactly that way,
// so they mean the same on every platform and for every backend, and they cost one key or key pair.
// Configuration uses them to offer only what works, such as the EDHOC cipher suites a browser can run.

/** Whether this backend provides [algorithm], asked for as an AEAD under an all-zero key. */
public suspend fun CryptoBackend.supports(algorithm: AeadAlgorithm): Boolean = isSupported {
    aead(algorithm, ByteArray(algorithm.keyLength))
}

/** Whether this backend generates (and so works with) key pairs of [type]. */
public suspend fun CryptoBackend.supports(type: KeyType): Boolean = isSupported {
    destroy(generateKeyPair(type))
}

/** Whether this backend signs with [algorithm]: it generates a key pair and signs an empty message. */
public suspend fun CryptoBackend.supports(algorithm: SignatureAlgorithm): Boolean = isSupported {
    val pair = generateKeyPair(algorithm.keyType)
    try {
        sign(algorithm, pair.privateKey, ByteArray(0))
    } finally {
        destroy(pair)
    }
}

private inline fun isSupported(probe: () -> Unit): Boolean = try {
    probe()
    true
} catch (_: UnsupportedAlgorithmException) {
    false
}

private fun destroy(pair: KeyPair) {
    (pair.privateKey as? SoftwarePrivateKey)?.destroy()
}
