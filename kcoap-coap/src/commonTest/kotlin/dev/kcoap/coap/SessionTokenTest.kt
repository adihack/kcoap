package dev.kcoap.coap

import dev.kcoap.coap.stack.TokenGenerator
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * RFC 9175 §4.2: a client whose security protocol does not bind responses to requests, DTLS or TLS, numbers its
 * tokens from zero for each new session; elsewhere they stay random (RFC 7252 §5.3.1). The default generator,
 * [TokenGenerator.sessionSequence], does both.
 */
class SessionTokenTest {
    private val tlsTcp = Endpoint.Tcp("127.0.0.1", 5684, usesTls = true)
    private val tlsWebSocket = Endpoint.WebSocket.of("127.0.0.1", 443, usesTls = true)
    private val plainTcp = Endpoint.Tcp("127.0.0.1", 5683)

    private fun ReliableFixture.tokensTo(peer: Endpoint): List<String> =
        wire.filter { it.to == peer && it.message.code == CoapCode.GET }.map { it.message.tokenHex }

    @Test
    fun rfc9175Section4_2RequestsOnATlsConnectionNumberTheirTokensFromZero() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 5684, scheme = CoapScheme.COAPS_TCP) { resource("x") { get { CoapResponse.content("tcp") } } }
        fixture.server(port = 443, scheme = CoapScheme.COAPS_WS) { resource("x") { get { CoapResponse.content("ws") } } }
        fixture.server(port = 5683) { resource("x") { get { CoapResponse.content("plain") } } }
        // The default configuration: every endpoint makes its own TokenGenerator.sessionSequence().
        val client = fixture.client(endpointConfig = fixture.endpointConfig())

        repeat(3) { assertEquals("tcp", client.get("coaps+tcp://127.0.0.1/x").payloadAsText()) }
        repeat(2) { assertEquals("ws", client.get("coaps+ws://127.0.0.1/x").payloadAsText()) }
        repeat(2) { assertEquals("plain", client.get("coap+tcp://127.0.0.1/x").payloadAsText()) }

        assertEquals(listOf("", "01", "02"), fixture.tokensTo(tlsTcp), "the shortest form, the empty token first")
        assertEquals(listOf("", "01"), fixture.tokensTo(tlsWebSocket), "a count of its own per connection")
        val plain = fixture.tokensTo(plainTcp)
        assertEquals(listOf(16, 16), plain.map { it.length }, "8 random bytes without TLS")
        assertTrue(plain[0] != plain[1])
    }

    @Test
    fun rfc9175Section4_2ANewTlsConnectionStartsTheCountAgain() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 5684, scheme = CoapScheme.COAPS_TCP) { resource("x") { get { CoapResponse.content("tcp") } } }
        val client = fixture.client()
        repeat(2) { client.get("coaps+tcp://127.0.0.1/x") }

        // The connection breaks; the next one has new keys, so its tokens start at zero again.
        fixture.connections.connections().first { it.remote == tlsTcp }.reset()
        delay(1.seconds)
        assertEquals("tcp", client.get("coaps+tcp://127.0.0.1/x").payloadAsText())
        assertEquals(listOf("", "01", ""), fixture.tokensTo(tlsTcp))
    }

    @Test
    fun theGeneratorCountsOnSecureChannelsOnly() {
        val tokens = TokenGenerator.sessionSequence(length = 4, random = Random(1))
        assertEquals(listOf(0, 1), List(2) { tokens.next(tlsTcp).size })
        assertEquals(listOf(0, 1), List(2) { tokens.next(tlsWebSocket).size })
        assertEquals(4, tokens.next(plainTcp).size)
        assertEquals(4, tokens.next(Endpoint.WebSocket.of("127.0.0.1", 80)).size)
        assertEquals(4, tokens.next(Endpoint.Udp("127.0.0.1", 5683)).size)
        assertEquals(4, tokens.next(Endpoint.Opaque("ble", "00:11")).size)
        tokens.forget(tlsTcp)
        assertEquals(0, tokens.next(tlsTcp).size, "forgetting a connection restarts its count")
        assertEquals(2, tokens.next(tlsWebSocket).last().toInt(), "and leaves the others")
    }
}
