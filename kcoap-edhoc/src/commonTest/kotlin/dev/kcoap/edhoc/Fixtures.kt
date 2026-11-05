package dev.kcoap.edhoc

import dev.kcoap.common.constantTimeEquals
import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.Shake256
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend

/** Credentials, configurations and handshakes shared by the tests. */
internal object Fixtures {
    /**
     * The registered suites [backend] runs with [method] on this platform: for the default backend all
     * but 25, and where the platform has no ChaCha20/Poly1305 (WebCrypto) not suites 4 and 5 either.
     */
    suspend fun registeredSuitesRunBy(backend: CryptoBackend, method: EdhocMethod): List<CipherSuite> =
        CipherSuite.registered.filter { it.isSupportedBy(backend, method) }

    /** The key type [kind] needs in [suite]. */
    fun keyTypeFor(kind: AuthenticationKind, suite: CipherSuite): KeyType = when (kind) {
        AuthenticationKind.SIGNATURE -> suite.signature.keyType
        AuthenticationKind.STATIC_DH -> (suite.keyExchange as KeyExchangeAlgorithm.DiffieHellman).curve.keyType
    }

    /** A fresh CCS credential identified by 'kid'. */
    suspend fun ccsCredential(
        backend: CryptoBackend,
        kind: AuthenticationKind,
        keyType: KeyType,
        kid: ByteArray,
        subject: String = "test-${kid.size}",
    ): LocalCredential {
        val pair = backend.generateKeyPair(keyType)
        val credential = AuthenticationCredential.ccs(CwtClaimsSet(subject, coseKey(keyType, pair.publicKey, kid)))
        return LocalCredential(credential, CredentialIdentifier.KeyId(kid), pair.privateKey, kind)
    }

    /** The public COSE_Key of [publicKey]; EC2 keys are split into x and y. */
    fun coseKey(type: KeyType, publicKey: ByteArray, kid: ByteArray? = null): CoseKey = when (type) {
        KeyType.X25519 -> CoseKey(CoseKeyType.OKP, CoseCurve.X25519, publicKey, kid = kid)
        KeyType.ED25519 -> CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, publicKey, kid = kid)
        KeyType.P256, KeyType.P384 -> {
            val length = (publicKey.size - 1) / 2
            val curve = if (type == KeyType.P256) CoseCurve.P_256 else CoseCurve.P_384
            val x = publicKey.copyOfRange(1, 1 + length)
            CoseKey(CoseKeyType.EC2, curve, x, publicKey.copyOfRange(1 + length, publicKey.size), kid = kid)
        }
        KeyType.ML_DSA_44, KeyType.ML_DSA_65, KeyType.ML_DSA_87 -> {
            val algorithm = SignatureAlgorithm.entries.first { it.keyType == type }
            CoseKey(CoseKeyType.AKP, null, publicKey, kid = kid, algorithm = algorithm.coseId)
        }
        else -> error("No COSE_Key for $type")
    }

    /** A matched pair of configurations for [method] and [suite], with fresh CCS credentials on both sides. */
    suspend fun configs(
        backend: CryptoBackend,
        method: EdhocMethod,
        suite: CipherSuite,
        initiatorId: ConnectionIdentifier = ConnectionIdentifier.ofInt(-14),
        responderId: ConnectionIdentifier = cid("18"),
        initiatorEad: EadHandler = EadHandler.None,
        responderEad: EadHandler = EadHandler.None,
    ): Pair<EdhocInitiatorConfig, EdhocResponderConfig> {
        val initiatorKind = method.initiatorAuthentication
        val responderKind = method.responderAuthentication
        val initiator = ccsCredential(backend, initiatorKind, keyTypeFor(initiatorKind, suite), byteArrayOf(0x2b))
        val responder = ccsCredential(backend, responderKind, keyTypeFor(responderKind, suite), byteArrayOf(0x32))
        return configs(method, suite, initiator, responder, initiatorId, responderId, initiatorEad, responderEad)
    }

    fun configs(
        method: EdhocMethod,
        suite: CipherSuite,
        initiatorCredential: LocalCredential,
        responderCredential: LocalCredential,
        initiatorId: ConnectionIdentifier = ConnectionIdentifier.ofInt(-14),
        responderId: ConnectionIdentifier = cid("18"),
        initiatorEad: EadHandler = EadHandler.None,
        responderEad: EadHandler = EadHandler.None,
        backend: CryptoBackend = DefaultCryptoBackend(),
    ): Pair<EdhocInitiatorConfig, EdhocResponderConfig> {
        val initiator = EdhocInitiatorConfig(
            method = method,
            cipherSuites = listOf(suite),
            credentials = listOf(initiatorCredential),
            credentialResolver = TrustedCredentials(backend, listOf(responderCredential.credential)),
            connectionIdentifiers = ConnectionIdentifierProvider.fixed(initiatorId),
            eadHandler = initiatorEad,
        )
        val responder = EdhocResponderConfig(
            methods = setOf(method),
            cipherSuites = listOf(suite),
            credentials = listOf(responderCredential),
            credentialResolver = TrustedCredentials(backend, listOf(initiatorCredential.credential)),
            connectionIdentifiers = ConnectionIdentifierProvider.fixed(responderId),
            eadHandler = responderEad,
        )
        return initiator to responder
    }

    /** The messages, both state machines and both sessions of one complete handshake. */
    class Handshake(
        val initiator: EdhocInitiator,
        val responder: EdhocResponder,
        val messages: List<ByteArray>,
        val initiatorSession: EdhocSession,
        val responderSession: EdhocSession,
    )

    suspend fun handshake(
        backend: CryptoBackend,
        configs: Pair<EdhocInitiatorConfig, EdhocResponderConfig>,
        withMessage4: Boolean = false,
    ): Handshake {
        val initiator = EdhocInitiator(configs.first, backend)
        val responder = EdhocResponder(configs.second, backend)
        val message1 = initiator.createMessage1()
        responder.processMessage1(message1)
        val message2 = responder.createMessage2()
        initiator.processMessage2(message2)
        val (message3, initiatorSession) = initiator.createMessage3()
        val responderSession = responder.processMessage3(message3)
        val messages = mutableListOf(message1, message2, message3)
        if (withMessage4) {
            val message4 = responder.createMessage4()
            initiator.processMessage4(message4)
            messages += message4
        }
        return Handshake(initiator, responder, messages, initiatorSession, responderSession)
    }
}

