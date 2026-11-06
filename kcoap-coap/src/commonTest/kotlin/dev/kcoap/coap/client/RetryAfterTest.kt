package dev.kcoap.coap.client

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.StackFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** RFC 8516 §4: a client does not repeat a request refused with 4.29 before its Max-Age has passed. */
class RetryAfterTest {
    /** A server whose `/busy` answers [refusals] times with [refusal], then with 2.05; counts what it got. */
    private class Busy(val refusals: Int, val refusal: CoapResponse = CoapResponse.tooManyRequests(2.seconds)) {
        var requests = 0
        fun answer(): CoapResponse = if (++requests <= refusals) refusal else CoapResponse.content("done")
    }

    private suspend fun StackFixture.busyServer(busy: Busy) = server { resource("busy") { get { busy.answer() } } }

    private fun get(uri: String) = CoapRequest(CoapCode.GET, CoapUri.parse(uri))

    @Test
    fun rfc8516Section4TheRequestIsRepeatedOnceMaxAgeHasPassed() = runTest {
        val fixture = StackFixture(this)
        val busy = Busy(refusals = 2)
        val server = fixture.busyServer(busy)
        val start = fixture.now

        val response = RetryAfter().request(fixture.client(), get(fixture.uri(server, "/busy")))
        assertEquals("done", response.payloadAsText())
        assertEquals(3, busy.requests)
        assertTrue(fixture.now - start >= 4.seconds, "two waits of Max-Age 2: ${fixture.now - start}")
    }

    @Test
    fun rfc7252Section5_9_3_4A503WithMaxAgeIsRepeatedToo() = runTest {
        val fixture = StackFixture(this)
        val busy = Busy(refusals = 1, refusal = CoapResponse(CoapCode.SERVICE_UNAVAILABLE, OptionSet.build { maxAge(1) }))
        val server = fixture.busyServer(busy)
        assertEquals("done", RetryAfter().request(fixture.client(), get(fixture.uri(server, "/busy"))).payloadAsText())
        assertEquals(2, busy.requests)
    }

    @Test
    fun rfc8516Section4AWaitLongerThanTheCapIsNotTakenAndTheRefusalIsReturned() = runTest {
        val fixture = StackFixture(this)
        val busy = Busy(refusals = 1, refusal = CoapResponse.tooManyRequests(120.seconds))
        val server = fixture.busyServer(busy)
        val start = fixture.now

        val response = RetryAfter(maxWait = 60.seconds).request(fixture.client(), get(fixture.uri(server, "/busy")))
        assertEquals(CoapCode.TOO_MANY_REQUESTS, response.code)
        assertEquals(120.seconds, response.retryAfter)
        assertEquals(1, busy.requests, "never sooner than the server asked")
        assertTrue(fixture.now - start < 1.seconds)
    }

    @Test
    fun theLastRefusalIsReturnedAfterTheLastAttempt() = runTest {
        val fixture = StackFixture(this)
        val busy = Busy(refusals = 10)
        val server = fixture.busyServer(busy)
        val response = RetryAfter(maxAttempts = 2).request(fixture.client(), get(fixture.uri(server, "/busy")))
        assertEquals(CoapCode.TOO_MANY_REQUESTS, response.code)
        assertEquals(2, busy.requests)
        RetryAfter(maxAttempts = 1).request(fixture.client(), get(fixture.uri(server, "/busy")))
        assertEquals(3, busy.requests, "one attempt is one request")
    }

    @Test
    fun cancellingTheCallerEndsTheWaitAndSendsNothingMore() = runTest {
        val fixture = StackFixture(this)
        val busy = Busy(refusals = 10, refusal = CoapResponse.tooManyRequests(30.seconds))
        val server = fixture.busyServer(busy)
        val client = fixture.client()
        val waiting = async { RetryAfter().request(client, get(fixture.uri(server, "/busy"))) }
        delay(5.seconds)
        waiting.cancel()
        assertFailsWith<CancellationException> { waiting.await() }
        delay(60.seconds)
        assertEquals(1, busy.requests)
    }

    @Test
    fun valuesOutOfRangeAreRefused() {
        assertFailsWith<IllegalArgumentException> { RetryAfter(maxWait = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { RetryAfter(maxAttempts = 0) }
        assertEquals(Duration.ZERO, RetryAfter(maxWait = Duration.ZERO).maxWait)
        assertEquals("RetryAfter(maxWait=1m, maxAttempts=3)", RetryAfter().toString())
        assertEquals(60.seconds, RetryAfter.DEFAULT_MAX_WAIT)
    }
}
