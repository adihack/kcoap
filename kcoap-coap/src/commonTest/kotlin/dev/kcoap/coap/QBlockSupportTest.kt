package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.stack.QBlockCapabilities.Capability
import dev.kcoap.coap.stack.QBlockClientEvent
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * How the client chooses between Q-Block (RFC 9177) and RFC 7959 (§4.1): the probe, what it caches and
 * when it forgets it, the requests that always use RFC 7959, and the fallback when a server rejects Q-Block.
 */
class QBlockSupportTest {
    private fun body(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    private fun CoapMessage.hasQBlock(): Boolean = options.any { it.number in qBlockOptions }

    private suspend fun TestScope.scripted(
        qBlock: QBlockClientConfig = QBlockClientConfig(QBlockMode.PROBE),
        security: ClientMessageSecurity? = null,
    ): QBlockClientTest.Setup {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now })
        val client = fixture.client(CoapClientConfig(security = security, blockSize = 64, qBlock = qBlock))
        return QBlockClientTest.Setup(fixture, server, client)
    }

    private fun probes(setup: QBlockClientTest.Setup): List<CoapMessage> =
        setup.requests().filter { it.options.path == "/.well-known/core" }

    private suspend fun CoapClient.known(peer: Endpoint, isProtected: Boolean = false): Capability? =
        qBlock!!.support.known(peer, isProtected)

    // ---------------------------------------------------------------- a server without Q-Block

    @Test
    fun rfc9177Section4_1AServerWithoutQBlockAnswersTheProbe402AndRequestsUseRfc7959() = runTest {
        val fixture = StackFixture(this)
        var stored = ByteArray(0)
        val server = fixture.server {
            resource("big") {
                get { CoapResponse.content(body(3000)) }
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client(CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.PROBE)))

        val got = client.get(fixture.uri(server, "/big"))
        val put = client.put(fixture.uri(server, "/big"), body(2000))

        assertContentEquals(body(3000), got.payload)
        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(body(2000), stored)
        val sent = fixture.sentBy(client.endpoint).filter { it.isRequest }
        assertEquals(1, sent.count { it.hasQBlock() }, "only the probe carries Q-Block")
        assertEquals("/.well-known/core", sent.first().options.path)
        assertTrue(sent.drop(1).any { it.options.block1 != null } && sent.drop(1).any { it.options.block2 != null })
        val probeAnswer = fixture.traffic.mapNotNull { it.message }.first { it.isResponse }
        assertEquals(CoapCode.BAD_OPTION, probeAnswer.code)
        assertEquals(Capability.UNSUPPORTED, client.known(fixture.address(server.endpoint)))
        val fallback = fixture.events.filterIsInstance<QBlockClientEvent.Fallback>().single()
        assertTrue(fallback.reason.contains("4.02"), fallback.reason)
        assertTrue(fallback.toString().startsWith("QBlockClientEvent.Fallback("))
    }

    @Test
    fun rfc9177Section4_1InAssumeSupportedModeA402IsAnsweredWithRfc7959AndRemembered() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("big") { get { CoapResponse.content(body(3000)) } } }
        val client = fixture.client(CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)))

        val first = client.get(fixture.uri(server, "/big"))
        val second = client.get(fixture.uri(server, "/big"))

        assertContentEquals(body(3000), first.payload)
        assertContentEquals(body(3000), second.payload)
        val sent = fixture.sentBy(client.endpoint).filter { it.isRequest }
        assertEquals(1, sent.count { it.hasQBlock() }, "rejected once, then RFC 7959 only")
        assertTrue(sent.none { it.options.path == "/.well-known/core" }, "no probe in this mode")
        assertEquals(Capability.UNSUPPORTED, client.known(fixture.address(server.endpoint)))
        assertEquals(1, fixture.events.filterIsInstance<QBlockClientEvent.Fallback>().size)
    }

    @Test
    fun rfc7252Section5_4_1AResetToANonConfirmableQBlockRequestFallsBackToRfc7959() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("big") { get { CoapResponse.content(body(3000)) } } }
        val client = fixture.client(CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)))

        val response = client.get(fixture.uri(server, "/big")) { nonConfirmable() }

        assertContentEquals(body(3000), response.payload)
        val wire = fixture.traffic.mapNotNull { it.message }
        assertEquals(CoapMessageType.RST, wire[1].type, "the server rejects the Q-Block2 request")
        assertTrue(wire.drop(2).filter { it.isRequest }.none { it.hasQBlock() })
        assertEquals(0, client.endpoint.exchangeCount())
        assertEquals(0, client.endpoint.leaseCount())
    }

    @Test
    fun rfc9177Section4_1AResetToAQBlockMessageInProbeModeForgetsTheProbe() = runTest {
        val setup = scripted()
        setup.server.resources["/big"] = body(300)
        setup.client.get(setup.server.uri("/big"))
        setup.server.script = { from, request ->
            val reject = request.options.qBlock2 != null && request.options.path == "/big"
            if (reject) send(from, CoapMessage.empty(CoapMessageType.RST, request.messageId))
            reject
        }

        val failure = runCatching { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } }

        assertTrue(failure.isSuccess, "RFC 7959 answered: ${failure.exceptionOrNull()}")
        assertNull(setup.client.known(setup.server.address), "the next request probes again")
    }

    // ---------------------------------------------------------------- the probe

    @Test
    fun rfc9177Section4_1ConcurrentRequestsShareOneProbe() = runTest {
        val setup = scripted()
        setup.server.resources["/big"] = body(300)

        val responses = List(3) { async { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } } }.awaitAll()

        assertTrue(responses.all { it.payload.contentEquals(body(300)) })
        assertEquals(1, probes(setup).size)
        assertEquals(1, setup.client.qBlock!!.support.size())
    }

    @Test
    fun rfc9177Section4_1AProbeWithoutAnswerFallsBackAndIsRepeatedAfterAMinute() = runTest {
        val setup = scripted()
        setup.server.resources["/small"] = body(40)
        var silent = true
        setup.fixture.network.filter = { datagram ->
            val isProbe = runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull()?.options?.path == "/.well-known/core"
            !silent || !isProbe
        }

        val first = setup.client.get(setup.server.uri("/small"))
        silent = false
        val second = setup.client.get(setup.server.uri("/small"))
        delay(1.minutes)
        val third = setup.client.get(setup.server.uri("/small"))

        assertTrue(listOf(first, second, third).all { it.payload.contentEquals(body(40)) })
        assertEquals(2, probes(setup).map { it.messageId }.toSet().size, "one probe, then another after a minute")
        assertEquals(Capability.SUPPORTED, setup.client.known(setup.server.address))
        val gets = setup.requests().filter { it.options.path == "/small" }
        assertEquals(listOf(false, false, true), gets.map { it.hasQBlock() }, "RFC 7959 until the probe succeeds")
        val fallback = setup.fixture.events.filterIsInstance<QBlockClientEvent.Fallback>().single()
        assertTrue(fallback.reason.contains("no answer"), fallback.reason)
    }

    @Test
    fun aProbeAnsweredWithAResetOrWithoutQBlockMeansNoSupport() = runTest {
        val reset = scripted()
        reset.server.script = { from, request ->
            send(from, CoapMessage.empty(CoapMessageType.RST, request.messageId))
            true
        }
        assertFailsWith<CoapException> { reset.client.get(reset.server.uri("/x")) }
        assertEquals(Capability.UNSUPPORTED, reset.client.known(reset.server.address))

        val plain = scripted()
        plain.server.script = { from, request ->
            val probe = request.options.path == "/.well-known/core"
            if (probe) {
                send(from, CoapMessage.response(request, CoapCode.CONTENT) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options { block2(BlockOption(0, true, 0)) }
                    payload = ByteArray(16)
                })
            }
            probe
        }
        plain.server.resources["/small"] = body(40)
        assertContentEquals(body(40), plain.client.get(plain.server.uri("/small")).payload)
        assertEquals(Capability.UNSUPPORTED, plain.client.known(plain.server.address))
    }

    @Test
    fun rfc9177Section4_1AProbeAnswered402WithTheRejectedOptionMeansNoSupport() = runTest {
        // libcoap 4.3.5 without -L repeats the Q-Block2 option it rejects in its 4.02.
        val setup = scripted()
        setup.server.script = { from, request ->
            val probe = request.options.path == "/.well-known/core"
            if (probe) {
                send(from, CoapMessage.response(request, CoapCode.BAD_OPTION) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options { qBlock2(BlockOption(0, false, 0)) }
                    payload("Bad Option")
                })
            }
            probe
        }
        setup.server.resources["/small"] = body(40)

        assertContentEquals(body(40), setup.client.get(setup.server.uri("/small")).payload)

        assertEquals(Capability.UNSUPPORTED, setup.client.known(setup.server.address))
        assertTrue(setup.requests().filter { it.options.path == "/small" }.none { it.hasQBlock() })
    }

    @Test
    fun rfc9177Section4_1AProbeAnswerMixingBlockAndQBlockMeansNoSupport() = runTest {
        val setup = scripted()
        setup.server.script = { from, request ->
            val probe = request.options.path == "/.well-known/core"
            if (probe) {
                send(from, CoapMessage.response(request, CoapCode.CONTENT) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options {
                        block2(BlockOption(0, true, 0))
                        qBlock2(BlockOption(0, true, 0))
                    }
                    payload = ByteArray(16)
                })
            }
            probe
        }
        setup.server.resources["/small"] = body(40)

        assertContentEquals(body(40), setup.client.get(setup.server.uri("/small")).payload)

        assertEquals(Capability.UNSUPPORTED, setup.client.known(setup.server.address))
        assertTrue(setup.requests().filter { it.options.path == "/small" }.none { it.hasQBlock() })
    }

    @Test
    fun rfc9175Section2_3AProbeChallengedWithEchoIsRepeatedWithIt() = runTest {
        val setup = scripted()
        val echo = byteArrayOf(9, 8, 7)
        setup.server.script = { from, request ->
            val challenge = request.options.path == "/.well-known/core" && request.options.echo == null
            if (challenge) {
                send(from, CoapMessage.response(request, CoapCode.UNAUTHORIZED) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options { echo(echo) }
                })
            }
            challenge
        }

        setup.client.get(setup.server.uri("/x"))

        assertEquals(2, probes(setup).size)
        assertContentEquals(echo, probes(setup).last().options.echo)
        assertEquals(Capability.SUPPORTED, setup.client.known(setup.server.address))
    }

    @Test
    fun rfc7252Section5_4_1AProbeAnswerWithAnUnknownCriticalOptionMeansNoSupport() = runTest {
        val setup = scripted()
        setup.server.script = { from, request ->
            val probe = request.options.path == "/.well-known/core"
            if (probe) {
                send(from, CoapMessage.response(request, CoapCode.CONTENT) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    options {
                        qBlock2(BlockOption(0, true, 0))
                        add(CoapOption(9999, byteArrayOf(1)))
                    }
                })
            }
            probe
        }
        setup.server.resources["/small"] = body(40)

        assertContentEquals(body(40), setup.client.get(setup.server.uri("/small")).payload)

        assertEquals(Capability.UNSUPPORTED, setup.client.known(setup.server.address))
        val reason = setup.fixture.events.filterIsInstance<QBlockClientEvent.Fallback>().single().reason
        assertTrue(reason.contains("rejected"), reason)
    }

    @Test
    fun aProbeThatFailsOtherwiseFailsEveryRequestWaitingForIt() = runTest {
        val setup = scripted()
        setup.server.script = { _, _ -> true }

        val requests = List(2) { async { runCatching { setup.client.get(setup.server.uri("/small")) } } }
        delay(1.seconds)
        setup.client.endpoint.close()

        val reasons = requests.map { (it.await().exceptionOrNull() as CoapException).reason }
        assertEquals(listOf(CoapException.Reason.CLOSED, CoapException.Reason.CLOSED), reasons)
        assertEquals(1, probes(setup).size)
        assertEquals(null, setup.client.known(setup.server.address))
        assertTrue(setup.client.qBlock!!.support.toString().contains("PROBE"))
    }

    // ---------------------------------------------------------------- forgetting

    @Test
    fun theProbeIsRepeatedOnceItsLifetimeEnds() = runTest {
        val setup = scripted(QBlockClientConfig(QBlockMode.PROBE, capabilityLifetime = 10.seconds))
        setup.server.resources["/small"] = body(40)

        setup.client.get(setup.server.uri("/small"))
        delay(5.seconds)
        setup.client.get(setup.server.uri("/small"))
        delay(10.seconds)
        setup.client.get(setup.server.uri("/small"))

        assertEquals(2, probes(setup).size)
    }

    @Test
    fun rfc7252Section9_1_1AnEndedDtlsSessionForgetsWhatIsKnownAboutThePeer() = runTest {
        val setup = scripted(QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        val session = Endpoint.Secure(SessionId(1), 0, setup.server.address, PeerIdentity.None)
        setup.client.qBlock!!.support.rejected(session, isProtected = false, reason = "4.02")
        assertEquals(Capability.UNSUPPORTED, setup.client.known(session))
        val transport = setup.client.endpoint.transport as InMemoryTransport
        setup.client.ping(setup.server.uri("/"))

        transport.inject(DatagramEvent.SessionEnded(session, SessionEndReason.CLOSED_BY_PEER))
        delay(1.seconds)

        assertNull(setup.client.known(session))
    }

    @Test
    fun theClientsOfAnEndpointShareWhatItKnowsAndRegisterNothingOfTheirOwn() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now })
        server.resources["/small"] = body(40)
        val endpoint = fixture.endpoint()
        val config = CoapClientConfig(blockSize = 64, qBlock = QBlockClientConfig(QBlockMode.PROBE))

        // Short-lived clients on a long-lived endpoint, never closed.
        repeat(100) { assertContentEquals(body(40), CoapClient(endpoint, config).get(server.uri("/small")).payload) }

        val probes = fixture.sentBy(endpoint).filter { it.options.path == "/.well-known/core" }
        assertEquals(1, probes.size, "one probe for every client of the endpoint")
        assertEquals(1, endpoint.qBlockCapabilities.size())
        val session = Endpoint.Secure(SessionId(1), 0, server.address, PeerIdentity.None)
        CoapClient(endpoint, config).qBlock!!.support.rejected(session, isProtected = false, reason = "4.02")
        (endpoint.transport as InMemoryTransport).inject(DatagramEvent.SessionEnded(session, SessionEndReason.CLOSED_BY_PEER))
        delay(1.seconds)
        assertNull(CoapClient(endpoint, config).known(session), "the endpoint forgets for all its clients")
        assertEquals(Capability.SUPPORTED, CoapClient(endpoint, config).known(server.address))
        assertTrue(endpoint.qBlockCapabilities.toString().contains("1024"))
    }

    @Test
    fun rfc8323Section3_4AClosedConnectionForgetsWhatTheProbeFound() = runTest {
        val fixture = ReliableFixture(this)
        val server = fixture.server { resource("small") { get { CoapResponse.content("small") } } }
        val client = fixture.client(CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.PROBE, onReliableTransports = true)))
        val peer = Endpoint.Tcp("127.0.0.1", 5683, false)

        assertEquals("small", client.get(fixture.uri("/small")).payloadAsText())
        assertEquals(Capability.UNSUPPORTED, client.known(peer))
        server.close()
        delay(1.seconds)

        assertNull(client.known(peer))
    }

    // ---------------------------------------------------------------- requests that always use RFC 7959

    @Test
    fun qBlockOffSendsNoQBlockOptionAtAll() = runTest {
        val setup = scripted(QBlockClientConfig())
        setup.server.resources["/small"] = body(40)

        setup.client.get(setup.server.uri("/small"))

        assertNull(setup.client.qBlock)
        assertTrue(setup.requests().none { it.hasQBlock() })
    }

    @Test
    fun requestsWithNoResponseObserveOrTheirOwnBlockOptionsUseRfc7959() = runTest {
        val setup = scripted()
        setup.server.resources["/small"] = body(40)
        setup.server.script = { from, request ->
            send(from, CoapMessage.response(request, CoapCode.CONTENT) {
                type = CoapMessageType.ACK
                messageId = request.messageId
            })
            true
        }

        setup.client.get(setup.server.uri("/small")) { options { noResponse(0) } }
        setup.client.get(setup.server.uri("/small")) { options { observe(0) } }
        setup.client.get(setup.server.uri("/small")) { options { block2(BlockOption(0, false, 2)) } }

        assertTrue(setup.requests().none { it.hasQBlock() }, "not even a probe")
    }

    @Test
    fun messageSecurityTurnsQBlockOffUnlessAllowed() = runTest {
        val passThrough = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? = null
        }
        val off = scripted(security = passThrough)
        off.server.resources["/small"] = body(40)
        off.client.get(off.server.uri("/small"))
        assertTrue(off.requests().none { it.hasQBlock() })

        val on = scripted(QBlockClientConfig(QBlockMode.PROBE, withMessageSecurity = true), security = passThrough)
        on.server.resources["/small"] = body(40)
        on.client.get(on.server.uri("/small"))
        assertEquals(Capability.SUPPORTED, on.client.known(on.server.address, isProtected = true))
        assertNull(on.client.known(on.server.address, isProtected = false), "known per protection")
    }

    @Test
    fun reliableTransportsUseRfc7959UnlessAllowed() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("big") { get { CoapResponse.content(body(3000)) } } }
        val client = fixture.client(CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.PROBE)))

        assertContentEquals(body(3000), client.get(fixture.uri("/big")).payload)
        assertTrue(fixture.sent(byServer = false).none { it.hasQBlock() })

        val allowed = fixture.client(
            CoapClientConfig(qBlock = QBlockClientConfig(QBlockMode.PROBE, onReliableTransports = true)),
        )
        assertContentEquals(body(3000), allowed.get(fixture.uri("/big")).payload)
        val probe = fixture.sent(byServer = false).single { it.hasQBlock() }
        assertEquals("/.well-known/core", probe.options.path, "kcoap's server rejects the probe with 4.02")
    }

    // ---------------------------------------------------------------- configuration

    @Test
    fun theConfigurationIsValidated() {
        assertFailsWith<IllegalArgumentException> { QBlockClientConfig(probePath = "core") }
        assertFailsWith<IllegalArgumentException> { QBlockClientConfig(capabilityLifetime = 0.seconds) }
        val config = QBlockClientConfig(QBlockMode.PROBE)
        assertTrue(config.isEnabled)
        assertTrue(!QBlockClientConfig().isEnabled)
        assertTrue(config.toString().contains("mode=PROBE"))
        assertTrue(CoapClientConfig(qBlock = config).toString().contains("qBlock=PROBE"))
        assertEquals(TransmissionParameters().maxPayloads, 10)
    }
}