/**
 * The default backend plus test doubles for the post-quantum algorithms, until a real ML-KEM / ML-DSA
 * backend is available. They have the sizes of FIPS 203 / FIPS 204 but none of their security:
 *
 * - ML-KEM is a Diffie-Hellman shim: the encapsulation key is an X25519 public key padded to the ML-KEM
 *   size, the ciphertext an ephemeral X25519 public key padded likewise, the shared secret their X25519.
 * - ML-DSA "signs" with SHAKE256 over the public key and the message; the private key is the public key.
 */
internal open class FakePostQuantumBackend : DefaultCryptoBackend() {
    var encapsulations: Int = 0
        private set

    override suspend fun generateKeyPair(type: KeyType): KeyPair = when (type) {
        KeyType.ML_KEM_512, KeyType.ML_KEM_768, KeyType.ML_KEM_1024 -> {
            val inner = super.generateKeyPair(KeyType.X25519)
            val decapsulationKey = SoftwarePrivateKey(type, (inner.privateKey as SoftwarePrivateKey).encoded())
            KeyPair(pad(inner.publicKey, type.publicKeyLength), decapsulationKey)
        }
        KeyType.ML_DSA_44, KeyType.ML_DSA_65, KeyType.ML_DSA_87 -> {
            val publicKey = randomBytes(type.publicKeyLength)
            KeyPair(publicKey, SoftwarePrivateKey(type, publicKey))
        }
        else -> super.generateKeyPair(type)
    }

    override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation {
        require(publicKey.size == algorithm.keyType.publicKeyLength) { "Wrong encapsulation key size" }
        encapsulations++
        val ephemeral = super.generateKeyPair(KeyType.X25519)
        val secret = super.keyAgreement(ephemeral.privateKey, publicKey.copyOf(32))
        return KemEncapsulation(pad(ephemeral.publicKey, algorithm.ciphertextLength), secret)
    }

