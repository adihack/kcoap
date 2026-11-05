package dev.kcoap.edhoc

import dev.kcoap.common.wipe
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.EcdhCurve
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where ephemeral key pairs come from: fresh ones for every session by default (RFC 9528 §9.2 requires
 * that), or fixed ones to replay the RFC 9529 traces. Called for X (Initiator, Diffie-Hellman and KEM
 * suites) and Y (Responder, Diffie-Hellman suites; a KEM Responder encapsulates instead).
 */
public fun interface EphemeralKeySource {
    /**
     * A key pair of [type], made with or for [crypto]. EDHOC [destroys][PrivateKey.destroy] the private key
     * once it no longer needs it, whatever kind of handle it is.
     */
    public suspend fun keyPair(crypto: CryptoBackend, type: KeyType): KeyPair

    public companion object {
        /** A new key pair from [CryptoBackend.generateKeyPair] each time. */
        public val Random: EphemeralKeySource = EphemeralKeySource { crypto, type -> crypto.generateKeyPair(type) }
    }
}

/** The Initiator's side of the exchange: G_X for message_1 and the private key that answers G_Y. */
internal class InitiatorShare(val gX: ByteArray, val privateKey: PrivateKey)

/** The Responder's side: G_Y for message_2, G_XY, and Y when static DH (G_IY) still needs it. */
internal class ResponderShare(val gY: ByteArray, val sharedSecret: ByteArray, val privateKey: PrivateKey?)

/**
 * Strategy for the ephemeral key exchange of a cipher suite (RFC 9528 §3.7, §4.1.1.1; draft-ietf-lake-pqsuites
 * §3). Both implementations validate the peer's share and turn backend failures into [EdhocException]s. A
 * backend that lacks the curve or KEM fails with a diagnostic that names it, not one about the peer's key.
 */
