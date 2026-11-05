package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * This module's js tests in a real browser, against a JVM listener: [JsWebSocketSubprotocolTest] checks
 * there that the browser's WebSocket requests the subprotocol coap from a [KtorWebSocketListener] on CIO
 * and that the connector reads it back from the negotiated protocol (RFC 8323 §4.1); the other js tests
 * run alongside. The page is the js test executable, loaded by a small AMD loader and run by mocha
 * (`browser-tests.html`), served from a ktor server here that also collects the results.
 *
 * Gradle runs it with `-Pkcoap.browser=<browser command>` (`scripts/test browser`; CI's browser job, in
 * headless Chrome), after compiling the js tests and installing mocha; without the property it skips.
 */
class BrowserAgainstJvmListenerTest {
    @Test
    fun rfc8323Section4_1ABrowserNegotiatesCoapWithAJvmListener() {
        val browser = System.getProperty("kcoap.browser")
        assumeTrue(!browser.isNullOrBlank()) { "-Pkcoap.browser=<browser command> runs the js tests in a browser" }
        val tests = File(System.getProperty("kcoap.browser.tests"))
        val mocha = File(System.getProperty("kcoap.browser.mocha"))
        val work = File(System.getProperty("kcoap.browser.work")).apply { mkdirs() }
        val module = File(tests, System.getProperty("kcoap.browser.module"))
        assertTrue(module.isFile, "No js test module $module")
        val page = javaClass.getResource("browser-tests.html")!!.readText()

        runBlocking(Dispatchers.IO) {
            val listener = KtorWebSocketListener.start(CIO, "127.0.0.1", 0)
            val accepted = AtomicInteger()
            val plainOffers = ConcurrentLinkedQueue<String>()
            val report = CompletableDeferred<String>()
            val files = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                install(WebSockets)
                routing {
                    get("/") { call.respondText(page, ContentType.Text.Html) }
                    get("/mocha.js") { call.respondFile(mocha) }
                    staticFiles("/kotlin", tests)
                    post("/report") {
                        report.complete(call.receiveText())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    // A WebSocket server that selects no subprotocol, for the refusal check.
                    webSocket("/plain") {
                        plainOffers += call.request.headers[HttpHeaders.SecWebSocketProtocol].orEmpty()
                        incoming.receiveCatching()
                    }
                }
            }
            files.startSuspend(wait = false)
            coroutineScope {
                // The listener's side: accept every WebSocket and echo its messages.
                val echo = launch {
                    while (true) {
                        val connection = listener.accept() as MessageConnection? ?: break
                        accepted.incrementAndGet()
                        launch { while (true) connection.send(connection.receive() ?: break) }
                    }
                }
                val port = files.engine.resolvedConnectors().first().port
                val url = "http://127.0.0.1:$port/?module=${module.name.encoded()}" +
                    "&coap=${listener.localEndpoint!!.uri.encoded()}&plain=${"ws://127.0.0.1:$port/plain".encoded()}"
                val opened = HeadlessBrowser.open(browser, url, work)
                try {
                    val text = withTimeoutOrNull(3.minutes) { report.await() }
                        ?: fail("No report from $browser within 3 minutes. Its output:\n${opened.logTail()}")
                    File(work, "report.txt").writeText(text)
                    val lines = text.lines()
                    val failures = lines.filter { it.startsWith("fail") || it.startsWith("error") }
                    assertTrue(failures.isEmpty(), "js tests failed in $browser:\n${failures.joinToString("\n")}")
                    val passed = lines.filter { it.startsWith("pass\t") }.map { it.substringAfter('\t') }
                    for (name in SUBPROTOCOL_TESTS) {
                        assertTrue(passed.any { it.endsWith("JsWebSocketSubprotocolTest $name") }, "$name in $passed")
                    }
                    // The listener only routes handshakes that ask for coap: the raw session and the connector's.
                    assertEquals(2, accepted.get(), "WebSockets the JVM listener accepted")
                    withTimeout(5.seconds) { while (plainOffers.isEmpty()) delay(20) }
                    assertEquals(listOf("coap"), plainOffers.toList(), "the browser asked the plain server for coap")
                } finally {
                    opened.close()
                    echo.cancelAndJoin()
                    files.stopSuspend(0, 1000)
                    listener.close()
                }
            }
        }
    }

    private fun String.encoded(): String = URLEncoder.encode(this, Charsets.UTF_8)

    private companion object {
        val SUBPROTOCOL_TESTS = listOf(
            "rfc8323Section4_1TheConnectorRequestsCoapAndReadsItBackFromTheNegotiatedProtocol",
            "rfc8323Section4_1AServerThatSelectsNoSubprotocolIsRefused",
        )
    }
}
