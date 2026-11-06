package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.DatagramSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OSCORE through kcoap's CoAP client and server, against libcoap v4.3.5b over real UDP. Unlike
 * [LibcoapOscoreInteropTest], which drives raw sockets, this goes through the stack: retransmission,
 * the Echo retry of RFC 9175, Block-wise inside OSCORE and Observe with protected notifications.
 *
 * Q-Block (RFC 9177) under OSCORE, cases K11 and L11 of docs/plan-qblock-and-platforms.md: with the defaults
 * protected bodies use Inner Block1 and Block2 against libcoap with Q-Block on (`-L 7`), in both roles. With
 * `withMessageSecurity` on, libcoap 4.3.5b takes or sends only one protected response per request, which breaks
 * Q-Block2 bursts in both roles; the `upstream…` tests pin that.
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
        val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, CoapClientConfig { security = OscoreClient(context) })
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

    // ---------------------------------------------------------------- Q-Block (RFC 9177) under OSCORE

    /** libcoap's initial `/example_data`: 150 groups of a letter from a to z and `123456789`. */
    private val exampleData = buildString { repeat(150) { append('a' + it % 26).append("123456789") } }

    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    /** Starts `coap-server -L 7 -E /conf/server.conf` (Q-Block and OSCORE) at [port], and returns its container. */
    private fun startQBlockServer(port: Int): String {
        writeConf("server.conf", sender = "02", recipient = "01")
        val name = "kcoap-oscore-${UUID.randomUUID()}"
        containers += name
        run(
            "docker", "run", "-d", "--rm", "--name", name, "--network", "host", "-v", "${confDir.absolutePath}:/conf:ro", image,
            "coap-server", "-A", "127.0.0.1", "-p", "$port", "-L", "7", "-E", "/conf/server.conf", "-v", "7",
        )
        return name
    }

    /** The client's context towards [startQBlockServer]'s. */
    private suspend fun qBlockClientContext(): SecurityContext = ephemeralContext(
        SecurityContextParameters(secret.hexToBytes(), senderId = "01".hexToBytes(), recipientId = "02".hexToBytes(), masterSalt = salt.hexToBytes()),
        crypto,
    )

    /**
     * A kcoap client with OSCORE towards [startQBlockServer]'s contexts, blocks of 64 bytes, and [qBlock]. Clients
     * that share [context] share its Sender Sequence Number, so libcoap sees no replay.
     */
    private suspend fun qBlockClient(
        qBlock: QBlockClientConfig,
        context: SecurityContext? = null,
    ): Pair<CoapClient, RecordingClientSecurity> {
        val recording = RecordingClientSecurity(OscoreClient(context ?: qBlockClientContext()))
        val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, CoapClientConfig {
            security = recording
            preferredBlockSize = 64
            this.qBlock = qBlock
        })
        return client to recording
    }

    /**
     * K11 of docs/plan-qblock-and-platforms.md with the defaults: a kcoap client that probes for Q-Block keeps
     * protected requests on Inner Block1 and Block2 (`withMessageSecurity` is off), so it never asks libcoap's
     * `coap-server -L 7 -E` for Q-Block, and bodies both ways complete.
     */
    @Test
    fun k11ProtectedBodiesUseInnerBlockOptionsAgainstLibcoapWithQBlockByDefault() = runBlocking {
        assumeDocker()
        val port = freePort()
        val container = startQBlockServer(port)
        val (client, recording) = qBlockClient(QBlockClientConfig(QBlockMode.PROBE))
        val server = "coap://127.0.0.1:$port"
        val upload = (0 until 3000).joinToString("") { ('A' + it % 26).toString() }
        try {
            withTimeout(60.seconds) {
                waitUntilUp { client.ping(server) }
                // The first request meets libcoap's Appendix B.1.2 challenge; the client repeats it with Echo.
                val got = client.get("$server/example_data") { nonConfirmable() }
                assertEquals(exampleData, got.payloadAsText())
                val put = client.put("$server/example_data", upload.encodeToByteArray(), ContentFormat.TEXT_PLAIN) { nonConfirmable() }
                assertTrue(put.code.isSuccess, "PUT answered ${put.code}")
                assertEquals(upload, client.get("$server/example_data") { nonConfirmable() }.payloadAsText())
            }
            assertTrue(recording.requests.none { request -> request.options.any { it.number in qBlockOptions } }, "not even a probe")
            assertTrue(recording.requests.any { it.options.block2 != null } && recording.requests.any { it.options.block1 != null })
            assertEquals(emptyList(), recording.rejected.toList())
        } catch (e: Throwable) {
            System.err.println("libcoap log:\n${run("docker", "logs", container).takeLast(20_000)}")
            throw e
        } finally {
            client.close()
        }
    }

    /**
     * K11 with `withMessageSecurity = true`, pinning libcoap 4.3.5b: its server protects only the first response
     * to a request and logs "OSCORE: PDU could not be encrypted" for every further block of a Q-Block2 set, so each
     * request brings one block and the body crawls along on kcoap's NON_RECEIVE_TIMEOUT ladder instead of
     * arriving in sets. Q-Block1 uploads work, as each block's request gets one response (a 2.31 or the final
     * one): libcoap stores the body, read back by a client on the defaults (Inner Block2). When libcoap protects
     * bursts, the download completes in time and this test fails: then turn it into a passing K11.
     */
    @Test
    fun upstreamLibcoapCannotProtectQBlock2Bursts() = runBlocking {
        assumeDocker()
        val port = freePort()
        val container = startQBlockServer(port)
        val context = qBlockClientContext()
        val (client, recording) = qBlockClient(QBlockClientConfig(QBlockMode.PROBE, withMessageSecurity = true), context)
        val (reader, _) = qBlockClient(QBlockClientConfig(QBlockMode.PROBE), context)
        val server = "coap://127.0.0.1:$port"
        val upload = (0 until 3000).joinToString("") { ('A' + it % 26).toString() }
        try {
            waitUntilUp { client.ping(server) }
            val download = withTimeoutOrNull(12.seconds) { client.get("$server/example_data") { nonConfirmable() } }
            val log = run("docker", "logs", container)

            assertNull(download, "libcoap protects only one response per request, so the body does not arrive in sets")
            assertTrue("OSCORE: PDU could not be encrypted" in log, "libcoap could not protect the burst")
            val asked = recording.requests.filter { it.options.path == "/example_data" }
            val blocks = recording.responses.filter { it.options.qBlock2?.size == 64 }
            assertTrue(asked.isNotEmpty() && asked.all { it.options.qBlock2 != null }, "kcoap asked with Q-Block2 inside OSCORE")
            assertTrue(blocks.size in 1..asked.size, "one block per request: ${blocks.size} blocks for ${asked.size} requests")
            assertEquals(emptyList(), recording.rejected.toList(), "every block that came verified")

            val put = withTimeout(30.seconds) {
                client.put("$server/example_data", upload.encodeToByteArray(), ContentFormat.TEXT_PLAIN) { nonConfirmable() }
            }
            assertTrue(put.code.isSuccess, "PUT answered ${put.code}")
            // Every block went in Q-Block1; one may have gone again, on libcoap's 4.08 or a timer.
            val sent = recording.requests.mapNotNull { it.options.qBlock1?.number }
            assertTrue(sent.size >= 47 && (0L..46L).all { it in sent }, "the upload went in Q-Block1: $sent")
            val stored = withTimeout(30.seconds) { reader.get("$server/example_data") { nonConfirmable() } }
            assertEquals(upload, stored.payloadAsText(), "libcoap stored the body")
        } catch (e: Throwable) {
            System.err.println("libcoap log:\n${run("docker", "logs", container).takeLast(20_000)}")
            throw e
        } finally {
            client.close()
            reader.close()
        }
    }

    /** Runs `coap-client -L 7 -N -b 64 -E` (Q-Block, Non-confirmable, blocks of 64 bytes, OSCORE) for [uri]. */
    private fun runQBlockClient(uri: String): String {
        writeConf("client.conf", sender = "11", recipient = "12")
        return run(
            "docker", "run", "--rm", "--network", "host", "-v", "${confDir.absolutePath}:/conf", image,
            "coap-client", "-L", "7", "-N", "-b", "64", "-v", "7", "-E", "/conf/client.conf,/conf/client.seq", "-B", "30", uri,
        )
    }

    /** A kcoap server with OSCORE and Q-Block on, [withMessageSecurity] or not, serving 3000 bytes at `/big`. */
    private suspend fun qBlockServer(withMessageSecurity: Boolean, big: String): CoapServer {
        val context = ephemeralContext(
            SecurityContextParameters(secret.hexToBytes(), senderId = "12".hexToBytes(), recipientId = "11".hexToBytes(), masterSalt = salt.hexToBytes()),
            crypto,
        )
        val repository = InMemoryContextRepository().apply { add(context) }
        val qBlock = QBlockServerConfig(isEnabled = true, withMessageSecurity = withMessageSecurity)
        val transport = KtorUdpTransport.bind("127.0.0.1")
        return CoapServer(transport, scope, CoapServerConfig(security = OscoreServer(repository), qBlock = qBlock)) {
            resource("big") { get { CoapResponse.content(big) } }
        }.also { ports[it] = transport.localEndpoint.port }
    }

    /** The UDP port of each server [qBlockServer] started. */
    private val ports = HashMap<CoapServer, Int>()

    private fun uriOf(server: CoapServer, path: String): String = "coap://127.0.0.1:${ports.getValue(server)}$path"

    /**
     * L11 of docs/plan-qblock-and-platforms.md: libcoap's `coap-client -L 7 -E` probes kcoap with a protected
     * Q-Block2 request; a kcoap server with Q-Block on but `withMessageSecurity` off answers with an inner 4.02,
     * and libcoap falls back to Block2 inside OSCORE and gets the body. libcoap stalls for about 5 s while it
     * tests for Q-Block support ("timeout waiting for first response" before it reads the 4.02), whatever the
     * answer; the run is recorded on stderr.
     */
    @Test
    fun l11LibcoapFallsBackToBlock2WhenKcoapKeepsQBlockOutOfOscore() = runBlocking {
        assumeDocker()
        val big = (0 until 3000).joinToString("") { ('a' + it % 26).toString() }
        val server = qBlockServer(withMessageSecurity = false, big)
        try {
            val started = System.nanoTime()
            val output = runQBlockClient(uriOf(server, "/big"))
            val took = (System.nanoTime() - started).nanoseconds
            System.err.println("L11: coap-client took $took, its Q-Block test included")

            assertTrue("Unrecognized critical option 31" in output, "kcoap answered the protected probe with 4.02: $output")
            assertTrue("Q-Block support not available" in output)
            assertTrue(big in output, "libcoap got the whole body with Block2")
            assertTrue(took < 20.seconds, "took $took")
        } finally {
            server.close()
        }
    }

    /**
     * L11 with kcoap's `withMessageSecurity = true`, pinning libcoap 4.3.5b: its client drops the OSCORE
     * association of a request with the first response ("OSCORE: Security Context association not found"), so
     * it cannot verify block 1 of a Q-Block2 set, which carries a Partial IV of its own (RFC 8613 §8.3), and gives
     * up without the body. When libcoap takes several responses, the body arrives and this test fails: then turn
     * it into a passing L11.
     */
    @Test
    fun upstreamLibcoapClientCannotVerifyQBlock2Bursts() = runBlocking {
        assumeDocker()
        val big = (0 until 3000).joinToString("") { ('a' + it % 26).toString() }
        val server = qBlockServer(withMessageSecurity = true, big)
        try {
            val output = runQBlockClient(uriOf(server, "/big"))

            assertTrue("Q-Block support available" in output, "libcoap found Q-Block under OSCORE: $output")
            assertTrue("Security Context association not found" in output, "libcoap could not verify block 1")
            assertTrue(big !in output, "libcoap gave up without the body")
        } finally {
            server.close()
        }
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
