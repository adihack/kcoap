package dev.kcoap.edhoc

import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.vectors.Rfc9529Trace1
import dev.kcoap.edhoc.vectors.Rfc9529Trace2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Both traces of RFC 9529, replayed through the real Initiator and Responder with the traces' ephemeral
 * keys and connection identifiers. Every message must match byte for byte, and every intermediate value
 * the RFC prints must be what each side computed.
 */
class Rfc9529TraceTest {
    private val backend = DefaultCryptoBackend()

    /** RFC 9529 §2: method 0, cipher suite 0, Ed25519 certificates identified by 'x5t'. */
    @Test
    fun trace1SignaturesWithX509Thumbprints() = runTest {
        val t = Rfc9529Trace1
        val credI = AuthenticationCredential.x509(t.CRED_I.hex())
        val credR = AuthenticationCredential.x509(t.CRED_R.hex())
        val idCredI = CredentialIdentifier.x509Thumbprint(backend, credI.certificate!!.encoded)
        val idCredR = CredentialIdentifier.x509Thumbprint(backend, credR.certificate!!.encoded)
        assertHex(t.ID_CRED_I, idCredI.encoded)
        assertHex(t.ID_CRED_R, idCredR.encoded)
        assertHex(t.CRED_R_CBOR, credR.encoded)
        assertHex(t.PK_R, credR.publicKey)
        assertEquals("EDHOC Responder Ed25519", credR.certificate.subjectCommonName)

        // Apple's CryptoKit randomizes Ed25519 signatures; see TraceSignatures.
        val signer = TraceSignatures(
            backend,
            publicKeys = listOf(t.PK_I.hex(), t.PK_R.hex()),
            expected = listOf(t.SIGNATURE_OR_MAC_2.hex(), t.SIGNATURE_OR_MAC_3.hex()),
        )
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                method = EdhocMethod.SIGNATURE_SIGNATURE,
                cipherSuites = listOf(CipherSuite.SUITE_0),
                credentials = listOf(LocalCredential.signature(credI, idCredI, signingKey(t.SK_I, t.PK_I))),
                credentialResolver = TrustedCredentials(backend, listOf(credR)),
                connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid(t.C_I)),
                ephemeralKeys = sequenceOfKeys(fixedKey(KeyType.X25519, t.X, t.G_X)),
            ),
            signer,
        )
        val responder = EdhocResponder(
            EdhocResponderConfig(
                methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
                cipherSuites = listOf(CipherSuite.SUITE_0),
                credentials = listOf(LocalCredential.signature(credR, idCredR, signingKey(t.SK_R, t.PK_R))),
                credentialResolver = TrustedCredentials(backend, listOf(credI)),
                connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid(t.C_R)),
                ephemeralKeys = sequenceOfKeys(fixedKey(KeyType.X25519, t.Y, t.G_Y)),
            ),
            signer,
        )
        val initiatorValues = RecordedValues().also { initiator.intermediateValues = it }
        val responderValues = RecordedValues().also { responder.intermediateValues = it }

        val message1 = initiator.createMessage1()
        assertHex(t.MESSAGE_1, message1, "message_1")
        responder.processMessage1(message1)
        val message2 = responder.createMessage2()
        assertHex(t.MESSAGE_2, message2, "message_2")
        initiator.processMessage2(message2)
        val message3 = initiator.createMessage3().bytes
        assertHex(t.MESSAGE_3, message3, "message_3")
        responder.processMessage3(message3)
        val message4 = responder.createMessage4()
        assertHex(t.MESSAGE_4, message4, "message_4")
        initiator.processMessage4(message4)

        for (values in listOf(initiatorValues, responderValues)) {
            values.assertValue("H(message_1)", t.H_MESSAGE_1)
            values.assertValue("TH_2", t.TH_2)
            values.assertValue("PRK_2e", t.PRK_2E)
            values.assertValue("KEYSTREAM_2", t.KEYSTREAM_2)
            values.assertValue("PLAINTEXT_2", t.PLAINTEXT_2)
            values.assertValue("PRK_3e2m", t.PRK_3E2M)
            values.assertValue("MAC_2", t.MAC_2)
            values.assertValue("Signature_or_MAC_2", t.SIGNATURE_OR_MAC_2)
            values.assertValue("TH_3", t.TH_3)
            values.assertValue("PRK_4e3m", t.PRK_4E3M)
            values.assertValue("MAC_3", t.MAC_3)
            values.assertValue("PLAINTEXT_3", t.PLAINTEXT_3)
            values.assertValue("K_3", t.K_3)
            values.assertValue("IV_3", t.IV_3)
            values.assertValue("TH_4", t.TH_4)
            values.assertValue("PRK_out", t.PRK_OUT)
            values.assertValue("PRK_exporter", t.PRK_EXPORTER)
            values.assertValue("K_4", t.K_4)
            values.assertValue("IV_4", t.IV_4)
        }
        initiatorValues.assertValue("G_X", t.G_X)
        initiatorValues.assertValue("G_Y", t.G_Y)
        initiatorValues.assertValue("G_XY", t.G_XY)
        initiatorValues.assertValue("Signature_or_MAC_3", t.SIGNATURE_OR_MAC_3)
        responderValues.assertValue("G_Y", t.G_Y)
        responderValues.assertValue("G_XY", t.G_XY)
        assertHex(t.CIPHERTEXT_2, message2.copyOfRange(2 + 32, message2.size), "CIPHERTEXT_2")
        assertHex(t.CIPHERTEXT_3, message3.copyOfRange(2, message3.size), "CIPHERTEXT_3")
        assertHex(t.CIPHERTEXT_4, message4.copyOfRange(1, message4.size), "CIPHERTEXT_4")

        assertEquals(EdhocPhase.PERSISTED, initiator.phase)
        assertEquals(EdhocPhase.PERSISTED, responder.phase)
        val initiatorSession = initiator.session!!
        val responderSession = responder.session!!
        assertEquals(credR, initiatorSession.peerCredential)
        assertEquals(credI, responderSession.peerCredential)
        assertEquals(idCredR, initiatorSession.peerCredentialIdentifier)
        // RFC 9529 §2.6: the client's (Initiator's) OSCORE Sender ID is C_R, the server's is C_I.
        assertHex(t.CLIENT_S_OSCORE_SENDER_ID, initiatorSession.peerConnectionIdentifier.bytes)
        assertHex(t.SERVER_S_OSCORE_SENDER_ID, responderSession.peerConnectionIdentifier.bytes)
        assertSessionKeys(
            initiatorSession, responderSession, t.PRK_OUT, t.PRK_EXPORTER, t.OSCORE_MASTER_SECRET, t.OSCORE_MASTER_SALT,
        )
        // RFC 9529 §2.7.
        for (session in listOf(initiatorSession, responderSession)) {
            session.keyUpdate(t.CONTEXT_FOR_KEYUPDATE.hex())
            assertHex(t.PRK_OUT_AFTER_KEYUPDATE, session.prkOut())
            assertHex(t.PRK_EXPORTER_AFTER_KEYUPDATE, session.prkExporter())
            assertHex(t.OSCORE_MASTER_SECRET_AFTER_KEYUPDATE, session.oscoreMasterSecret())
            assertHex(t.OSCORE_MASTER_SALT_AFTER_KEYUPDATE, session.oscoreMasterSalt())
        }
    }

    /**
     * RFC 9529 §3: method 3 with P-256 static DH keys and CCS identified by 'kid', including the cipher
     * suite negotiation of §3.1–§3.3: the Responder rejects suite 6 with error code 2, and the Initiator
     * retries with SUITES_I = [6, 2].
     */
    @Test
    fun trace2StaticDiffieHellmanWithNegotiation() = runTest {
        val t = Rfc9529Trace2
        val credI = AuthenticationCredential.ccs(t.CRED_I.hex())
        val credR = AuthenticationCredential.ccs(t.CRED_R.hex())
        val idCredI = CredentialIdentifier.KeyId(byteArrayOf(0x2b))
        val idCredR = CredentialIdentifier.KeyId(byteArrayOf(0x32))
        assertHex(t.ID_CRED_I, idCredI.encoded)
        assertHex(t.ID_CRED_R, idCredR.encoded)
        val staticI = backend.importKeyPair(KeyType.P256, t.SK_I.hex(), ("04" + t.PK_I_X + t.PK_I_Y).hex())
        val staticR = backend.importKeyPair(KeyType.P256, t.SK_R.hex(), ("04" + t.PK_R_X + t.PK_R_Y).hex())
        assertHex("04" + t.PK_R_X + t.PK_R_Y, credR.publicKey)

        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                method = EdhocMethod.STATIC_DH_STATIC_DH,
                cipherSuites = listOf(CipherSuite.SUITE_6, CipherSuite.SUITE_2),
                credentials = listOf(LocalCredential.staticDiffieHellman(credI, idCredI, staticI.privateKey)),
                credentialResolver = TrustedCredentials(backend, listOf(credR)),
                connectionIdentifiers = sequenceOfIdentifiers(cid(t.FIRST_C_I), cid(t.C_I)),
                ephemeralKeys = sequenceOfKeys(
                    fixedKey(KeyType.X25519, t.FIRST_X, t.FIRST_G_X),
                    fixedKey(KeyType.P256, t.X, "04" + t.G_X + t.G_X_Y_COORDINATE),
                ),
            ),
            backend,
        )
        fun responder() = EdhocResponder(
            EdhocResponderConfig(
                methods = setOf(EdhocMethod.STATIC_DH_STATIC_DH),
                cipherSuites = listOf(CipherSuite.SUITE_2),
                credentials = listOf(LocalCredential.staticDiffieHellman(credR, idCredR, staticR.privateKey)),
                credentialResolver = TrustedCredentials(backend, listOf(credI)),
                connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid(t.C_R)),
                ephemeralKeys = sequenceOfKeys(fixedKey(KeyType.P256, t.Y, "04" + t.G_Y + t.G_Y_Y_COORDINATE)),
            ),
            backend,
        )

        // §3.1 and §3.2: suite 6 is not supported, the Responder answers with SUITES_R = 2.
        val firstMessage1 = initiator.createMessage1()
        assertHex(t.FIRST_MESSAGE_1, firstMessage1, "first message_1")
        val rejecting = responder()
        val rejection = assertFailsWith<EdhocException> { rejecting.processMessage1(firstMessage1) }
        assertEquals(EdhocError.WRONG_SELECTED_CIPHER_SUITE, rejection.errorCode)
        assertHex(t.ERROR, rejection.errorMessage!!, "error")
        assertEquals(EdhocPhase.ABORTED, rejecting.phase)

        val peerError = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(rejection.errorMessage!!) }
        assertEquals(listOf(2), peerError.error.cipherSuites)
        assertEquals(null, peerError.errorMessage)
        assertEquals(EdhocPhase.START, initiator.phase)
        assertEquals(CipherSuite.SUITE_2, initiator.cipherSuite)

        // §3.3 onwards.
        val responder = responder()
        val initiatorValues = RecordedValues().also { initiator.intermediateValues = it }
        val responderValues = RecordedValues().also { responder.intermediateValues = it }
        val message1 = initiator.createMessage1()
        assertHex(t.MESSAGE_1, message1, "message_1")
        responder.processMessage1(message1)
        assertEquals(EdhocMethod.STATIC_DH_STATIC_DH, responder.method)
        assertEquals(CipherSuite.SUITE_2, responder.cipherSuite)
        assertEquals(cid(t.C_I), responder.peerConnectionIdentifier)
        val message2 = responder.createMessage2()
        assertHex(t.MESSAGE_2, message2, "message_2")
        initiator.processMessage2(message2)
        val message3 = initiator.createMessage3().bytes
        assertHex(t.MESSAGE_3, message3, "message_3")
        responder.processMessage3(message3)
        val message4 = responder.createMessage4()
        assertHex(t.MESSAGE_4, message4, "message_4")
        initiator.processMessage4(message4)

        for (values in listOf(initiatorValues, responderValues)) {
            values.assertValue("H(message_1)", t.H_MESSAGE_1)
            values.assertValue("G_Y", t.G_Y)
            values.assertValue("G_XY", t.G_XY)
            values.assertValue("TH_2", t.TH_2)
            values.assertValue("PRK_2e", t.PRK_2E)
            values.assertValue("SALT_3e2m", t.SALT_3E2M)
            values.assertValue("G_RX", t.G_RX)
            values.assertValue("PRK_3e2m", t.PRK_3E2M)
            values.assertValue("MAC_2", t.MAC_2)
            values.assertValue("Signature_or_MAC_2", t.SIGNATURE_OR_MAC_2)
            values.assertValue("PLAINTEXT_2", t.PLAINTEXT_2)
            values.assertValue("KEYSTREAM_2", t.KEYSTREAM_2)
            values.assertValue("TH_3", t.TH_3)
            values.assertValue("SALT_4e3m", t.SALT_4E3M)
            values.assertValue("G_IY", t.G_IY)
            values.assertValue("PRK_4e3m", t.PRK_4E3M)
            values.assertValue("MAC_3", t.MAC_3)
            values.assertValue("PLAINTEXT_3", t.PLAINTEXT_3)
            values.assertValue("K_3", t.K_3)
            values.assertValue("IV_3", t.IV_3)
            values.assertValue("TH_4", t.TH_4)
            values.assertValue("PRK_out", t.PRK_OUT)
            values.assertValue("PRK_exporter", t.PRK_EXPORTER)
            values.assertValue("K_4", t.K_4)
            values.assertValue("IV_4", t.IV_4)
        }
        initiatorValues.assertValue("G_X", t.G_X)
        initiatorValues.assertValue("Signature_or_MAC_3", t.SIGNATURE_OR_MAC_3)
        assertHex(t.CIPHERTEXT_2, message2.copyOfRange(2 + 32, message2.size), "CIPHERTEXT_2")
        assertHex(t.CIPHERTEXT_3, message3.copyOfRange(1, message3.size), "CIPHERTEXT_3")
        assertHex(t.CIPHERTEXT_4, message4.copyOfRange(1, message4.size), "CIPHERTEXT_4")

        val initiatorSession = initiator.session!!
        val responderSession = responder.session!!
        assertEquals(credR, initiatorSession.peerCredential)
        assertEquals(credI, responderSession.peerCredential)
        // RFC 9529 §3.8.
        assertHex(t.CLIENT_S_OSCORE_SENDER_ID, initiatorSession.peerConnectionIdentifier.bytes)
        assertHex(t.SERVER_S_OSCORE_SENDER_ID, responderSession.peerConnectionIdentifier.bytes)
        assertSessionKeys(
            initiatorSession, responderSession, t.PRK_OUT, t.PRK_EXPORTER, t.OSCORE_MASTER_SECRET, t.OSCORE_MASTER_SALT,
        )
        // RFC 9529 §3.9.
        for (session in listOf(initiatorSession, responderSession)) {
            session.keyUpdate(t.CONTEXT_FOR_KEYUPDATE.hex())
            assertHex(t.PRK_OUT_AFTER_KEYUPDATE, session.prkOut())
            assertHex(t.PRK_EXPORTER_AFTER_KEYUPDATE, session.prkExporter())
            assertHex(t.OSCORE_MASTER_SECRET_AFTER_KEYUPDATE, session.oscoreMasterSecret())
            assertHex(t.OSCORE_MASTER_SALT_AFTER_KEYUPDATE, session.oscoreMasterSalt())
        }
    }

    private suspend fun signingKey(privateKey: String, publicKey: String) =
        backend.importKeyPair(KeyType.ED25519, privateKey.hex(), publicKey.hex()).privateKey

    private suspend fun assertSessionKeys(
        initiator: EdhocSession,
        responder: EdhocSession,
        prkOut: String,
        prkExporter: String,
        masterSecret: String,
        masterSalt: String,
    ) {
        for (session in listOf(initiator, responder)) {
            assertHex(prkOut, session.prkOut(), "PRK_out of ${session.role}")
            assertHex(prkExporter, session.prkExporter(), "PRK_exporter of ${session.role}")
            assertHex(masterSecret, session.oscoreMasterSecret(), "OSCORE Master Secret of ${session.role}")
            assertHex(masterSalt, session.oscoreMasterSalt(), "OSCORE Master Salt of ${session.role}")
        }
    }
}
