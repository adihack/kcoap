package dev.kcoap.edhoc

import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The KEM-based key exchange of draft-ietf-lake-pqsuites §3 on libedhoc's suite -24, with a test double
 * for ML-KEM and ML-DSA ([FakePostQuantumBackend]) that has the real sizes. What this proves: the
 * KEM code path, the lengths of G_X, G_Y and the signatures, SHAKE256 / KMAC256 key derivation, and that
 * static-DH methods are refused. What it cannot prove: interoperability, which needs a real ML-KEM.
 */
class PostQuantumTest {
    private val backend = FakePostQuantumBackend()
    private val suite = CipherSuite.SUITE_PQC_1

    @Test
    fun method0HandshakeOverMlKem512AndMlDsa44() = runTest {
        // The Initiator sends an ML-DSA-44 certificate chain by value, as libedhoc's PQC test does.
        val initiatorKey = backend.generateKeyPair(KeyType.ML_DSA_44)
        val mlDsa44 = TestCertificates.algorithm(TestCertificates.ML_DSA_44)
        val leaf = TestCertificates.certificate(mlDsa44, initiatorKey.publicKey, "pq initiator")
        val root = TestCertificates.certificate(mlDsa44, ByteArray(1312) { 7 }, "pq root")
        val chain = CredentialIdentifier.X509Chain(listOf(leaf, root))
        val initiatorCredential =
            LocalCredential.signature(AuthenticationCredential.x509(leaf), chain, initiatorKey.privateKey)
        val responderCredential = signer(byteArrayOf(0x32))
        val validated = mutableListOf<List<ByteArray>>()
        val validator = CertificateValidator { chain ->
            validated += chain
            if (!chain.last().contentEquals(root)) throw CertificateValidationException("unknown root")
        }
        val initiatorConfig = EdhocInitiatorConfig(
            EdhocMethod.SIGNATURE_SIGNATURE, listOf(suite), listOf(initiatorCredential),
            TrustedCredentials(backend, listOf(responderCredential.credential)),
            connectionIdentifiers = ConnectionIdentifierProvider.fixed(ConnectionIdentifier.ofInt(-14)),
        )
        val responderConfig = EdhocResponderConfig(
            setOf(EdhocMethod.SIGNATURE_SIGNATURE), listOf(suite), listOf(responderCredential),
            TrustedCredentials(backend, emptyList(), validator),
            connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid("18")),
        )
        val initiator = EdhocInitiator(initiatorConfig, backend)
        val responder = EdhocResponder(responderConfig, backend)
        val initiatorValues = RecordedValues().also { initiator.intermediateValues = it }
        val responderValues = RecordedValues().also { responder.intermediateValues = it }

        val message1 = initiator.createMessage1()
        // METHOD 00, SUITES_I 37 (-24), G_X 59 0320 || 800 bytes, C_I 2d.
        assertEquals(1 + 1 + 3 + 800 + 1, message1.size)
        assertEquals("0037590320", message1.copyOf(5).toHexString())
        responder.processMessage1(message1)
        // The backend's one probe of ML-KEM-512 (the capability check) has run by now.
        val encapsulationsBefore = backend.encapsulations
        val message2 = responder.createMessage2()
        initiator.processMessage2(message2)
        val message3 = initiator.createMessage3().bytes
        responder.processMessage3(message3)
        initiator.processMessage4(responder.createMessage4())

