package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.JsClientEngineConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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
}
