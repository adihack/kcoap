package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The client's error contract (CoapClient KDoc, "Errors"): every runtime failure is a [CoapException] whose
 * reason says what failed, with the lower layer's exception as cause; `IllegalArgumentException` only for
 * mistakes of the caller; cancellation is never translated.
 */
class ClientErrorContractTest {
    /** A datagram transport whose close waits for [gate], so a test can act while the endpoint is closing. */
    private class SlowClosingTransport(private val inner: DatagramTransport) : DatagramTransport by inner {
        val gate = CompletableDeferred<Unit>()

        override suspend fun close() {
            gate.await()
            inner.close()
        }
    }

    @Test
    fun anExchangeOpenedWhileTheEndpointClosesFailsWithClosedAtOnce() = runTest {
        val fixture = StackFixture(this)
        val transport = SlowClosingTransport(fixture.transport())
        val endpoint = CoapEndpoint(transport, backgroundScope, fixture.endpointConfig())
        val resolving = CompletableDeferred<Unit>()
        val resolved = CompletableDeferred<Unit>()
        val resolver = EndpointResolver { uri ->
            resolving.complete(Unit)
            resolved.await()
            EndpointResolver.Default.resolve(uri)
        }
        val client = CoapClient(endpoint, CoapClientConfig(resolver = resolver))
        // The request starts while the endpoint is open, and opens its exchange only once close() is under way.
        val pending = async { runCatching { client.get("coap://127.0.0.1:9/x") } }
        resolving.await()
        val closing = launch { endpoint.close() }
        runCurrent()
        assertTrue(endpoint.isClosed)
        assertFalse(closing.isCompleted, "close() still waits for its transport")

        val started = fixture.now
        resolved.complete(Unit)
        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.CLOSED, error.reason)
        assertEquals(started, fixture.now, "at once, not after a timeout")
        assertEquals(0, endpoint.exchangeCount(), "nothing was registered after the exchanges were failed")
        assertTrue(fixture.traffic.isEmpty(), "nothing was sent")

        transport.gate.complete(Unit)
        closing.join()
    }

    @Test
    fun callsAfterCloseFailWithClosedWithoutResolvingTheHost() = runTest {
        val fixture = StackFixture(this)
        var lookups = 0
        val resolver = EndpointResolver { uri ->
            lookups++
            EndpointResolver.Default.resolve(uri)
        }
        val client = fixture.client(CoapClientConfig(resolver = resolver))
        client.endpoint.close()

        val reasons = listOf(
            assertFailsWith<CoapException> { client.get("coap://example.com/x") },
            assertFailsWith<CoapException> { client.ping("coap://example.com") },
            assertFailsWith<CoapException> { client.observe("coap://example.com/x").collect() },
            assertFailsWith<CoapException> { client.discover("coap://example.com") },
        ).map { it.reason }
        assertEquals(List(4) { CoapException.Reason.CLOSED }, reasons)
        assertEquals(0, lookups, "a closed endpoint looks nothing up")
    }

    @Test
    fun aResolverThatFailsAtRuntimeGivesUnresolvedHostWithItsFailureAsCause() = runTest {
        val fixture = StackFixture(this)
        val outage = IllegalStateException("No network")
        val client = fixture.client(CoapClientConfig(resolver = EndpointResolver { throw outage }))

        val error = assertFailsWith<CoapException> { client.get("coap://sensor.example/x") }
        assertEquals(CoapException.Reason.UNRESOLVED_HOST, error.reason)
        assertSame(outage, error.cause)
        assertTrue("sensor.example" in error.message.orEmpty(), error.message)
        assertTrue(fixture.traffic.isEmpty())
        assertEquals(CoapException.Reason.UNRESOLVED_HOST, assertFailsWith<CoapException> { client.ping("coap://sensor.example") }.reason)
    }

    @Test
    fun aResolverFollowingTheContractIsPassedOnUnchanged() = runTest {
        val fixture = StackFixture(this)
        val unresolved = CoapException("No address for sensor.example", CoapException.Reason.UNRESOLVED_HOST)
        val client = fixture.client(CoapClientConfig(resolver = EndpointResolver { throw unresolved }))
        assertSame(unresolved, assertFailsWith<CoapException> { client.get("coap://sensor.example/x") })

        // A host name given to the default resolver, which knows no DNS, is a mistake of configuration.
        val plain = fixture.client()
        val mistake = assertFailsWith<IllegalArgumentException> { plain.get("coap://sensor.example/x") }
        assertTrue("EndpointResolver" in mistake.message.orEmpty(), mistake.message)
    }

    @Test
    fun aCancelledLookupIsNotAResolverFailure() = runTest {
        val fixture = StackFixture(this)
        val client = fixture.client(CoapClientConfig(resolver = EndpointResolver { awaitCancellation() }))
        assertNull(withTimeoutOrNull(1.seconds) { client.get("coap://sensor.example/x") })
    }

    @Test
    fun anyFailureOfTheSecurityLayerIsSecurityWithItsCause() = runTest {
        val fixture = StackFixture(this)
        // As a sequence-number store that fails with an exception of its own while OSCORE protects.
        val storeFailure = IllegalStateException("Sequence number store unavailable")
        val security = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = throw storeFailure
        }
        val client = fixture.client(CoapClientConfig(security = security))

        val error = assertFailsWith<CoapException> { client.get("coap://127.0.0.1:9/x") }
        assertEquals(CoapException.Reason.SECURITY, error.reason)
        assertSame(storeFailure, error.cause)
        assertTrue(fixture.traffic.isEmpty(), "nothing was sent")
        assertEquals(0, client.endpoint.exchangeCount())
        val observing = assertFailsWith<CoapException> { client.observe("coap://127.0.0.1:9/x").collect() }
        assertEquals(CoapException.Reason.SECURITY, observing.reason)
    }

    @Test
    fun aCancelledProtectionIsNotASecurityFailure() = runTest {
        val fixture = StackFixture(this)
        val security = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest = awaitCancellation()
        }
        val client = fixture.client(CoapClientConfig(security = security))
        assertNull(withTimeoutOrNull(1.seconds) { client.get("coap://127.0.0.1:9/x") })
        assertEquals(0, client.endpoint.exchangeCount())
    }
}
