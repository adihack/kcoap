package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LoggerFactory
import dev.kcoap.log.RingBufferSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Complete handshakes between the Initiator and the Responder. */
class RoundTripTest {
    private val backend = DefaultCryptoBackend()

    /**
     * Every method with every suite the default backend runs, alternating message_4 and the two connection
     * identifier encodings (one-byte integer, byte string).
     */
    @Test
    fun everyMethodWithEverySuite() = runTest {
        var round = 0
        for (method in EdhocMethod.entries) {
            for (suite in Fixtures.registeredSuitesRunBy(backend, method)) {
                val shortIds = round % 2 == 0
                val initiatorId = if (shortIds) ConnectionIdentifier.ofInt(-24) else cid("abcd")
                val responderId = if (shortIds) ConnectionIdentifier.ofInt(23) else ConnectionIdentifier(ByteArray(0))
                val withMessage4 = round % 3 != 0
                val configs = Fixtures.configs(backend, method, suite, initiatorId, responderId)
                val handshake = Fixtures.handshake(backend, configs, withMessage4)
                val label = "method ${method.value}, suite ${suite.value}"
                assertSessionsAgree(handshake, label)
                val expectedPhase = if (withMessage4) EdhocPhase.PERSISTED else EdhocPhase.COMPLETED
                assertEquals(expectedPhase, handshake.initiator.phase, label)
                assertEquals(initiatorId, handshake.responderSession.peerConnectionIdentifier, label)
                assertEquals(responderId, handshake.initiatorSession.peerConnectionIdentifier, label)
                assertEquals(initiatorId, handshake.initiatorSession.ownConnectionIdentifier, label)
                assertEquals(method, handshake.responderSession.method, label)
                assertEquals(suite, handshake.initiatorSession.cipherSuite, label)
                round++
            }
        }
    }

    /** message_3 comes with the session it completes on both sides (RFC 9528 §5.4.2, §5.4.3). */
    @Test
    fun theSessionComesWithTheMessageThatCompletesIt() = runTest {
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, CipherSuite.SUITE_0)
        val initiator = EdhocInitiator(initiatorConfig, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())

        val message3 = initiator.createMessage3()
        val (bytes, session) = message3

