package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.StackFixture
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * When the client side of an endpoint acknowledges a separate response and when it resets it: only after
 * the response was verified and its critical options checked (RFC 7252 §5.4.1, RFC 8613 §8.4). The server
 * is a [RawPeer] that answers with whatever the test builds, on virtual time.
 */
class ResponseAcknowledgementTest {
    private fun get(path: String = "x"): CoapMessage = CoapMessage.request(CoapCode.GET) { options { uriPath(path) } }

    /** Option 9999: odd, so critical (RFC 7252 §5.4.1), and unknown to the client. */
    private val unknownCritical = CoapOption(9999, byteArrayOf(1))

    private fun StackFixture.sentBy(endpoint: CoapEndpoint, type: CoapMessageType): List<CoapMessage> =
        sentBy(endpoint).filter { it.type == type }

    /** Message security that sends requests as they are and verifies responses with [verify]. */
    private fun verifying(verify: suspend (CoapMessage) -> CoapMessage) = object : ClientMessageSecurity {
        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = object : ProtectedRequest {
            override val message: CoapMessage = request

            override suspend fun unprotectResponse(response: CoapMessage): CoapMessage = verify(response)
        }
    }

    @Test
    fun rfc7252Section5_4_1AConfirmableSeparateResponseWithAnUnknownCriticalOptionGetsAReset() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val client = fixture.client()
        val pending = async { runCatching { client.get(peer.uri("/x")) } }
        val request = peer.receive()
        peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))
        val response = peer.response(request, CoapMessageType.CON, "x") { options { add(unknownCritical) } }
        peer.send(response)

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
        assertEquals("Response with unrecognized critical options 9999", error.message)
        val reset = assertNotNull(fixture.sentBy(client.endpoint, CoapMessageType.RST).singleOrNull())
        assertEquals(response.messageId, reset.messageId)
        assertEquals(emptyList(), fixture.sentBy(client.endpoint, CoapMessageType.ACK), "never acknowledged")
        assertTrue(fixture.events.any { it is EndpointEvent.MessageRejected && it.messageId == response.messageId })

        // §4.5: the peer's retransmission is a duplicate and gets the same Reset.
        peer.send(response)
        delay(1.seconds)
        assertEquals(listOf(response.messageId, response.messageId), fixture.sentBy(client.endpoint, CoapMessageType.RST).map { it.messageId })
        assertEquals(0, client.endpoint.exchangeCount())
    }

    @Test
    fun rfc7252Section5_4_1ANonConfirmableResponseWithAnUnknownCriticalOptionIsRejected() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val client = fixture.client()
        val pending = async {
            runCatching { client.get(peer.uri("/x")) { nonConfirmable() } }
        }
        val request = peer.receive()
        val response = peer.response(request, CoapMessageType.NON, "x") { options { add(unknownCritical) } }
        peer.send(response)

        assertEquals(CoapException.Reason.BAD_RESPONSE, assertIs<CoapException>(pending.await().exceptionOrNull()).reason)
        assertEquals(listOf(response.messageId), fixture.sentBy(client.endpoint, CoapMessageType.RST).map { it.messageId })
    }

    @Test
    fun rfc7252Section5_4_1ASeparateResponseIsAnsweredOnlyAfterVerification() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        var acknowledgedBeforeVerification: Boolean? = null
        val security = verifying { response ->
            acknowledgedBeforeVerification = fixture.sentBy(endpoint, CoapMessageType.ACK).any { it.messageId == response.messageId }
            response
        }

        val exchange = endpoint.openExchange(peer.address, get(), true, security)
        val request = peer.receive()
        peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))
        val response = peer.response(request, CoapMessageType.CON, "verified")
        peer.send(response)

        assertEquals("verified", endpoint.awaitResponse(exchange, 10.seconds).payloadAsText())
        assertEquals(false, acknowledgedBeforeVerification)
        assertEquals(listOf(response.messageId), fixture.sentBy(endpoint, CoapMessageType.ACK).map { it.messageId })
    }

    @Test
    fun rfc8613Section8_4ASeparateResponseThatFailsVerificationIsAcknowledgedAndDropped() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val endpoint = fixture.endpoint()
        val security = verifying { response ->
            if (response.payloadAsText() == "forged") throw MessageSecurityException("forged")
            response
        }

        val exchange = endpoint.openExchange(peer.address, get(), false, security)
        val request = peer.receive()
        val forged = peer.response(request, CoapMessageType.CON, "forged")
        val genuine = peer.response(request, CoapMessageType.CON, "genuine")
        peer.send(forged)
        delay(1.seconds)
        assertTrue(exchange.isOpen, "the forged response neither answers nor ends the request")
        assertEquals(true, exchange.transmission?.occupiesSlot, "nor frees its NSTART slot")
        peer.send(genuine)

        assertEquals("genuine", endpoint.awaitResponse(exchange, 10.seconds).payloadAsText())
        // An ACK says only that the message arrived; a Reset would cancel an observation (RFC 8613 §8.4.2).
        assertEquals(listOf(forged.messageId, genuine.messageId), fixture.sentBy(endpoint, CoapMessageType.ACK).map { it.messageId })
        assertEquals(emptyList(), fixture.sentBy(endpoint, CoapMessageType.RST))
    }

    @Test
    fun rfc7641Section3_6ANotificationWithAnUnknownCriticalOptionIsResetAndEndsTheObservation() = runTest {
        val fixture = StackFixture(this)
        val peer = RawPeer(fixture.transport())
        val client = fixture.client()
        val received = mutableListOf<String>()
        val observing = async {
            runCatching { client.observe(peer.uri("/obs")).collect { received += it.payloadAsText() } }
        }
        val registration = peer.receive()
        assertEquals(0L, registration.options.observe)
        peer.send(
            CoapMessage.response(registration, CoapCode.CONTENT) {
                type = CoapMessageType.ACK
                messageId = registration.messageId
                options { observe(1) }
                payload("1")
            },
        )
        val notification = peer.response(registration, CoapMessageType.CON, "2") {
            options {
                observe(2)
                add(unknownCritical)
            }
        }
        peer.send(notification)

        val error = assertIs<CoapException>(observing.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
        assertEquals(listOf("1"), received)
        assertEquals(listOf(notification.messageId), fixture.sentBy(client.endpoint, CoapMessageType.RST).map { it.messageId })
        assertEquals(0, client.endpoint.exchangeCount(), "the endpoint ended the observation's exchange")
    }
}