    override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray {
        val algorithm = KemAlgorithm.entries.first { it.keyType == privateKey.type }
        require(ciphertext.size == algorithm.ciphertextLength) { "Wrong ciphertext size" }
        val inner = SoftwarePrivateKey(KeyType.X25519, (privateKey as SoftwarePrivateKey).encoded())
        return super.keyAgreement(inner, ciphertext.copyOf(32))
    }

    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        if (algorithm.keyType.isMlDsa()) {
            fakeSignature(algorithm, (privateKey as SoftwarePrivateKey).encoded(), data)
        } else {
            super.sign(algorithm, privateKey, data)
        }

    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean = if (algorithm.keyType.isMlDsa()) {
        constantTimeEquals(fakeSignature(algorithm, publicKey, data), signature)
    } else {
        super.verify(algorithm, publicKey, data, signature)
    }

    private fun fakeSignature(algorithm: SignatureAlgorithm, publicKey: ByteArray, data: ByteArray): ByteArray =
        Shake256.digest(publicKey + data, algorithm.signatureLength)

    private fun KeyType.isMlDsa(): Boolean = this in setOf(KeyType.ML_DSA_44, KeyType.ML_DSA_65, KeyType.ML_DSA_87)

    /** [prefix] followed by deterministic filler up to [length] bytes. */
    private fun pad(prefix: ByteArray, length: Int): ByteArray = prefix + Shake256.digest(prefix, length - prefix.size)
}

/**
 * [inner] without some algorithms, as a platform that lacks them: their operations throw
 * [UnsupportedAlgorithmException] and the `supports` members say so (docs/adr/0001-crypto-capabilities.md).
 * A key type in [keyTypes] takes its key agreement and signatures with it.
 */
internal class RestrictedBackend(
    private val inner: CryptoBackend = DefaultCryptoBackend(),
    private val aeads: Set<AeadAlgorithm> = emptySet(),
    private val keyTypes: Set<KeyType> = emptySet(),
    private val signatures: Set<SignatureAlgorithm> = emptySet(),
    private val hashes: Set<HashAlgorithm> = emptySet(),
) : CryptoBackend by inner {
    override suspend fun supports(algorithm: AeadAlgorithm): Boolean = algorithm !in aeads && inner.supports(algorithm)

    override suspend fun supports(type: KeyType): Boolean = type !in keyTypes && inner.supports(type)

    override suspend fun supports(algorithm: SignatureAlgorithm): Boolean =
        algorithm !in signatures && algorithm.keyType !in keyTypes && inner.supports(algorithm)

    override suspend fun supports(algorithm: HashAlgorithm): Boolean = algorithm !in hashes && inner.supports(algorithm)

    override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead =
        if (algorithm in aeads) unsupported(algorithm.name) else inner.aead(algorithm, key)

    override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
        if (algorithm in hashes) unsupported(algorithm.name) else inner.digest(algorithm, data)

    override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
        if (algorithm in hashes) unsupported(algorithm.name) else inner.hmac(algorithm, key, data)

    override suspend fun generateKeyPair(type: KeyType): KeyPair =
        if (type in keyTypes) unsupported(type.name) else inner.generateKeyPair(type)

    override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray {
        if (privateKey.type in keyTypes) unsupported(privateKey.type.name)
        return inner.keyAgreement(privateKey, peerPublicKey)
    }

    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        if (isMissing(algorithm)) unsupported(algorithm.name) else inner.sign(algorithm, privateKey, data)

    override suspend fun verify(
        algorithm: SignatureAlgorithm,
        publicKey: ByteArray,
        data: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (isMissing(algorithm)) unsupported(algorithm.name)
        return inner.verify(algorithm, publicKey, data, signature)
    }

    private fun isMissing(algorithm: SignatureAlgorithm) = algorithm in signatures || algorithm.keyType in keyTypes

    private fun unsupported(algorithm: String): Nothing = throw UnsupportedAlgorithmException(algorithm)
}

/** Records EAD items and hands out the ones configured per message. */
internal class RecordingEadHandler(
    private val toSend: Map<EdhocMessage, List<EadItem>> = emptyMap(),
    private val recognized: Set<Int> = emptySet(),
) : EadHandler {
    val received: MutableMap<EdhocMessage, MutableList<EadItem>> = mutableMapOf()

    override suspend fun produce(message: EdhocMessage): List<EadItem> = toSend[message].orEmpty()

    override suspend fun consume(message: EdhocMessage, item: EadItem): Boolean {
        received.getOrPut(message) { mutableListOf() } += item
        return item.label in recognized
    }
}
