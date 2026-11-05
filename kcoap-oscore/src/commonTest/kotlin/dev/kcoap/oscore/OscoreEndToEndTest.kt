package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.readUnsigned
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LoggerFactory
import dev.kcoap.log.RingBufferSink
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A client and a server exchanging messages through the stack-facing SPI. */
class OscoreEndToEndTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val logs = RingBufferSink(200)
    private val loggers = LoggerFactory(LogConfig.build { level = LogLevel.TRACE; sink = logs })

    private suspend fun pair(idContext: ByteArray? = null, includeIdContext: Boolean = false): Pair<SecurityContext, SecurityContext> {
        val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val client = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b), idContext = idContext, includeIdContext = includeIdContext), crypto)
        val server = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a), idContext = idContext), crypto)
        return client to server
    }

    private suspend fun serverFor(context: SecurityContext): OscoreServer {
        val repository = InMemoryContextRepository().apply { add(context) }
        return OscoreServer(repository, loggers)
    }

    private fun request(observe: Long? = null) = CoapMessage.request(CoapCode.PUT) {
        messageId = 7
        token = "a1b2".hexToBytes()
        options {
            uriHost("device.example")
            uriPath("lights/1")
            uriQuery("brightness=80")
            contentFormat(0)
            if (observe != null) observe(observe)
            add(CoapOption(65001, byteArrayOf(1)))
        }
        payload("on")
    }

    @Test
    fun requestAndResponseRoundTrip() = runTest {
        val (clientContext, serverContext) = pair()
        val client = OscoreClient(clientContext, loggers)
        val server = serverFor(serverContext)

        val protected = client.protectRequest(request(), peer)!!
        val outer = protected.message
        assertEquals(CoapCode.POST, outer.code, "Outer Code hides the method")
        assertEquals("device.example", outer.options.uriHost, "Uri-Host is Class U")
        assertTrue(outer.options.uriPath.isEmpty(), "Uri-Path is encrypted")
        assertNull(outer.options.contentFormat)
        assertTrue(65001 !in outer.options, "unknown options are Class E")
        assertContentEquals("a1b2".hexToBytes(), outer.token)
        assertEquals(7, outer.messageId)

        val verified = server.unprotectRequest(outer, peer)!!
        assertEquals(request(), verified.message)
        assertEquals("OSCORE rid=0a", verified.identity.description)
        assertEquals(OscoreIdentity(byteArrayOf(0x0a), null), verified.identity)

        val response = CoapMessage.response(verified.message, CoapCode.CHANGED) {
            type = CoapMessageType.ACK
            messageId = 7
            options { maxAge(30) }
            payload("done")
        }
        val protectedResponse = verified.protectResponse(response)
        assertEquals(CoapCode.CHANGED, protectedResponse.code)
        assertTrue(protectedResponse.options.oscore!!.isEmpty(), "first response reuses the request nonce")
        assertEquals(60, protectedResponse.options.maxAge, "Max-Age is Inner, so outside it is absent")

        assertEquals(response, protected.unprotectResponse(protectedResponse))
        assertTrue(logs.snapshot().any { it.tag == "oscore.client" })
    }

    @Test
    fun observeNotificationsAreOrderedAndReplayProtected() = runTest {
        val (clientContext, serverContext) = pair()
        val client = OscoreClient(clientContext, loggers)
        val server = serverFor(serverContext)

        val registration = client.protectRequest(request(observe = 0).toBuilder().apply { code = CoapCode.GET; payload = ByteArray(0) }.build(), peer)!!
        assertEquals(CoapCode.FETCH, registration.message.code, "Observe requests go out as FETCH")
        assertEquals(0, registration.message.options.observe, "Outer Observe keeps the value")

        val verified = server.unprotectRequest(registration.message, peer)!!
        assertEquals(0, verified.message.options.observe)

        fun notification(sequence: Long, text: String) = CoapMessage.response(verified.message, CoapCode.CONTENT) {
            options { observe(sequence) }
            payload(text)
        }
        val first = verified.protectResponse(notification(5, "a"))
        val second = verified.protectResponse(notification(6, "b"))
        val third = verified.protectResponse(notification(7, "c"))
        assertEquals(CoapCode.CONTENT, first.code)
        assertEquals(5, first.options.observe, "Outer Observe of a notification")

        val a = registration.unprotectResponse(first)
        assertEquals("a", a.payloadAsText())
        assertEquals(0, a.options.observe, "the notification without Partial IV is the oldest")
        val c = registration.unprotectResponse(third)
        assertEquals("c", c.payloadAsText())
        assertFailsWith<MessageSecurityException>("older than the freshest") { registration.unprotectResponse(second) }
        assertFailsWith<MessageSecurityException>("replayed") { registration.unprotectResponse(third) }
        assertNotEquals(a.options.observe, c.options.observe)
    }

    @Test
    fun clientRejectsWhatTheRfcForbids() = runTest {
        val (clientContext, serverContext) = pair()
        val client = OscoreClient(clientContext, loggers)
        val server = serverFor(serverContext)

        val plain = client.protectRequest(request(), peer)!!
        val verified = server.unprotectRequest(plain.message, peer)!!
        val ok = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CHANGED))
        plain.unprotectResponse(ok)
        assertFailsWith<MessageSecurityException>("only one response without Observe") { plain.unprotectResponse(ok) }

        val notification = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CONTENT) { options { observe(1) } })
        val other = client.protectRequest(request(), peer)!!
        assertFailsWith<MessageSecurityException>("notification for a non-Observe request") {
            OscoreClient(clientContext).protectRequest(request(), peer)!!.unprotectResponse(notification)
        }

        val tampered = ok.toBuilder().apply { payload = payload.also { it[0] = (it[0].toInt() xor 1).toByte() } }.build()
        assertFailsWith<MessageSecurityException> { other.unprotectResponse(tampered) }
        assertFailsWith<MessageSecurityException>("unprotected success") {
            other.unprotectResponse(CoapMessage.response(plain.message, CoapCode.CONTENT))
        }
        val error = CoapMessage.response(plain.message, CoapCode.UNAUTHORIZED) { payload("Security context not found") }
        assertSame(error, other.unprotectResponse(error), "OSCORE errors are unprotected and passed on")
    }

    @Test
    fun serverErrorsMapToRfcResponses() = runTest {
        val (clientContext, serverContext) = pair()
        val client = OscoreClient(clientContext, loggers)
        val server = serverFor(serverContext)
        val protected = client.protectRequest(request(), peer)!!.message

        assertNull(server.unprotectRequest(request(), peer), "unprotected requests are passed to the stack")
        server.unprotectRequest(protected, peer)
        val replay = assertFailsWith<MessageSecurityException> { server.unprotectRequest(protected, peer) }
        assertEquals(CoapCode.UNAUTHORIZED, replay.responseCode)
        assertEquals("Replay detected", replay.diagnostic)

        val unknown = assertFailsWith<MessageSecurityException> { OscoreServer(InMemoryContextRepository(), loggers).unprotectRequest(protected, peer) }
        assertEquals(CoapCode.UNAUTHORIZED, unknown.responseCode)
        assertEquals("Security context not found", unknown.diagnostic)

        val fresh = client.protectRequest(request(), peer)!!.message
        val tampered = fresh.toBuilder().apply { payload = payload.also { it[2] = (it[2].toInt() xor 4).toByte() } }.build()
        val decryption = assertFailsWith<MessageSecurityException> { server.unprotectRequest(tampered, peer) }
        assertEquals(CoapCode.BAD_REQUEST, decryption.responseCode)
        assertEquals("Decryption failed", decryption.diagnostic)
        // A failed decryption does not consume the sequence number: the genuine message still verifies.
        server.unprotectRequest(fresh, peer)

        val malformed = fresh.toBuilder().options { set(CoapOption(CoapOptions.OSCORE.number, byteArrayOf(0x0f))) }.build()
        val decode = assertFailsWith<MessageSecurityException> { server.unprotectRequest(malformed, peer) }
        assertEquals(CoapCode.BAD_OPTION, decode.responseCode)
        assertEquals("Failed to decode COSE", decode.diagnostic)

        val noKid = fresh.toBuilder().options { set(CoapOption(CoapOptions.OSCORE.number, OscoreOptionValue(partialIv = byteArrayOf(9)).encode())) }.build()
        assertEquals(CoapCode.BAD_OPTION, assertFailsWith<MessageSecurityException> { server.unprotectRequest(noKid, peer) }.responseCode)
        val noPiv = fresh.toBuilder().options { set(CoapOption(CoapOptions.OSCORE.number, OscoreOptionValue(kid = byteArrayOf(0x0a)).encode())) }.build()
        assertEquals(CoapCode.BAD_OPTION, assertFailsWith<MessageSecurityException> { server.unprotectRequest(noPiv, peer) }.responseCode)
        val noPayload = fresh.toBuilder().apply { payload = ByteArray(0) }.build()
        assertEquals(CoapCode.BAD_OPTION, assertFailsWith<MessageSecurityException> { server.unprotectRequest(noPayload, peer) }.responseCode)
    }

    @Test
    fun kidContextSelectsTheContext() = runTest {
        val idContext = "c0ffee".hexToBytes()
        val (clientContext, serverContext) = pair(idContext, includeIdContext = true)
        val (_, otherServer) = pair("dead".hexToBytes())
        val repository = InMemoryContextRepository().apply {
            add(serverContext)
            add(otherServer)
        }
        val protected = OscoreClient(clientContext).protectRequest(request(), peer)!!.message
        val verified = OscoreServer(repository).unprotectRequest(protected, peer)!!
        assertEquals("OSCORE rid=0a idc=c0ffee", verified.identity.description)

        // Without 'kid context' two contexts share the Recipient ID, so the lookup is ambiguous.
        assertNull(repository.find(byteArrayOf(0x0a), null))
        repository.remove(otherServer)
        assertSame(serverContext, repository.find(byteArrayOf(0x0a), null))
    }

    /** `addIfAbsent` never replaces a context, unlike `add`; the key is the Recipient ID with the ID Context. */
    @Test
    fun addIfAbsentKeepsTheContextInPlace() = runTest {
        val (_, first) = pair()
        val (_, second) = pair()
        val (_, withIdContext) = pair("c0ffee".hexToBytes())
        val repository = InMemoryContextRepository()
        assertTrue(repository.addIfAbsent(first))
        assertFalse(repository.addIfAbsent(second), "same Recipient ID, no ID Context")
        assertSame(first, repository.find(byteArrayOf(0x0a), null))
        assertTrue(repository.addIfAbsent(withIdContext), "same Recipient ID, another ID Context")
        repository.add(second)
        assertSame(second, repository.find(byteArrayOf(0x0a), null), "add replaces")
    }

    @Test
    fun proxyUriIsDecomposed() = runTest {
        val (clientContext, serverContext) = pair()
        val original = CoapMessage.request(CoapCode.GET) {
            options { proxyUri("coap://example.com/resource?q=1") }
        }
        val protected = OscoreClient(clientContext).protectRequest(original, peer)!!.message
        // RFC 8613 §4.1.3.3: the outer Proxy-Uri keeps scheme and authority only.
        assertEquals("coap://example.com", protected.options.proxyUri)
        val inner = serverFor(serverContext).unprotectRequest(protected, peer)!!.message
        assertEquals(listOf("resource"), inner.options.uriPath)
        assertEquals(listOf("q=1"), inner.options.uriQuery)
        assertEquals("coap://example.com", inner.options.proxyUri)

        val bad = CoapMessage.request(CoapCode.GET) { options { proxyUri("not a uri") } }
        assertEquals(CoapCode.BAD_OPTION, assertFailsWith<MessageSecurityException> { OscoreClient(clientContext).protectRequest(bad, peer) }.responseCode)
    }

    @Test
    fun resolverMayDeclineToProtect() = runTest {
        val client = OscoreClient(ClientContextResolver { _, _ -> null })
        assertNull(client.protectRequest(request(), peer))
    }

    /**
     * RFC 8613 Appendix B.1.2: a server restored from its store has lost its replay window, so it proves
     * the client's freshness with Echo first, also for a request it accepted before the restart.
     */
    @Test
    fun restartedServerChallengesWithEcho() = runTest {
        val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val clientContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b)), crypto)
        val serverParameters = SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a))
        val serverStore = EphemeralSequenceNumberStore()
        val client = OscoreClient(clientContext, loggers)

        // Before the restart: a new context trusts its window, and its second notification takes a Partial IV.
        val running = serverFor(SecurityContext.derive(serverParameters, crypto, serverStore))
        val recorded = client.protectRequest(request(observe = 0).toBuilder().apply { code = CoapCode.GET; payload = ByteArray(0) }.build(), peer)!!
        val beforeRestart = running.unprotectRequest(recorded.message, peer)!!
        assertNull(beforeRestart.challenge, "a new context with the ephemeral store needs no proof")
        fun notification(value: Long) = CoapMessage.response(beforeRestart.message, CoapCode.CONTENT) { options { observe(value) } }
        beforeRestart.protectResponse(notification(1))
        val usedBefore = OscoreOptionValue.decode(beforeRestart.protectResponse(notification(2)).options.oscore!!).partialIv!!.readUnsigned()

        // After the restart the store knows the context: the recorded request is challenged, not processed.
        val server = serverFor(SecurityContext.derive(serverParameters, crypto, serverStore))
        assertNotNull(server.unprotectRequest(recorded.message, peer)!!.challenge, "a recorded request is not processed again")

        val first = client.protectRequest(request(), peer)!!
        val challenged = server.unprotectRequest(first.message, peer)!!
        val challenge = challenged.challenge!!
        assertEquals(CoapCode.UNAUTHORIZED, challenge.code)
        val protectedChallenge = challenged.protectResponse(challenge)
        val challengePiv = assertNotNull(OscoreOptionValue.decode(protectedChallenge.options.oscore!!).partialIv, "the challenge carries the server's Partial IV")
        assertTrue(challengePiv.readUnsigned() > usedBefore, "above every Partial IV the server used before the restart")

        val seenByClient = first.unprotectResponse(protectedChallenge)
        assertEquals(CoapCode.UNAUTHORIZED, seenByClient.code)
        val echo = seenByClient.options.echo!!
        assertEquals(0, seenByClient.payloadSize, "no diagnostic payload")

        val wrongEcho = client.protectRequest(request().toBuilder().options { echo(byteArrayOf(1, 2, 3)) }.build(), peer)!!
        val challengedAgain = server.unprotectRequest(wrongEcho.message, peer)!!
        val repeated = wrongEcho.unprotectResponse(challengedAgain.protectResponse(challengedAgain.challenge!!))
            .options.echo!!
        assertContentEquals(echo, repeated, "a wrong Echo is challenged again, with the outstanding value")

        val proof = client.protectRequest(request().toBuilder().options { echo(echo) }.build(), peer)!!
        val accepted = server.unprotectRequest(proof.message, peer)!!
        assertNull(accepted.challenge, "the Echo proves freshness")
        assertContentEquals(echo, accepted.message.options.echo)

        // The request that proved freshness set the lower limit: the earlier ones are replays now.
        val replay = assertFailsWith<MessageSecurityException> { server.unprotectRequest(first.message, peer) }
        assertEquals("Replay detected", replay.diagnostic)
        assertNull(server.unprotectRequest(client.protectRequest(request(), peer)!!.message, peer)!!.challenge)
    }

    /**
     * RFC 8613 Appendix B.1.2, RFC 9175 §2.3: an attacker who replays a recorded request between a
     * challenge and the client's answer gets a challenge with the same Echo value, so the answer still
     * proves freshness. A new value per challenge would let every replay revoke the one the client holds.
     */
    @Test
    fun aReplayBetweenChallengeAndAnswerDoesNotRevokeTheEcho() = runTest {
        val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val clientParameters = SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b))
        val client = OscoreClient(ephemeralContext(clientParameters, crypto), loggers)
        val serverParameters = SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a))
        val server = serverFor(restoredContext(serverParameters, crypto))
        suspend fun echoOfChallengeTo(request: ProtectedRequest): ByteArray {
            val challenged = server.unprotectRequest(request.message, peer)!!
            return request.unprotectResponse(challenged.protectResponse(challenged.challenge!!)).options.echo!!
        }

        val recorded = client.protectRequest(request(), peer)!!
        val first = client.protectRequest(request(), peer)!!
        val echo = echoOfChallengeTo(first)
        repeat(3) { assertContentEquals(echo, echoOfChallengeTo(recorded), "replay ${it + 1} gets the same value") }

        val answer = client.protectRequest(request().toBuilder().options { echo(echo) }.build(), peer)!!
        val accepted = server.unprotectRequest(answer.message, peer)!!
        assertNull(accepted.challenge, "the client's answer is accepted")
        assertFailsWith<MessageSecurityException>("the recorded request is a replay now") {
            server.unprotectRequest(recorded.message, peer)
        }
    }
}