        assertEquals(1, backend.encapsulations - encapsulationsBefore)
        for (values in listOf(initiatorValues, responderValues)) {
            assertEquals(768, values["G_Y"].size, "G_Y is the ML-KEM-512 ciphertext")
            assertEquals(32, values["G_XY"].size, "G_XY is the ML-KEM shared secret")
            assertEquals(64, values["TH_2"].size, "SHAKE256 transcript hash")
            assertEquals(64, values["PRK_2e"].size, "KMAC256 extract")
            assertEquals(64, values["MAC_2"].size, "mac_length_2 is hash_length with signatures")
            assertEquals(16, values["K_3"].size)
            assertEquals(13, values["IV_3"].size)
        }
        assertEquals(2420, initiatorValues["Signature_or_MAC_2"].size)
        assertEquals(2420, initiatorValues["Signature_or_MAC_3"].size)
        assertContentEquals(initiatorValues["G_XY"], responderValues["G_XY"])
        assertEquals(1, validated.size)
        val initiatorSession = initiator.session!!
        val responderSession = responder.session!!
        assertContentEquals(initiatorSession.prkOut(), responderSession.prkOut())
        assertEquals(64, initiatorSession.prkOut().size)
        assertContentEquals(initiatorSession.oscoreMasterSecret(), responderSession.oscoreMasterSecret())
        assertEquals(16, responderSession.oscoreMasterSecret().size)
        assertEquals(KeyType.ML_DSA_44, responderSession.peerCredential.keyType)
        assertEquals("pq initiator", responderSession.peerCredential.certificate?.subjectCommonName)
        assertTrue(responderSession.peerCredentialIdentifier is CredentialIdentifier.X509Chain)
    }

    /** A private suite in the shape of draft TBD3: ML-KEM-1024, ML-DSA-87, A256GCM, SHA-384. */
    @Test
    fun privateSuiteWithMlKem1024AndSha384() = runTest {
        val tbd3 = CipherSuite(
            -23, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384, 16, KeyExchangeAlgorithm.Kem(KemAlgorithm.ML_KEM_1024),
            SignatureAlgorithm.ML_DSA_87, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384,
        )
        val handshake = Fixtures.handshake(
            backend, Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, tbd3), withMessage4 = true,
        )
        assertContentEquals(handshake.initiatorSession.prkOut(), handshake.responderSession.prkOut())
        assertEquals(48, handshake.initiatorSession.prkOut().size)
        // message_2 carries the 1568-byte ciphertext and a 4627-byte signature: PLAINTEXT_2 is over 4 kB.
        assertTrue(handshake.messages[1].size > 1568 + 4627)
    }

    /** draft-ietf-lake-pqsuites §6.3: methods 1–3 require DH/NIKE, which a KEM suite does not support. */
    @Test
    fun staticDiffieHellmanMethodsAreRefusedOnKemSuites() = runTest {
        val credential = Fixtures.ccsCredential(backend, AuthenticationKind.STATIC_DH, KeyType.X25519, byteArrayOf(1))
        for (method in EdhocMethod.entries.filter { it.requiresDiffieHellman }) {
            assertFailsWith<IllegalArgumentException>(method.name) {
                EdhocInitiatorConfig(method, listOf(suite), listOf(credential), nobody)
            }
        }
        val signer = signer(byteArrayOf(2))
        val method3 = message1(method = 3, gXLength = 800)

        // A Responder that only runs the KEM suite cannot do method 3 at all.
        val kemOnly = EdhocResponder(
            EdhocResponderConfig(EdhocMethod.entries.toSet(), listOf(suite), listOf(signer), nobody),
            backend,
        )
        val refused = assertFailsWith<EdhocException> { kemOnly.processMessage1(method3) }
        assertEquals(EdhocError.UNSPECIFIED_ERROR, refused.errorCode)
        assertEquals("Method 3 not supported", refused.error.diagnostic)

        // One that also runs suite 2 points the Initiator there.
        val staticP256 = Fixtures.ccsCredential(backend, AuthenticationKind.STATIC_DH, KeyType.P256, byteArrayOf(3))
        val mixed = EdhocResponder(
            EdhocResponderConfig(
                EdhocMethod.entries.toSet(), listOf(suite, CipherSuite.SUITE_2), listOf(signer, staticP256),
                TrustedCredentials(backend, emptyList()),
            ),
            backend,
        )
        val redirected = assertFailsWith<EdhocException> { mixed.processMessage1(method3) }
        assertEquals(listOf(2), redirected.error.cipherSuites)

        // The strategy itself refuses static DH.
        val kem = EphemeralKeyExchange.forSuite(suite, backend, EphemeralKeySource.Random)
        val key = SoftwarePrivateKey(KeyType.ML_KEM_512, ByteArray(32))
        assertFailsWith<EdhocException> { kem.staticAgreement(key, ByteArray(32)) }
        assertFailsWith<EdhocException> { kem.staticAgreementWithShare(key, ByteArray(800)) }
    }

    @Test
    fun kemSharesAreValidated() = runTest {
        val signer = signer(byteArrayOf(2))
        val responderConfig =
            EdhocResponderConfig(setOf(EdhocMethod.SIGNATURE_SIGNATURE), listOf(suite), listOf(signer), nobody)
        val shortKey = message1(method = 0, gXLength = 799)
        val failure = assertFailsWith<EdhocException> {
            EdhocResponder(responderConfig, backend).processMessage1(shortKey)
        }
        assertEquals("G_X has the wrong length for cipher suite -24", failure.error.diagnostic)

        // A backend whose encapsulation has the wrong size is caught before anything is sent.
        val broken = object : FakePostQuantumBackend() {
            override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
                KemEncapsulation(ByteArray(767), ByteArray(32))
        }
        val responder = EdhocResponder(responderConfig, broken)
        responder.processMessage1(
            Message1(0, listOf(suite.value), ByteArray(800), ConnectionIdentifier.ofInt(1), emptyList()).encode(),
        )
        assertFailsWith<EdhocException> { responder.createMessage2() }
        assertEquals(EdhocPhase.ABORTED, responder.phase)

        // A decapsulation of the wrong size is caught when message_2 arrives.
        val shortSecrets = object : FakePostQuantumBackend() {
            override suspend fun decapsulate(privateKey: PrivateKey, ciphertext: ByteArray): ByteArray =
                super.decapsulate(privateKey, ciphertext).copyOf(31)
        }
        val (initiatorConfig, honestConfig) = Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, suite)
        val pqInitiator = EdhocInitiator(initiatorConfig, shortSecrets)
        val honest = EdhocResponder(honestConfig, backend)
        honest.processMessage1(pqInitiator.createMessage1())
        assertEquals(
            "ML_KEM_512 shared secret has the wrong size",
            assertFailsWith<EdhocException> { pqInitiator.processMessage2(honest.createMessage2()) }.error.diagnostic,
        )

        // Encapsulation keys of the wrong size never leave the Initiator.
        val badKeys = EphemeralKeySource { _, _ ->
            KeyPair(ByteArray(10), SoftwarePrivateKey(KeyType.ML_KEM_512, ByteArray(32)))
        }
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                EdhocMethod.SIGNATURE_SIGNATURE, listOf(suite), listOf(signer), nobody, ephemeralKeys = badKeys,
            ),
            backend,
        )
        val noMessage = assertFailsWith<EdhocException> { initiator.createMessage1() }
        assertEquals(null, noMessage.errorMessage, "nothing to answer before message_1")
    }

    @Test
    fun akpCredentialsCarryMlDsaKeys() {
        val types = listOf(
            KeyType.ML_DSA_65 to SignatureAlgorithm.ML_DSA_65,
            KeyType.ML_DSA_87 to SignatureAlgorithm.ML_DSA_87,
        )
        for ((type, algorithm) in types) {
            val key = Fixtures.coseKey(type, ByteArray(type.publicKeyLength), byteArrayOf(1))
            val credential = AuthenticationCredential.ccs(CwtClaimsSet(null, key))
            assertEquals(type, credential.keyType)
            assertEquals(algorithm.keyType, credential.keyType)
        }
        val unknown = dev.kcoap.cose.CoseKey(dev.kcoap.cose.CoseKeyType.AKP, null, ByteArray(10), algorithm = -999)
        assertFailsWith<IllegalArgumentException> { AuthenticationCredential.ccs(CwtClaimsSet(null, unknown)) }
    }

    private val nobody = TrustedCredentials(backend, emptyList())

    private suspend fun signer(kid: ByteArray) =
        Fixtures.ccsCredential(backend, AuthenticationKind.SIGNATURE, KeyType.ML_DSA_44, kid)

    private fun message1(method: Int, gXLength: Int): ByteArray =
        Message1(method, listOf(suite.value), ByteArray(gXLength), ConnectionIdentifier.ofInt(1), emptyList()).encode()

    private fun ByteArray.toHexString(): String = dev.kcoap.common.Hex.encode(this)
}
