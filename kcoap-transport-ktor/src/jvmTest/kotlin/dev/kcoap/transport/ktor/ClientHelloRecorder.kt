package dev.kcoap.transport.ktor

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A TCP server at 127.0.0.1 that reads the first TLS ClientHello a client sends (RFC 5246 §7.4.1.2), keeps the
 * cipher suites and extension types it offers, and closes the connection: what a client offers, whatever a
 * server would choose from it.
 */
class ClientHelloRecorder : AutoCloseable {
    /** A ClientHello as offered: the cipher suite and extension type codes, in the client's order. */
    class ClientHello(val cipherSuites: List<Int>, val extensions: List<Int>)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @Volatile
    var hello: ClientHello? = null
        private set

    init {
        thread(isDaemon = true, name = "client-hello") {
            try {
                server.accept().use { socket -> hello = read(DataInputStream(socket.getInputStream())) }
            } catch (e: Exception) {
                // Closed before a client came; the test finds no ClientHello.
            }
        }
    }

    val port: Int get() = server.localPort

    override fun close() {
        server.close()
    }

    private fun read(input: DataInputStream): ClientHello {
        // TLSPlaintext: ContentType handshake (22), ProtocolVersion, length; then the Handshake header.
        check(input.readUnsignedByte() == HANDSHAKE) { "not a handshake record" }
        input.readUnsignedShort()
        val record = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
        val hello = DataInputStream(record.inputStream())
        check(hello.readUnsignedByte() == CLIENT_HELLO) { "not a ClientHello" }
        hello.skipBytes(3 + 2 + 32) // length, client_version, random
        hello.skipBytes(hello.readUnsignedByte()) // session_id
        val suites = List(hello.readUnsignedShort() / 2) { hello.readUnsignedShort() }
        hello.skipBytes(hello.readUnsignedByte()) // compression_methods
        val extensions = mutableListOf<Int>()
        if (hello.available() > 0) {
            var remaining = hello.readUnsignedShort()
            while (remaining > 0) {
                extensions += hello.readUnsignedShort()
                val length = hello.readUnsignedShort()
                hello.skipBytes(length)
                remaining -= 4 + length
            }
        }
        return ClientHello(suites, extensions)
    }

    private companion object {
        const val HANDSHAKE = 22
        const val CLIENT_HELLO = 1
    }
}
