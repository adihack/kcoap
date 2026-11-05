package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The store of responses held back for the repeat of a POST or PATCH with Echo (RFC 9175 §2.4 item 3):
 * one per request, found by address and request, bounded and expiring.
 */
class HeldBackResponsesTest {
    private val a = Endpoint.Udp("192.0.2.1", 5683)
    private val b = Endpoint.Udp("192.0.2.2", 5683)

    private fun post(payload: String, path: String = "r", echo: ByteArray? = null, token: Byte = 1) = CoapMessage.request(CoapCode.POST) {
        this.token = byteArrayOf(token)
        options {
            uriPath(path)
            echo?.let { echo(it) }
        }
        payload(payload)
    }

    private fun key(peer: Endpoint, request: CoapMessage) = HeldBackResponses.Key(peer, request)

    private fun content(text: String) = CoapResponse.content(text)

    @Test
    fun aRepeatMatchesByAddressMethodOptionsWithoutEchoAndPayload() {
        val held = HeldBackResponses(capacity = 8, lifetime = 10.seconds)
        held.put(key(a, post("body")), content("kept"), 0.seconds)

        assertNull(held.take(key(b, post("body")), 1.seconds), "another address")
        assertNull(held.take(key(a, post("other")), 1.seconds), "another payload")
        assertNull(held.take(key(a, post("body", path = "s")), 1.seconds), "other options")
        assertNull(held.take(key(a, CoapMessage.request(CoapCode.PATCH) { options { uriPath("r") } }), 1.seconds), "another method")
        assertEquals(
            "kept",
            held.take(key(a, post("body", echo = byteArrayOf(1, 2), token = 2)), 1.seconds)?.payloadAsText(),
            "Echo and token aside",
        )
        assertNull(held.take(key(a, post("body")), 1.seconds), "handed out once")
        assertEquals(0, held.size)
    }

    @Test
    fun identicalRequestsKeepOneResponseEachInTheOrderTheyWereKept() {
        val held = HeldBackResponses(capacity = 8, lifetime = 10.seconds)
        held.put(key(a, post("x")), content("1"), 0.seconds)
        held.put(key(a, post("y")), content("2"), 1.seconds)
        held.put(key(a, post("x")), content("3"), 2.seconds)

        assertEquals("1", held.take(key(a, post("x")), 3.seconds)?.payloadAsText())
        assertEquals("3", held.take(key(a, post("x")), 3.seconds)?.payloadAsText())
        assertNull(held.take(key(a, post("x")), 3.seconds))
        assertEquals("2", held.take(key(a, post("y")), 3.seconds)?.payloadAsText())
    }

    @Test
    fun theOldestGivesWayAndItsAddressIsReturned() {
        val held = HeldBackResponses(capacity = 2, lifetime = 10.seconds)
        assertNull(held.put(key(a, post("x")), content("1"), 0.seconds))
        assertNull(held.put(key(a, post("x")), content("2"), 1.seconds))

        assertEquals(a, held.put(key(b, post("x")), content("3"), 2.seconds), "a's first response made room")
        assertEquals(2, held.size)
        assertEquals("2", held.take(key(a, post("x")), 3.seconds)?.payloadAsText(), "a's second stays, and is next for its request")
        assertEquals(null, held.put(key(b, post("y")), content("4"), 4.seconds), "taking one made room")
        assertEquals(b, held.put(key(a, post("z")), content("5"), 5.seconds))
        assertNull(held.take(key(b, post("x")), 6.seconds), "b's oldest gave way")
        assertEquals("4", held.take(key(b, post("y")), 6.seconds)?.payloadAsText())
    }

    @Test
    fun responsesExpireAfterTheirLifetimeWithoutCountingAsEvictions() {
        val held = HeldBackResponses(capacity = 1, lifetime = 10.seconds)
        held.put(key(a, post("x")), content("1"), 0.seconds)

        assertNull(held.take(key(a, post("x")), 10.seconds))
        assertEquals(0, held.size)
        held.put(key(a, post("x")), content("2"), 10.seconds)
        assertNull(held.put(key(b, post("x")), content("3"), 20.seconds), "the expired one was dropped, not evicted")
        assertEquals("3", held.take(key(b, post("x")), 21.seconds)?.payloadAsText())
    }

    @Test
    fun keysOfEqualRequestsAreEqual() {
        assertEquals(key(a, post("x")), key(a, post("x", echo = byteArrayOf(9))))
        assertEquals(key(a, post("x")).hashCode(), key(a, post("x", echo = byteArrayOf(9))).hashCode())
        assertEquals(false, key(a, post("x")).equals("x"))
    }

    @Test
    fun aStoreHoldsAtLeastOneResponse() {
        assertFailsWith<IllegalArgumentException> { HeldBackResponses(capacity = 0, lifetime = 10.seconds) }
    }
}
