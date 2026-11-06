package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.JsClientEngineConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/** Runs on `js` and `wasmJs`, on Node.js, and in the browser job for `js`. */
class DefaultEngineTest {
    /**
     * In browsers and Node.js the connector's default client runs on ktor's Js engine, which opens the
     * runtime's own WebSocket.
     */
    @Test
    fun theDefaultClientRunsOnTheRuntimesWebSocket() {
        val client = defaultClient()
        try {
            assertIs<JsClientEngineConfig>(client.engine.config)
            assertNotNull(client.pluginOrNull(WebSockets))
        } finally {
            client.close()
        }
    }

    /**
     * An application's own `HttpClient()`, which it may pass to the connector, finds the Js engine
     * without naming it: ktor-client-core brings it on both web targets.
     */
    @Test
    fun anHttpClientWithoutAnEngineFindsTheJsEngine() {
        val client = HttpClient()
        try {
            assertIs<JsClientEngineConfig>(client.engine.config)
        } finally {
            client.close()
        }
    }

    /**
     * RFC 8323 §9.2: the runtime's own `wss` checks the server's chain and host name, so the default connector
     * serves `coaps+ws` here.
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
