package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The connector without a server: what holds on every target before a WebSocket opens. */
class KtorWebSocketConnectorTest {
    /**
     * `KtorWebSocketConnector()` builds its own client on every target, so a platform without an engine
     * would fail here rather than in an application. It serves `coap+ws` everywhere (RFC 8323 §8.3), and
     * `coaps+ws` (§8.4) only where its engine authenticates `wss` servers; the platform tests pin where.
     */
    @Test
    fun theDefaultConnectorBuildsOnEveryTarget() = runTest {
        val connector = KtorWebSocketConnector()
        try {
            assertTrue(CoapScheme.COAP_WS in connector.schemes)
            assertEquals(defaultClientAuthenticatesServers, CoapScheme.COAPS_WS in connector.schemes)
            assertEquals("KtorWebSocketConnector", connector.toString())
        } finally {
            connector.close()
        }
    }

    /**
     * RFC 8323 §9.2: an application's own client serves `coaps+ws` too, on every target, since passing it is the
     * application's word that its engine authenticates `wss` servers.
     */
    @Test
    fun aConnectorOnTheApplicationsClientServesBothSchemes() = runTest {
        val client = defaultClient()
        val connector = KtorWebSocketConnector(client)
        try {
            assertEquals(setOf(CoapScheme.COAP_WS, CoapScheme.COAPS_WS), connector.schemes)
        } finally {
            connector.close()
            client.close()
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
