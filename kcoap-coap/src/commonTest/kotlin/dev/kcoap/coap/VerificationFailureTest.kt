package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.ResponseRejected
import dev.kcoap.coap.stack.RawPeer
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Responses that fail verification (RFC 8613 §8.4) are dropped and the exchange waits on, as the RFC asks, but
 * they do not vanish: each is an [EndpointEvent.ResponseRejected], and a request that gets nothing else fails with
 * [CoapException.Reason.RESPONSE_TIMEOUT] whose cause is the last of them, so an application with a stale security
 * context learns that, not only that the peer was silent.
 *
 * The toy security marks a protected message with an empty OSCORE option, as in UnverifiedResponseTest: a response
 * without it that is not an error fails verification, and an error without it is handed on unverified.
 */
class VerificationFailureTest {
    private object Toy : ClientMessageSecurity {
        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = object : ProtectedRequest {
            override val message = request.toBuilder().options { oscore(ByteArray(0)) }.build()

            override suspend fun unprotectResponse(response: CoapMessage): ResponseVerification {
                if (response.options.oscore != null) {
                    return ResponseVerification.Verified(response.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build())
                }
                if (response.code.isClientError || response.code.isServerError) return ResponseVerification.Unverified(response, "unprotected")
                throw MessageSecurityException("Unprotected ${response.code}")
            }
        }
    }

    /** [records] are what the client's endpoint logged, at DEBUG and above. */
    private class Setup(val fixture: StackFixture, val peer: RawPeer, val client: CoapClient, val records: List<LogRecord>)

    private suspend fun TestScope.setup(
        config: CoapClientConfig = CoapClientConfig { security = Toy; requestTimeout = 30.seconds },
    ): Setup {
        val fixture = StackFixture(this)
        val records = mutableListOf<LogRecord>()
        val logs = LoggerFactory(LogConfig.build { level = LogLevel.DEBUG; sink = LogSink { records += it } })
        val endpointConfig = fixture.endpointConfig().toBuilder().apply { loggerFactory = logs }.build()
        return Setup(fixture, RawPeer(fixture.transport()), fixture.client(config, endpointConfig), records)
    }

    /**
     * A peer's input, expected in RFC 8613 §8.4, so logged once per response at DEBUG without a stack trace: a stale
     * context must not flood a log that prints WARN by default (docs/logging.md).
     */
    private fun Setup.assertLoggedOnceAtDebug(about: String, count: Int) {
        assertTrue(records.none { it.level >= LogLevel.WARN }, "${records.filter { it.level >= LogLevel.WARN }}")
        val lines = records.filter { about in it.message }
        assertEquals(count, lines.size, "$records")
        assertTrue(lines.all { it.level == LogLevel.DEBUG && it.error == null }, "$lines")
    }

    private val Setup.rejections: List<ResponseRejected> get() = fixture.events.filterIsInstance<ResponseRejected>()

