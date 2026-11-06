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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The production bundle in a real browser: a headless browser loads the page from the sample server, and the page
 * reads `lights` with OSCORE over the browser's WebSocket, with WebCrypto underneath, and reports what it read.
 *
 * Gradle runs it with `-Pkcoap.browser=<browser command>` (CI's js-consumer job, in headless Chrome), after building
 * the production bundle; without the property it skips, so a build without a browser still passes.
 */
class BrowserSmokeTest {
    @Test
    fun theProductionBundleReadsLightsWithOscoreOverTheBrowsersWebSocket() {
        val browser = System.getProperty("kcoap.browser")
        assumeTrue(!browser.isNullOrBlank()) { "-Pkcoap.browser=<browser command> runs the bundle in a browser" }
        val site = File(System.getProperty("kcoap.sample.site"))
        val work = File(System.getProperty("kcoap.browser.work")).apply { mkdirs() }
        assertTrue(File(site, "index.html").isFile && File(site, "js-consumer.js").isFile, "No production bundle in $site")

        runBlocking(Dispatchers.IO) {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val reported = CompletableDeferred<String>()
            val server = SampleServer.start(scope, site) { reported.complete(it) }
            val opened = HeadlessBrowser.open(browser, "http://127.0.0.1:${server.port}/", work)
            try {
                val outcome = withTimeoutOrNull(2.minutes) { reported.await() }
                    ?: fail("No report from $browser within 2 minutes. Its output:\n${opened.logTail()}")
                File(work, "report.txt").writeText(outcome)
                assertEquals("on", outcome, "what the page read; the browser's output:\n${opened.logTail()}")
            } finally {
                opened.close()
                server.close()
                scope.cancel()
            }
        }
    }
}
