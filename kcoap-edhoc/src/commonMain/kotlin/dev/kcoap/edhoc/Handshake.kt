package dev.kcoap.edhoc

import dev.kcoap.cose.CoseStructures
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.CryptoBackend

/** Receives copies of intermediate values by RFC 9529 name; tests use it to check every step of the traces. */
internal fun interface IntermediateValues {
    fun record(name: String, value: ByteArray)
}

/** Steps both roles share. */
internal object Handshake {
    /** The binary additive stream cipher of message_2 (RFC 9528 §5.3.2). */
    fun xor(data: ByteArray, keystream: ByteArray): ByteArray = ByteArray(data.size) { i ->
        (data[i].toInt() xor keystream[i].toInt()).toByte()
    }

    /** `[ "Encrypt0", h'', TH_x ]`, the associated data of message_3 and message_4 (RFC 9528 Appendix C.3). */
    fun encrypt0Aad(transcriptHash: ByteArray): ByteArray =
        CoseStructures.encStructure(protected = ByteArray(0), externalAad = transcriptHash)

    suspend fun encrypt(
        crypto: CryptoBackend,
        suite: CipherSuite,
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        crypto.aead(suite.edhocAead, key).encrypt(iv, plaintext, aad)

    /** Decrypts CIPHERTEXT_3 or CIPHERTEXT_4; a failed tag aborts the session. */
    suspend fun decrypt(
        crypto: CryptoBackend,
        suite: CipherSuite,
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
        what: String,
    ): ByteArray = try {
        crypto.aead(suite.edhocAead, key).decrypt(iv, ciphertext, aad)
    } catch (e: AeadAuthenticationException) {
        throw EdhocException.unspecified("Decryption of $what failed", e)
    }

    /** Whether [credential] holds the kind of key [kind] needs in [suite]. */
    fun isUsable(credential: AuthenticationCredential, kind: AuthenticationKind, suite: CipherSuite): Boolean =
        when (kind) {
            AuthenticationKind.SIGNATURE -> credential.canVerify(suite.signature)
            AuthenticationKind.STATIC_DH -> {
                val exchange = suite.keyExchange
                exchange is KeyExchangeAlgorithm.DiffieHellman && credential.keyType == exchange.curve.keyType
            }
        }

    /**
     * The peer's candidate credentials for [identifier] that fit [kind] and [suite]. No candidate at all is
     * error code 3 for a reference (RFC 9528 §6.4) and code 1 otherwise.
     */
    suspend fun resolvePeer(
        resolver: CredentialResolver,
        identifier: CredentialIdentifier,
        kind: AuthenticationKind,
        suite: CipherSuite,
    ): List<AuthenticationCredential> {
        val candidates = resolver.resolve(identifier)
        if (candidates.isEmpty()) {
            throw when (identifier) {
                is CredentialIdentifier.KeyId, is CredentialIdentifier.X509Thumbprint ->
                    EdhocException(EdhocError.unknownCredentialReferenced(), "Unknown credential $identifier")
                is CredentialIdentifier.Other -> EdhocException.unspecified("Unsupported credential identifier")
                else -> EdhocException.unspecified("Credential not trusted")
            }
        }
        return candidates.filter { isUsable(it, kind, suite) }
            .ifEmpty { throw EdhocException.unspecified("Peer credential does not fit cipher suite ${suite.value}") }
    }

    /** Decodes an error message received instead of message_2, message_3 or message_4. */
    fun peerError(bytes: ByteArray): EdhocError = try {
        EdhocError.decode(bytes)
    } catch (_: EdhocException) {
        EdhocError.unspecified("Malformed error message")
    }
}
