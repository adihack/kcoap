package dev.kcoap.sample.js

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/**
 * The page's code path on the JVM, against the sample server: what the browser does, without a browser. The
 * WebSocket connector runs on ktor's CIO client here and on the browser's WebSocket in the page; the protocol code
 * is the same common code.
 */
class ReadLightsTest {
    @Test
    fun rfc8613APageReadsLightsWithTheOscoreSessionTheServerGaveIt() = withServer { server, scope ->
        val session = OscoreSession.decode(post(server, "/oscore-session"))

        assertEquals("on", readLights(scope, "coap+ws://127.0.0.1:${server.port}/lights", session))
    }

    @Test
    fun everyPageLoadGetsKeysAndASenderIdOfItsOwn() = withServer { server, scope ->
        val first = OscoreSession.decode(post(server, "/oscore-session"))
        val second = OscoreSession.decode(post(server, "/oscore-session"))

        assertFalse(first.masterSecret.contentEquals(second.masterSecret), "a new Master Secret for each page")
        assertFalse(first.senderId.contentEquals(second.senderId), "RFC 8613 §3.3: Sender IDs are unique")
        assertContentEquals(byteArrayOf(), first.recipientId, "the server's Sender ID")
        val uri = "coap+ws://127.0.0.1:${server.port}/lights"
        assertEquals("on", readLights(scope, uri, second))
        assertEquals("on", readLights(scope, uri, first))
    }

    @Test
    fun theServerKeepsTheSessionsOfTheNewestPageLoadsOnly() = withServer(maxSessions = 2) { server, scope ->
        val oldest = OscoreSession.decode(post(server, "/oscore-session"))
        val older = OscoreSession.decode(post(server, "/oscore-session"))
        val newest = OscoreSession.decode(post(server, "/oscore-session"))
        val uri = "coap+ws://127.0.0.1:${server.port}/lights"

        val failure = assertFailsWith<CoapException> { readLights(scope, uri, oldest) }
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason, "RFC 8613 §8.2: no context left")
        assertEquals("on", readLights(scope, uri, older))
        assertEquals("on", readLights(scope, uri, newest))
    }

    @Test
    fun aServerWithoutRoomForASessionIsRefused() {
        assertFailsWith<IllegalArgumentException> { RecentSessions(InMemoryContextRepository(), max = 0) }
    }

    @Test
    fun aRequestWithoutOscoreIsRefused() = withServer { server, scope ->
        val plain = CoapClient(listOf(KtorWebSocketConnector()), scope)
        try {
            assertEquals(CoapCode.UNAUTHORIZED, plain.get("coap+ws://127.0.0.1:${server.port}/lights").code)
        } finally {
            plain.close()
        }
    }

    /** RFC 8613 §8.2: no context for the 'kid' gets an unprotected 4.01, which the client cannot verify. */
    @Test
    fun rfc8613Section8_2ASessionTheServerDoesNotKnowIsRefused() = withServer { server, scope ->
        val madeUp = OscoreSession(ByteArray(16) { 7 }, senderId = byteArrayOf(0x7f), recipientId = byteArrayOf())

        val failure = assertFailsWith<CoapException> {
            readLights(scope, "coap+ws://127.0.0.1:${server.port}/lights", madeUp)
        }
        assertEquals(CoapException.Reason.UNVERIFIED_RESPONSE, failure.reason)
        assertEquals(CoapCode.UNAUTHORIZED, failure.response?.code)
    }

    @Test
    fun theServerHandsOnWhatAPageReports() {
        val reported = CompletableDeferred<String>()
        withServer(onReport = { reported.complete(it) }) { server, _ ->
            post(server, "/report", "on")

            assertEquals("on", withTimeout(5.seconds) { reported.await() })
        }
    }

    @Test
    fun theSessionTextFormKeepsAnEmptyId() {
        val session = OscoreSession(byteArrayOf(1, 2), senderId = byteArrayOf(0x0a), recipientId = byteArrayOf())

        val text = session.encode()
        val decoded = OscoreSession.decode(text)

        assertEquals("0102 0a ", text)
        assertContentEquals(session.masterSecret, decoded.masterSecret)
        assertContentEquals(session.senderId, decoded.senderId)
        assertContentEquals(session.recipientId, decoded.recipientId)
        assertFailsWith<IllegalArgumentException> { OscoreSession.decode("0102 0a") }
    }

    private fun post(server: SampleServer, path: String, body: String = ""): String {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "POST $path answered ${response.statusCode()}" }
        return response.body()
    }

    private fun withServer(
        maxSessions: Int = SampleServer.DEFAULT_MAX_SESSIONS,
        onReport: (String) -> Unit = {},
        block: suspend (SampleServer, CoroutineScope) -> Unit,
    ) = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = SampleServer.start(scope, site = null, maxSessions = maxSessions, onReport = onReport)
        try {
            withTimeout(30.seconds) { block(server, scope) }
        } finally {
            server.close()
            scope.cancel()
        }
    }
}
