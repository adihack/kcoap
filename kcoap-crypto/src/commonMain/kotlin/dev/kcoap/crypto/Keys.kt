package dev.kcoap.crypto

import dev.kcoap.common.wipe

/**
 * A private key, as a handle owned by a [CryptoBackend]. Protocol code never sees key material: it
 * passes the handle back to the backend that created it. A backend for a secure element, Android
 * KeyStore or the Secure Enclave subclasses this with a reference to the stored key.
 *
 * `toString()` never reveals key material.
 */
public abstract class PrivateKey protected constructor(public val type: KeyType) {
    override fun toString(): String = "PrivateKey($type)"
}

/**
 * A private key held in memory, used by software backends. [encoded] hands the raw bytes to the
 * backend that performs the operation; nothing else should call it.
 */
public class SoftwarePrivateKey(type: KeyType, encoded: ByteArray) : PrivateKey(type) {
    private val material = encoded.copyOf()

    /** A copy of the raw private key: scalar for EC, seed for Ed25519, FIPS 203/204 encoding for ML-*. */
    public fun encoded(): ByteArray = material.copyOf()

    /** Overwrites the key material; the key is unusable afterwards. */
    public fun destroy() {
        material.wipe()
    }
}

/** A public key in the raw encoding of its [KeyType], with the handle of its private key. */
public class KeyPair(publicKey: ByteArray, public val privateKey: PrivateKey) {
    private val publicBytes = publicKey.copyOf()

    public val type: KeyType get() = privateKey.type

    public val publicKey: ByteArray get() = publicBytes.copyOf()

    override fun toString(): String = "KeyPair($type)"
}

/** The result of a KEM encapsulation: [ciphertext] goes to the key owner, [sharedSecret] stays here. */
public class KemEncapsulation(public val ciphertext: ByteArray, public val sharedSecret: ByteArray) {
    override fun toString(): String = "KemEncapsulation(${ciphertext.size}-byte ciphertext)"
}
