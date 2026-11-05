package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.ktor.KtorUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * OSCORE through kcoap's CoAP client and server, against libcoap v4.3.5b over real UDP. Unlike
 * [LibcoapOscoreInteropTest], which drives raw sockets, this goes through the stack: retransmission,
 * the Echo retry of RFC 9175, Block-wise inside OSCORE and Observe with protected notifications.
 */
class StackOscoreInteropTest {
    private val image = "kcoap/libcoap:v4.3.5b"
    private val crypto = DefaultCryptoBackend()
    private val secret = "0102030405060708090a0b0c0d0e0f10"
    private val salt = "9e7ca92223786340"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val containers = mutableListOf<String>()
    private val confDir: File = Files.createTempDirectory("kcoap-stack-oscore").toFile()

    @AfterTest
    fun cleanUp() {
        containers.forEach { run("docker", "rm", "-f", it) }
        scope.cancel()
        confDir.deleteRecursively()
    }

    @Test
    fun kcoapClientWithOscoreAgainstLibcoapServer() = runBlocking {
        assumeDocker()
        val port = freePort()
        writeConf("server.conf", sender = "02", recipient = "01")
        containers += run(
            "docker", "run", "-d", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf:ro", image,
            "coap-server", "-A", "127.0.0.1", "-p", "$port", "-E", "/conf/server.conf", "-d", "5", "-v", "4",
        ).trim()

        val context = ephemeralContext(
            SecurityContextParameters(secret.hexToBytes(), senderId = "01".hexToBytes(), recipientId = "02".hexToBytes(), masterSalt = salt.hexToBytes()),
            crypto,
        )
        val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, CoapClientConfig(security = OscoreClient(context)))
        val server = "coap://127.0.0.1:$port"
        withTimeout(60.seconds) {
            waitUntilUp { client.ping(server) }
            // The first request meets libcoap's Appendix B.1.2 challenge; the client repeats it with Echo.
            val time = client.get("$server/time")
            assertEquals(CoapCode.CONTENT, time.code, time.payloadAsText())
            assertTrue(time.payloadSize > 0)

            // A resource created and read back under OSCORE (coap-server -d allows dynamic resources).
            assertEquals(CoapCode.CREATED, client.put("$server/kcoap", "protected".encodeToByteArray(), ContentFormat.TEXT_PLAIN).code)
            assertEquals("protected", client.get("$server/kcoap").payloadAsText())

            // Observe: protected notifications, ordered by their Partial IVs.
            val ticks = client.observe("$server/time").take(2).toList()
            assertEquals(2, ticks.size)
        }
        client.close()
    }

    @Test
    fun libcoapClientWithOscoreAgainstKcoapServer() = runBlocking {
        assumeDocker()
        // A restarted server, which challenges libcoap's first request (Appendix B.1.2).
        val context = restoredContext(
            SecurityContextParameters(secret.hexToBytes(), senderId = "12".hexToBytes(), recipientId = "11".hexToBytes(), masterSalt = salt.hexToBytes()),
            crypto,
        )
        val repository = InMemoryContextRepository().apply { add(context) }
        val big = (0 until 2000).joinToString("") { ('a' + it % 26).toString() }
        var stored = ""
        val counter = object : ObservableResource("counter") {
            var value = 0
            override suspend fun handleGet(context: dev.kcoap.coap.server.RequestContext) = CoapResponse.content("count ${value++}")
        }
        val transport = KtorUdpTransport.bind("127.0.0.1")
        val server = CoapServer(transport, scope, CoapServerConfig(security = OscoreServer(repository))) {
            resource("hello") {
                get {
                    if (securityIdentity is OscoreIdentity) CoapResponse.content("hello over OSCORE") else CoapResponse.error(CoapCode.UNAUTHORIZED)
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
        scope.launch {
            while (isActive) {
                delay(500)
                counter.notifyObservers()
            }
        }
        val base = "coap://127.0.0.1:${transport.localEndpoint.port}"
        writeConf("client.conf", sender = "11", recipient = "12")

        // libcoap answers kcoap's freshness challenge (B.1.2) on its own.
        assertEquals("hello over OSCORE", runClient("-m", "get", "$base/hello").trim())
        // Block2 inside OSCORE: every block is protected on its own.
        assertEquals(big, runClient("-m", "get", "-b", "64", "$base/big").trim())
        // Block1 inside OSCORE.
        val upload = (0 until 300).joinToString("") { ('A' + it % 26).toString() }
        runClient("-m", "put", "-b", "64", "-e", upload, "$base/store")
        assertEquals(upload, stored)
        // Observe: coap-client prints each verified notification's payload, back to back.
        val output = runClient("-s", "3", "$base/counter")
        val notifications = Regex("count \\d+").findAll(output).count()
        assertTrue(notifications >= 2, "notifications in: $output")
        server.close()
    }

    private suspend fun waitUntilUp(ping: suspend () -> Boolean) {
        repeat(50) {
            if (runCatching { ping() }.getOrDefault(false)) return
            delay(200)
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

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    private fun assumeDocker() {
        val available = try {
            ProcessBuilder("docker", "image", "inspect", image).start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
        assumeTrue(available, "Docker with $image is needed for libcoap interop")
    }
}