internal sealed class EphemeralKeyExchange(
    protected val crypto: CryptoBackend,
    protected val keys: EphemeralKeySource,
) {
    /** An ephemeral key pair of [type], for the curve or KEM called [algorithm]. */
    protected suspend fun ephemeralKeyPair(type: KeyType, algorithm: String): KeyPair = try {
        keys.keyPair(crypto, type)
    } catch (e: UnsupportedAlgorithmException) {
        throw EdhocException.unavailable(algorithm, e)
    }

    /** Initiator, message_1: generate X and G_X. */
    abstract suspend fun initiatorShare(): InitiatorShare

    /** Responder, message_2: answer G_X with G_Y and compute G_XY. */
    abstract suspend fun respond(gX: ByteArray): ResponderShare

    /** Initiator, message_2: G_XY from X and G_Y. */
    abstract suspend fun complete(privateKey: PrivateKey, gY: ByteArray): ByteArray

    /**
     * Ephemeral-static Diffie-Hellman for G_RX and G_IY (RFC 9528 §4.1.1.2, §4.1.1.3), with the peer's static
     * public key in its raw [KeyType] encoding.
     */
    abstract suspend fun staticAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray

    /** Ephemeral-static Diffie-Hellman with the peer's ephemeral share G_X or G_Y. */
    abstract suspend fun staticAgreementWithShare(privateKey: PrivateKey, share: ByteArray): ByteArray

    /**
     * Ephemeral-ephemeral ECDH. G_X and G_Y are x-coordinates only (RFC 9528 Appendix B); a peer share on
     * P-256 or P-384 becomes the compressed point `02 || x`, which yields the same shared secret whichever
     * square root the peer's y is.
     */
    class DiffieHellman(private val curve: EcdhCurve, crypto: CryptoBackend, keys: EphemeralKeySource) :
        EphemeralKeyExchange(crypto, keys) {

        override suspend fun initiatorShare(): InitiatorShare {
            val pair = ephemeralKeyPair(curve.keyType, curve.name)
            return InitiatorShare(shareOf(pair), pair.privateKey)
        }

        override suspend fun respond(gX: ByteArray): ResponderShare {
            val pair = ephemeralKeyPair(curve.keyType, curve.name)
            return ResponderShare(shareOf(pair), agree(pair.privateKey, peerKeyFromShare(gX)), pair.privateKey)
        }

        override suspend fun complete(privateKey: PrivateKey, gY: ByteArray): ByteArray =
            agree(privateKey, peerKeyFromShare(gY))

        override suspend fun staticAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
            agree(privateKey, peerPublicKey)

        override suspend fun staticAgreementWithShare(privateKey: PrivateKey, share: ByteArray): ByteArray =
            agree(privateKey, peerKeyFromShare(share))

        private suspend fun agree(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
            val secret = backendCall(curve.name, "Invalid public key for ${curve.name}") {
                crypto.keyAgreement(privateKey, peerPublicKey)
            }
            // RFC 9528 §9.2: the all-zero check of RFC 7748 §6 is mandatory for X25519 and X448
            // (RFC 9529 §4.2.4). It never holds for valid NIST curve points, so it is applied to all curves.
            if (secret.fold(0) { acc, byte -> acc or byte.toInt() } == 0) {
                throw EdhocException.unspecified("Invalid public key: all-zero shared secret")
            }
            return secret
        }

        private fun shareOf(pair: KeyPair): ByteArray {
            val publicKey = pair.publicKey
            return when (curve.keyType) {
                KeyType.P256, KeyType.P384 -> publicKey.copyOfRange(1, 1 + curve.keyLength)
                else -> publicKey
            }.also { require(it.size == curve.keyLength) { "Ephemeral public key does not fit ${curve.name}" } }
        }

        private fun peerKeyFromShare(share: ByteArray): ByteArray = when (curve.keyType) {
            KeyType.P256, KeyType.P384 -> byteArrayOf(COMPRESSED_EVEN_Y) + share
            else -> share
        }
    }

    /** KEM-based exchange (draft-ietf-lake-pqsuites §3): G_X = ek, G_Y = c, G_XY = K. */
    class Kem(private val algorithm: KemAlgorithm, crypto: CryptoBackend, keys: EphemeralKeySource) :
        EphemeralKeyExchange(crypto, keys) {

        override suspend fun initiatorShare(): InitiatorShare {
            val pair = ephemeralKeyPair(algorithm.keyType, algorithm.name)
            val ek = pair.publicKey
            require(ek.size == algorithm.keyType.publicKeyLength) { "Encapsulation key does not fit ${algorithm.name}" }
            return InitiatorShare(ek, pair.privateKey)
        }

        override suspend fun respond(gX: ByteArray): ResponderShare {
            val encapsulation = backendCall(algorithm.name, "Invalid ${algorithm.name} encapsulation key") {
                crypto.encapsulate(algorithm, gX)
            }
            try {
                val secret = encapsulation.sharedSecret()
                if (encapsulation.ciphertext.size != algorithm.ciphertextLength ||
                    secret.size != algorithm.sharedSecretLength
                ) {
                    secret.wipe()
                    throw EdhocException.unspecified("${algorithm.name} encapsulation has the wrong size")
                }
                return ResponderShare(encapsulation.ciphertext, secret, privateKey = null)
            } finally {
                encapsulation.destroy()
            }
        }

        override suspend fun complete(privateKey: PrivateKey, gY: ByteArray): ByteArray {
            val secret = backendCall(algorithm.name, "Invalid ${algorithm.name} ciphertext") {
                crypto.decapsulate(privateKey, gY)
            }
            if (secret.size != algorithm.sharedSecretLength) {
                throw EdhocException.unspecified("${algorithm.name} shared secret has the wrong size")
            }
            return secret
        }

        // Methods 1–3 are rejected before a KEM suite gets this far (draft-ietf-lake-pqsuites §6.3).
        override suspend fun staticAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
            throw EdhocException.unspecified("${algorithm.name} cannot do static Diffie-Hellman")

        override suspend fun staticAgreementWithShare(privateKey: PrivateKey, share: ByteArray): ByteArray =
            staticAgreement(privateKey, share)
    }

    companion object {
        private const val COMPRESSED_EVEN_Y: Byte = 0x02

        fun forSuite(suite: CipherSuite, crypto: CryptoBackend, keys: EphemeralKeySource): EphemeralKeyExchange =
            when (val exchange = suite.keyExchange) {
                is KeyExchangeAlgorithm.DiffieHellman -> DiffieHellman(exchange.curve, crypto, keys)
                is KeyExchangeAlgorithm.Kem -> Kem(exchange.algorithm, crypto, keys)
            }

        /**
         * Runs a backend operation of [algorithm] on peer input. Backends report invalid keys with
         * platform-specific exceptions, so everything except cancellation becomes an [EdhocException]
         * (RFC 9528 §9.8: abort when public key validation fails): [diagnostic] about the peer's input, or,
         * when the backend lacks [algorithm] altogether, a diagnostic that names it.
         */
        private inline fun <T> backendCall(algorithm: String, diagnostic: String, block: () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: EdhocException) {
            throw e
        } catch (e: UnsupportedAlgorithmException) {
            throw EdhocException.unavailable(algorithm, e)
        } catch (e: Exception) {
            throw EdhocException.unspecified(diagnostic, e)
        }
    }
}

/** Wipes every array, for intermediate secrets that are no longer needed (RFC 9528 §9.8). */
internal fun wipeAll(vararg secrets: ByteArray?) {
    secrets.forEach { it?.wipe() }
}
