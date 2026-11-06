package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class DefaultEngineTest {
    /**
     * On Apple the connector's default client runs on the Darwin engine, so its WebSockets go through
     * URLSession, which brings up the cellular radio and honours VPN On Demand; BSD sockets do neither.
     *
     * This test binary links ktor-client-cio as well, as an app does that uses CIO for its own HTTP
     * calls. ktor's engine lookup would then take whichever engine registered first; the default client
     * names Darwin, so the lookup never runs.
     */
    @Test
    fun theDefaultClientRunsOnUrlSessionEvenWhenCioIsLinked() {
        val cio = HttpClient(CIO)
        val client = defaultClient()
        try {
            assertIs<CIOEngineConfig>(cio.engine.config, "CIO is linked into this binary")
            assertIs<DarwinClientEngineConfig>(client.engine.config)
            assertNotNull(client.pluginOrNull(WebSockets))
        } finally {
            client.close()
            cio.close()
        }
    }

    /**
     * RFC 8323 §9.2: URLSession checks the server's chain and host name, so the default connector serves
     * `coaps+ws` here.
     */
    @Test
    fun theDefaultConnectorServesCoapsWs() = runTest {
        val connector = KtorWebSocketConnector()
        try {
            assertEquals(setOf(CoapScheme.COAP_WS, CoapScheme.COAPS_WS), connector.schemes)
        } finally {
            connector.close()
        }
    }
}
