package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.RawPeer
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The client acts only on verified responses (ADR 0008): a party on the path, which sees the token, answers a
 * protected request with an unprotected error, and the client neither repeats the request for its Echo option,
 * nor continues or restarts a body, nor changes what it knows about the server, nor ends an observation. The
 * request ends with [CoapException.Reason.UNVERIFIED_RESPONSE] instead of a timeout.
 *
 * The toy security marks a protected message with an empty OSCORE option and verifies a response by that option,
 * handing on an unprotected error unverified, as kcoap-oscore does (RFC 8613 §8.2). [RawPeer] plays the server
 * and the party on the path.
 */
class UnverifiedResponseTest {
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

    private val echo = "0102030405060708".hexToBytes()

    private class Setup(val fixture: StackFixture, val peer: RawPeer, val client: CoapClient)

    private suspend fun TestScope.setup(config: CoapClientConfig = CoapClientConfig(security = Toy)): Setup {
        val fixture = StackFixture(this)
        return Setup(fixture, RawPeer(fixture.transport()), fixture.client(config))
    }

    /**
     * A [code] response to [request], piggybacked on its ACK or, for a NON request, Non-confirmable; protected
     * (an empty OSCORE option) when [isProtected].
     */
    private fun answer(request: CoapMessage, code: CoapCode, isProtected: Boolean = false, block: CoapMessage.Builder.() -> Unit = {}) =
        CoapMessage.response(request, code) {
            if (request.type == CoapMessageType.NON) {
                type = CoapMessageType.NON
                messageId = 0x6001
            } else {
                type = CoapMessageType.ACK
                messageId = request.messageId
            }
            if (isProtected) options { oscore(ByteArray(0)) }
            block()
        }

    /** An unprotected 4.01 with Echo to [request], as anyone on the path who saw its token can send. */
    private fun forged401(request: CoapMessage): CoapMessage = answer(request, CoapCode.UNAUTHORIZED) { options { echo(echo) } }

    /** No further request reaches [peer] within ten seconds. */
    private suspend fun RawPeer.assertNoRequest() {
        assertNull(withTimeoutOrNull(10.seconds) { receive() }, "the request was not sent again")
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertUnverified(failure: Throwable?, code: CoapCode = CoapCode.UNAUTHORIZED): CoapResponse {
        val error = assertIs<CoapException>(failure)
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, error.reason)
        val response = assertNotNull(error.response, "the unverified response, for the application")
        assertEquals(code, response.code)
        return response
    }

