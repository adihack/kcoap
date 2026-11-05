package dev.kcoap.edhoc

import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborText
import dev.kcoap.cbor.toCbor
import dev.kcoap.common.toHex
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.vectors.Rfc9529Trace1
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Error messages (RFC 9528 §6), cipher suite negotiation (§6.3) and every way a session can fail. */
class ErrorHandlingTest {
    private val backend = DefaultCryptoBackend()
    private val nobody = TrustedCredentials(backend, emptyList())
    private val method0 = EdhocMethod.SIGNATURE_SIGNATURE
    private val authenticationFailed = EdhocError.unspecified("Authentication failed")

    // --- §6.3 cipher suite negotiation -------------------------------------------------------------------

    /** RFC 9528 Figure 9: the Responder supports none of the offered suites and lists all of its own. */
    @Test
    fun responderListsAllItsSuitesWhenNoneIsOffered() = runTest {
        val ed = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 1)
        val p256 = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 2)
        val preferences = listOf(CipherSuite.SUITE_6, CipherSuite.SUITE_0, CipherSuite.SUITE_2)
        val initiator = EdhocInitiator(EdhocInitiatorConfig(method0, preferences, listOf(ed, p256), nobody), backend)
        val responderSuites = listOf(CipherSuite.SUITE_24, CipherSuite.SUITE_2)
        val responderConfig = EdhocResponderConfig(setOf(method0), responderSuites, listOf(p256), nobody)
        val responder = EdhocResponder(responderConfig, backend)
        val error = assertFailsWith<EdhocException> { responder.processMessage1(initiator.createMessage1()) }
        assertEquals(listOf(2), error.error.cipherSuites, "only suite 2 has a credential at the Responder")
        val retry = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(error.errorMessage!!) }
        assertEquals(CipherSuite.SUITE_2, retry.retryWith)
        assertNull(retry.errorMessage, "error messages are not answered")
        assertEquals(CipherSuite.SUITE_2, initiator.cipherSuite)
        assertEquals(EdhocPhase.START, initiator.phase)
        // The next message_1 offers every more preferred suite: SUITES_I = [6, 0, 2].
        assertTrue(initiator.createMessage1().toHex().startsWith("0083060002"))
    }

    @Test
    fun initiatorGivesUpWithoutACommonSuite() = runTest {
        val (initiator, _) = pair()
        initiator.createMessage1()
        val failure = assertFailsWith<EdhocPeerErrorException> {
            initiator.processMessage2(EdhocError.wrongSelectedCipherSuite(listOf(24, 25)).encode())
        }
        assertEquals(listOf(24, 25), failure.error.cipherSuites)
        assertNull(failure.retryWith)
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
        assertSame(failure, initiator.failure)
        assertFailsWith<IllegalStateException> { initiator.createMessage1() }
    }

    /** A (forged) SUITES_R that names the selected suite again must not loop. */
    @Test
    fun initiatorGivesUpWhenSuitesRNamesTheSelectedSuite() = runTest {
        val (initiator, _) = pair()
        initiator.createMessage1()
        assertNull(assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2("0200".hex()) }.retryWith)
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
    }

    // --- methods ----------------------------------------------------------------------------------------

    @Test
    fun unsupportedMethodIsUnspecifiedError() = runTest {
        val (initiator, _) = pair(EdhocMethod.STATIC_DH_STATIC_DH)
        val (_, responder) = pair(method0)
        val failure = assertFailsWith<EdhocException> { responder.processMessage1(initiator.createMessage1()) }
        assertEquals(EdhocError.UNSPECIFIED_ERROR, failure.errorCode)
        assertEquals("Method 3 not supported", failure.error.diagnostic)
        val peer = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(failure.errorMessage!!) }
        assertEquals("Method 3 not supported", peer.error.diagnostic)
        assertNull(peer.errorMessage)

        val unknown = Message1(7, listOf(0), ByteArray(32), ConnectionIdentifier.ofInt(1), emptyList()).encode()
        val (_, other) = pair()
        assertEquals("Method 7 not supported", diagnosticOf { other.processMessage1(unknown) })
    }

    @Test
    fun methodWithoutUsableCredentialIsNotSupported() = runTest {
        val staticOnly = credential(AuthenticationKind.STATIC_DH, KeyType.X25519, 1)
        val methods = EdhocMethod.entries.toSet()
        val config = EdhocResponderConfig(methods, listOf(CipherSuite.SUITE_0), listOf(staticOnly), nobody)
        val responder = EdhocResponder(config, backend)
        val (initiator, _) = pair(method0)
        assertEquals("Method 0 not supported", diagnosticOf { responder.processMessage1(initiator.createMessage1()) })
    }

    // --- tampering --------------------------------------------------------------------------------------

    @Test
    fun tamperedMessage2FailsVerification() = runTest {
        for (method in listOf(method0, EdhocMethod.STATIC_DH_STATIC_DH)) {
            val (initiator, responder) = pair(method, CipherSuite.SUITE_2)
            responder.processMessage1(initiator.createMessage1())
            val message2 = responder.createMessage2()
            message2.flip(message2.size - 1)
            val failure = assertFailsWith<EdhocException> { initiator.processMessage2(message2) }
            assertEquals("Verification of Signature_or_MAC_2 failed", failure.message, method.name)
            assertEquals(EdhocPhase.ABORTED, initiator.phase)
            assertEquals(authenticationFailed.encode().toHex(), failure.errorMessage!!.toHex())
        }
    }

    /**
     * RFC 9528 §9.7 identity protection: the peer is told "Authentication failed" whatever failed, so it
     * cannot probe which credentials are trusted; the exception keeps the reason.
     */
    @Test
    fun everyPeerAuthenticationFailureSendsTheSameError() = runTest {
        val signer = backend.generateKeyPair(KeyType.ED25519)
        val ccs = AuthenticationCredential.ccs(CwtClaimsSet("x", Fixtures.coseKey(KeyType.ED25519, signer.publicKey)))
        val byValue = LocalCredential.signature(ccs, CredentialIdentifier.Kccs(ccs.claimsSet!!), signer.privateKey)
        val responderCredential = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 9)
        val impostor = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 9).credential
        val wrongType = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 0x32).credential
        val rejectingValidator = TrustedCredentials(
            backend, emptyList(), CertificateValidator { throw CertificateValidationException("no anchor") },
        )
        val failures = mutableMapOf<String, EdhocException>()

        // Responder: an untrusted credential by value, then a trusted kid whose key does not verify.
        for ((credential, resolver) in listOf(byValue to nobody, byValue to CredentialResolver { listOf(impostor) })) {
            val (initiatorConfig, responderConfig) =
                Fixtures.configs(method0, CipherSuite.SUITE_0, credential, responderCredential)
            val initiator = EdhocInitiator(initiatorConfig, backend)
            val responder = EdhocResponder(responderConfig.withResolver(resolver), backend)
            responder.processMessage1(initiator.createMessage1())
            initiator.processMessage2(responder.createMessage2())
            val failure = assertFailsWith<EdhocException> { responder.processMessage3(initiator.createMessage3().bytes) }
            failures[failure.message!!] = failure
        }
        // Initiator: a credential that does not fit suite 2, and a rejected certificate chain.
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method0, CipherSuite.SUITE_2)
        val initiator = EdhocInitiator(initiatorConfig.withResolver { listOf(wrongType) }, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        val misfit = assertFailsWith<EdhocException> { initiator.processMessage2(responder.createMessage2()) }
        failures[misfit.message!!] = misfit
        val chain = CredentialIdentifier.X509Chain(listOf(Rfc9529Trace1.CRED_I.hex()))
        val rejected = assertFailsWith<EdhocException> { rejectingValidator.resolve(chain) }
        failures[rejected.message!!] = rejected

        assertEquals(
            setOf(
                "Credential not trusted",
                "Verification of Signature_or_MAC_3 failed",
                "Peer credential does not fit cipher suite 2",
                "Certificate chain not trusted",
            ),
            failures.keys,
        )
        for ((reason, failure) in failures) {
            assertEquals(authenticationFailed, failure.error, reason)
            assertEquals(authenticationFailed.encode().toHex(), failure.errorMessage!!.toHex(), reason)
        }
        assertTrue(failures.getValue("Certificate chain not trusted").cause is CertificateValidationException)
    }

    @Test
    fun tamperedMessage3And4FailDecryption() = runTest {
        val (initiator, responder) = pair(EdhocMethod.STATIC_DH_SIGNATURE, CipherSuite.SUITE_3)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val message3 = initiator.createMessage3().bytes
        message3.flip(3)
        assertEquals("Decryption of message_3 failed", diagnosticOf { responder.processMessage3(message3) })

        val (initiator2, responder2) = pair(method0, CipherSuite.SUITE_6)
        responder2.processMessage1(initiator2.createMessage1())
        initiator2.processMessage2(responder2.createMessage2())
        responder2.processMessage3(initiator2.createMessage3().bytes)
        val message4 = responder2.createMessage4()
        message4.flip(1)
        val failure = assertFailsWith<EdhocException> { initiator2.processMessage4(message4) }
        assertEquals("Decryption of message_4 failed", failure.error.diagnostic)
        assertNotNull(failure.errorMessage, "RFC 9528 §5.5.3: the Initiator MUST send an error")
        assertNull(initiator2.session)
    }

    @Test
    fun tamperedGyBreaksTheSession() = runTest {
        val (initiator, responder) = pair(method0, CipherSuite.SUITE_6)
        responder.processMessage1(initiator.createMessage1())
        val message2 = responder.createMessage2()
        message2.flip(5)
        assertFailsWith<EdhocException> { initiator.processMessage2(message2) }
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
    }

    // --- credentials ------------------------------------------------------------------------------------

    @Test
    fun unknownKidIsErrorCode3() = runTest {
        val blind = CredentialResolver { emptyList() }
        val method3 = EdhocMethod.STATIC_DH_STATIC_DH
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method3, CipherSuite.SUITE_2)
        val initiator = EdhocInitiator(initiatorConfig, backend)
        val responder = EdhocResponder(responderConfig.withResolver(blind), backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val failure = assertFailsWith<EdhocException> { responder.processMessage3(initiator.createMessage3().bytes) }
        assertEquals(EdhocError.UNKNOWN_CREDENTIAL_REFERENCED, failure.errorCode)
        assertEquals("03f5", failure.errorMessage!!.toHex())

        val (initiatorConfig2, responderConfig2) = Fixtures.configs(backend, method0, CipherSuite.SUITE_0)
        val initiator2 = EdhocInitiator(initiatorConfig2.withResolver(blind), backend)
        val responder2 = EdhocResponder(responderConfig2, backend)
        responder2.processMessage1(initiator2.createMessage1())
        val failure2 = assertFailsWith<EdhocException> { initiator2.processMessage2(responder2.createMessage2()) }
        assertEquals(EdhocError.UNKNOWN_CREDENTIAL_REFERENCED, failure2.errorCode)
    }

    @Test
    fun untrustedOrUnsupportedIdentifiersAreUnspecifiedErrors() = runTest {
        val signer = backend.generateKeyPair(KeyType.ED25519)
        val ccs = AuthenticationCredential.ccs(CwtClaimsSet("x", Fixtures.coseKey(KeyType.ED25519, signer.publicKey)))
        val byValue = LocalCredential.signature(ccs, CredentialIdentifier.Kccs(ccs.claimsSet!!), signer.privateKey)
        val header = CborMap(CborInt(4) to byteArrayOf(1).toCbor(), CborInt(99) to CborText("x"))
        val other = LocalCredential.signature(ccs, CredentialIdentifier.Other(header), signer.privateKey)
        val responderCredential = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 9)
        // The untrusted credential is an authentication failure; the unsupported type is the peer's input.
        val cases = listOf(
            Triple(byValue, "Authentication failed", "Credential not trusted"),
            Triple(other, "Unsupported credential identifier", "Unsupported credential identifier"),
        )
        for ((credential, diagnostic, reason) in cases) {
            val (initiatorConfig, responderConfig) =
                Fixtures.configs(method0, CipherSuite.SUITE_0, credential, responderCredential)
            val initiator = EdhocInitiator(initiatorConfig, backend)
            val responder = EdhocResponder(responderConfig.withResolver(nobody), backend)
            responder.processMessage1(initiator.createMessage1())
            initiator.processMessage2(responder.createMessage2())
            val failure = assertFailsWith<EdhocException> { responder.processMessage3(initiator.createMessage3().bytes) }
            assertEquals(EdhocError.UNSPECIFIED_ERROR, failure.errorCode, reason)
            assertEquals(diagnostic, failure.error.diagnostic, reason)
            assertEquals(reason, failure.message)
        }
    }

    @Test
    fun resolvedCredentialMustFitTheSuite() = runTest {
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method0, CipherSuite.SUITE_2)
        val wrongType = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 0x32).credential
        val initiator = EdhocInitiator(initiatorConfig.withResolver { listOf(wrongType) }, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        val failure = assertFailsWith<EdhocException> { initiator.processMessage2(responder.createMessage2()) }
        assertEquals("Peer credential does not fit cipher suite 2", failure.message)
        assertEquals(authenticationFailed, failure.error, "the peer is not told why")
    }

    @Test
    fun resolverCanAbortWithItsOwnError() = runTest {
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method0, CipherSuite.SUITE_0)
        val policy = EdhocException(EdhocError.unspecified("Not my peer"))
        val initiator = EdhocInitiator(initiatorConfig.withResolver { throw policy }, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        assertSame(policy, assertFailsWith<EdhocException> { initiator.processMessage2(responder.createMessage2()) })
    }

    // --- EAD --------------------------------------------------------------------------------------------

    @Test
    fun unknownCriticalEadAborts() = runTest {
        val sender = RecordingEadHandler(
            toSend = mapOf(EdhocMessage.MESSAGE_1 to listOf(EadItem(4), EadItem(-77, byteArrayOf(1)))),
        )
        val receiver = RecordingEadHandler()
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, method0, CipherSuite.SUITE_0, initiatorEad = sender, responderEad = receiver)
        val responder = EdhocResponder(responderConfig, backend)
        val message1 = EdhocInitiator(initiatorConfig, backend).createMessage1()
        assertEquals("Unsupported critical EAD item -77", diagnosticOf { responder.processMessage1(message1) })
        assertEquals(2, receiver.received.getValue(EdhocMessage.MESSAGE_1).size)
    }

    @Test
    fun eadHandlerCanAbortWithItsOwnError() = runTest {
        val refusal = EdhocException(EdhocError(-1000, CborText("voucher rejected")))
        val handler = object : EadHandler {
            override suspend fun consume(message: EdhocMessage, item: EadItem): Boolean = throw refusal
        }
        val sender = RecordingEadHandler(toSend = mapOf(EdhocMessage.MESSAGE_3 to listOf(EadItem(-3))))
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, method0, CipherSuite.SUITE_0, initiatorEad = sender, responderEad = handler)
        val initiator = EdhocInitiator(initiatorConfig, backend)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val failure = assertFailsWith<EdhocException> { responder.processMessage3(initiator.createMessage3().bytes) }
        assertSame(refusal, failure)
        assertEquals(-1000, failure.errorCode)
        assertNull(failure.error.diagnostic, "only error code 1 has a diagnostic")
    }

    /** [EadHandler.None] ignores non-critical items. */
    @Test
    fun defaultEadHandlerIgnoresNonCriticalItems() = runTest {
        val sender = RecordingEadHandler(toSend = mapOf(EdhocMessage.MESSAGE_1 to listOf(EadItem(12, byteArrayOf(1)))))
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, method0, CipherSuite.SUITE_0, initiatorEad = sender)
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(EdhocInitiator(initiatorConfig, backend).createMessage1())
        assertEquals(EdhocPhase.VERIFIED_M1, responder.phase)
        assertEquals(emptyList(), EadHandler.None.produce(EdhocMessage.MESSAGE_2))
    }

    // --- malformed input --------------------------------------------------------------------------------

    @Test
    fun malformedMessagesAreRejected() = runTest {
        val missingConnectionIdentifier = "00005820" + "00".repeat(32)
        for (garbage in listOf("", "ff", "00", "0000", "000058", "00005820", "f5", missingConnectionIdentifier)) {
            val (_, responder) = pair()
            val failure = assertFailsWith<EdhocException>(garbage) { responder.processMessage1(garbage.hex()) }
            assertEquals(EdhocError.UNSPECIFIED_ERROR, failure.errorCode, garbage)
            assertTrue(failure.error.diagnostic!!.startsWith("Malformed message_1"), failure.error.diagnostic)
            assertNotNull(failure.errorMessage)
        }
        for (garbage in listOf("", "f5", "4100", "5801", "4101" + "00")) {
            val (initiator, _) = pair()
            initiator.createMessage1()
            val diagnostic = diagnosticOf { initiator.processMessage2(garbage.hex()) }
            assertTrue(diagnostic.startsWith("Malformed message_2"), diagnostic)
        }
    }

    // --- errors received in reply -----------------------------------------------------------------------

    @Test
    fun errorsReceivedLaterAbortWithoutAnswer() = runTest {
        val (initiator, responder) = pair()
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val reply = initiator.abort("Responder identity not allowed")
        assertEquals(EdhocError.unspecified("Responder identity not allowed").encode().toHex(), reply!!.toHex())
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
        assertNull(initiator.abort("again"))
        val peer = assertFailsWith<EdhocPeerErrorException> { responder.processMessage3(reply) }
        assertEquals("Responder identity not allowed", peer.error.diagnostic)
        assertEquals(EdhocPhase.ABORTED, responder.phase)
        assertNull(responder.session)

        // An error instead of message_4 aborts the Initiator, but the session it already has stays usable.
        val (initiator2, responder2) = pair()
        responder2.processMessage1(initiator2.createMessage1())
        initiator2.processMessage2(responder2.createMessage2())
        responder2.processMessage3(initiator2.createMessage3().bytes)
        val session = initiator2.session!!
        assertFailsWith<EdhocPeerErrorException> { initiator2.processMessage4(EdhocError.unspecified("late").encode()) }
        assertEquals(EdhocPhase.ABORTED, initiator2.phase)
        assertEquals(32, session.prkOut().size)
    }

    /** RFC 9528 §6.1: error code 0 aborts and is not answered; a malformed error is still an error. */
    @Test
    fun successCodeAndMalformedErrorsAbort() = runTest {
        val cases = listOf(
            "0001" to EdhocError(0, CborInt(1)),
            "01" to EdhocError.unspecified("Malformed error message"),
        )
        for ((bytes, expected) in cases) {
            val (initiator, _) = pair()
            initiator.createMessage1()
            val peer = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(bytes.hex()) }
            assertEquals(expected, peer.error)
            assertNull(peer.errorMessage)
            assertEquals(EdhocPhase.ABORTED, initiator.phase)
        }
    }

    @Test
    fun responderCanAbortAfterMessage1() = runTest {
        val (initiator, responder) = pair()
        assertNull(responder.method)
        assertNull(responder.cipherSuite)
        assertNull(responder.peerConnectionIdentifier)
        responder.processMessage1(initiator.createMessage1())
        assertEquals(EdhocPhase.VERIFIED_M1, responder.phase)
        val reply = responder.abort("Busy")
        assertEquals("Busy", EdhocError.decode(reply!!).diagnostic)
        assertEquals(EdhocPhase.ABORTED, responder.phase)
        assertEquals(method0, responder.method, "what message_1 settled stays visible")
        assertEquals("Busy", responder.failure!!.error.diagnostic)
        assertFailsWith<IllegalStateException> { responder.createMessage2() }
        assertNull(responder.abort("again"))
        assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(reply) }
        val (_, fresh) = pair()
        assertNull(fresh.abort("before anything"))
    }

    // --- state order ------------------------------------------------------------------------------------

    @Test
    fun callsOutOfOrderAreRejectedWithoutChangingState() = runTest {
        val (initiator, responder) = pair()
        assertFailsWith<IllegalStateException> { initiator.processMessage2(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { initiator.createMessage3().bytes }
        assertFailsWith<IllegalStateException> { initiator.processMessage4(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { responder.createMessage2() }
        assertFailsWith<IllegalStateException> { responder.processMessage3(ByteArray(0)) }
        assertFailsWith<IllegalStateException> { responder.createMessage4() }
        assertEquals(EdhocPhase.START, initiator.phase)
        assertEquals(EdhocPhase.START, responder.phase)

        val message1 = initiator.createMessage1()
        assertEquals(EdhocPhase.WAIT_M2, initiator.phase)
        assertFailsWith<IllegalStateException> { initiator.createMessage1() }
        responder.processMessage1(message1)
        assertFailsWith<IllegalStateException> { responder.processMessage1(message1) }
        val message2 = responder.createMessage2()
        assertEquals(EdhocPhase.WAIT_M3, responder.phase)
        initiator.processMessage2(message2)
        assertEquals(EdhocPhase.VERIFIED_M2, initiator.phase)
        assertNotNull(initiator.peerCredential)
        assertNull(initiator.session)
        assertFailsWith<IllegalStateException> { initiator.processMessage2(message2) }
        val message3 = initiator.createMessage3().bytes
        responder.processMessage3(message3)
        assertFailsWith<IllegalStateException> { responder.processMessage3(message3) }
        assertEquals(EdhocPhase.COMPLETED, responder.phase)
        val message4 = responder.createMessage4()
        assertFailsWith<IllegalStateException> { responder.createMessage4() }
        initiator.processMessage4(message4)
        assertFailsWith<IllegalStateException> { initiator.processMessage4(message4) }
        assertEquals(EdhocPhase.PERSISTED, initiator.phase)
        assertEquals(EdhocPhase.PERSISTED, responder.phase)
        assertNotNull(initiator.peerCredential)
        assertNotNull(responder.session)
        assertNull(initiator.failure)
    }

    // --- local failures ---------------------------------------------------------------------------------

    @Test
    fun connectionIdentifiersMustDiffer() = runTest {
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(backend, method0, CipherSuite.SUITE_0, cid("18"), cid("18"))
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(EdhocInitiator(initiatorConfig, backend).createMessage1())
        assertEquals("C_R must differ from C_I", diagnosticOf { responder.createMessage2() })
    }

    @Test
    fun backendFailuresBecomeEdhocExceptions() = runTest {
        val failing = object : DefaultCryptoBackend() {
            override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray) =
                throw IllegalStateException("secure element unavailable")
        }
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method0, CipherSuite.SUITE_0)
        val responder = EdhocResponder(responderConfig, failing)
        responder.processMessage1(EdhocInitiator(initiatorConfig, backend).createMessage1())
        val failure = assertFailsWith<EdhocException> { responder.createMessage2() }
        assertEquals("Processing message_2 failed", failure.error.diagnostic)
        assertTrue(failure.cause is IllegalStateException)
        assertNotNull(failure.errorMessage)

        val noRandomness = EphemeralKeySource { _, _ -> throw IllegalStateException("no randomness") }
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                initiatorConfig.method, initiatorConfig.cipherSuites, initiatorConfig.credentials,
                initiatorConfig.credentialResolver, ephemeralKeys = noRandomness,
            ),
            backend,
        )
        val early = assertFailsWith<EdhocException> { initiator.createMessage1() }
        assertNull(early.errorMessage, "nothing to answer before message_1 is sent")
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
    }

    /** Checks that do not trust the backend: the all-zero DH output (RFC 9528 §9.2) and signature lengths. */
    @Test
    fun backendOutputIsChecked() = runTest {
        val lax = object : DefaultCryptoBackend() {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray) = ByteArray(32)
        }
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method0, CipherSuite.SUITE_0)
        val responder = EdhocResponder(responderConfig, lax)
        responder.processMessage1(EdhocInitiator(initiatorConfig, backend).createMessage1())
        assertEquals("Invalid public key: all-zero shared secret", diagnosticOf { responder.createMessage2() })

        val shortSignatures = object : DefaultCryptoBackend() {
            override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray) =
                super.sign(algorithm, privateKey, data).copyOf(63)
        }
        val initiator = EdhocInitiator(initiatorConfig, shortSignatures)
        val honest = EdhocResponder(responderConfig, backend)
        honest.processMessage1(initiator.createMessage1())
        initiator.processMessage2(honest.createMessage2())
        val message3 = initiator.createMessage3().bytes
        assertEquals("Signature_or_MAC_3 has the wrong length", diagnosticOf { honest.processMessage3(message3) })
    }

    @Test
    fun cancellationAbortsAndPropagates() = runTest {
        val cancelling = object : EadHandler {
            override suspend fun produce(message: EdhocMessage): List<EadItem> = throw CancellationException("stop")
        }
        val (initiatorConfig, responderConfig) =
            Fixtures.configs(
                backend, method0, CipherSuite.SUITE_0, initiatorEad = cancelling, responderEad = cancelling,
            )
        val initiator = EdhocInitiator(initiatorConfig, backend)
        assertFailsWith<CancellationException> { initiator.createMessage1() }
        assertEquals(EdhocPhase.ABORTED, initiator.phase)
        assertTrue(initiator.failure!!.cause is CancellationException)

        val responder = EdhocResponder(responderConfig, backend)
        val (otherInitiator, _) = pair()
        responder.processMessage1(otherInitiator.createMessage1())
        assertFailsWith<CancellationException> { responder.createMessage2() }
        assertEquals(EdhocPhase.ABORTED, responder.phase)
    }

    @Test
    fun initiatorWithoutCredentialForTheNegotiatedSuiteFailsAtMessage3() = runTest {
        val ed = credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 1)
        val p256 = credential(AuthenticationKind.SIGNATURE, KeyType.P256, 2)
        val preferences = listOf(CipherSuite.SUITE_2, CipherSuite.SUITE_0)
        val initiator = EdhocInitiator(EdhocInitiatorConfig(method0, preferences, listOf(ed), trusting(p256)), backend)
        val responderConfig =
            EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), trusting(ed))
        val responder = EdhocResponder(responderConfig, backend)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        assertEquals("No credential for cipher suite 2", diagnosticOf { initiator.createMessage3().bytes })
    }

    // --- configuration ----------------------------------------------------------------------------------

    @Test
    fun configurationsAreValidated() = runTest {
        val ed = listOf(credential(AuthenticationKind.SIGNATURE, KeyType.ED25519, 1))
        val suite0 = listOf(CipherSuite.SUITE_0)
        assertFailsWith<IllegalArgumentException> { EdhocInitiatorConfig(method0, emptyList(), ed, nobody) }
        assertFailsWith<IllegalArgumentException> { EdhocInitiatorConfig(method0, suite0 + suite0, ed, nobody) }
        val suite25 = listOf(CipherSuite.SUITE_25)
        val suite2 = listOf(CipherSuite.SUITE_2)
        assertFailsWith<IllegalArgumentException> { EdhocInitiatorConfig(method0, suite25, ed, nobody) }
        assertFailsWith<IllegalArgumentException> { EdhocInitiatorConfig(method0, suite2, ed, nobody) }
        assertFailsWith<IllegalArgumentException> {
            EdhocInitiatorConfig(method0, suite0, ed, nobody, initialCipherSuite = CipherSuite.SUITE_1)
        }
        assertFailsWith<IllegalArgumentException> { EdhocResponderConfig(emptySet(), suite0, ed, nobody) }
        assertFailsWith<IllegalArgumentException> { EdhocResponderConfig(setOf(method0), suite0, emptyList(), nobody) }
        assertFailsWith<IllegalArgumentException> { EdhocResponderConfig(setOf(method0), emptyList(), ed, nobody) }
        val config = EdhocInitiatorConfig(method0, suite0, ed, nobody)
        assertSame(EadHandler.None, config.eadHandler)
        assertSame(EphemeralKeySource.Random, config.ephemeralKeys)
        assertSame(ConnectionIdentifierProvider.Random, config.connectionIdentifiers)
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private suspend fun pair(
        method: EdhocMethod = method0,
        suite: CipherSuite = CipherSuite.SUITE_0,
    ): Pair<EdhocInitiator, EdhocResponder> {
        val (initiatorConfig, responderConfig) = Fixtures.configs(backend, method, suite)
        return EdhocInitiator(initiatorConfig, backend) to EdhocResponder(responderConfig, backend)
    }

    private suspend fun credential(kind: AuthenticationKind, type: KeyType, kid: Int): LocalCredential =
        Fixtures.ccsCredential(backend, kind, type, byteArrayOf(kid.toByte()))

    private fun trusting(vararg credentials: LocalCredential): CredentialResolver =
        TrustedCredentials(backend, credentials.map { it.credential })

    private fun EdhocInitiatorConfig.withResolver(resolver: CredentialResolver) =
        EdhocInitiatorConfig(method, cipherSuites, credentials, resolver)

    private fun EdhocResponderConfig.withResolver(resolver: CredentialResolver) =
        EdhocResponderConfig(methods, cipherSuites, credentials, resolver)

    private suspend fun diagnosticOf(block: suspend () -> Unit): String {
        val failure = try {
            block()
            null
        } catch (e: EdhocException) {
            e
        }
        return assertNotNull(failure, "expected an EdhocException").error.diagnostic!!
    }

    private fun ByteArray.flip(index: Int) {
        this[index] = (this[index].toInt() xor 1).toByte()
    }
}
