package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import kotlin.test.Test
import kotlin.test.assertIs

class DefaultEngineTest {
    /**
     * On Apple the connector's default client runs on the Darwin engine, so its WebSockets go through
     * URLSession, which brings up the cellular radio and honours VPN On Demand; BSD sockets do neither.
     */
    @Test
    fun theDefaultClientRunsOnUrlSession() {
        val client = HttpClient()
        try {
            assertIs<DarwinClientEngineConfig>(client.engine.config)
        } finally {
            client.close()
        }
    }
}
