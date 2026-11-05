package dev.kcoap.transport.ktor.websocket

import io.ktor.client.engine.cio.CIOEngineConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DefaultEngineTest {
    /** On the JVM the connector's default client runs on CIO, with the WebSockets plugin. */
    @Test
    fun theDefaultClientRunsOnCio() {
        val client = defaultClient()
        try {
            assertIs<CIOEngineConfig>(client.engine.config)
            assertNotNull(client.pluginOrNull(WebSockets))
        } finally {
            client.close()
        }
    }
}
