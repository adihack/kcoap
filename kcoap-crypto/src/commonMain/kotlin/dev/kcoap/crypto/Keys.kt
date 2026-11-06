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
    /**
     * Releases the key once nothing needs it any more. Protocol code calls this for the keys it owns, such
     * as EDHOC's ephemeral keys (RFC 9528 §9.8), whatever backend made them. The default does nothing; a
     * subclass overwrites its key material, or frees a session or handle on a device. Calling it again
     * does nothing, and a destroyed key must not be used.
     *
     * Release is best effort and must not throw: protocol code calls this while it cleans up after a
     * failure, too. A subclass whose platform call can fail, such as deleting a key-store entry, catches
     * and reports that failure itself. EDHOC also contains and logs one that escapes, so that its other
     * secrets are still wiped.
     */
    public open fun destroy() {}

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
    override fun destroy() {
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

/**
 * The result of a KEM encapsulation (FIPS 203 §7.2): [ciphertext] goes to the key owner, the shared
 * secret stays here.
 *
 * A transfer value from the backend to its caller, which owns both arrays afterwards: the backend must
 * not reuse them, and the constructor does not copy them. The secret leaves only through
 * [sharedSecret], one copy per call, so every copy shows at the call site; [destroy] overwrites it once
 * the caller has what it needs.
 */
public class KemEncapsulation(
    /** The KEM ciphertext, public: EDHOC sends it as G_Y (draft-ietf-lake-pqsuites §3). */
    public val ciphertext: ByteArray,
    sharedSecret: ByteArray,
) {
    private val secret = sharedSecret
    private var isDestroyed = false

    /**
     * A copy of the shared secret K.
     *
     * @throws IllegalStateException after [destroy].
     */
    public fun sharedSecret(): ByteArray {
        check(!isDestroyed) { "The shared secret was destroyed" }
        return secret.copyOf()
    }

    /** Overwrites the shared secret; [sharedSecret] throws afterwards. Calling it again does nothing. */
    public fun destroy() {
        isDestroyed = true
        secret.wipe()
    }

    override fun toString(): String = "KemEncapsulation(${ciphertext.size}-byte ciphertext)"
}
