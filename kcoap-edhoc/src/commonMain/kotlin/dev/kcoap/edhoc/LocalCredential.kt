package dev.kcoap.edhoc

import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey

/**
 * This endpoint's own authentication material: CRED_x, how the peer is told about it (ID_CRED_x), and
 * the handle of the private authentication key, which never leaves its [dev.kcoap.crypto.CryptoBackend].
 *
 * [authentication] fixes what the key is for. RFC 9528 §9.2 discourages using one key both to sign and
 * for static Diffie-Hellman, and a P-256 key could do either, so the purpose is explicit.
 *
 * @property credential CRED_x.
 * @property identifier ID_CRED_x as sent to the peer.
 * @property privateKey the private authentication key; its type must be the credential's.
 * @property authentication whether the key signs or does static Diffie-Hellman.
 */
public class LocalCredential(
    public val credential: AuthenticationCredential,
    public val identifier: CredentialIdentifier,
    public val privateKey: PrivateKey,
    public val authentication: AuthenticationKind,
) {
    init {
        require(privateKey.type == credential.keyType) {
            "The private key is ${privateKey.type}, the credential holds a ${credential.keyType} key"
        }
        val usable = if (authentication == AuthenticationKind.SIGNATURE) SIGNATURE_KEYS else STATIC_DH_KEYS
        require(credential.keyType in usable) { "A ${credential.keyType} key cannot be used for $authentication" }
    }

    /** Whether this credential authenticates [kind] in [suite]: the key type must be the suite's. */
    public fun isUsableFor(kind: AuthenticationKind, suite: CipherSuite): Boolean =
        kind == authentication && Handshake.isUsable(credential, kind, suite)

    override fun toString(): String = "LocalCredential($authentication, $credential, $identifier)"

    public companion object {
        /** A credential whose key signs (methods 0 and 1 for the Initiator, 0 and 2 for the Responder). */
        public fun signature(
            credential: AuthenticationCredential,
            identifier: CredentialIdentifier,
            privateKey: PrivateKey,
        ): LocalCredential = LocalCredential(credential, identifier, privateKey, AuthenticationKind.SIGNATURE)

        /** A credential whose key is a static Diffie-Hellman key. */
        public fun staticDiffieHellman(
            credential: AuthenticationCredential,
            identifier: CredentialIdentifier,
            privateKey: PrivateKey,
        ): LocalCredential = LocalCredential(credential, identifier, privateKey, AuthenticationKind.STATIC_DH)

        private val SIGNATURE_KEYS = setOf(
            KeyType.ED25519, KeyType.P256, KeyType.P384, KeyType.ML_DSA_44, KeyType.ML_DSA_65, KeyType.ML_DSA_87,
        )
        private val STATIC_DH_KEYS = setOf(KeyType.X25519, KeyType.X448, KeyType.P256, KeyType.P384)
    }
}
