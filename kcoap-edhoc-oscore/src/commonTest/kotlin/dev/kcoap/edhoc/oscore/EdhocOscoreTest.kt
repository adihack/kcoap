package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborReader
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.edhoc.EdhocRole
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreServer
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
import kotlin.test.assertTrue

class EdhocOscoreTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.7", 5683)

    private fun request(path: String) = CoapMessage.request(CoapCode.GET) {
        messageId = 1
        token = byteArrayOf(0x42)
        options { uriPath(path) }
    }

    /** RFC 9528 Appendix A.1, Table 14: each side's Recipient ID is its own connection identifier. */
    @Test
    fun sequentialFlowDerivesMatchingOscoreContexts() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val established = mutableListOf<String>()
        val server = EdhocServer(peers.responderConfig(), crypto, contexts, listener = EdhocSessionListener { session, context ->
            established += "${session.role} ${context.recipientId.toHex()}"
        })
        val handshake = EdhocClientHandshake(peers.initiatorConfig(), crypto) { server.handle(it) }
        val session = handshake.run()

        val clientContext = session.oscore
        assertEquals(EdhocRole.INITIATOR, session.edhoc.role)
        assertContentEquals(session.edhoc.responderConnectionIdentifier.bytes, clientContext.senderId)
        assertContentEquals(session.edhoc.initiatorConnectionIdentifier.bytes, clientContext.recipientId)
        assertEquals(listOf("RESPONDER ${clientContext.senderId.toHex()}"), established)
        assertEquals(0, server.pendingSessions())

        // The contexts interoperate: the client protects, the server's OSCORE layer verifies.
        val protected = OscoreClient(clientContext).protectRequest(request("temp"), peer)!!
        val verified = OscoreServer(contexts).unprotectRequest(protected.message, peer)!!
        assertEquals(listOf("temp"), verified.message.options.uriPath)
        val response = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { payload("21") })
        assertEquals("21", protected.unprotectResponse(response).payloadAsText())
    }

    @Test
    fun message4WhenTheProfileUsesIt() = runTest {
        val peers = Peers.create(crypto)
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), sendMessage4 = true)
        val session = EdhocClientHandshake(peers.initiatorConfig(), crypto) { server.handle(it) }.run(expectMessage4 = true)
        assertNotNull(session.oscore)
    }

    /** RFC 9528 §6.3 across the CoAP transfer: error code 2, then message_1 again with the server's suite. */
    @Test
    fun cipherSuiteNegotiationOverCoap() = runTest {
        val peers = Peers.create(crypto)
        // Suite 1 runs on every platform and takes the same Ed25519 credentials as suite 0.
        val server = EdhocServer(peers.responderConfig(listOf(CipherSuite.SUITE_1)), crypto, InMemoryContextRepository())
        val requests = mutableListOf<CoapMessage>()
        val handshake = EdhocClientHandshake(peers.initiatorConfig(listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_1)), crypto) {
            requests += it
            server.handle(it)
        }
        val session = handshake.run()
        assertEquals(CipherSuite.SUITE_1, session.edhoc.cipherSuite)
        assertEquals(3, requests.size, "message_1, message_1 again, message_3")
    }

    /** RFC 9668 §3: message_3 rides on the first OSCORE request; the server completes EDHOC first. */
    @Test
    fun combinedRequestSavesARoundTrip() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts)
        val server = CombinedRequestServer(edhoc, OscoreServer(contexts))
        var edhocRequests = 0
        val (session, security) = EdhocClientHandshake(peers.initiatorConfig(), crypto) {
            edhocRequests++
            edhoc.handle(it)
        }.runCombined()
        assertEquals(1, edhocRequests, "only message_1 goes to the EDHOC resource")
        assertTrue(security.isMessage3Pending())

        val first = security.protectRequest(request("lights"), peer)!!
        assertTrue(first.message.options.edhoc)
        assertContentEquals(session.edhoc.responderConnectionIdentifier.bytes, kidOf(first))
        val verified = server.unprotectRequest(first.message, peer)!!
        assertEquals(listOf("lights"), verified.message.options.uriPath)
        assertFalse(verified.message.options.edhoc, "the EDHOC option is consumed")
        val response = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { payload("on") })
        assertEquals("on", first.unprotectResponse(response).payloadAsText())

        // From now on plain OSCORE.
        assertFalse(security.isMessage3Pending())
        val second = security.protectRequest(request("lights"), peer)!!
        assertFalse(second.message.options.edhoc)
        assertEquals(listOf("lights"), server.unprotectRequest(second.message, peer)!!.message.options.uriPath)
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
                override suspend fun unprotectResponse(response: CoapMessage) = response
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
        val server = CombinedRequestServer(edhoc, OscoreServer(contexts))
        val (_, security) = EdhocClientHandshake(peers.initiatorConfig(), crypto) { edhoc.handle(it) }.runCombined()
        val good = security.protectRequest(request("x"), peer)!!.message

        fun mutate(block: CoapMessage.Builder.() -> Unit) = good.toBuilder().apply(block).build()

        val noOscore = mutate { options { remove(dev.kcoap.coap.CoapOptions.OSCORE.number) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { server.unprotectRequest(noOscore, peer) }.responseCode)
        val notCbor = mutate { payload = byteArrayOf(0x01, 0x02) }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { server.unprotectRequest(notCbor, peer) }.responseCode)
        val onlyMessage3 = mutate { payload = CborReader(good.payload).readEncodedItem() }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { server.unprotectRequest(onlyMessage3, peer) }.responseCode)
        val badKid = mutate { options { oscore("19".hexToBytes()) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { server.unprotectRequest(badKid, peer) }.responseCode)
        val noKid = mutate { options { oscore("0100".hexToBytes()) } }
        assertEquals(CoapCode.BAD_REQUEST, assertFailsWith<MessageSecurityException> { server.unprotectRequest(noKid, peer) }.responseCode)

        // A tampered message_3 fails EDHOC: an EDHOC error message, not an OSCORE error.
        val tampered = mutate { payload = payload.also { it[3] = (it[3].toInt() xor 1).toByte() } }
        val edhocFailure = assertFailsWith<MessageSecurityException> { server.unprotectRequest(tampered, peer) }
        assertEquals(CoapCode.BAD_REQUEST, edhocFailure.responseCode)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, edhocFailure.contentFormat)
        assertEquals(EdhocError.UNSPECIFIED_ERROR, EdhocError.decode(edhocFailure.errorPayload!!).code)

        // The session is gone after the failure, so even the genuine request now finds none.
        val unknown = assertFailsWith<MessageSecurityException> { server.unprotectRequest(good, peer) }
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, unknown.contentFormat)
    }

    @Test
    fun profilesWithMessage4CannotCombine() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(peers.responderConfig(), crypto, contexts, sendMessage4 = true)
        val (_, security) = EdhocClientHandshake(peers.initiatorConfig(), crypto) { edhoc.handle(it) }.runCombined()
        val combined = security.protectRequest(request("x"), peer)!!.message
        val error = assertFailsWith<MessageSecurityException> { CombinedRequestServer(edhoc, OscoreServer(contexts)).unprotectRequest(combined, peer) }
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, error.contentFormat)
    }

    @Test
    fun laterBlocksDoNotCarryMessage3() = runTest {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        val (_, security) = EdhocClientHandshake(peers.initiatorConfig(), crypto) { edhoc.handle(it) }.runCombined()
        val secondBlock = request("big").toBuilder().options { block1(dev.kcoap.coap.BlockOption(1, true, 2)) }.build()
        assertFalse(security.protectRequest(secondBlock, peer)!!.message.options.edhoc, "RFC 9668 §3.2.2 step 2.1")
        assertTrue(security.isMessage3Pending())
    }

    @Test
    fun serverAnswersBadRequestsWithEdhocErrors() = runTest {
        val peers = Peers.create(crypto)
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        fun post(payload: ByteArray) = CoapMessage.request(CoapCode.POST) {
            type = CoapMessageType.CON
            this.payload = payload
        }
        for (payload in listOf(ByteArray(0), byteArrayOf(0xF5.toByte()), "1800".hexToBytes(), "f6".hexToBytes(), "41".hexToBytes(), "0a".hexToBytes())) {
            val response = server.handle(post(payload))
            assertEquals(CoapCode.BAD_REQUEST, response.code, payload.toHex())
            assertEquals(ContentFormat.EDHOC_CBOR_SEQ, response.options.contentFormat)
        }
        // A broken message_1 is an EDHOC error too.
        assertEquals(CoapCode.BAD_REQUEST, server.handle(EdhocOverCoap.message1Request("0001".hexToBytes())).code)
    }

    @Test
    fun clientAbortAndUnknownSessions() = runTest {
        val peers = Peers.create(crypto)
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), maxPendingSessions = 1)
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        val message2 = server.handle(EdhocOverCoap.message1Request(initiator.createMessage1())).payload
        initiator.processMessage2(message2)
        initiator.createMessage3() // not sent; it gives the client its session and so C_R
        val responderId = initiator.peerConnectionIdentifier()
        assertEquals(1, server.pendingSessions())

        // The client sends an error instead of message_3: the session ends without a reply error.
        val abort = server.handle(EdhocOverCoap.continuationRequest(responderId, EdhocError.unspecified("bye").encode()))
        assertEquals(CoapCode.CHANGED, abort.code)
        assertEquals(0, server.pendingSessions())
        val unknown = server.handle(EdhocOverCoap.continuationRequest(responderId, byteArrayOf(0x40)))
        assertEquals(CoapCode.BAD_REQUEST, unknown.code)

        // Beyond maxPendingSessions the oldest session is dropped.
        val first = EdhocInitiator(peers.initiatorConfig(), crypto)
        first.processMessage2(server.handle(EdhocOverCoap.message1Request(first.createMessage1())).payload)
        val second = EdhocInitiator(peers.initiatorConfig(), crypto)
        second.processMessage2(server.handle(EdhocOverCoap.message1Request(second.createMessage1())).payload)
        assertEquals(1, server.pendingSessions())
        val firstMessage3 = first.createMessage3().bytes
        val dropped = server.handle(EdhocOverCoap.continuationRequest(first.peerConnectionIdentifier(), firstMessage3))
        assertEquals(CoapCode.BAD_REQUEST, dropped.code)
        val secondMessage3 = second.createMessage3().bytes
        val kept = server.handle(EdhocOverCoap.continuationRequest(second.peerConnectionIdentifier(), secondMessage3))
        assertEquals(CoapCode.CHANGED, kept.code)
        assertFailsWith<IllegalArgumentException> { EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository(), maxPendingSessions = 0) }
    }

    @Test
    fun clientSurfacesServerFailures() = runTest {
        val peers = Peers.create(crypto)
        val rejecting = EdhocClientHandshake(peers.initiatorConfig(), crypto) { request ->
            CoapMessage.response(request, CoapCode.NOT_FOUND)
        }
        val error = assertFailsWith<EdhocTransferException> { rejecting.run() }
        assertEquals(CoapCode.NOT_FOUND, error.response?.code)

        // The server rejects message_3 with an EDHOC error: the client raises it.
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        var calls = 0
        val failing = EdhocClientHandshake(peers.initiatorConfig(), crypto) { request ->
            if (++calls == 2) EdhocOverCoap.errorResponse(request, EdhocError.unspecified("no").encode()) else server.handle(request)
        }
        assertIs<EdhocPeerErrorException>(assertFailsWith<EdhocPeerErrorException> { failing.run() })

        // Endless negotiation stops.
        val stubborn = EdhocClientHandshake(peers.initiatorConfig(listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_4)), crypto) { request ->
            EdhocOverCoap.errorResponse(request, EdhocError.wrongSelectedCipherSuite(listOf(4)).encode())
        }
        assertFailsWith<Exception> { stubborn.run() }
    }

    @Test
    fun requestFraming() {
        val first = EdhocOverCoap.message1Request("0102".hexToBytes())
        assertEquals(listOf(".well-known", "edhoc"), first.options.uriPath)
        assertEquals(ContentFormat.CID_EDHOC_CBOR_SEQ, first.options.contentFormat)
        assertEquals("f50102", first.payload.toHex())
        val parsed = EdhocOverCoap.parseRequest(first.payload)
        assertContentEquals("0102".hexToBytes(), (parsed as EdhocOverCoap.ServerRequest.NewSession).message1)

        // RFC 9528 §3.3.2: one-byte integers and byte strings both name a C_R.
        val integer = EdhocOverCoap.parseRequest(EdhocOverCoap.continuationRequest(ConnectionIdentifier.ofInt(-8), "40".hexToBytes()).payload)
        assertEquals(ConnectionIdentifier.ofInt(-8), (integer as EdhocOverCoap.ServerRequest.Continuation).responderId)
        val bytes = EdhocOverCoap.parseRequest(EdhocOverCoap.continuationRequest(ConnectionIdentifier("1234".hexToBytes()), "40".hexToBytes()).payload)
        assertEquals(ConnectionIdentifier("1234".hexToBytes()), (bytes as EdhocOverCoap.ServerRequest.Continuation).responderId)
        assertContentEquals("40".hexToBytes(), bytes.message)

        val response = EdhocOverCoap.messageResponse(first, "aa".hexToBytes())
        assertEquals(CoapCode.CHANGED, response.code)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, response.options.contentFormat)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, EdhocOverCoap.errorResponse(first, ByteArray(1), isServerError = true).code)
        assertNull(EdhocOverCoap.completedResponse(first).options.contentFormat)
    }

    @Test
    fun suitesWithoutHmacCannotFeedOscore() = runTest {
        // Suite 25's application hash is SHAKE256; OSCORE needs an HMAC-based HKDF.
        assertTrue(CipherSuite.SUITE_25.appHash.isXof)
    }

    private fun kidOf(request: ProtectedRequest): ByteArray =
        dev.kcoap.oscore.OscoreOptionValue.decode(request.message.options.oscore!!).kid!!

    private fun EdhocInitiator.peerConnectionIdentifier(): ConnectionIdentifier =
        session?.peerConnectionIdentifier ?: error("no session yet")
}
