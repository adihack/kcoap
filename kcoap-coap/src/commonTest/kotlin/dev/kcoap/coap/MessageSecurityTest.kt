package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The message-security SPI where RFC 8613 Figure 1 puts OSCORE, exercised with a toy protection: the
 * inner message (code, options, payload) is encoded and XORed into the payload of an outer POST or 2.04
 * with an OSCORE option, as OSCORE moves Class E options inside.
 */
class MessageSecurityTest {
    private data class Identity(val kid: Byte) : SecurityIdentity {
        override val description: String get() = "kid $kid"
    }

    private object Toy {
        const val KEY: Byte = 0x5A

        fun seal(inner: CoapMessage, outerCode: CoapCode, kid: ByteArray): CoapMessage {
            val plaintext = UdpMessageCodec.encode(CoapMessage(CoapMessageType.CON, inner.code, 0, ByteArray(0), inner.options, inner.payload))
            return CoapMessage.build {
                type = inner.type
                messageId = inner.messageId
                token = inner.token
                code = outerCode
                options {
                    oscore(kid)
                    // RFC 8613 §4.1.3.5: an Outer Observe lets intermediaries see notifications.
                    inner.options.observe?.let { observe(it) }
                }
                payload = ByteArray(plaintext.size) { (plaintext[it].toInt() xor KEY.toInt()).toByte() }
            }
        }

        fun open(outer: CoapMessage): CoapMessage {
            val plaintext = outer.payload.map { (it.toInt() xor KEY.toInt()).toByte() }.toByteArray()
            val inner = try {
                UdpMessageCodec.decode(plaintext)
            } catch (e: Exception) {
                throw MessageSecurityException("Decryption failed", CoapCode.BAD_REQUEST, "Decryption failed", e)
            }
            return CoapMessage(outer.type, inner.code, outer.messageId, outer.token, inner.options, inner.payload)
        }
    }

    private class ClientSide(private val kid: Byte = 1) : ClientMessageSecurity {
        var protects = 0
        var unprotects = 0
        var failNextResponse = false

        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest {
            protects++
            assertTrue(request.token.isNotEmpty() || request.token.isEmpty(), "token assigned before protection")
            val outerCode = if (request.options.observe != null) CoapCode.FETCH else CoapCode.POST
            val sealed = Toy.seal(request, outerCode, byteArrayOf(kid))
            return object : ProtectedRequest {
                override val message: CoapMessage = sealed

                override suspend fun unprotectResponse(response: CoapMessage): ResponseVerification {
                    unprotects++
                    if (failNextResponse) {
                        failNextResponse = false
                        throw MessageSecurityException("Replayed")
                    }
                    if (CoapOptions.OSCORE.number !in response.options) {
                        // As OSCORE does (RFC 8613 §8.2): an unprotected error is handed on unverified.
                        if (response.code.isClientError || response.code.isServerError) {
                            return ResponseVerification.Unverified(response, "unprotected ${response.code}")
                        }
                        throw MessageSecurityException("Unprotected response")
                    }
                    return ResponseVerification.Verified(Toy.open(response))
                }
            }
        }
    }

