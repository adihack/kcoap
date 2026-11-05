package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/** The connector without a server: what holds on every target before a WebSocket opens. */
class KtorWebSocketConnectorTest {
    /**
     * `KtorWebSocketConnector()` builds its own client on every target, so a platform without an engine
     * would fail here rather than in an application; it serves `coap+ws` and `coaps+ws` (RFC 8323 §8.3,
     * §8.4).
     */
    @Test
    fun theDefaultConnectorBuildsOnEveryTarget() = runTest {
        val connector = KtorWebSocketConnector()
        try {
            assertEquals(setOf(CoapScheme.COAP_WS, CoapScheme.COAPS_WS), connector.schemes)
            assertEquals("KtorWebSocketConnector", connector.toString())
        } finally {
            connector.close()
        }
    }

    /** A WebSocket connector reaches WebSocket endpoints only (RFC 8323 §4). */
    @Test
    fun otherEndpointsAreRefused() = runTest {
        val connector = KtorWebSocketConnector()
        try {
            assertFailsWith<IllegalArgumentException> { connector.connect(Endpoint.Tcp("127.0.0.1", 5683)) }
        } finally {
            connector.close()
        }
    }
}
