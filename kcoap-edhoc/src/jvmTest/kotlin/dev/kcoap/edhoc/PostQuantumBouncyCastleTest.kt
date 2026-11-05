package dev.kcoap.edhoc

import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.bc.BouncyCastleCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The post-quantum suites with real ML-KEM (FIPS 203) and ML-DSA (FIPS 204) from Bouncy Castle: libedhoc's
 * suite -24 and a private ML-KEM-1024 / ML-DSA-87 / SHA-384 suite in the shape of draft-ietf-lake-pqsuites
 * TBD3, with credentials as CCS (AKP COSE_Key) and as ML-DSA X.509 chains.
 */
class PostQuantumBouncyCastleTest {
    private val backend = BouncyCastleCryptoBackend()

    private val mlKem1024Suite = CipherSuite(
        -23, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384, 16, KeyExchangeAlgorithm.Kem(KemAlgorithm.ML_KEM_1024),
        SignatureAlgorithm.ML_DSA_87, AeadAlgorithm.A256GCM, HashAlgorithm.SHA_384,
    )

    @Test
    fun suitePqc1WithCcsCredentials() = runTest {
        val handshake = handshake(CipherSuite.SUITE_PQC_1, x509 = false)
        assertSessionsAgree(handshake, keyLength = 64)
        // message_1: 00 37 59 0320 <800-byte ek> C_I; message_2 starts with the 768-byte ciphertext.
        assertEquals(1 + 1 + 3 + 800 + 1, handshake.messages[0].size)
        assertTrue(handshake.messages[1].size > 3 + 768 + 2420, "G_Y and the ML-DSA-44 signature")
        assertEquals(KeyType.ML_DSA_44, handshake.responderSession.peerCredential.keyType)
    }

    @Test
    fun suitePqc1WithMlDsaCertificateChains() = runTest {
        val handshake = handshake(CipherSuite.SUITE_PQC_1, x509 = true)
        assertSessionsAgree(handshake, keyLength = 64)
        assertTrue(handshake.initiatorSession.peerCredentialIdentifier is CredentialIdentifier.X509Chain)
        assertEquals("pq responder", handshake.initiatorSession.peerCredential.certificate?.subjectCommonName)
    }

    @Test
    fun mlKem1024SuiteWithBothCredentialTypes() = runTest {
        for (x509 in listOf(false, true)) {
            val handshake = handshake(mlKem1024Suite, x509)
            assertSessionsAgree(handshake, keyLength = 48)
            assertTrue(handshake.messages[1].size > 1568 + 4627, "ML-KEM-1024 ciphertext and ML-DSA-87 signature")
        }
    }

    /**
     * A flipped bit anywhere fails the session: in G_Y, ML-KEM's implicit rejection yields a different G_XY,
     * so PLAINTEXT_2 decrypts to garbage; in CIPHERTEXT_2 the ML-DSA signature fails; in message_3 the AEAD tag.
     */
    @Test
    fun tamperingFails() = runTest {
        for (position in listOf(10, -1)) {
            val (initiatorConfig, responderConfig) = configs(CipherSuite.SUITE_PQC_1, x509 = false)
            val initiator = EdhocInitiator(initiatorConfig, backend)
            val responder = EdhocResponder(responderConfig, backend)
            responder.processMessage1(initiator.createMessage1())
            val message2 = responder.createMessage2()
            val index = if (position < 0) message2.size + position else position
            message2[index] = (message2[index].toInt() xor 1).toByte()
            assertFailsWith<EdhocException> { initiator.processMessage2(message2) }
            assertEquals(EdhocPhase.ABORTED, initiator.phase)
        }
        val (initiatorConfig, responderConfig) = configs(CipherSuite.SUITE_PQC_1, x509 = true)
        val initiator = EdhocInitiator(initiatorConfig, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val message3 = initiator.createMessage3().bytes
        message3[message3.size - 1] = (message3[message3.size - 1].toInt() xor 1).toByte()
        val failure = assertFailsWith<EdhocException> { responder.processMessage3(message3) }
        assertEquals("Decryption of message_3 failed", failure.error.diagnostic)
    }

    private suspend fun handshake(suite: CipherSuite, x509: Boolean): Fixtures.Handshake =
        Fixtures.handshake(backend, configs(suite, x509), withMessage4 = true)

    private suspend fun configs(suite: CipherSuite, x509: Boolean): Pair<EdhocInitiatorConfig, EdhocResponderConfig> {
        val initiator = credential(backend, suite.signature.keyType, "pq initiator", 0x2b, x509)
        val responder = credential(backend, suite.signature.keyType, "pq responder", 0x32, x509)
        return Fixtures.configs(EdhocMethod.SIGNATURE_SIGNATURE, suite, initiator, responder, backend = backend)
    }

    private suspend fun assertSessionsAgree(handshake: Fixtures.Handshake, keyLength: Int) {
        val initiator = handshake.initiatorSession
        val responder = handshake.responderSession
        assertEquals(keyLength, initiator.prkOut().size)
        assertContentEquals(initiator.prkOut(), responder.prkOut())
        assertContentEquals(initiator.oscoreMasterSecret(), responder.oscoreMasterSecret())
        assertContentEquals(initiator.oscoreMasterSalt(), responder.oscoreMasterSalt())
        val context = "post-quantum".encodeToByteArray()
        assertContentEquals(initiator.exporter(40000, context, 32), responder.exporter(40000, context, 32))
        initiator.keyUpdate(context)
        responder.keyUpdate(context)
        assertContentEquals(initiator.prkExporter(), responder.prkExporter())
        assertEquals(EdhocPhase.PERSISTED, handshake.initiator.phase)
    }

    companion object {
        /** An ML-DSA credential: a CCS with an AKP COSE_Key under [kid], or a two-certificate chain. */
        suspend fun credential(
            backend: CryptoBackend,
            type: KeyType,
            name: String,
            kid: Int,
            x509: Boolean,
        ): LocalCredential {
            val pair = backend.generateKeyPair(type)
            val kidBytes = byteArrayOf(kid.toByte())
            if (!x509) {
                val key = Fixtures.coseKey(type, pair.publicKey, kidBytes)
                val credential = AuthenticationCredential.ccs(CwtClaimsSet(name, key))
                return LocalCredential.signature(credential, CredentialIdentifier.KeyId(kidBytes), pair.privateKey)
            }
            val oid = if (type == KeyType.ML_DSA_44) TestCertificates.ML_DSA_44 else TestCertificates.ML_DSA_87
            val algorithm = TestCertificates.algorithm(oid)
            val leaf = TestCertificates.certificate(algorithm, pair.publicKey, name)
            val root = TestCertificates.certificate(algorithm, backend.generateKeyPair(type).publicKey, "pq root")
            val chain = CredentialIdentifier.X509Chain(listOf(leaf, root))
            return LocalCredential.signature(AuthenticationCredential.x509(leaf), chain, pair.privateKey)
        }
    }
}
