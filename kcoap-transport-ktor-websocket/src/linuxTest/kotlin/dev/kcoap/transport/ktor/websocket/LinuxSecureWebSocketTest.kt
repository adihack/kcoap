package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.Endpoint
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Why `coaps+ws` does not run on Linux native (docs/platforms.md): the default client's CIO engine takes its TLS
 * from ktor-network-tls, whose handshake there is a stub that throws, so a `wss` URI fails before any byte of TLS.
 * When an update makes this test fail, ktor brought TLS to Linux native.
 */
class LinuxSecureWebSocketTest {
    @Test
    fun upstreamCioHasNoTlsOnLinuxNativeSoCoapsWsFails() = runBlocking {
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                // Any listening socket will do: the client fails before it would send its ClientHello.
                val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {}
                server.startSuspend(wait = false)
                val connector = KtorWebSocketConnector()
                try {
                    val port = server.engine.resolvedConnectors().first().port
                    val error = assertFails { connector.connect(Endpoint.WebSocket.of("127.0.0.1", port, usesTls = true)) }
                    val messages = generateSequence(error) { it.cause }.map { it.message.orEmpty() }.toList()
                    assertTrue(messages.any { "TLS sessions are not supported on Native platform" in it }, "$messages")
                } finally {
                    connector.close()
                    server.stopSuspend(0, 1000)
                }
            }
        }
    }
}
