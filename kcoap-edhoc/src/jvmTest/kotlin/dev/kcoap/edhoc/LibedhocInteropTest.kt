package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.bc.BouncyCastleCryptoBackend
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live EDHOC interop with libedhoc (`kcoap/libedhoc:0363bd3`, built from `interop/libedhoc/Dockerfile`), one
 * container per session, driven through `edhoc_peer`'s line protocol ([LibedhocPeer]).
 *
 * Sessions run in both roles with fresh random ephemeral keys on both sides and are compared across the
 * boundary by what each side derives: OSCORE Master Secret and Salt, Sender and Recipient IDs,
 * EDHOC_Exporter, a value derived from PRK_out, and the same again after EDHOC_KeyUpdate.
 *
 * Tests named `upstream...` document where libedhoc deviates from RFC 9528: they assert libedhoc's current
 * behaviour and say what the RFC requires, so they fail, and get flipped, once libedhoc is fixed.
 *
 * Skipped without Docker or the image.
 */
class LibedhocInteropTest {
    private val backend = BouncyCastleCryptoBackend(DefaultCryptoBackend())

    /** The suites libedhoc implements, apart from the post-quantum one tested separately. */
    private val classicalSuites =
        listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2, CipherSuite.SUITE_4, CipherSuite.SUITE_24)

    @Test
    fun kcoapInitiatorWithLibedhocResponder() = runBlocking {
        LibedhocPeer.assumeAvailable()
        var round = 0
        for (suite in classicalSuites) {
            for (method in EdhocMethod.entries) {
                kcoapInitiates(Session(suite, method, withMessage4 = round % 2 == 0, shortIds = round % 3 != 0))
                round++
            }
        }
    }

    @Test
    fun libedhocInitiatorWithKcoapResponder() = runBlocking {
        LibedhocPeer.assumeAvailable()
        var round = 0
        for (suite in classicalSuites) {
            for (method in EdhocMethod.entries) {
                libedhocInitiates(Session(suite, method, withMessage4 = round % 2 == 1, shortIds = round % 3 != 1))
                round++
            }
        }
    }

    /** Ed25519 X.509 chains sent by value (x5chain), in both directions. */
    @Test
    fun x509ChainsByValue() = runBlocking {
        LibedhocPeer.assumeAvailable()
        kcoapInitiates(Session(CipherSuite.SUITE_0, EdhocMethod.SIGNATURE_SIGNATURE, x509 = true))
        libedhocInitiates(
            Session(CipherSuite.SUITE_4, EdhocMethod.SIGNATURE_SIGNATURE, x509 = true, withMessage4 = true),
        )
    }

    /**
     * libedhoc's suite -24: ML-KEM-512 and ML-DSA-44 from liboqs on its side, from Bouncy Castle on ours, with
     * credentials as CCS (AKP COSE_Key) and as ML-DSA-44 certificate chains, in both roles.
     */
    @Test
    fun postQuantumSuite() = runBlocking {
        LibedhocPeer.assumeAvailable()
        for (x509 in listOf(false, true)) {
            val method = EdhocMethod.SIGNATURE_SIGNATURE
            kcoapInitiates(Session(CipherSuite.SUITE_PQC_1, method, x509 = x509, withMessage4 = x509))
            libedhocInitiates(Session(CipherSuite.SUITE_PQC_1, method, x509 = x509, shortIds = x509))
        }
    }

    /**
     * RFC 9528 §6.3, kcoap as Initiator: it prefers suite 24; libedhoc supports only 2 and answers with error
     * code 2 and SUITES_R = 2; kcoap retries with SUITES_I = [24, 2] against a new libedhoc session.
     */
    @Test
    fun cipherSuiteNegotiationWithKcoapInitiator() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        val identities = Identities.create(backend, method, CipherSuite.SUITE_2)
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                method, listOf(CipherSuite.SUITE_24, CipherSuite.SUITE_2), listOf(identities.initiator.local),
                trusting(identities.responder), connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid("2d")),
            ),
            backend,
        )
        val responder = arguments(
            "responder", method, listOf(CipherSuite.SUITE_2), cid("18"), identities.responder, identities.initiator,
        )
        LibedhocPeer.start("negotiation-responder-1", responder).use { first ->
            first.send(initiator.createMessage1())
            assertEquals("2", first.expect("failed").tokens[1], "ERR_CODE")
            val error = first.receiveMessage()
            assertEquals("0202", error.toHex(), "error code 2 with SUITES_R = 2")
            val peerError = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(error) }
            assertEquals(listOf(2), peerError.error.cipherSuites)
            assertEquals(1, first.exitStatus())
        }
        assertEquals(CipherSuite.SUITE_2, initiator.cipherSuite)
        LibedhocPeer.start("negotiation-responder-2", responder).use { second ->
            val message1 = initiator.createMessage1()
            assertTrue(message1.toHex().startsWith("00" + "82181802"), "SUITES_I = [24, 2]")
            second.send(message1)
            initiator.processMessage2(second.receiveMessage())
            second.send(initiator.createMessage3().bytes)
            compare(initiator.session!!, second.results())
            assertEquals(0, second.exitStatus())
        }
    }

    /**
     * RFC 9528 §6.3, libedhoc as Initiator: it prefers suite 24; kcoap supports only 2 and answers with error
     * code 2; libedhoc's peer retries with SUITES_I = [24, 2], which kcoap accepts.
     */
    @Test
    fun cipherSuiteNegotiationWithLibedhocInitiator() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        val identities = Identities.create(backend, method, CipherSuite.SUITE_2)
        val responderConfig = EdhocResponderConfig(
            setOf(method), listOf(CipherSuite.SUITE_2), listOf(identities.responder.local),
            trusting(identities.initiator), connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid("18")),
        )
        // The peer's key is for suite 2; it offers 24 first, which kcoap refuses before any key is used.
        val suites = listOf(CipherSuite.SUITE_24, CipherSuite.SUITE_2)
        val initiator = arguments("initiator", method, suites, cid("2d"), identities.initiator, identities.responder)
        LibedhocPeer.start("negotiation-initiator", initiator).use { peer ->
            val message1 = peer.receiveMessage()
            assertTrue(message1.toHex().startsWith("00" + "1818"), "SUITES_I = 24")
            val error = assertFailsWith<EdhocException> {
                EdhocResponder(responderConfig, backend).processMessage1(message1)
            }
            peer.send(error.errorMessage!!)
            assertEquals(listOf("2", "2"), peer.expect("peer_error").tokens, "error code 2, SUITES_R = 2")
            assertEquals("2", peer.expect("retry").tokens.single())
            val responder = EdhocResponder(responderConfig, backend)
            val retried = peer.receiveMessage()
            assertTrue(retried.toHex().startsWith("00" + "82181802"), "SUITES_I = [24, 2]")
            responder.processMessage1(retried)
            peer.send(responder.createMessage2())
            responder.processMessage3(peer.receiveMessage())
            compare(responder.session!!, peer.results())
        }
    }

    /** Tampered messages are rejected across the boundary, and the error messages each side sends decode. */
    @Test
    fun tamperedMessagesAreRejected() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val session = Session(CipherSuite.SUITE_2, EdhocMethod.STATIC_DH_STATIC_DH)
        val identities = Identities.create(backend, session.method, session.suite)
        LibedhocPeer.start("tampered-initiator", arguments("initiator", session, identities)).use { peer ->
            val responder = EdhocResponder(responderConfig(session, identities), backend)
            responder.processMessage1(peer.receiveMessage())
            peer.send(responder.createMessage2())
            val message3 = peer.receiveMessage().flipLastBit()
            val failure = assertFailsWith<EdhocException> { responder.processMessage3(message3) }
            assertEquals("Decryption of message_3 failed", failure.error.diagnostic)
        }
        LibedhocPeer.start("tampered-responder", arguments("responder", session, identities)).use { peer ->
            val initiator = EdhocInitiator(initiatorConfig(session, identities), backend)
            peer.send(initiator.createMessage1())
            val message2 = peer.receiveMessage()
            initiator.processMessage2(message2)
            peer.send(initiator.createMessage3().bytes.flipLastBit())
            assertEquals("1", peer.expect("failed").tokens[1])
            assertEquals(EdhocError.UNSPECIFIED_ERROR, EdhocError.decode(peer.receiveMessage()).code)
            assertEquals(1, peer.exitStatus())
        }
        LibedhocPeer.start("tampered-message-2", arguments("responder", session, identities)).use { peer ->
            val initiator = EdhocInitiator(initiatorConfig(session, identities), backend)
            peer.send(initiator.createMessage1())
            val message2 = peer.receiveMessage().flipLastBit()
            val failure = assertFailsWith<EdhocException> { initiator.processMessage2(message2) }
            assertEquals("Verification of Signature_or_MAC_2 failed", failure.message)
            assertEquals("Authentication failed", failure.error.diagnostic, "the wire error does not say why")
            // libedhoc decodes kcoap's error message (code 1 with a diagnostic) and stops.
            peer.send(failure.errorMessage!!)
            assertEquals(listOf("1"), peer.expect("peer_error").tokens)
            assertEquals(1, peer.exitStatus())
        }
    }

    /** Non-critical EAD items and padding (RFC 9528 §3.8) from kcoap do not disturb libedhoc. */
    @Test
    fun nonCriticalEadIsIgnored() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val ead = RecordingEadHandler(
            toSend = mapOf(
                EdhocMessage.MESSAGE_1 to listOf(EadItem(5, byteArrayOf(1, 2)), EadItem.padding(ByteArray(3))),
                EdhocMessage.MESSAGE_3 to listOf(EadItem.padding()),
            ),
        )
        kcoapInitiates(Session(CipherSuite.SUITE_0, EdhocMethod.SIGNATURE_SIGNATURE), ead)
    }

    /**
     * Upstream issue: a libedhoc endpoint without an EAD interface skips EAD processing entirely
     * (`edhoc_classic_message_1.c` step 4 runs only when `ead.process` is bound), so it accepts a critical EAD
     * item it does not recognize. RFC 9528 §3.8: such an endpoint "MUST send an EDHOC error message back ... and
     * the EDHOC session MUST be aborted". kcoap rejects the same message (see [ErrorHandlingTest]).
     */
    @Test
    fun upstreamLibedhocIgnoresUnknownCriticalEadWithoutAnEadInterface() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val ead = RecordingEadHandler(toSend = mapOf(EdhocMessage.MESSAGE_1 to listOf(EadItem(-5, byteArrayOf(1)))))
        // RFC 9528 would make libedhoc answer message_1 with an error; it completes the session instead.
        kcoapInitiates(Session(CipherSuite.SUITE_0, EdhocMethod.SIGNATURE_SIGNATURE), ead)
    }

    /**
     * Upstream issue: libedhoc's Responder treats only its last configured cipher suite as supported and skips
     * the check of RFC 9528 §5.2.3 step 2 / §6.3.1 that no suite before the selected one in SUITES_I is
     * supported. Configured with suites [0, 2] it accepts SUITES_I = [0, 2] with 2 selected, where the RFC
     * requires error code 2 with SUITES_R naming 0; and it refuses SUITES_I = 0 although 0 is configured, which
     * contradicts the documentation of `edhoc_set_cipher_suites` ("the Responder checks the selected suite
     * against those it supports").
     */
    @Test
    fun upstreamLibedhocResponderSupportsOnlyItsLastSuiteAndSkipsThePriorSuiteCheck() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        val identities = Identities.create(backend, method, CipherSuite.SUITE_2)
        val configured = listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2)
        val responder =
            arguments("responder", method, configured, cid("18"), identities.responder, identities.initiator)

        val preferringZero = EdhocInitiator(
            EdhocInitiatorConfig(
                method, configured, listOf(identities.initiator.local), trusting(identities.responder),
                initialCipherSuite = CipherSuite.SUITE_2,
            ),
            backend,
        )
        LibedhocPeer.start("prior-suite", responder).use { peer ->
            val message1 = preferringZero.createMessage1()
            assertTrue(message1.toHex().startsWith("00" + "820002"), "SUITES_I = [0, 2]")
            peer.send(message1)
            // RFC 9528: error code 2 with SUITES_R = 0. libedhoc answers with message_2 and completes.
            preferringZero.processMessage2(peer.receiveMessage())
            peer.send(preferringZero.createMessage3().bytes)
            compare(preferringZero.session!!, peer.results())
        }

        val ed25519 =
            InteropIdentity.create(backend, AuthenticationKind.SIGNATURE, CipherSuite.SUITE_0, "i0", byteArrayOf(0x07))
        val onlyZero = EdhocInitiator(
            EdhocInitiatorConfig(
                method, listOf(CipherSuite.SUITE_0), listOf(ed25519.local), trusting(identities.responder),
            ),
            backend,
        )
        LibedhocPeer.start("configured-suite", responder).use { peer ->
            peer.send(onlyZero.createMessage1())
            // RFC 9528: suite 0 is supported, so message_2 follows. libedhoc refuses it with error code 2.
            assertEquals("2", peer.expect("failed").tokens[1])
            val error = assertFailsWith<EdhocPeerErrorException> { onlyZero.processMessage2(peer.receiveMessage()) }
            assertEquals(listOf(0, 2), error.error.cipherSuites, "SUITES_R names the suite it just refused")
        }
    }

    /**
     * draft-ietf-lake-pqsuites §2, §6.3 and §6.4: methods 1–3 need DH/NIKE, which the ML-KEM suites do not
     * offer. kcoap refuses such an Initiator configuration and answers such a message_1 with error code 1.
     * libedhoc does not check the combination up front (the suites' `supports_dh_nike` flag is never read by
     * its core): its Initiator sends message_1 with method 3 on suite -24, and its Responder accepts that
     * message_1 and fails only while composing message_2, when the suite's static-DH key agreement reports
     * "ML-KEM suite does not support static Diffie-Hellman". Both directions end in an error message, so
     * this is a robustness note for libedhoc rather than an interop failure.
     */
    @Test
    fun staticDiffieHellmanMethodsOnTheKemSuiteAreRefused() = runBlocking {
        LibedhocPeer.assumeAvailable()
        val suite = CipherSuite.SUITE_PQC_1
        val signer = InteropIdentity.create(backend, AuthenticationKind.SIGNATURE, suite, "pq", byteArrayOf(0x32))
        val kcoapResponder = EdhocResponder(
            EdhocResponderConfig(EdhocMethod.entries.toSet(), listOf(suite), listOf(signer.local), trusting(signer)),
            backend,
        )
        val initiatorArguments = listOf("--role", "initiator", "--method", "3", "--suite", "-24", "--cid", "2d") +
            signer.ownArguments + signer.peerArguments
        LibedhocPeer.start("kem-method-3-initiator", initiatorArguments).use { peer ->
            val message1 = peer.receiveMessage()
            assertTrue(message1.toHex().startsWith("03" + "37"), "libedhoc sends METHOD 3 with SUITES_I = -24")
            val failure = assertFailsWith<EdhocException> { kcoapResponder.processMessage1(message1) }
            assertEquals("Method 3 not supported", failure.error.diagnostic)
            peer.send(failure.errorMessage!!)
            assertEquals(listOf("1"), peer.expect("peer_error").tokens)
        }

        val responderArguments = listOf("--role", "responder", "--method", "3", "--suite", "-24", "--cid", "18") +
            signer.ownArguments + signer.peerArguments
        LibedhocPeer.start("kem-method-3-responder", responderArguments).use { peer ->
            val ek = backend.generateKeyPair(KeyType.ML_KEM_512).publicKey
            peer.send(Message1(3, listOf(suite.value), ek, ConnectionIdentifier.ofInt(-14), emptyList()).encode())
            assertEquals("1", peer.expect("failed").tokens[1])
            assertEquals(EdhocError.UNSPECIFIED_ERROR, EdhocError.decode(peer.receiveMessage()).code)
        }
    }

    // --- one session ----------------------------------------------------------------------------------------

    private class Session(
        val suite: CipherSuite,
        val method: EdhocMethod,
        val withMessage4: Boolean = false,
        val shortIds: Boolean = true,
        val x509: Boolean = false,
    ) {
        // One-byte identifiers in -24..23 travel as CBOR integers, the others as byte strings (RFC 9528 §3.3.2).
        val initiatorId: ConnectionIdentifier get() = if (shortIds) ConnectionIdentifier.ofInt(-14) else cid("abcdef")
        val responderId: ConnectionIdentifier get() = if (shortIds) ConnectionIdentifier.ofInt(7) else cid("18")

        // The same holds for compact 'kid's in ID_CRED_x (RFC 9528 §3.5.3.2).
        val initiatorKid: ByteArray get() = if (shortIds) byteArrayOf(0x2b) else byteArrayOf(0x18)
        val responderKid: ByteArray
            get() = if (shortIds) byteArrayOf(0x32) else byteArrayOf(0xa1.toByte(), 0xb2.toByte())

        override fun toString(): String =
            "suite ${suite.value}, method ${method.value}, message_4 $withMessage4, " +
                "${if (shortIds) "integer" else "byte string"} identifiers${if (x509) ", x5chain" else ""}"
    }

    private class Identities(val initiator: InteropIdentity, val responder: InteropIdentity) {
        companion object {
            suspend fun create(backend: BouncyCastleCryptoBackend, method: EdhocMethod, suite: CipherSuite) =
                create(backend, Session(suite, method))

            suspend fun create(backend: BouncyCastleCryptoBackend, session: Session): Identities {
                val method = session.method
                return Identities(
                    InteropIdentity.create(
                        backend, method.initiatorAuthentication, session.suite, "initiator", session.initiatorKid,
                        session.x509,
                    ),
                    InteropIdentity.create(
                        backend, method.responderAuthentication, session.suite, "responder", session.responderKid,
                        session.x509,
                    ),
                )
            }
        }
    }

    private suspend fun kcoapInitiates(session: Session, ead: EadHandler = EadHandler.None) {
        val identities = Identities.create(backend, session)
        LibedhocPeer.start("responder", arguments("responder", session, identities)).use { peer ->
            try {
                val initiator = EdhocInitiator(initiatorConfig(session, identities, ead), backend)
                peer.send(initiator.createMessage1())
                initiator.processMessage2(peer.receiveMessage())
                peer.send(initiator.createMessage3().bytes)
                if (session.withMessage4) initiator.processMessage4(peer.receiveMessage())
                compare(initiator.session!!, peer.results())
                assertEquals(0, peer.exitStatus())
            } catch (e: Throwable) {
                peer.saveLog("kcoap-initiator")
                throw AssertionError("kcoap Initiator, libedhoc Responder, $session\n${peer.diagnostics()}", e)
            }
        }
    }

    private suspend fun libedhocInitiates(session: Session) {
        val identities = Identities.create(backend, session)
        LibedhocPeer.start("initiator", arguments("initiator", session, identities)).use { peer ->
            try {
                val responder = EdhocResponder(responderConfig(session, identities), backend)
                responder.processMessage1(peer.receiveMessage())
                peer.send(responder.createMessage2())
                responder.processMessage3(peer.receiveMessage())
                if (session.withMessage4) peer.send(responder.createMessage4())
                compare(responder.session!!, peer.results())
                assertEquals(0, peer.exitStatus())
            } catch (e: Throwable) {
                peer.saveLog("kcoap-responder")
                throw AssertionError("libedhoc Initiator, kcoap Responder, $session\n${peer.diagnostics()}", e)
            }
        }
    }

    private fun trusting(identity: InteropIdentity): CredentialResolver =
        TrustedCredentials(backend, listOf(identity.local.credential))

    private fun initiatorConfig(session: Session, identities: Identities, ead: EadHandler = EadHandler.None) =
        EdhocInitiatorConfig(
            session.method, listOf(session.suite), listOf(identities.initiator.local), trusting(identities.responder),
            connectionIdentifiers = ConnectionIdentifierProvider.fixed(session.initiatorId), eadHandler = ead,
        )

    private fun responderConfig(session: Session, identities: Identities) = EdhocResponderConfig(
        setOf(session.method), listOf(session.suite), listOf(identities.responder.local),
        trusting(identities.initiator), connectionIdentifiers = ConnectionIdentifierProvider.fixed(session.responderId),
    )

    /** edhoc_peer arguments for libedhoc in [role] in [session]. */
    private fun arguments(role: String, session: Session, identities: Identities): List<String> {
        val isInitiator = role == "initiator"
        val cid = if (isInitiator) session.initiatorId else session.responderId
        val own = if (isInitiator) identities.initiator else identities.responder
        val peer = if (isInitiator) identities.responder else identities.initiator
        return arguments(role, session.method, listOf(session.suite), cid, own, peer) +
            if (session.withMessage4) listOf("--message4") else emptyList()
    }

    private fun arguments(
        role: String,
        method: EdhocMethod,
        suites: List<CipherSuite>,
        cid: ConnectionIdentifier,
        own: InteropIdentity,
        peer: InteropIdentity,
    ): List<String> = listOf("--role", role, "--method", method.value.toString()) +
        suites.flatMap { listOf("--suite", it.value.toString()) } +
        listOf("--cid", cid.bytes.toHex().ifEmpty { "-" }) +
        listOf("--oscore-secret-length", suites.last().appAead.keyLength.toString()) +
        own.ownArguments + peer.peerArguments

    /** Everything libedhoc derived must equal what the kcoap session derives. */
    private suspend fun compare(session: EdhocSession, results: Map<String, ByteArray>) {
        fun result(name: String) = results[name] ?: fail("libedhoc did not report $name")
        assertEquals(session.cipherSuite.value.toByte(), result("suite").single(), "selected suite")
        assertContentEquals(session.initiatorConnectionIdentifier.bytes, result("c_i"), "C_I")
        assertContentEquals(session.responderConnectionIdentifier.bytes, result("c_r"), "C_R")
        assertContentEquals(session.oscoreMasterSecret(), result("oscore_secret"), "OSCORE Master Secret")
        assertContentEquals(session.oscoreMasterSalt(), result("oscore_salt"), "OSCORE Master Salt")
        // RFC 9528 Appendix A.1: libedhoc's Sender ID is our connection identifier, its Recipient ID its own.
        assertContentEquals(session.ownConnectionIdentifier.bytes, result("sender_id"), "libedhoc's Sender ID")
        assertContentEquals(session.peerConnectionIdentifier.bytes, result("recipient_id"), "libedhoc's Recipient ID")
        val keySchedule = KeySchedule(session.cipherSuite, backend)
        val context = "kcoap".encodeToByteArray()
        val checkInfo = "kcoap-prk-out-check".encodeToByteArray()
        assertContentEquals(session.exporter(32768, context, 32), result("exporter"), "EDHOC_Exporter")
        assertContentEquals(keySchedule.expand(session.prkOut(), checkInfo, 32), result("prk_out_check"), "PRK_out")
        session.keyUpdate("kcoap-key-update".encodeToByteArray())
        assertContentEquals(
            session.exporter(32768, context, 32), result("exporter_updated"), "EDHOC_Exporter after key update",
        )
        val updatedCheck = keySchedule.expand(session.prkOut(), checkInfo, 32)
        assertContentEquals(updatedCheck, result("prk_out_check_updated"), "PRK_out after key update")
    }

    private fun ByteArray.flipLastBit(): ByteArray =
        copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
}
