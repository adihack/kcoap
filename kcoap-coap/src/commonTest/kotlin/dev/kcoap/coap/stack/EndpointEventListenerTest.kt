package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class EndpointEventListenerTest {
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val event = EndpointEvent.ResetReceived(peer, 7)

    @Test
    fun plusSendsEachEventToEveryListenerInOrder() {
        val seen = mutableListOf<String>()
        val first = EndpointEventListener { seen += "first $it" }
        val second = EndpointEventListener { seen += "second $it" }
        val third = EndpointEventListener { seen += "third $it" }

        val combined = first + second + third
        combined.onEvent(event)

        assertEquals(listOf("first $event", "second $event", "third $event"), seen)
        assertEquals("EndpointEventListener(3 listeners)", combined.toString(), "nested sums are flattened")
    }

    @Test
    fun noneAddsNothing() {
        val listener = EndpointEventListener { }
        assertSame(listener, EndpointEventListener.None + listener)
        assertSame(listener, listener + EndpointEventListener.None)
    }

    @Test
    fun aListenerThatThrowsDoesNotKeepTheEventFromTheOthers() {
        val seen = mutableListOf<EndpointEvent>()
        val combined = EndpointEventListener { error("first") } + EndpointEventListener { seen += it } +
            EndpointEventListener { error("third") }

        // The endpoint catches and logs what its listener throws: the first exception, with the others suppressed.
        val thrown = assertFailsWith<IllegalStateException> { combined.onEvent(event) }

        assertEquals(listOf<EndpointEvent>(event), seen)
        assertEquals("first", thrown.message)
        assertEquals(listOf("third"), thrown.suppressedExceptions.map { it.message })
    }
}