    @Test
    fun rfc8613Section8_4ResponsesThatFailVerificationAreTheCauseOfTheTimeout() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.get(s.peer.uri("/reading")) } }
        val request = s.peer.receive()
        s.peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))

        // Two separate responses that do not verify, as from a server whose context went out of sync.
        delay(1.seconds)
        s.peer.send(s.peer.response(request, CoapMessageType.NON, "first"))
        delay(1.seconds)
        s.peer.send(s.peer.response(request, CoapMessageType.NON, "second"))
        delay(1.seconds)
        assertEquals(2, s.rejections.size, "each is reported when it is dropped")
        assertTrue(!pending.isCompleted, "and the request waits on for one that verifies (RFC 8613 §8.4)")
        s.assertLoggedOnceAtDebug("failed verification", 2)

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
        assertEquals(30.seconds, s.fixture.now, "at the request timeout, on virtual time")
        val cause = assertIs<MessageSecurityException>(error.cause)
        assertEquals("Unprotected 2.05 Content", cause.message)
        assertEquals(
            "No response from ${s.peer.address} within 30s; 2 responses failed verification and were dropped " +
                "(RFC 8613 §8.4), the last: Unprotected 2.05 Content",
            error.message,
        )
        val rejected = s.rejections.first()
        assertEquals(ResponseRejected.Reason.VERIFICATION_FAILED, rejected.reason)
        assertEquals(s.peer.address, rejected.peer)
        assertEquals("Unprotected 2.05 Content", rejected.detail)
    }

    @Test
    fun aTimeoutWithoutResponsesHasNoCause() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.get(s.peer.uri("/reading")) } }
        val request = s.peer.receive()
        s.peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
        assertNull(error.cause)
        assertEquals("No response from ${s.peer.address} within 30s", error.message)
        assertEquals(emptyList(), s.rejections)
    }

    @Test
    fun aResponseThatVerifiesAfterOneThatDidNotEndsTheRequest() = runTest {
        val s = setup()
        val pending = async { s.client.get(s.peer.uri("/reading")) }
        val request = s.peer.receive()
        s.peer.send(CoapMessage.empty(CoapMessageType.ACK, request.messageId))
        s.peer.send(s.peer.response(request, CoapMessageType.NON, "forged"))
        s.peer.send(s.peer.response(request, CoapMessageType.NON, "real") { options { oscore(ByteArray(0)) } })

        assertEquals("real", pending.await().payloadAsText())
        assertEquals(listOf(ResponseRejected.Reason.VERIFICATION_FAILED), s.rejections.map { it.reason })
    }

    @Test
    fun rfc8613Section8_4_2ANotificationThatFailsVerificationIsReportedAndTheObservationGoesOn() = runTest {
        val s = setup()
        val pending = async { s.client.observe(s.peer.uri("/counter")).take(2).toList() }
        val registration = s.peer.receive()
        s.peer.send(
            CoapMessage.response(registration, CoapCode.CONTENT) {
                type = CoapMessageType.ACK
                messageId = registration.messageId
                options { observe(1); oscore(ByteArray(0)) }
                payload("1")
            },
        )

        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "replayed") { options { observe(2) } })
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "3") { options { observe(3); oscore(ByteArray(0)) } })

        assertEquals(listOf("1", "3"), pending.await().map { it.payloadAsText() })
        assertEquals(listOf(ResponseRejected.Reason.VERIFICATION_FAILED), s.rejections.map { it.reason })
        s.assertLoggedOnceAtDebug("failed verification", 1)
    }

    @Test
    fun adr0008AnUnverifiedErrorIsReportedAsRejected() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.post(s.peer.uri("/pay"), "10 EUR".encodeToByteArray()) } }
        val request = s.peer.receive()
        s.peer.send(
            CoapMessage.response(request, CoapCode.UNAUTHORIZED) {
                type = CoapMessageType.ACK
                messageId = request.messageId
            },
        )

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, error.reason)
        val rejected = s.rejections.single()
        assertEquals(ResponseRejected.Reason.UNVERIFIED, rejected.reason)
        assertEquals("4.01 Unauthorized: unprotected", rejected.detail)
        assertTrue("UNVERIFIED" in rejected.toString() && "4.01" in rejected.toString(), rejected.toString())
    }

    @Test
    fun rfc8613Section8_4AQBlockBodyThatGetsOnlyFailingResponsesTimesOutWithTheLastAsCause() = runTest {
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
        val s = setup(CoapClientConfig { security = Toy; this.qBlock = qBlock; requestTimeout = 30.seconds })
        val pending = async { runCatching { s.client.get(s.peer.uri("/big")) { nonConfirmable() } } }
        val request = s.peer.receive()
        assertEquals(CoapMessageType.NON, request.type)

        s.peer.send(s.peer.response(request, CoapMessageType.NON, "forged"))

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, error.reason)
        assertEquals("Unprotected 2.05 Content", assertIs<MessageSecurityException>(error.cause).message)
        assertTrue("1 response failed verification and was dropped" in error.message.orEmpty(), error.message)
        assertEquals(listOf(ResponseRejected.Reason.VERIFICATION_FAILED), s.rejections.map { it.reason })
    }
}
