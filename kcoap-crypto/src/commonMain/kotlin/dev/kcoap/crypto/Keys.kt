package dev.kcoap.crypto

import dev.kcoap.common.wipe
import kotlin.concurrent.Volatile

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
 *
 * Once [destroy]ed, the key fails closed: [encoded] throws instead of handing out the overwritten bytes,
 * which would be a valid, publicly known key (an all-zero Ed25519 seed, or an X25519 scalar that clamps
 * to a fixed value).
 */
public class SoftwarePrivateKey(type: KeyType, encoded: ByteArray) : PrivateKey(type) {
    private val material = encoded.copyOf()

    // Read without a lock by the backends that use the key; set once, before the material is wiped.
    @Volatile
    private var isDestroyed = false

    /**
     * A copy of the raw private key: scalar for EC, seed for Ed25519, FIPS 203/204 encoding for ML-*.
     *
     * @throws IllegalStateException after [destroy], also when [destroy] runs on another thread while the
     *   copy is taken.
     */
    public fun encoded(): ByteArray {
        check(!isDestroyed) { DESTROYED }
        val copy = material.copyOf()
        // destroy() sets the flag before it wipes: if the flag is still clear once the copy is complete, the
        // wipe had not begun while it was taken. Otherwise the copy may hold zeros.
        if (isDestroyed) {
            copy.wipe()
            throw IllegalStateException(DESTROYED)
        }
        return copy
    }

    /** Overwrites the key material; [encoded] throws afterwards. Calling it again does nothing. */
    public fun destroy() {
        isDestroyed = true
        material.wipe()
    }
}

private const val DESTROYED = "The private key was destroyed"

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
