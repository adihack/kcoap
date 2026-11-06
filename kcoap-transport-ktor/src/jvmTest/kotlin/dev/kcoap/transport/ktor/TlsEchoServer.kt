package dev.kcoap.transport.ktor

import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * A TLS server on the JDK's own TLS stack (JSSE) at 127.0.0.1, which sends [chain] and echoes every byte it
 * receives: an implementation independent of the ktor-network-tls client under test.
 */
class TlsEchoServer(
    chain: List<X509Certificate>,
    key: PrivateKey,
    protocols: Array<String> = arrayOf("TLSv1.2", "TLSv1.3"),
) : AutoCloseable {
    private val server: SSLServerSocket

    /** The server name each client asked for (SNI, RFC 6066 §3), or null when it sent none. */
    val serverNames: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** How many connections were accepted, whether their handshake succeeded or not. */
    @Volatile
    var accepted: Int = 0
        private set

    init {
        val password = "test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", key, password, chain.toTypedArray())
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, password) }.keyManagers
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        server = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = protocols
        thread(isDaemon = true, name = "tls-echo-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (e: Exception) {
                    break
                }
                accepted++
                thread(isDaemon = true, name = "tls-echo") { echo(socket) }
            }
        }
    }

    val port: Int get() = server.localPort

    private fun echo(socket: SSLSocket) {
        socket.use {
            try {
                socket.startHandshake()
                val names = (socket.session as ExtendedSSLSession).requestedServerNames
                serverNames += names.filterIsInstance<SNIHostName>().firstOrNull()?.asciiName
                val input = socket.inputStream
                val output = socket.outputStream
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (e: Exception) {
                // A refused handshake or a client that went away: the test looks at the client's side.
            }
        }
    }

    override fun close() {
        server.close()
    }
}
