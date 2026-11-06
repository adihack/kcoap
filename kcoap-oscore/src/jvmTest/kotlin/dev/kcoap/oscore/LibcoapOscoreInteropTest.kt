package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live OSCORE interop with libcoap (the reference implementation by RFC authors), over real UDP, with
 * libcoap in the `kcoap/libcoap` Docker image built from `interop/libcoap/Dockerfile`.
 *
 * Skipped when Docker or the image is not available.
 */
class LibcoapOscoreInteropTest {
    private val image = "kcoap/libcoap:v4.3.5b"
    private val crypto = DefaultCryptoBackend()
    private val secret = "0102030405060708090a0b0c0d0e0f10"
    private val salt = "9e7ca92223786340"
    private val localhost = InetAddress.getByName("127.0.0.1")
    private val containers = mutableListOf<String>()
    private val confDir: File = Files.createTempDirectory("kcoap-oscore").toFile()

    @AfterTest
    fun cleanUp() {
        containers.forEach { run("docker", "rm", "-f", it) }
        confDir.deleteRecursively()
    }

    @Test
    fun kcoapClientAgainstLibcoapServer() = runBlocking {
        assumeDocker()
        val port = freePort()
        writeConf("server.conf", sender = "02", recipient = "01")
        startContainer("coap-server", "-A", "127.0.0.1", "-p", "$port", "-E", "/conf/server.conf", "-v", "7")
        waitForServer(port)

        val context = ephemeralContext(
            SecurityContextParameters(secret.hexToBytes(), senderId = "01".hexToBytes(), recipientId = "02".hexToBytes(), masterSalt = salt.hexToBytes()),
            crypto,
        )
        val client = OscoreClient(context)
        val peer = Endpoint.Udp("127.0.0.1", port)
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            var request = CoapMessage.request(CoapCode.GET) {
                messageId = Random.nextInt(0, 0xFFFF)
                token = Random.nextBytes(4)
                options { uriPath("time") }
            }
            var response = exchange(socket, port, client.protectRequest(request, peer)!!)

            // RFC 8613 Appendix B.1.2: a freshly started libcoap server cannot trust its replay window,
            // so it answers the first request with a protected 4.01 carrying Echo. The client repeats
            // the request with that Echo value (RFC 9175 §2.4), which proves its freshness.
            val echo = response.options.echo
            assertEquals(CoapCode.UNAUTHORIZED, response.code, "B.1.2 challenge: ${response.payloadAsText()}")
            assertTrue(echo != null, "the challenge carries Echo")
            request = request.toBuilder().apply {
                messageId = (messageId + 1) and 0xFFFF
                options { echo(echo) }
            }.build()
            response = exchange(socket, port, client.protectRequest(request, peer)!!)
            if (response.code != CoapCode.CONTENT) dumpContainerLogs("libcoap-server")
            assertEquals(CoapCode.CONTENT, response.code, response.payloadAsText())
            assertTrue(response.payloadSize > 0, "/time returns the server time")

            // The replay window is now initialized: the next request goes through directly.
            request = request.toBuilder().apply {
                messageId = (messageId + 1) and 0xFFFF
                options { remove(dev.kcoap.coap.CoapOptions.ECHO.number) }
            }.build()
            assertEquals(CoapCode.CONTENT, exchange(socket, port, client.protectRequest(request, peer)!!).code)
        }
    }

    @Test
    fun libcoapClientAgainstKcoapServer() = runBlocking {
        assumeDocker()
        // A restarted server: its store knows the context, so libcoap's client must answer the
        // Appendix B.1.2 Echo challenge (FreshnessPolicy.AUTO).
        val serverContext = restoredContext(
            SecurityContextParameters(secret.hexToBytes(), senderId = "12".hexToBytes(), recipientId = "11".hexToBytes(), masterSalt = salt.hexToBytes()),
            crypto,
        )
        val server = OscoreServer(InMemoryContextRepository().apply { add(serverContext) })
        writeConf("client.conf", sender = "11", recipient = "12")

        DatagramSocket(0, localhost).use { socket ->
            val received = mutableListOf<String>()
            val responder = thread(isDaemon = true) { serve(socket, server, received) }

            val get = runClient("-m", "get", "coap://127.0.0.1:${socket.localPort}/hello")
            assertEquals("hello from kcoap", get.trim())
            val put = runClient("-m", "put", "-e", "ping", "coap://127.0.0.1:${socket.localPort}/echo")
            assertEquals("echo: ping", put.trim())
            assertEquals(listOf("challenge", "GET /hello", "PUT /echo"), synchronized(received) { received.toList() })
            socket.close()
            responder.join(2000)
        }
    }

    /** A minimal OSCORE responder: piggybacked answers for /hello and /echo. */
    private fun serve(socket: DatagramSocket, server: OscoreServer, log: MutableList<String>) {
        val buffer = ByteArray(2048)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            val request = UdpMessageCodec.decode(packet.data.copyOf(packet.length))
            val peer = Endpoint.Udp(packet.address.hostAddress, packet.port)
            val answer = runBlocking {
                val verified = try {
                    server.unprotectRequest(request, peer)
                } catch (e: dev.kcoap.coap.security.MessageSecurityException) {
                    // RFC 8613 §8.2: OSCORE errors go back unprotected, with Max-Age 0.
                    return@runBlocking CoapMessage.response(request, e.responseCode) {
                        type = CoapMessageType.ACK
                        messageId = request.messageId
                        options { maxAge(0) }
                        payload(e.diagnostic ?: "")
                    }
                } ?: return@runBlocking CoapMessage.response(request, CoapCode.UNAUTHORIZED) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                }
                val inner = verified.message
                verified.challenge?.let { challenge ->
                    synchronized(log) { log += "challenge" }
                    return@runBlocking verified.protectResponse(
                        challenge.toBuilder().apply {
                            type = CoapMessageType.ACK
                            messageId = request.messageId
                        }.build(),
                    )
                }
                synchronized(log) { log += "${inner.code.description} ${inner.options.path}" }
                val text = when (inner.options.path) {
                    "/hello" -> "hello from kcoap"
                    "/echo" -> "echo: ${inner.payloadAsText()}"
                    else -> "?"
                }
                verified.protectResponse(
                    CoapMessage.response(inner, CoapCode.CONTENT) {
                        type = CoapMessageType.ACK
                        messageId = request.messageId
                        payload(text)
                    },
                )
            }
            val bytes = UdpMessageCodec.encode(answer)
            socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
        }
    }

    /**
     * Sends a protected request and returns its decrypted response, acknowledging separate (CON)
     * responses and skipping anything for another token, as a CoAP client must (RFC 7252 §5.2.2).
     */
    private suspend fun exchange(socket: DatagramSocket, port: Int, protected: dev.kcoap.coap.security.ProtectedRequest): CoapMessage {
        val bytes = UdpMessageCodec.encode(protected.message)
        socket.send(DatagramPacket(bytes, bytes.size, localhost, port))
        val buffer = ByteArray(2048)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val message = UdpMessageCodec.decode(packet.data.copyOf(packet.length))
            if (message.type == CoapMessageType.CON) {
                val ack = UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.ACK, message.messageId))
                socket.send(DatagramPacket(ack, ack.size, localhost, port))
            }
            if (message.isEmpty || !message.hasToken(protected.message.token)) continue
            // ADR 0008: the stack acts only on verified responses, so libcoap's answers, the B.1.2 challenge included, must verify.
            return assertIs<ResponseVerification.Verified>(protected.unprotectResponse(message), "verified").message
        }
    }

    private fun waitForServer(port: Int) {
        // An Empty CON is a CoAP ping (RFC 7252 §4.3); a running server answers with a Reset.
        DatagramSocket().use { socket ->
            socket.soTimeout = 200
            val ping = UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.CON, 1))
            repeat(50) {
                socket.send(DatagramPacket(ping, ping.size, localhost, port))
                try {
                    socket.receive(DatagramPacket(ByteArray(64), 64))
                    return
                } catch (_: SocketTimeoutException) {
                    // not up yet
                }
            }
            error("libcoap server did not start on port $port")
        }
    }

    private fun writeConf(name: String, sender: String, recipient: String, extra: String = "") {
        File(confDir, name).writeText(
            """
            master_secret,hex,"$secret"
            master_salt,hex,"$salt"
            sender_id,hex,"$sender"
            recipient_id,hex,"$recipient"
            $extra
            """.trimIndent(),
        )
    }

    private fun startContainer(vararg command: String) {
        val id = run("docker", "run", "-d", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf:ro", image, *command).trim()
        containers += id
    }

    /** Keeps libcoap's debug log next to the test results, for diagnosing interop failures. */
    private fun dumpContainerLogs(name: String) {
        val dir = File("build/interop-logs").apply { mkdirs() }
        containers.forEach { File(dir, "$name.log").writeText(run("docker", "logs", it)) }
    }

    private fun stopContainers() {
        containers.forEach { run("docker", "rm", "-f", it) }
        containers.clear()
    }

    /** Each run is a new process, so the client keeps its Sender Sequence Number in a file (RFC 8613 B.1.1). */
    private fun runClient(vararg args: String): String =
        run(
            "docker", "run", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf", image,
            "coap-client", "-E", "/conf/client.conf,/conf/client.seq", "-B", "5", *args,
        )

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return output
    }

    private fun freePort(): Int = DatagramSocket(0, localhost).use { it.localPort }

    private fun assumeDocker() {
        val available = try {
            ProcessBuilder("docker", "image", "inspect", image).start().waitFor(30, TimeUnit.SECONDS) &&
                ProcessBuilder("docker", "image", "inspect", image).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        assumeTrue(available, "Docker with $image is needed for libcoap interop")
    }
}
