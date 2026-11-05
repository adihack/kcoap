package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.server.cio.CIO
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * OSCORE (RFC 8613) over CoAP over TCP and WebSockets (RFC 8323) through kcoap's client and server, against
 * libcoap v4.3.5b both ways. OSCORE sits above the link the endpoint picks for the URI's scheme (ADR 0005), so
 * the exchanges [StackOscoreInteropTest] runs over UDP run on connections here: the freshness challenge of
 * Appendix B.1.2, asserted on each transport in both roles with a fresh server each time (the challenge and its
 * Echo travel encrypted, so [RecordingClientSecurity] and [RecordingServerSecurity] record them), Block-wise
 * inside OSCORE, a resource created and read back, and Observe with protected notifications. libcoap reads
 * WebSocket frames into a 1472-byte buffer whatever its CSM announces, so its Max-Message-Size is held at 1152
 * over WebSockets (`-X 1152`). Skipped when docker or the image is missing.
 */
class ReliableOscoreInteropTest {
    private val image = System.getenv("KCOAP_LIBCOAP_IMAGE") ?: "kcoap/libcoap:v4.3.5b"
    private val crypto = DefaultCryptoBackend()
    private val secret = "0102030405060708090a0b0c0d0e0f10"
    private val salt = "9e7ca92223786340"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val containers = mutableListOf<String>()
    private val confDir: File = Files.createTempDirectory("kcoap-reliable-oscore").toFile()

    @AfterTest
    fun cleanUp() {
        containers.forEach { run("docker", "rm", "-f", it) }
        scope.cancel()
        confDir.deleteRecursively()
    }

    @Test
    fun kcoapClientWithOscoreOverTcpAndWebSocketsAgainstLibcoapServer() = runBlocking {
        assumeDocker()
        writeConf("server.conf", sender = "02", recipient = "01")
        for (name in listOf("tcp", "ws")) {
            // A libcoap server of its own per transport: libcoap keeps one replay window per context, so only the
            // first protected request it ever gets meets the Appendix B.1.2 challenge.
            val port = freeUdpAndTcpPort()
            val webSocketPort = ServerSocket(0).use { it.localPort }
            val container = run(
                "docker", "run", "-d", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf:ro", image,
                "coap-server", "-A", "127.0.0.1", "-p", "$port", "-w", "$webSocketPort", "-E", "/conf/server.conf", "-d", "5",
                "-X", "1152", "-v", "4",
            ).trim()
            containers += container
            val base = if (name == "tcp") "coap+tcp://127.0.0.1:$port" else "coap+ws://127.0.0.1:$webSocketPort"
            val context = ephemeralContext(
                SecurityContextParameters(secret.hexToBytes(), senderId = "01".hexToBytes(), recipientId = "02".hexToBytes(), masterSalt = salt.hexToBytes()),
                crypto,
            )
            val recording = RecordingClientSecurity(OscoreClient(context))
            val client = CoapClient(listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope, CoapClientConfig(security = recording))
            withTimeout(60.seconds) {
                waitUntilUp { client.ping(base) }
                val time = client.get("$base/time")
                assertEquals(CoapCode.CONTENT, time.code, "$base: ${time.payloadAsText()}")
                assertTrue(time.payloadSize > 0)
                // Appendix B.1.2: libcoap answered the first protected request with a protected 4.01 carrying Echo,
                // and the client repeated it with that Echo (RFC 9175 §2.3) before it got the time.
                val challenge = recording.responses.first()
                assertEquals(CoapCode.UNAUTHORIZED, challenge.code, "$base: ${recording.responses}")
                val echo = assertNotNull(challenge.options.echo, "$base: the challenge carries Echo")
                val (first, repeated) = recording.requests.take(2)
                assertEquals(listOf("/time", "/time"), listOf(first.options.path, repeated.options.path), base)
                assertNull(first.options.echo, base)
                assertContentEquals(echo, repeated.options.echo, "$base: the repeated request carries the challenge's Echo")
                assertEquals(CoapCode.CONTENT, recording.responses[1].code, base)

                // A resource created and read back under OSCORE (coap-server -d allows dynamic resources).
                val path = "$base/kcoap-$name"
                assertEquals(CoapCode.CREATED, client.put(path, "protected".encodeToByteArray(), ContentFormat.TEXT_PLAIN).code, base)
                assertEquals("protected", client.get(path).payloadAsText(), base)

                // Observe on the connection: protected notifications, ordered by their Partial IVs.
                assertEquals(2, client.observe("$base/time").take(2).toList().size, base)
            }
            assertEquals(1, recording.responses.count { it.code == CoapCode.UNAUTHORIZED }, "$base: one challenge")
            client.close()
            run("docker", "rm", "-f", container)
        }
    }