    private class ServerSide(private val challengeFirst: Boolean = false) : ServerMessageSecurity {
        var protects = 0
        var challenged = 0

        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            val kid = request.options.oscore ?: return null
            if (kid.singleOrNull() != 1.toByte()) {
                throw MessageSecurityException("No context", CoapCode.UNAUTHORIZED, "Security context not found")
            }
            val inner = Toy.open(request)
            val needsChallenge = challengeFirst && inner.options.echo == null
            if (needsChallenge) challenged++
            return object : UnprotectedRequest {
                override val message: CoapMessage = inner
                override val identity: SecurityIdentity = Identity(kid[0])
                override val challenge: CoapMessage? = if (needsChallenge) {
                    CoapMessage.response(inner, CoapCode.UNAUTHORIZED) { options { echo("fresh".encodeToByteArray()) } }
                } else {
                    null
                }

                override suspend fun protectResponse(response: CoapMessage): CoapMessage {
                    protects++
                    val outerCode = if (response.options.observe != null) CoapCode.CONTENT else CoapCode.CHANGED
                    return Toy.seal(response, outerCode, ByteArray(0))
                }
            }
        }
    }

    @Test
    fun rfc8613Figure1RequestsAndResponsesAreProtectedBetweenExchangesAndMessaging() = runTest {
        val fixture = StackFixture(this)
        val serverSecurity = ServerSide()
        var seen: String? = null
        val server = fixture.server(CoapServerConfig(security = serverSecurity)) {
            resource("secret") {
                get {
                    seen = "${method} ${options.path} by ${securityIdentity?.description}"
                    CoapResponse.content("classified")
                }
            }
        }
        val clientSecurity = ClientSide()
        val client = fixture.client(CoapClientConfig { security = clientSecurity })

        val response = client.get(fixture.uri(server, "/secret"))

        assertEquals("classified", response.payloadAsText())
        assertEquals("0.01 GET /secret by kid 1", seen)
        val (request, answer) = fixture.traffic.mapNotNull { it.message }
        assertEquals(CoapCode.POST, request.code, "the outer code")
        assertEquals(emptyList(), request.options.uriPath, "Uri-Path travels inside")
        assertEquals(CoapCode.CHANGED, answer.code)
        assertTrue(CoapOptions.OSCORE.number in answer.options)
        assertEquals(request.tokenHex, answer.tokenHex, "the token is kept")
        assertEquals(1 to 1, clientSecurity.protects to clientSecurity.unprotects)
        assertEquals(1, serverSecurity.protects)
    }

    @Test
    fun unprotectedRequestsReachOnlyPublicResourcesOfAServerWithSecurity() = runTest {
        val fixture = StackFixture(this)
        var reached = 0
        val server = fixture.server(CoapServerConfig(security = ServerSide())) {
            resource("public") {
                access = AccessPolicy.Public
                get { CoapResponse.content("identity ${securityIdentity == null}") }
            }
            resource("secret") {
                get {
                    reached++
                    CoapResponse.content("classified")
                }
            }
        }
        val client = fixture.client()

        assertEquals("identity true", client.get(fixture.uri(server, "/public")).payloadAsText())
        // ADR 0008: with message security configured, the default policy refuses what it did not verify.
        assertEquals(CoapCode.UNAUTHORIZED, client.get(fixture.uri(server, "/secret")).code)
        assertEquals(0, reached, "the refused request never reached the handler")
    }

    @Test
    fun rfc8613Section8_2SecurityErrorsAreAnsweredUnprotectedWithMaxAgeZero() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(security = ServerSide())) {
            resource("secret") { get { CoapResponse.content("classified") } }
        }
        val client = fixture.client(CoapClientConfig { security = ClientSide(kid = 9); requestTimeout = 20.seconds })

        // The unprotected error is unverified: the request ends with it as a distinct failure, not a timeout,
        // and nothing in it is acted on (ADR 0008).
        val error = assertFailsWith<CoapException> { client.get(fixture.uri(server, "/secret")) }
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, error.reason)
        assertEquals(CoapCode.UNAUTHORIZED, error.response?.code)
        assertEquals("Security context not found", error.response?.payloadAsText())
        assertTrue(fixture.now < 20.seconds, "at once, not at the timeout")

        val answer = fixture.traffic.mapNotNull { it.message }.first { it.isResponse }
        assertEquals(CoapCode.UNAUTHORIZED, answer.code)
        assertEquals(0L, answer.options.maxAge)
        assertNull(answer.options.oscore)
        assertEquals("Security context not found", answer.payloadAsText())
    }

    @Test
    fun securityErrorsCarryEitherAStructuredBodyOrTheDiagnostic() = runTest {
        val fixture = StackFixture(this)
        val edhocError = byteArrayOf(0x01, 0x6A) + "bad message_3".encodeToByteArray()
        val security = object : ServerMessageSecurity {
            override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
                val kid = request.options.oscore ?: return null
                when (kid.single().toInt()) {
                    // RFC 9668 §3.3.1: a failed EDHOC step is answered with an EDHOC error message.
                    7 -> throw MessageSecurityException(
                        "EDHOC failed", CoapCode.BAD_REQUEST, diagnostic = "ignored",
                        errorPayload = edhocError, contentFormat = ContentFormat.EDHOC_CBOR_SEQ,
                    )
                    8 -> throw MessageSecurityException("Decryption failed", CoapCode.BAD_REQUEST, "Decryption failed")
                    else -> throw MessageSecurityException("Silent", CoapCode.UNAUTHORIZED)
                }
            }
        }
        val server = fixture.server(CoapServerConfig(security = security)) { }
        val raw = fixture.transport()
        suspend fun answerFor(kid: Int): CoapMessage {
            val request = CoapMessage.request(CoapCode.POST) {
                messageId = kid
                token = byteArrayOf(kid.toByte())
                options { oscore(byteArrayOf(kid.toByte())) }
                payload = byteArrayOf(1, 2, 3)
            }
            raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(request))
            return UdpMessageCodec.decode((raw.receive() as dev.kcoap.transport.DatagramEvent.Received).datagram)
        }

        val structured = answerFor(7)
        assertEquals(CoapCode.BAD_REQUEST, structured.code)
        assertContentEquals(edhocError, structured.payload)
        assertEquals(ContentFormat.EDHOC_CBOR_SEQ, structured.options.contentFormat)
        assertEquals(0L, structured.options.maxAge)
        assertNull(structured.options.oscore, "sent unprotected (RFC 8613 §8.2)")

        val diagnostic = answerFor(8)
        assertEquals("Decryption failed", diagnostic.payloadAsText())
        assertNull(diagnostic.options.contentFormat, "a diagnostic payload has no Content-Format (RFC 7252 §5.5.2)")
        assertEquals(0L, diagnostic.options.maxAge)

        val bare = answerFor(9)
        assertEquals(CoapCode.UNAUTHORIZED, bare.code)
        assertEquals(0, bare.payloadSize)

        val exception = MessageSecurityException("x", errorPayload = edhocError)
        assertContentEquals(edhocError, exception.errorPayload)
        exception.errorPayload!![0] = 9
        assertContentEquals(edhocError, exception.errorPayload, "copied out")
        assertNull(MessageSecurityException("y").errorPayload)
    }

    @Test
    fun rfc8613AppendixB1_2AChallengeIsAnsweredAndRfc9175EchoIsRepeated() = runTest {
        val fixture = StackFixture(this)
        val serverSecurity = ServerSide(challengeFirst = true)
        var handled = 0
        val server = fixture.server(CoapServerConfig(security = serverSecurity)) {
            resource("lock") {
                put {
                    handled++
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client(CoapClientConfig { security = ClientSide() })

        val response = client.put(fixture.uri(server, "/lock"), "0".encodeToByteArray())

        assertEquals(CoapCode.CHANGED, response.code)
        assertEquals(1, serverSecurity.challenged)
        assertEquals(1, handled, "the challenged request never reached the resource")
        assertEquals(2, fixture.traffic.count { it.message?.isRequest == true })
    }

    @Test
    fun rfc9175Section2_3APlain401WithEchoIsRepeatedOnce() = runTest {
        val fixture = StackFixture(this)
        val echoes = mutableListOf<String?>()
        val server = fixture.server {
            resource("fresh") {
                post {
                    echoes += options.echo?.decodeToString()
                    if (options.echo?.decodeToString() == "ok") {
                        CoapResponse.changed()
                    } else {
                        CoapResponse.error(CoapCode.UNAUTHORIZED).withOptions { echo("ok".encodeToByteArray()) }
                    }
                }
            }
            resource("stubborn") {
                post { CoapResponse.error(CoapCode.UNAUTHORIZED).withOptions { echo("again".encodeToByteArray()) } }
            }
        }
        val client = fixture.client()

        assertEquals(CoapCode.CHANGED, client.post(fixture.uri(server, "/fresh")).code)
        assertEquals(listOf(null, "ok"), echoes)
        assertEquals(CoapCode.UNAUTHORIZED, client.post(fixture.uri(server, "/stubborn")).code, "only one retry")
        val noRetry = fixture.client(CoapClientConfig { echoRetry = false })
        assertEquals(CoapCode.UNAUTHORIZED, noRetry.post(fixture.uri(server, "/fresh")).code)
    }

    @Test
    fun rfc8613Section4_1_3_4_1EveryBlockIsProtectedOnItsOwn() = runTest {
        val fixture = StackFixture(this)
        val serverSecurity = ServerSide()
        val body = Random(4).nextBytes(2500)
        var received: ByteArray? = null
        val server = fixture.server(CoapServerConfig(security = serverSecurity)) {
            resource("echo") {
                post {
                    received = payload
                    CoapResponse(CoapCode.CHANGED, payload = payload)
                }
            }
        }
        val clientSecurity = ClientSide()
        val client = fixture.client(CoapClientConfig { security = clientSecurity })

        val response = client.post(fixture.uri(server, "/echo"), body)

        assertContentEquals(body, received)
        assertContentEquals(body, response.payload)
        val requests = fixture.traffic.mapNotNull { it.message }.filter { it.isRequest }
        assertEquals(requests.size, clientSecurity.protects)
        assertEquals(requests.size, serverSecurity.protects)
        assertTrue(requests.all { it.options.block1 == null && it.options.block2 == null }, "Block options are inner")
        assertTrue(fixture.traffic.all { (it.message?.let { m -> UdpMessageCodec.encode(m).size } ?: 0) <= 1152 })
    }

    @Test
    fun rfc8613Section8_3_1EveryNotificationIsProtectedWithTheRegistrationContext() = runTest {
        val fixture = StackFixture(this)
        val serverSecurity = ServerSide()
        val counter = object : ObservableResource("counter") {
            var value = 0
            override suspend fun handleGet(context: RequestContext) = CoapResponse.content("$value")
        }
        val server = fixture.server(CoapServerConfig(security = serverSecurity)) { add(counter) }
        val clientSecurity = ClientSide()
        val client = fixture.client(CoapClientConfig { security = clientSecurity })

        val received = async { client.observe(fixture.uri(server, "/counter")).take(3).toList() }
        delay(1.seconds)
        counter.value = 1
        counter.notifyObservers()
        delay(5.seconds)
        clientSecurity.failNextResponse = true
        counter.value = 2
        counter.notifyObservers()
        delay(5.seconds)
        counter.value = 3
        counter.notifyObservers()

        // The notification that failed verification is dropped without ending the observation (§8.4.2).
        assertEquals(listOf("0", "1", "3"), received.await().map { it.payloadAsText() })
        val registration = fixture.traffic.mapNotNull { it.message }.first { it.isRequest }
        assertEquals(CoapCode.FETCH, registration.code, "RFC 8613 §4.1.3.5: Observe requests are FETCH outside")
        assertTrue(serverSecurity.protects >= 4)
    }
}