        assertSame(initiator.session, session)
        assertContentEquals(message3.bytes, bytes)
        assertNotSame(message3.bytes, message3.bytes, "each read is a copy")
        assertEquals("Message3(${bytes.size} bytes, $session)", message3.toString())
        val responderSession = responder.processMessage3(bytes)
        assertSame(responder.session, responderSession)
        assertContentEquals(session.prkOut(), responderSession.prkOut())
    }

    /** Method 3 with compact kids on suite 2 gives the small messages of RFC 9529 §3 (with one suite in SUITES_I). */
    @Test
    fun staticDiffieHellmanMessagesAreCompact() = runTest {
        val method = EdhocMethod.STATIC_DH_STATIC_DH
        val configs = Fixtures.configs(backend, method, CipherSuite.SUITE_2, ConnectionIdentifier.ofInt(-24), cid("27"))
        assertEquals(listOf(37, 45, 19), Fixtures.handshake(backend, configs).messages.map { it.size })
    }

    @Test
    fun eadItemsReachTheOtherSideAndPaddingIsDropped() = runTest {
        val initiatorEad = RecordingEadHandler(
            toSend = mapOf(
                EdhocMessage.MESSAGE_1 to listOf(
                    EadItem(-5, byteArrayOf(1)), EadItem.padding(), EadItem.padding(ByteArray(3)),
                ),
                EdhocMessage.MESSAGE_3 to listOf(EadItem(7, byteArrayOf(2, 3))),
            ),
            recognized = setOf(9, -9),
        )
        val responderEad = RecordingEadHandler(
            toSend = mapOf(
                EdhocMessage.MESSAGE_2 to listOf(EadItem(9), EadItem(40, ByteArray(0))),
                EdhocMessage.MESSAGE_4 to listOf(EadItem(-9, byteArrayOf(4)), EadItem.padding(ByteArray(0))),
            ),
            recognized = setOf(-5, 7),
        )
        val configs = Fixtures.configs(
            backend, EdhocMethod.SIGNATURE_STATIC_DH, CipherSuite.SUITE_0,
            initiatorEad = initiatorEad, responderEad = responderEad,
        )
        val handshake = Fixtures.handshake(backend, configs, withMessage4 = true)
        assertSessionsAgree(handshake, "EAD")
        assertEquals(listOf(EadItem(-5, byteArrayOf(1))), responderEad.items(EdhocMessage.MESSAGE_1))
        assertEquals(listOf(EadItem(7, byteArrayOf(2, 3))), responderEad.items(EdhocMessage.MESSAGE_3))
        // The unrecognized non-critical item 40 is offered and ignored.
        assertEquals(listOf(EadItem(9), EadItem(40, ByteArray(0))), initiatorEad.items(EdhocMessage.MESSAGE_2))
        assertEquals(listOf(EadItem(-9, byteArrayOf(4))), initiatorEad.items(EdhocMessage.MESSAGE_4))
        // EAD_1 follows C_I in the clear: 2d (C_I), 24 41 01 (item -5), 00 (padding), 00 43 000000 (padding).
        assertTrue(handshake.messages[0].toHex().endsWith("2d" + "244101" + "00" + "0043000000"))
    }

    @Test
    fun kidsThatAreNotUniqueAreTriedInTurn() = runTest {
        val initiator = credential(AuthenticationKind.STATIC_DH, KeyType.P256, 0x2b)
        val impostorI = credential(AuthenticationKind.STATIC_DH, KeyType.P256, 0x2b)
        val responder = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 0x32)
        val impostorR = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 0x32)
        val method = EdhocMethod.STATIC_DH_SIGNATURE
        val suites = listOf(CipherSuite.SUITE_2)
        val initiatorConfig = EdhocInitiatorConfig(method, suites, listOf(initiator), trusting(impostorR, responder))
        val responderConfig =
            EdhocResponderConfig(setOf(method), suites, listOf(responder), trusting(impostorI, initiator))
        val handshake = Fixtures.handshake(backend, initiatorConfig to responderConfig)
        assertEquals(responder.credential, handshake.initiatorSession.peerCredential)
        assertEquals(initiator.credential, handshake.responderSession.peerCredential)
    }

    /** RFC 9528 §3.5.3.1 and RFC 9360: credentials carried by value in ID_CRED_x. */
    @Test
    fun credentialsByValue() = runTest {
        val signer = backend.generateKeyPair(KeyType.ED25519)
        val claims = CwtClaimsSet("by value", Fixtures.coseKey(KeyType.ED25519, signer.publicKey))
        val ccs = AuthenticationCredential.ccs(claims)
        val byValue = LocalCredential.signature(ccs, CredentialIdentifier.Kccs(ccs.claimsSet!!), signer.privateKey)
        val other = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 0x07)
        val configs = Fixtures.configs(EdhocMethod.SIGNATURE_SIGNATURE, CipherSuite.SUITE_0, byValue, other)
        val handshake = Fixtures.handshake(backend, configs)
        assertEquals(ccs, handshake.responderSession.peerCredential)
        assertTrue(handshake.responderSession.peerCredentialIdentifier is CredentialIdentifier.Kccs)
    }

    /**
     * The Initiator prefers 24 but starts with 2 (say, remembered from an earlier session); the Responder
     * supports both, so it must insist on 24 (RFC 9528 §6.3, Note 1 of §6.3.2).
     */
    @Test
    fun responderInsistsOnTheMorePreferredSuite() = runTest {
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        val i24 = credential(AuthenticationKind.SIGNATURE, KeyType.P384, 0x01)
        val i2 = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 0x02)
        val r24 = credential(AuthenticationKind.SIGNATURE, KeyType.P384, 0x03)
        val r2 = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 0x04)
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                method, listOf(CipherSuite.SUITE_24, CipherSuite.SUITE_2), listOf(i2, i24), trusting(r2, r24),
                initialCipherSuite = CipherSuite.SUITE_2,
            ),
            backend,
        )
        val suites = listOf(CipherSuite.SUITE_2, CipherSuite.SUITE_24)
        val responderConfig = EdhocResponderConfig(setOf(method), suites, listOf(r2, r24), trusting(i2, i24))
        val first = EdhocResponder(responderConfig, backend)
        val error = assertFailsWith<EdhocException> { first.processMessage1(initiator.createMessage1()) }
        assertEquals(listOf(24), error.error.cipherSuites)
        assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(error.errorMessage!!) }
        assertEquals(CipherSuite.SUITE_24, initiator.cipherSuite)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        responder.processMessage3(initiator.createMessage3().bytes)
        assertEquals(CipherSuite.SUITE_24, responder.session!!.cipherSuite)
        assertEquals(r24.credential, initiator.peerCredential)
    }

    @Test
    fun randomConnectionIdentifiersAreShortAndDistinct() = runTest {
        repeat(20) {
            val identifier = ConnectionIdentifierProvider.Random.choose(backend, ConnectionIdentifier.ofInt(0))
            assertEquals(1, identifier.encode().size)
            assertNotEquals(ConnectionIdentifier.ofInt(0), identifier)
        }
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        val suites = listOf(CipherSuite.SUITE_0)
        val i = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 1)
        val r = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 2)
        val handshake = Fixtures.handshake(
            backend,
            EdhocInitiatorConfig(method, suites, listOf(i), trusting(r)) to
                EdhocResponderConfig(setOf(method), suites, listOf(r), trusting(i)),
        )
        val session = handshake.initiatorSession
        assertNotEquals(session.initiatorConnectionIdentifier, session.responderConnectionIdentifier)
    }

    /** Protocol steps are logged; keys, PRKs and plaintexts never are, even at TRACE with payload dumps. */
    @Test
    fun logsNeverContainSecrets() = runTest {
        val sink = RingBufferSink(capacity = 200)
        val logging = LoggerFactory(
            LogConfig.build {
                level = LogLevel.TRACE
                this.sink = sink
                payloadDumps = true
            },
        )
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, EdhocMethod.STATIC_DH_STATIC_DH, CipherSuite.SUITE_2)
        val initiator = EdhocInitiator(initiatorConfig, backend, logging)
        val responder = EdhocResponder(responderConfig, backend, logging)
        val values = RecordedValues().also { initiator.intermediateValues = it }
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        responder.processMessage3(initiator.createMessage3().bytes)
        val session = initiator.session!!
        val log = sink.dump()
        assertTrue("message_1" in log && "session completed" in log, log)
        val secrets = listOf(
            "G_XY", "PRK_2e", "PRK_3e2m", "PRK_4e3m", "K_3", "IV_3", "PLAINTEXT_2", "PLAINTEXT_3", "PRK_out",
        )
        for (secret in secrets) {
            assertFalse(values[secret].toHex() in log, "$secret appears in the log")
        }
        assertFalse(session.prkOut().toHex() in log)
        assertFalse(session.toString().contains(session.prkOut().toHex()))
        assertTrue(session.toString().startsWith("EdhocSession(INITIATOR, method 3, suite 2"))
    }

    @Test
    fun sessionExporterAndKeyUpdate() = runTest {
        // Suite 24 runs on every platform and has a 32-byte application key, unlike the AES-128 suites.
        val configs = Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, CipherSuite.SUITE_24)
        val handshake = Fixtures.handshake(backend, configs)
        val initiator = handshake.initiatorSession
        val responder = handshake.responderSession
        val context = byteArrayOf(1, 2)
        assertContentEquals(initiator.exporter(40000, context, 42), responder.exporter(40000, context, 42))
        assertFalse(initiator.exporter(40000, context, 16).contentEquals(initiator.exporter(40001, context, 16)))
        assertEquals(32, initiator.oscoreMasterSecret().size, "the key length of A256GCM")
        val before = initiator.prkOut()
        initiator.keyUpdate(byteArrayOf(9))
        responder.keyUpdate(byteArrayOf(9))
        assertFalse(before.contentEquals(initiator.prkOut()))
        assertContentEquals(initiator.oscoreMasterSalt(), responder.oscoreMasterSalt())
        assertFailsWith<IllegalArgumentException> { initiator.exporter(-1, ByteArray(0), 16) }
        assertFailsWith<IllegalArgumentException> { initiator.exporter(0, ByteArray(0), 0) }
        assertNull(EdhocInitiator(configs.first, backend).session)
    }

    /** The wiped keys would derive predictable OSCORE secrets, so a destroyed session fails closed. */
    @Test
    fun destroyedSessionsThrow() = runTest {
        val configs = Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, CipherSuite.SUITE_0)
        val handshake = Fixtures.handshake(backend, configs)
        val session = handshake.initiatorSession
        session.destroy()
        session.destroy()
        assertFailsWith<IllegalStateException> { session.prkOut() }
        assertFailsWith<IllegalStateException> { session.prkExporter() }
        assertFailsWith<IllegalStateException> { session.exporter(40000, ByteArray(0), 16) }
        assertFailsWith<IllegalStateException> { session.oscoreMasterSecret() }
        assertFailsWith<IllegalStateException> { session.oscoreMasterSalt() }
        assertFailsWith<IllegalStateException> { session.keyUpdate(byteArrayOf(1)) }
        assertTrue(session.toString().startsWith("EdhocSession(INITIATOR"), "toString still works")
        // The peer's session is its own and keeps working.
        assertEquals(16, handshake.responderSession.oscoreMasterSecret().size)
    }

    /**
     * RFC 9528 §9.8: the ephemeral keys are destroyed after use whatever backend holds them, not only
     * software keys: here they are handles of a key store that a decorator unwraps (PrivateKey.destroy).
     */
    @Test
    fun ephemeralKeysOfAKeyStoreAreDestroyed() = runTest {
        val handles = mutableListOf<KeyStoreHandle>()
        val keyStore = object : CryptoBackend by backend {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
                backend.keyAgreement((privateKey as? KeyStoreHandle)?.key ?: privateKey, peerPublicKey)
        }
        val inKeyStore = EphemeralKeySource { crypto, type ->
            val pair = crypto.generateKeyPair(type)
            KeyPair(pair.publicKey, KeyStoreHandle(pair.privateKey).also { handles += it })
        }
        for (method in listOf(EdhocMethod.SIGNATURE_SIGNATURE, EdhocMethod.STATIC_DH_STATIC_DH)) {
            for (suite in listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2)) {
                handles.clear()
                val (initiator, responder) = Fixtures.configs(backend, method, suite)
                val configs = EdhocInitiatorConfig(
                    initiator.method, initiator.cipherSuites, initiator.credentials, initiator.credentialResolver,
                    initiator.connectionIdentifiers, ephemeralKeys = inKeyStore,
                ) to EdhocResponderConfig(
                    responder.methods, responder.cipherSuites, responder.credentials, responder.credentialResolver,
                    responder.connectionIdentifiers, ephemeralKeys = inKeyStore,
                )
                assertSessionsAgree(Fixtures.handshake(keyStore, configs), "method ${method.value}, suite ${suite.value}")
                assertEquals(2, handles.size, "X and Y")
                for (handle in handles) assertTrue(handle.isDestroyed, "method ${method.value}, suite ${suite.value}")
            }
        }
    }

    /** A key-store key: it holds a software key here, and records that EDHOC released it. */
    private class KeyStoreHandle(val key: PrivateKey) : PrivateKey(key.type) {
        var isDestroyed = false

        override fun destroy() {
            isDestroyed = true
            key.destroy()
        }
    }

    private suspend fun credential(kind: AuthenticationKind, type: KeyType, kid: Int): LocalCredential =
        Fixtures.ccsCredential(backend, kind, type, byteArrayOf(kid.toByte()))

    private fun trusting(vararg credentials: LocalCredential): CredentialResolver =
        TrustedCredentials(backend, credentials.map { it.credential })

    private fun RecordingEadHandler.items(message: EdhocMessage): List<EadItem> = received.getValue(message).toList()

    private suspend fun assertSessionsAgree(handshake: Fixtures.Handshake, label: String) {
        val initiator = handshake.initiatorSession
        val responder = handshake.responderSession
        assertContentEquals(initiator.prkOut(), responder.prkOut(), label)
        assertContentEquals(initiator.prkExporter(), responder.prkExporter(), label)
        assertContentEquals(initiator.oscoreMasterSecret(), responder.oscoreMasterSecret(), label)
        assertContentEquals(initiator.oscoreMasterSalt(), responder.oscoreMasterSalt(), label)
        assertEquals(EdhocRole.INITIATOR, initiator.role)
        assertEquals(EdhocRole.RESPONDER, responder.role)
        assertEquals(initiator.initiatorConnectionIdentifier, responder.initiatorConnectionIdentifier, label)
        assertEquals(initiator.responderConnectionIdentifier, responder.responderConnectionIdentifier, label)
        assertEquals(handshake.initiator.peerCredential, initiator.peerCredential)
    }
}