    @Test
    fun libcoapClientWithOscoreOverTcpAndWebSocketsAgainstKcoapServer() = runBlocking {
        assumeDocker()
        writeConf("client.conf", sender = "11", recipient = "12")
        val tcpListener = KtorTcpListener.bind("127.0.0.1")
        val webSocketListener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
        val tcp = "coap+tcp://127.0.0.1:${tcpListener.localEndpoint.port}"
        val webSocket = webSocketListener.localEndpoint!!.uri.replace("ws://", "coap+ws://").removeSuffix("/.well-known/coap")

        val transports = listOf(Triple(tcpListener, tcp, emptyArray<String>()), Triple(webSocketListener, webSocket, arrayOf("-X", "1152")))
        for ((listener, base, limit) in transports) {
            // A restarted server per transport, whose context challenges libcoap's first request (Appendix B.1.2).
            val parameters = SecurityContextParameters(
                secret.hexToBytes(), senderId = "12".hexToBytes(), recipientId = "11".hexToBytes(), masterSalt = salt.hexToBytes(),
            )
            val repository = InMemoryContextRepository().apply { add(restoredContext(parameters, crypto)) }
            val server = KcoapServer(listener, RecordingServerSecurity(OscoreServer(repository)))
            assertEquals("hello over OSCORE", runClient(*limit, "-m", "get", "$base/hello").trim(), base)
            // Appendix B.1.2: the server answered libcoap's first request with a protected 4.01 carrying Echo, and
            // libcoap repeated it with that Echo before it got the answer.
            val (first, repeated) = server.security.requests.take(2)
            val paths = listOf(first.message.options.path, repeated.message.options.path)
            assertEquals(listOf("/hello", "/hello"), paths, "$base: ${server.security.requests}")
            val challenge = assertNotNull(first.challenge, "$base: the first request is challenged")
            assertEquals(CoapCode.UNAUTHORIZED, challenge.code, base)
            assertNull(first.message.options.echo, base)
            val echo = assertNotNull(challenge.options.echo, "$base: the challenge carries Echo")
            assertContentEquals(echo, repeated.message.options.echo, "$base: the repeat carries its Echo")
            assertNull(repeated.challenge, "$base: the repeat proved freshness")

            // Block2 inside OSCORE: every block is protected on its own.
            assertEquals(server.big, runClient(*limit, "-m", "get", "-b", "64", "$base/big").trim(), base)
            // Block1 inside OSCORE.
            val upload = (0 until 300).joinToString("") { ('A' + (it + base.length) % 26).toString() }
            runClient(*limit, "-m", "put", "-b", "64", "-e", upload, "$base/store")
            assertEquals(upload, server.stored, base)
            // Observe: coap-client prints each verified notification's payload.
            val output = runClient(*limit, "-s", "3", "$base/counter")
            assertTrue(Regex("count \\d+").findAll(output).count() >= 2, "$base: notifications in $output")
            assertEquals(1, server.security.requests.count { it.challenge != null }, "$base: one challenge")
            server.close()
        }
    }

    /**
     * A kcoap server with OSCORE [security] on [listener] alone; with a context as after a restart, it does not
     * trust its replay window until a request proves its freshness with Echo (Appendix B.1.2). `/counter` changes
     * every 500 ms.
     */
    private inner class KcoapServer(listener: ConnectionListener, val security: RecordingServerSecurity) {
        val big = (0 until 2000).joinToString("") { ('a' + it % 26).toString() }

        @Volatile
        var stored = ""

        private val server: CoapServer
        private val ticks: Job

        init {
            val counter = object : ObservableResource("counter") {
                var value = 0

                override suspend fun handleGet(context: RequestContext) = CoapResponse.content("count ${value++}")
            }
            server = CoapServer(listOf(listener), scope, CoapServerConfig(security = security)) {
                resource("hello") {
                    get {
                        if (securityIdentity is OscoreIdentity) {
                            CoapResponse.content("hello over OSCORE")
                        } else {
                            CoapResponse.error(CoapCode.UNAUTHORIZED)
                        }
                    }
                }
                resource("big") { get { CoapResponse.content(big) } }
                resource("store") {
                    put {
                        stored = payloadAsText()
                        CoapResponse.changed()
                    }
                }
                add(counter)
            }
            ticks = scope.launch {
                while (isActive) {
                    delay(500.milliseconds)
                    counter.notifyObservers()
                }
            }
        }

        suspend fun close() {
            ticks.cancel()
            server.close()
        }
    }

    private suspend fun waitUntilUp(ping: suspend () -> Boolean) {
        repeat(50) {
            if (withTimeoutOrNull(2.seconds) { runCatching { ping() }.getOrDefault(false) } == true) return
            delay(200.milliseconds)
        }
        error("libcoap server did not come up")
    }

    private fun writeConf(name: String, sender: String, recipient: String) {
        File(confDir, name).writeText(
            """
            master_secret,hex,"$secret"
            master_salt,hex,"$salt"
            sender_id,hex,"$sender"
            recipient_id,hex,"$recipient"
            """.trimIndent() + "\n", // libcoap ignores a last line without a newline
        )
    }

    /** Each run is a new process, so the client keeps its Sender Sequence Number in a file (RFC 8613 B.1.1). */
    private fun runClient(vararg args: String): String =
        run(
            "docker", "run", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf", image,
            "coap-client", "-E", "/conf/client.conf,/conf/client.seq", "-B", "10", *args,
        )

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(90, TimeUnit.SECONDS)
        return output
    }

    /** A port free for both UDP and TCP, since `coap-server -p` listens on both (RFC 8323 §8.1 shares 5683). */
    private fun freeUdpAndTcpPort(): Int {
        while (true) {
            val port = DatagramSocket(0).use { it.localPort }
            if (runCatching { ServerSocket(port).close() }.isSuccess) return port
        }
    }

    private fun assumeDocker() {
        val available = try {
            ProcessBuilder("docker", "image", "inspect", image).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        assumeTrue(available, "Docker with $image is needed for libcoap interop")
    }
}
