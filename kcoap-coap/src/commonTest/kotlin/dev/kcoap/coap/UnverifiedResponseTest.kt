package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MessageInterceptor
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
import kotlinx.coroutines.test.runCurrent
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
    fun responseVerificationsShowTheirCodeAndReason() {
        val response = CoapMessage.response(CoapMessage.request(CoapCode.GET) {}, CoapCode.UNAUTHORIZED)
        assertEquals("Verified(4.01 Unauthorized)", ResponseVerification.Verified(response).toString())
        val unverified = ResponseVerification.Unverified(response, "sent without OSCORE")
        assertEquals("Unverified(4.01 Unauthorized: sent without OSCORE)", unverified.toString())
        assertEquals("sent without OSCORE", unverified.reason)
        assertEquals(response, unverified.message)
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
    fun adr0008AForgedUnprotected401WithEchoToANonConfirmableObserveRegistrationIsNotRepeated() = runTest {
        val s = setup()
        val pending = async { runCatching { s.client.observe(s.peer.uri("/counter")) { nonConfirmable() }.toList() } }
        val registration = s.peer.receive()
        assertEquals(CoapMessageType.NON, registration.type)
        assertEquals(0L, registration.options.observe)

        s.peer.send(forged401(registration))

        assertUnverified(pending.await().exceptionOrNull())
        s.peer.assertNoRequest()
    }

    @Test
    fun adr0008AnUnverifiedAnswerToTheBlock2RequestOfANotificationDropsOnlyThatNotification() = runTest {
        val s = setup()
        val pending = async { s.client.observe(s.peer.uri("/counter")).take(2).toList() }
        val registration = s.peer.receive()
        s.peer.send(answer(registration, CoapCode.CONTENT, isProtected = true) {
            options { observe(1) }
            payload("first")
        })
        // RFC 7959 §2.6: a notification too large for one message; the client asks for the rest with Block2.
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") {
            options { observe(2); block2(BlockOption(0, true, 0)); oscore(ByteArray(0)) }
            payload = ByteArray(16)
        })
        val followUp = s.peer.receive()
        assertEquals(1L, followUp.options.block2?.number)
        assertNull(followUp.options.observe)

        s.peer.send(forged401(followUp))

        // Neither repeated with the Echo value nor the end of the observation: the next notification arrives.
        s.peer.assertNoRequest()
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "third") {
            options { observe(3); oscore(ByteArray(0)) }
        })
        assertEquals(listOf("first", "third"), pending.await().map { it.payloadAsText() })
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

    @Test
    fun adr0008AForgedUnprotected401WithEchoToAQBlockObserveRegistrationIsNotRepeated() = runTest {
        for (type in listOf(CoapMessageType.NON, CoapMessageType.CON)) {
            val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
            val s = setup(CoapClientConfig(security = Toy, qBlock = qBlock))
            val uri = s.peer.uri("/counter")
            val observation = s.client.observe(uri) { if (type == CoapMessageType.NON) nonConfirmable() }
            val pending = async { runCatching { observation.toList() } }
            val registration = s.peer.receive()
            assertEquals(0L, registration.options.observe, "$type")
            assertNotNull(registration.options.qBlock2, "$type: it asks for Q-Block2 bodies (RFC 9177 §4.5)")

            s.peer.send(forged401(registration))

            assertUnverified(pending.await().exceptionOrNull())
            s.peer.assertNoRequest()
        }
    }

    @Test
    fun adr0008UnverifiedErrorsNeitherEndNorReachAnEstablishedQBlockObservation() = runTest {
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
        val s = setup(CoapClientConfig(security = Toy, blockSize = 16, qBlock = qBlock))
        val pending = async { s.client.observe(s.peer.uri("/counter")) { nonConfirmable() }.take(2).toList() }
        val registration = s.peer.receive()
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "first") {
            options { observe(1); qBlock2(BlockOption(0, false, 0)); etag(byteArrayOf(1)); oscore(ByteArray(0)) }
        })

        // On the observation's own token, where a verified error would end it (RFC 7641 §3.2).
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") { code = CoapCode.SERVICE_UNAVAILABLE })
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") { code = CoapCode.NOT_FOUND })
        // A body of two blocks whose second one is lost: the client asks for it on a token of its own (§4.4).
        val head = ByteArray(16) { it.toByte() }
        val tail = "tail".encodeToByteArray()
        s.peer.send(s.peer.response(registration, CoapMessageType.NON, "") {
            options {
                observe(2)
                qBlock2(BlockOption(0, true, 0))
                etag(byteArrayOf(2))
                size2(20)
                oscore(ByteArray(0))
            }
            payload = head
        })
        val missing = s.peer.receive()
        assertEquals(1L, missing.options.qBlock2?.number, "a request for the missing block")
        assertTrue(!missing.token.contentEquals(registration.token))
        s.peer.send(forged401(missing))
        s.peer.send(s.peer.response(missing, CoapMessageType.NON, "") { code = CoapCode.NOT_FOUND })

        s.peer.send(s.peer.response(missing, CoapMessageType.NON, "") {
            options { qBlock2(BlockOption(1, false, 0)); etag(byteArrayOf(2)); size2(20); oscore(ByteArray(0)) }
            payload = tail
        })

        val bodies = pending.await().map { it.payload.toList() }
        assertEquals(listOf("first".encodeToByteArray().toList(), (head + tail).toList()), bodies)
        val requests = s.fixture.sentBy(s.client.endpoint).filter { it.isRequest }
        assertTrue(requests.none { it.options.echo != null }, "no request took the forged Echo value")
    }

    /** As the server side of [Toy]: a request with the OSCORE option is protected, and so is every answer to it. */
    private object ToyServer : ServerMessageSecurity, SecurityIdentity {
        override val description: String get() = "toy"

        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            if (request.options.oscore == null) return null
            return object : UnprotectedRequest {
                override val message = request.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build()
                override val identity: SecurityIdentity = ToyServer

                override suspend fun protectResponse(response: CoapMessage): CoapMessage =
                    response.toBuilder().options { oscore(ByteArray(0)) }.build()
            }
        }
    }

    @Test
    fun rfc8323Section7_4AnUnverifiedAnswerToARegistrationIsFollowedByADeregistration() = runTest {
        val fixture = ReliableFixture(this)
        lateinit var counter: ObservableResource
        fixture.server(config = CoapServerConfig(security = ToyServer)) {
            counter = resource("counter") {
                observable()
                get { CoapResponse.content("tick") }
            }
        }
        // A party on the path, such as a WebSocket proxy, answers the registration before the server's protected
        // 2.05 with Observe, which therefore never reaches the client: the server has the client as an observer.
        var forged = false
        val onPath = object : MessageInterceptor {
            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage {
                if (forged || message.options.observe == null || !message.isResponse) return message
                forged = true
                return message.toBuilder().apply {
                    code = CoapCode.UNAUTHORIZED
                    payload = ByteArray(0)
                    options {
                        remove(CoapOptions.OSCORE.number)
                        remove(CoapOptions.OBSERVE.number)
                        remove(CoapOptions.CONTENT_FORMAT.number)
                        echo(echo)
                    }
                }.build()
            }
        }
        val endpointConfig = fixture.endpointConfig(interceptors = listOf(onPath))
        val client = fixture.client(CoapClientConfig(security = Toy), endpointConfig)

        val failure = runCatching { client.observe(fixture.uri("/counter")).toList() }.exceptionOrNull()

        assertUnverified(failure)
        runCurrent()
        // There is no Reset on a connection: the client deregisters (Observe 1, on the token), protected.
        val deregistration = fixture.sent(CoapCode.GET, byServer = false).last()
        assertEquals(1L, deregistration.options.observe)
        assertNotNull(deregistration.options.oscore)
        assertEquals(0, counter.observerCount, "the server forgot the observer")
    }
}
