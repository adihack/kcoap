package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIOEngineConfig
import kotlin.test.Test
import kotlin.test.assertIs

class DefaultEngineTest {
    /** On the JVM the connector's default client runs on CIO, the one engine this module brings there. */
    @Test
    fun theDefaultClientRunsOnCio() {
        val client = HttpClient()
        try {
            assertIs<CIOEngineConfig>(client.engine.config)
        } finally {
            client.close()
        }
    }
}
