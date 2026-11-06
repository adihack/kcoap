package dev.kcoap.transport.ktor

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.tls.tls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Why `coaps+tcp` has no connector on Linux native and Apple platforms (docs/platforms.md): ktor-network-tls
 * 3.6.1 compiles there, but its client handshake is a stub that throws. When an update makes this test fail,
 * ktor brought TLS to these targets, and KtorTlsConnector can move to common code.
 */
class NativeTlsUnavailableTest {
    @Test
    fun upstreamKtorNetworkTlsHasNoTlsSessionsOnNativeTargets() = runBlocking {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                val selector = SelectorManager(Dispatchers.IO)
                val server = aSocket(selector).tcp().bind("127.0.0.1", 0)
                val socket = aSocket(selector).tcp().connect(server.localAddress)
                try {
                    val error = assertFailsWith<IllegalStateException> { socket.tls(Dispatchers.IO) }
                    assertEquals("TLS sessions are not supported on Native platform.", error.message)
                } finally {
                    socket.close()
                    server.close()
                    selector.close()
                }
            }
        }
    }
}
