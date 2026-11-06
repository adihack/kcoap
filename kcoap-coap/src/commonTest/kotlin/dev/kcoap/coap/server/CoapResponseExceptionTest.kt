package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.StackFixture
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * A handler answers with an error by throwing [CoapResponseException] from anywhere below it: the client gets that
 * response, for a request and for a notification, which ends the observation (RFC 7641 §3.2, §4.2), and the server
 * logs it as an ordinary outcome, not as a failure.
 */
class CoapResponseExceptionTest {
    /** The application's lookup, several calls below the handler, which cannot return a response itself. */
    private class Repository(var items: Map<Int, String>) {
        fun find(id: Int): String = items[id] ?: throw CoapResponseException(CoapResponse.error(CoapCode.NOT_FOUND, "no item $id"))
    }

    @Test
    fun aThrown404ReachesTheClient() = runTest {
        val fixture = StackFixture(this)
        val records = mutableListOf<LogRecord>()
        val logs = LoggerFactory(LogConfig.build { level = LogLevel.DEBUG; sink = LogSink { records += it } })
        val repository = Repository(mapOf(1 to "one"))
        val server = fixture.server(endpointConfig = fixture.endpointConfig().toBuilder().apply { loggerFactory = logs }.build()) {
            resource("items") { get { CoapResponse.content(repository.find(query.single().toInt())) } }
        }
        val client = fixture.client()

        assertEquals("one", client.get(fixture.uri(server, "/items?1")).payloadAsText())
        val missing = client.get(fixture.uri(server, "/items?7"))

        assertEquals(CoapCode.NOT_FOUND, missing.code)
        assertEquals("no item 7", missing.payloadAsText(), "the response as it was thrown, its diagnostic included")
        assertEquals(CoapCode.NOT_FOUND, fixture.events.filterIsInstance<EndpointEvent.RequestCompleted>().last().responseCode)
        // An ordinary outcome: one DEBUG line, no ERROR and no stack trace.
        val answered = records.single { it.message.startsWith("Handler of /items answered") }
        assertEquals(LogLevel.DEBUG, answered.level)
        assertNull(answered.error)
        assertTrue(records.none { it.level >= LogLevel.WARN }, "$records")
    }

    @Test
    fun rfc7641Section4_2AThrown404InANotificationEndsTheObservation() = runTest {
        val fixture = StackFixture(this)
        val repository = Repository(mapOf(1 to "one"))
        val item = object : ObservableResource("item") {
            override suspend fun handleGet(context: RequestContext) = CoapResponse.content(repository.find(1))
        }
        val server = fixture.server { add(item) }
        val client = fixture.client()

        val responses = async { client.observe(fixture.uri(server, "/item")).toList() }
        delay(1.seconds)
        repository.items = emptyMap()
        item.notifyObservers()

        // RFC 7641 §3.2: the error notification ends the client's observation, and the server's with it.
        assertEquals(listOf(CoapCode.CONTENT, CoapCode.NOT_FOUND), responses.await().map { it.code })
        assertEquals(0, item.observerCount)
        val removed = fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>().single()
        assertEquals(EndpointEvent.ObserverRemoved.Reason.ERROR_RESPONSE, removed.reason)
        val notification = fixture.traffic.mapNotNull { it.message }.last { it.code == CoapCode.NOT_FOUND }
        assertNull(notification.options.observe, "an error notification carries no Observe (RFC 7641 §4.2)")
        assertEquals("no item 1", notification.payloadAsText())
    }

    @Test
    fun onlyErrorsAnswerThroughTheException() {
        assertFailsWith<IllegalArgumentException> { CoapResponseException(CoapResponse.content("fine")) }
        assertFailsWith<IllegalArgumentException> { CoapResponseException(CoapResponse(CoapCode.of(3, 1))) }
        val server = CoapResponseException(CoapResponse.error(CoapCode.SERVICE_UNAVAILABLE, "maintenance"))
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, server.response.code)
        // The message shows the code and the payload's size, never the payload.
        assertEquals("Answered with 5.03 Service Unavailable 11 bytes", server.message)
    }
}