    @Test
    fun adr0008AForgedUnprotected401WithEchoToAConfirmablePostIsNotRepeated() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.post(s.peer.uri("/pay"), "10 EUR".encodeToByteArray()) } }
        val request = s.peer.receive()
        assertNotNull(request.options.oscore, "protected")

        s.peer.send(forged401(request))

        val response = assertUnverified(pending.await().exceptionOrNull())
        assertTrue(s.fixture.now < 1.seconds, "at once, not after the request timeout: ${s.fixture.now}")
        assertContentEquals(echo, response.options.echo, "as it came, Echo included")
        s.peer.assertNoRequest()
    }

    @Test
    fun adr0008AForgedUnprotected401WithEchoToANonConfirmablePostIsNotRepeated() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.post(s.peer.uri("/pay"), "10 EUR".encodeToByteArray()) { nonConfirmable() } } }
        val request = s.peer.receive()
        assertEquals(CoapMessageType.NON, request.type)

        s.peer.send(forged401(request))

        assertUnverified(pending.await().exceptionOrNull())
        s.peer.assertNoRequest()
    }

    @Test
    fun rfc8613AppendixB1_2AVerified401WithEchoIsRepeatedOnce() = runTest {
        val s = setup()
        val pending = async { s.client.post(s.peer.uri("/pay"), "10 EUR".encodeToByteArray()) }
        val request = s.peer.receive()

        // The freshness challenge of a restarted server is protected (Appendix B.1.2), so it verifies.
        s.peer.send(answer(request, CoapCode.UNAUTHORIZED, isProtected = true) { options { echo(echo) } })
        val repeated = s.peer.receive()
        assertContentEquals(echo, repeated.options.echo)
        s.peer.send(answer(repeated, CoapCode.CHANGED, isProtected = true))

        assertEquals(CoapCode.CHANGED, pending.await().code)
    }

    @Test
    fun adr0008AnUnverified413DoesNotRestartABlock1Upload() = runTest {
        val s = setup(CoapClientConfig(security = Toy, blockSize = 64))
        val pending = async { runCatching { s.client.put(s.peer.uri("/store"), ByteArray(300)) } }
        val first = s.peer.receive()
        assertEquals(0L, first.options.block1?.number)

        // RFC 7959 §2.9.3: a verified 4.13 with a smaller Block1 size would start the upload over.
        s.peer.send(answer(first, CoapCode.REQUEST_ENTITY_TOO_LARGE) { options { block1(BlockOption(0, true, 0)) } })

        assertUnverified(pending.await().exceptionOrNull(), CoapCode.REQUEST_ENTITY_TOO_LARGE)
        s.peer.assertNoRequest()
    }

    @Test
    fun adr0008AForgedUnprotected401WithEchoToAnObserveRegistrationIsNotRepeated() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.observe(s.peer.uri("/counter")).toList() } }
        val registration = s.peer.receive()
        assertEquals(0L, registration.options.observe)

        s.peer.send(forged401(registration))

        assertUnverified(pending.await().exceptionOrNull())
        s.peer.assertNoRequest()
    }

    @Test
    fun adr0008AnUnverifiedErrorNeitherEndsNorReachesAnEstablishedObservation() = runTest {
        val s = setup()
        val pending = async { s.client.observe(s.peer.uri("/counter")).take(2).toList() }
        val registration = s.peer.receive()
        s.peer.send(answer(registration, CoapCode.CONTENT, isProtected = true) {
            options { observe(1) }
            payload("first")
        })

        // RFC 7641 §3.2: a verified error would end the observation; this one is unverified.
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") { code = CoapCode.SERVICE_UNAVAILABLE })
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") { code = CoapCode.NOT_FOUND })
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "second") { options { observe(2); oscore(ByteArray(0)) } })

        assertEquals(listOf("first", "second"), pending.await().map { it.payloadAsText() })
    }

    @Test
    fun adr0008AForgedUnprotected401WithEchoToAQBlockRequestIsNotRepeated() = runTest {
        for (type in listOf(CoapMessageType.NON, CoapMessageType.CON)) {
            val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
            val s = setup(CoapClientConfig(security = Toy, qBlock = qBlock))
            val pending = async {
                runCatching { s.client.get(s.peer.uri("/big")) { if (type == CoapMessageType.NON) nonConfirmable() } }
            }
            val request = s.peer.receive()
            assertNotNull(request.options.qBlock2, "$type: the request offers Q-Block2")

            s.peer.send(forged401(request))

            assertUnverified(pending.await().exceptionOrNull())
            s.peer.assertNoRequest()
        }
    }

    @Test
    fun adr0008AnUnverified402ToAQBlockRequestFallsBackToNothing() = runTest {
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
        val s = setup(CoapClientConfig(security = Toy, qBlock = qBlock))
        val pending = async { runCatching { s.client.get(s.peer.uri("/big")) { nonConfirmable() } } }
        val request = s.peer.receive()

        // RFC 9177 §4.1: a verified 4.02 before any answer would have the request sent again with RFC 7959.
        s.peer.send(answer(request, CoapCode.BAD_OPTION))

        assertUnverified(pending.await().exceptionOrNull(), CoapCode.BAD_OPTION)
        s.peer.assertNoRequest()
        assertTrue(s.fixture.events.none { it is EndpointEvent.QBlockFallback }, "Q-Block was not turned off")
        assertNull(s.client.qBlock!!.support.known(s.peer.address, isProtected = true), "nothing learnt about the server")
    }

    @Test
    fun adr0008AQBlockProbeAnsweredOnlyUnverifiedLeavesTheCapabilityUnknown() = runTest {
        val qBlock = QBlockClientConfig(QBlockMode.PROBE, withMessageSecurity = true)
        val s = setup(CoapClientConfig(security = Toy, qBlock = qBlock))
        val pending = async { runCatching { s.client.get(s.peer.uri("/big")) } }
        val probe = s.peer.receive()
        assertEquals(listOf(".well-known", "core"), probe.options.uriPath)

        s.peer.send(answer(probe, CoapCode.BAD_OPTION))

        // The request itself goes with RFC 7959, and its own unverified answer ends it.
        val request = s.peer.receive()
        assertNull(request.options.qBlock2)
        s.peer.send(forged401(request))
        assertUnverified(pending.await().exceptionOrNull())
        assertEquals(
            dev.kcoap.coap.stack.QBlockCapabilities.Capability.UNKNOWN,
            s.client.qBlock!!.support.known(s.peer.address, isProtected = true),
        )
    }
}
