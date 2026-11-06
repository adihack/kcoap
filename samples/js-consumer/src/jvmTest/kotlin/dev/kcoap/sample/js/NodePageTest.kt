package dev.kcoap.sample.js

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The production bundle, minified as browsers get it, on Node.js against the sample server: the page's whole code
 * path, from asking for an OSCORE session to reporting what it read, without a browser (node-page.js stands in for
 * the page). It catches what only the minified bundle shows, such as code that webpack or the Kotlin/JS compiler
 * dropped, and what WebCrypto and fetch do differently from the JVM. The browser's own WebSocket runs only in
 * [BrowserSmokeTest].
 *
 * Gradle passes Node.js (the one the Kotlin plugin set up) and the bundle; run from elsewhere, it skips.
 */
class NodePageTest {
    @Test
    fun theProductionBundleReadsLightsWithOscoreOnNodeJs() {
        val node = System.getProperty("kcoap.node")
        assumeTrue(!node.isNullOrBlank()) { "Gradle's jvmTest passes Node.js and the production bundle" }
        val site = File(System.getProperty("kcoap.sample.site"))
        val bundle = File(site, "js-consumer.js")
        val page = Files.createTempFile("node-page", ".js").toFile().apply {
            writeText(NodePageTest::class.java.getResource("node-page.js")!!.readText())
            deleteOnExit()
        }
        val log = Files.createTempFile("node-page", ".log").toFile().apply { deleteOnExit() }

        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val reported = CompletableDeferred<String>()
            val server = SampleServer.start(scope, site) { reported.complete(it) }
            val process = ProcessBuilder(node, page.path, bundle.path, "http://127.0.0.1:${server.port}")
                .apply { environment()["NODE_PATH"] = System.getProperty("kcoap.node.modules") }
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            try {
                val outcome = withTimeoutOrNull(90.seconds) { reported.await() }
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                val output = log.readLines().filter { it.length < 400 }.takeLast(30).joinToString("\n")
                assertEquals("on", outcome, "what the page read; Node.js printed:\n$output")
                assertEquals(true, finished && process.exitValue() == 0, "Node.js exits with 0 once the page shows 'on':\n$output")
            } finally {
                process.destroyForcibly()
                server.close()
                scope.cancel()
            }
        }
    }
}
