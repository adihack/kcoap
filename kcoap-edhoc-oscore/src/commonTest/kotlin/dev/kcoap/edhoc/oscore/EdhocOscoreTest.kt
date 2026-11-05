package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborReader
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.edhoc.EdhocRole
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.MutableSecurityContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreIdentity
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EdhocOscoreTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.7", 5683)
    private val server = CoapUri.parse("coap://192.0.2.7")
    private val resource = CoapUri.parse("coap://192.0.2.7/.well-known/edhoc")

    private fun request(path: String) = CoapMessage.request(CoapCode.GET) {
        messageId = 1
        token = byteArrayOf(0x42)
        options { uriPath(path) }
    }

    /** A handshake whose requests [edhoc] answers, as the EDHOC resource of [server] would. */
    private fun handshake(peers: Peers, edhoc: EdhocServer, suites: List<CipherSuite> = listOf(CipherSuite.SUITE_0)) =
        EdhocClientHandshake(peers.initiatorConfig(suites), crypto, server) { edhoc.handle(it.payload, peer) }

    /** RFC 9528 Appendix A.1, Table 14: each side's Recipient ID is its own connection identifier. */
    @Test
    fun sequentialFlowDerivesMatchingOscoreContexts() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val established = mutableListOf<String>()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts, listener = EdhocSessionListener { session, context ->
            established += "${session.role} ${context.recipientId.toHex()}"
        })
        val session = handshake(peers, edhoc).run()

        val clientContext = session.oscore
        assertEquals(EdhocRole.INITIATOR, session.edhoc.role)
        assertContentEquals(session.edhoc.responderConnectionIdentifier.bytes, clientContext.senderId)
        assertContentEquals(session.edhoc.initiatorConnectionIdentifier.bytes, clientContext.recipientId)
        assertEquals(listOf("RESPONDER ${clientContext.senderId.toHex()}"), established)
        assertEquals(0, edhoc.pendingSessions())

        // The contexts interoperate: the client protects, the server's OSCORE layer verifies.
        val protected = OscoreClient(clientContext).protectRequest(request("temp"), peer)!!
        val verified = OscoreServer(contexts).unprotectRequest(protected.message, peer)!!
        assertEquals(listOf("temp"), verified.message.options.uriPath)
        val response = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { payload("21") })
        assertEquals("21", protected.verified(response).payloadAsText())

        // ADR 0008: the verified request carries who EDHOC authenticated, with the context's Recipient ID (C_R).
        val identity = assertIs<EdhocIdentity>(verified.identity)
        assertEquals(peers.client.credential, identity.peerCredential)
        assertEquals(peers.client.identifier, identity.peerCredentialIdentifier)
        assertContentEquals(clientContext.senderId, identity.oscore.recipientId)
        assertEquals(peers.server.credential, assertIs<EdhocIdentity>(clientContext.identity).peerCredential, "the client's names the server")
    }

    /**
     * API-08: the server takes any [MutableSecurityContextRepository], such as a bounded or persistent one, and
     * adds its contexts with addIfAbsent, never replacing another writer's (RFC 9668 §4.1.2).
     */
    @Test
    fun theServerAddsItsContextsToAnyMutableRepository() = runTest {
        val peers = Peers.create(crypto)
        val calls = mutableListOf<String>()
        val repository = object : MutableSecurityContextRepository {
            private val delegate = InMemoryContextRepository()

            override suspend fun find(recipientId: ByteArray, idContext: ByteArray?): SecurityContext? =
                delegate.find(recipientId, idContext).also { calls += "find ${recipientId.toHex()}" }

            override suspend fun add(context: SecurityContext) {
                calls += "add"
                delegate.add(context)
            }

            override suspend fun addIfAbsent(context: SecurityContext): Boolean =
                delegate.addIfAbsent(context).also { calls += "addIfAbsent ${context.recipientId.toHex()}" }

            override suspend fun remove(context: SecurityContext) {
                calls += "remove"
                delegate.remove(context)
            }
        }
        val session = handshake(peers, EdhocServer(peers.responderConfig(), crypto, repository)).run()

        val responderId = session.edhoc.responderConnectionIdentifier.bytes.toHex()
        assertEquals(listOf("find $responderId", "addIfAbsent $responderId"), calls, "C_R checked, then added once")
        assertNotNull(repository.find(session.oscore.senderId, null))
    }

    @Test
    fun edhocIdentitiesAreEqualByOscoreIdentityAndCredential() = runTest {
        val peers = Peers.create(crypto)
        val rid = OscoreIdentity(byteArrayOf(0x05), null)
        val one = EdhocIdentity(rid, peers.client.credential, peers.client.identifier)

        assertEquals(one, EdhocIdentity(OscoreIdentity(byteArrayOf(0x05), null), peers.client.credential, peers.server.identifier), "however identified")
        assertEquals(one.hashCode(), EdhocIdentity(rid, peers.client.credential, peers.client.identifier).hashCode())
        assertFalse(one == EdhocIdentity(rid, peers.server.credential, peers.server.identifier), "another credential")
        assertFalse(one == EdhocIdentity(OscoreIdentity(byteArrayOf(0x06), null), peers.client.credential, peers.client.identifier))
        assertFalse(one.equals(rid), "not an OSCORE identity alone")
        assertTrue(one.description.startsWith("EDHOC AuthenticationCredential(CCS"), one.description)
        assertTrue(one.description.endsWith("OSCORE rid=05"), one.description)
        assertEquals(one.description, one.toString())
    }

    @Test
    fun adr0008PeerSecurityFailsClosedForAServerWithoutAProtection() = runTest {
        val (clientContext, _) = contextPair()
        val security = PeerSecurity()

        val failure = assertFailsWith<MessageSecurityException> { security.protectRequest(request("lights"), peer) }
        assertEquals("No message security installed for $peer", failure.message)
        assertNull(security.protectRequest(request(".well-known/edhoc"), peer), "the EDHOC resource goes unprotected")

        security.install(peer, OscoreClient(clientContext))
        assertNotNull(security.protectRequest(request("lights"), peer)!!.message.options.oscore)
        security.remove(peer)
        assertFailsWith<MessageSecurityException>("removed, so closed again") { security.protectRequest(request("lights"), peer) }

        val opportunistic = PeerSecurity(allowUnprotected = true)
        assertNull(opportunistic.protectRequest(request("lights"), peer), "plaintext only when allowed explicitly")
    }

    /** A matching client and server context, without EDHOC. */
    private suspend fun contextPair(): Pair<SecurityContext, SecurityContext> {
        val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val client = SecurityContext.derive(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b)), crypto, EphemeralSequenceNumberStore())
        val server = SecurityContext.derive(SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a)), crypto, EphemeralSequenceNumberStore())
        return client to server
    }

    @Test
    fun message4WhenTheProfileUsesIt() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), sendMessage4 = true)
        val session = handshake(peers, edhoc).run(expectMessage4 = true)
        assertNotNull(session.oscore)
    }

    /** RFC 9528 §6.3 across the CoAP transfer: error code 2, then message_1 again with the server's suite. */
    @Test
    fun cipherSuiteNegotiationOverCoap() = runTest {
        val peers = Peers.create(crypto)
        // Suite 1 runs on every platform and takes the same Ed25519 credentials as suite 0.
        val edhoc = EdhocServer(peers.responderConfig(listOf(CipherSuite.SUITE_1)), crypto, InMemoryContextRepository())
        val requests = mutableListOf<CoapRequest>()
        val handshake = EdhocClientHandshake(peers.initiatorConfig(listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_1)), crypto, server) {
            requests += it
            edhoc.handle(it.payload, peer)
        }
        val session = handshake.run()
        assertEquals(CipherSuite.SUITE_1, session.edhoc.cipherSuite)
        assertEquals(3, requests.size, "message_1, message_1 again, message_3")
        assertTrue(requests.all { it.method == CoapCode.POST && it.uri == resource }, "${requests.map { it.uri }}")
    }

    /**
     * U1: the suites a configuration names are narrowed to what the backend runs once, on first use, not per
     * session, so neither unauthenticated clients nor repeated handshakes make the backend answer again
     * (docs/adr/0001-crypto-capabilities.md).
     */
    @Test
    fun serverAndClientAskTheBackendOncePerConfiguration() = runTest {
        val peers = Peers.create(crypto)
        val serverCrypto = CountingSupports(crypto)
        val clientCrypto = CountingSupports(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), serverCrypto, InMemoryContextRepository())
        val handshake = EdhocClientHandshake(peers.initiatorConfig(), clientCrypto, server) { edhoc.handle(it.payload, peer) }

        handshake.run()
        val asked = serverCrypto.questions to clientCrypto.questions
        assertTrue(asked.first > 0 && asked.second > 0, "$asked")
        handshake.run()
        handshake.runCombined()
        assertEquals(asked, serverCrypto.questions to clientCrypto.questions, "later sessions ask nothing")
    }

    /** RFC 9668 §3: message_3 rides on the first OSCORE request; the server completes EDHOC first. */
    @Test
    fun combinedRequestSavesARoundTrip() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts)
        val combined = CombinedRequestServer(edhoc, OscoreServer(contexts))
        var edhocRequests = 0
        val handshake = EdhocClientHandshake(peers.initiatorConfig(), crypto, server) {
            edhocRequests++
            edhoc.handle(it.payload, peer)
        }.runCombined()
        val (session, security) = handshake
        assertSame(handshake.session, session)
        assertSame(handshake.security, security)
        assertEquals(1, edhocRequests, "only message_1 goes to the EDHOC resource")
        assertTrue(security.isMessage3Pending())

        val first = security.protectRequest(request("lights"), peer)!!
        assertTrue(first.message.options.edhoc)
        assertContentEquals(session.edhoc.responderConnectionIdentifier.bytes, kidOf(first))
        val verified = combined.unprotectRequest(first.message, peer)!!
        assertEquals(listOf("lights"), verified.message.options.uriPath)
        assertFalse(verified.message.options.edhoc, "the EDHOC option is consumed")
        val response = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { payload("on") })
        assertEquals("on", first.verified(response).payloadAsText())

        // From now on plain OSCORE.
        assertFalse(security.isMessage3Pending())
        val second = security.protectRequest(request("lights"), peer)!!
        assertFalse(second.message.options.edhoc)
        assertEquals(listOf("lights"), combined.unprotectRequest(second.message, peer)!!.message.options.uriPath)
    }

    /** RFC 9668 Figure 4, byte for byte: the combined payload and the empty EDHOC option. */
    @Test
    fun combinedRequestMatchesRfc9668Figure4() = runTest {
        val oscoreRequest = CoapMessage.request(CoapCode.POST) {
            messageId = 0x5d1f
            token = "00003974".hexToBytes()
            options { oscore("090001".hexToBytes()) }
            payload = "612f1092f1776f1c1668b3825e".hexToBytes()
        }
        val fixed = object : dev.kcoap.coap.security.ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = object : ProtectedRequest {
                override val message = oscoreRequest
                override suspend fun unprotectResponse(response: CoapMessage) = ResponseVerification.Verified(response)
            }
        }
        val combined = CombinedRequestSecurity(fixed, "52d5535f3147e85f1cfacd9e78abf9e0a81bbf".hexToBytes())
            .protectRequest(request("ignored"), peer)!!
        assertEquals(
            "44025d1f00003974" + "93090001" + "c0" + "ff52d5535f3147e85f1cfacd9e78abf9e0a81bbf612f1092f1776f1c1668b3825e",
            UdpMessageCodec.encode(combined.message).toHex(),
        )
    }

    @Test
    fun combinedRequestErrorsFollowRfc9668() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts)
        val combined = CombinedRequestServer(edhoc, OscoreServer(contexts))
        val (_, security) = handshake(peers, edhoc).runCombined()
        val good = security.protectRequest(request("x"), peer)!!.message

        fun mutate(block: CoapMessage.Builder.() -> Unit) = good.toBuilder().apply(block).build()

        val noOscore = mutate { options { remove(dev.kcoap.coap.CoapOptions.OSCORE.number) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { combined.unprotectRequest(noOscore, peer) }.responseCode)
        val notCbor = mutate { payload = byteArrayOf(0x01, 0x02) }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { combined.unprotectRequest(notCbor, peer) }.responseCode)
        val onlyMessage3 = mutate { payload = CborReader(good.payload).readEncodedItem() }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { combined.unprotectRequest(onlyMessage3, peer) }.responseCode)
        val badKid = mutate { options { oscore("19".hexToBytes()) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { combined.unprotectRequest(badKid, peer) }.responseCode)
        val noKid = mutate { options { oscore("0100".hexToBytes()) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { combined.unprotectRequest(noKid, peer) }.responseCode)

        // A tampered message_3 fails EDHOC: an EDHOC error message, not an OSCORE error.
        val tampered = mutate { payload = payload.also { it[3] = (it[3].toInt() xor 1).toByte() } }
        val edhocFailure = assertFailsWith<MessageSecurityException> { combined.unprotectRequest(tampered, peer) }
        assertEquals(CoapCode.BAD_REQUEST, edhocFailure.responseCode)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, edhocFailure.contentFormat)
        assertEquals(EdhocError.UNSPECIFIED_ERROR, EdhocError.decode(edhocFailure.errorPayload!!).code)

        // The session is gone after the failure, so even the genuine request now finds none.
        val unknown = assertFailsWith<MessageSecurityException> { combined.unprotectRequest(good, peer) }
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, unknown.contentFormat)
    }

    @Test
    fun profilesWithMessage4CannotCombine() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts, sendMessage4 = true)
        val (_, security) = handshake(peers, edhoc).runCombined()
        val combined = security.protectRequest(request("x"), peer)!!.message
        val error = assertFailsWith<MessageSecurityException> { CombinedRequestServer(edhoc, OscoreServer(contexts)).unprotectRequest(combined, peer) }
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, error.contentFormat)
    }

    @Test
    fun laterBlocksDoNotCarryMessage3() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        val (_, security) = handshake(peers, edhoc).runCombined()
        val secondBlock = request("big").toBuilder().options { block1(dev.kcoap.coap.BlockOption(1, true, 2)) }.build()
        assertFalse(security.protectRequest(secondBlock, peer)!!.message.options.edhoc, "RFC 9668 §3.2.2 step 2.1")
        assertTrue(security.isMessage3Pending())
    }

    /**
     * RFC 9528 Appendix A.2.3: a malformed request gets an EDHOC error in a 4.00, generic on the wire, from
     * the [EdhocException] that [EdhocOverCoap.parseRequest] throws (API-03), not from a caught
     * IllegalArgumentException.
     */
    @Test
    fun serverAnswersBadRequestsWithEdhocErrors() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        for (payload in listOf(ByteArray(0), byteArrayOf(0xF5.toByte()), "1800".hexToBytes(), "f6".hexToBytes(), "41".hexToBytes(), "0a".hexToBytes())) {
            val response = edhoc.handle(payload, peer)
            assertEquals(CoapCode.BAD_REQUEST, response.code, payload.toHex())
            assertEquals(ContentFormat.EDHOC_CBOR_SEQ, response.contentFormat)
            assertEquals(EdhocError.unspecified("Malformed EDHOC request"), EdhocError.decode(response.payload), payload.toHex())
        }
        // A broken message_1 is an EDHOC error too.
        assertEquals(CoapCode.BAD_REQUEST, edhoc.handle(EdhocOverCoap.message1Request(resource, "0001".hexToBytes()).payload, peer).code)
    }

    /** API-03: what the peer sent is an EDHOC failure, with the detail kept for this side's logs. */
    @Test
    fun parseRequestThrowsEdhocExceptionsForMalformedPayloads() {
        val cases = mapOf(
            "" to "empty payload",
            "f5" to "no message_1 after true",
            "18" to "no CBOR item for a connection identifier",
            "1818" to "connection identifiers as integers are -24..23",
            "f6" to "a connection identifier is an integer or a byte string",
            "41" to "no CBOR item for a connection identifier",
            "4100" to "no EDHOC message after the connection identifier",
            "0a" to "no EDHOC message after the connection identifier",
        )
        for ((hex, detail) in cases) {
            val failure = assertFailsWith<EdhocException>(hex) { EdhocOverCoap.parseRequest(hex.hexToBytes()) }
            assertEquals(EdhocError.unspecified("Malformed EDHOC request"), failure.error, hex)
            assertContentEquals(failure.error.encode(), failure.errorMessage, "an error to answer with")
            assertTrue(failure.message!!.startsWith("Malformed EDHOC request: $detail"), "$hex: ${failure.message}")
        }
    }

    @Test
    fun clientAbortAndUnknownSessions() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), maxPendingSessions = 1)
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        val message2 = edhoc.handle(EdhocOverCoap.message1Request(resource, initiator.createMessage1()).payload, peer).payload
        initiator.processMessage2(message2)
        val responderId = initiator.createMessage3().session.responderConnectionIdentifier // not sent
        assertEquals(1, edhoc.pendingSessions())

        // The client sends an error instead of message_3: the session ends without a reply error.
        val bye = EdhocError.unspecified("bye").encode()
        val abort = edhoc.handle(EdhocOverCoap.continuationRequest(resource, responderId, bye).payload, peer)
        assertEquals(CoapResponse.changed(), abort)
        assertEquals(0, edhoc.pendingSessions())
        val unknown = edhoc.handle(EdhocOverCoap.continuationRequest(resource, responderId, byteArrayOf(0x40)).payload, peer)
        assertEquals(CoapCode.BAD_REQUEST, unknown.code)

        // Beyond maxPendingSessions a new message_1 is refused; the pending session is kept.
        val first = EdhocInitiator(peers.initiatorConfig(), crypto)
        first.processMessage2(edhoc.handle(EdhocOverCoap.message1Request(resource, first.createMessage1()).payload, peer).payload)
        val second = EdhocInitiator(peers.initiatorConfig(), crypto)
        val refused = edhoc.handle(EdhocOverCoap.message1Request(resource, second.createMessage1()).payload, peer)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, refused.code, "RFC 9528 Appendix A.2.3: a server error")
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, refused.contentFormat)
        assertEquals("Too many pending EDHOC sessions", EdhocError.decode(refused.payload).diagnostic)
        assertEquals(1, edhoc.pendingSessions())
        val (firstMessage3, firstSession) = first.createMessage3()
        val kept = edhoc.handle(EdhocOverCoap.continuationRequest(resource, firstSession.responderConnectionIdentifier, firstMessage3).payload, peer)
        assertEquals(CoapCode.CHANGED, kept.code)
        assertFailsWith<IllegalArgumentException> { EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), maxPendingSessions = 0) }
    }

    /** API-03: every way the transfer fails is an [EdhocException]; a transfer failure has nothing to answer. */
    @Test
    fun clientSurfacesServerFailures() = runTest {
        val peers = Peers.create(crypto)
        val rejecting = EdhocClientHandshake(peers.initiatorConfig(), crypto, server) { CoapResponse.error(CoapCode.NOT_FOUND) }
        val error = assertFailsWith<EdhocTransferException> { rejecting.run() }
        assertEquals(CoapCode.NOT_FOUND, error.response?.code)
        assertEquals("Unexpected 4.04 Not Found after message_1", error.message)
        assertIs<EdhocException>(error)
        assertEquals(EdhocError.UNSPECIFIED_ERROR, error.errorCode)
        assertNull(error.errorMessage, "nothing to send back")

        // The server rejects message_3 with an EDHOC error: the client raises it.
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        var calls = 0
        val failing = EdhocClientHandshake(peers.initiatorConfig(), crypto, server) { request ->
            if (++calls == 2) EdhocOverCoap.errorResponse(EdhocError.unspecified("no").encode()) else edhoc.handle(request.payload, peer)
        }
        assertFailsWith<EdhocPeerErrorException> { failing.run() }

        // A message_3 answered with neither a 2.04 nor an EDHOC error is a transfer failure, message_4 or not.
        val notEdhoc = EdhocClientHandshake(peers.initiatorConfig(), crypto, server) { request ->
            if (EdhocOverCoap.parseRequest(request.payload) is EdhocOverCoap.ServerRequest.NewSession) {
                edhoc.handle(request.payload, peer)
            } else {
                CoapResponse.content("not EDHOC")
            }
        }
        val afterMessage3 = assertFailsWith<EdhocException> { notEdhoc.run(expectMessage4 = true) }
        assertEquals("Unexpected 2.05 Content after message_3", assertIs<EdhocTransferException>(afterMessage3).message)

        // A server that names the suite just rejected ends the session (RFC 9528 §6.3.1).
        val stubborn = EdhocClientHandshake(peers.initiatorConfig(listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_4)), crypto, server) {
            EdhocOverCoap.errorResponse(EdhocError.wrongSelectedCipherSuite(listOf(4)).encode())
        }
        assertFailsWith<EdhocPeerErrorException> { stubborn.run() }

        // Endless negotiation stops: a server that always names the other suite.
        var rounds = 0
        val fickle = EdhocClientHandshake(peers.initiatorConfig(listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_1)), crypto, server) {
            val other = if (++rounds % 2 == 1) 1 else 0
            EdhocOverCoap.errorResponse(EdhocError.wrongSelectedCipherSuite(listOf(other)).encode())
        }
        val endless = assertFailsWith<EdhocTransferException> { fickle.run() }
        assertEquals("Cipher suite negotiation did not converge", endless.message)
        assertNull(endless.response, "no single response to blame")
        assertEquals(4, rounds)
    }

    @Test
    fun requestFraming() {
        val first = EdhocOverCoap.message1Request(resource, "0102".hexToBytes())
        assertEquals(CoapCode.POST, first.method)
        assertEquals(resource, first.uri)
        assertEquals(listOf(".well-known", "edhoc"), first.uri.pathSegments)
        assertEquals(ContentFormat.CID_EDHOC_CBOR_SEQ, first.options.contentFormat)
        assertTrue(first.isConfirmable)
        assertEquals("f50102", first.payload.toHex())
        val parsed = EdhocOverCoap.parseRequest(first.payload)
        assertContentEquals("0102".hexToBytes(), (parsed as EdhocOverCoap.ServerRequest.NewSession).message1)

        // RFC 9528 §3.3.2: one-byte integers and byte strings both name a C_R.
        val integer = EdhocOverCoap.parseRequest(EdhocOverCoap.continuationRequest(resource, ConnectionIdentifier.ofInt(-8), "40".hexToBytes()).payload)
        assertEquals(ConnectionIdentifier.ofInt(-8), (integer as EdhocOverCoap.ServerRequest.Continuation).responderId)
        val bytes = EdhocOverCoap.parseRequest(EdhocOverCoap.continuationRequest(resource, ConnectionIdentifier("1234".hexToBytes()), "40".hexToBytes()).payload)
        assertEquals(ConnectionIdentifier("1234".hexToBytes()), (bytes as EdhocOverCoap.ServerRequest.Continuation).responderId)
        assertContentEquals("40".hexToBytes(), bytes.message)

        val response = EdhocOverCoap.messageResponse("aa".hexToBytes())
        assertEquals(CoapCode.CHANGED, response.code)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, response.contentFormat)
        assertContentEquals("aa".hexToBytes(), response.payload)
        val clientError = EdhocOverCoap.errorResponse(ByteArray(1))
        assertEquals(CoapCode.BAD_REQUEST, clientError.code)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, clientError.contentFormat)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, EdhocOverCoap.errorResponse(ByteArray(1), isServerError = true).code)
        assertEquals(CoapResponse(CoapCode.CHANGED), EdhocOverCoap.completedResponse())
    }

    /** The handshake posts to [path] at the scheme, host and port of the server URI, whatever its own path. */
    @Test
    fun theHandshakeTargetsItsPathOnTheServer() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        val targets = mutableListOf<CoapUri>()
        val elsewhere = CoapUri.parse("coap+tcp://[2001:db8::1]:5684/ignored/path?q=1")
        EdhocClientHandshake(peers.initiatorConfig(), crypto, elsewhere, path = "/auth/edhoc/") {
            targets += it.uri
            edhoc.handle(it.payload, peer)
        }.run()
        assertEquals(List(2) { CoapUri.parse("coap+tcp://[2001:db8::1]:5684/auth/edhoc") }, targets)
    }

    @Test
    fun suitesWithoutHmacCannotFeedOscore() = runTest {
        // Suite 25's application hash is SHAKE256; OSCORE needs an HMAC-based HKDF.
        assertTrue(CipherSuite.SUITE_25.appHash.isXof)
    }

    private fun kidOf(request: ProtectedRequest): ByteArray =
        dev.kcoap.oscore.OscoreOptionValue.decode(request.message.options.oscore!!).kid!!

    /** [delegate], counting the capability questions it is asked. */
    private class CountingSupports(private val delegate: CryptoBackend) : CryptoBackend by delegate {
        var questions = 0
            private set

        override suspend fun supports(algorithm: AeadAlgorithm): Boolean = delegate.supports(algorithm).also { questions++ }

        override suspend fun supports(type: KeyType): Boolean = delegate.supports(type).also { questions++ }

        override suspend fun supports(algorithm: SignatureAlgorithm): Boolean = delegate.supports(algorithm).also { questions++ }

        override suspend fun supports(algorithm: KemAlgorithm): Boolean = delegate.supports(algorithm).also { questions++ }

        override suspend fun supports(algorithm: HashAlgorithm): Boolean = delegate.supports(algorithm).also { questions++ }
    }
}
