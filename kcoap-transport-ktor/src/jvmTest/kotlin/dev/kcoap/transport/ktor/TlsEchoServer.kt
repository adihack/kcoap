package dev.kcoap.transport.ktor

import java.io.InputStream
import java.io.OutputStream
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
 * A TLS server on the JDK's own TLS stack (JSSE) at 127.0.0.1 and [atPort] (0 for a free one), which sends [chain]
 * and then runs [conversation] over each connection, by default echoing every byte it receives: an implementation
 * independent of the ktor-network-tls client under test.
 *
 * @throws java.net.BindException when [atPort] is taken.
 */
class TlsEchoServer(
    chain: List<X509Certificate>,
    key: PrivateKey,
    protocols: Array<String> = arrayOf("TLSv1.2", "TLSv1.3"),
    atPort: Int = 0,
    private val conversation: (InputStream, OutputStream) -> Unit = ::echo,
) : AutoCloseable {
    private val server: SSLServerSocket

    /** The server name each client asked for (SNI, RFC 6066 §3), or null when it sent none. */
    val serverNames: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** The cipher suite of each completed handshake. */
    val cipherSuites: MutableList<String> = Collections.synchronizedList(mutableListOf())

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
        server = context.serverSocketFactory.createServerSocket(atPort, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        server.enabledProtocols = protocols
        thread(isDaemon = true, name = "tls-echo-accept") {
            while (!server.isClosed) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (e: Exception) {
                    break
                }
                accepted++
                thread(isDaemon = true, name = "tls-echo") { serve(socket) }
            }
        }
    }

    val port: Int get() = server.localPort

    private fun serve(socket: SSLSocket) {
        socket.use {
            try {
                socket.startHandshake()
                val names = (socket.session as ExtendedSSLSession).requestedServerNames
                serverNames += names.filterIsInstance<SNIHostName>().firstOrNull()?.asciiName
                cipherSuites += socket.session.cipherSuite
                conversation(socket.inputStream, socket.outputStream)
            } catch (e: Exception) {
                // A refused handshake or a client that went away: the test looks at the client's side.
            }
        }
    }

    override fun close() {
        server.close()
    }
}

/** Sends back every byte received, until the client closes. */
private fun echo(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(4096)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        output.write(buffer, 0, read)
        output.flush()
    }
}
