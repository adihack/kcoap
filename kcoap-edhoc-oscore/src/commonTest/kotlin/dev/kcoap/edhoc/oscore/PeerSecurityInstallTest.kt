package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * `PeerSecurity.install(client, server, security)` resolves the server with the client's resolver and reports its
 * failures as the client's requests do (the `EndpointResolver` contract): a lookup that fails at runtime is a
 * `CoapException` with `UNRESOLVED_HOST` and the failure as cause, which `@Throws` lists for Swift, whatever the
 * resolver threw.
 */
class PeerSecurityInstallTest {
    private val server = CoapUri.parse("coap://edhoc.example")
    private val protection = object : ClientMessageSecurity {
        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? = null
    }

    private suspend fun client(scope: CoroutineScope, security: PeerSecurity, resolver: EndpointResolver): CoapClient =
        CoapClient(InMemoryNetwork(scope, Random(5)).attach(), scope, CoapClientConfig {
            this.security = security
            this.resolver = resolver
        })

    /** A platform's DNS exception, here an `IllegalStateException`, is a lookup that failed at runtime. */
    @Test
    fun aResolverThatFailsAtRuntimeIsUnresolvedHostWithItsCause() = runTest {
        val dnsFailure = IllegalStateException("Network is unreachable")
        val security = PeerSecurity()
        val client = client(backgroundScope, security, EndpointResolver { throw dnsFailure })

        val error = assertFailsWith<CoapException> { security.install(client, server, protection) }
        assertEquals(CoapException.Reason.UNRESOLVED_HOST, error.reason)
        assertSame(dnsFailure, error.cause)
        assertTrue("edhoc.example" in error.message.orEmpty(), error.message)
        client.close()
    }

    /**
     * A lookup that times out in the resolver's own `withTimeout` is a failed lookup, not a cancellation: the
     * caller is still active, and a `CancellationException` would end a coroutine launched for the install
     * without a word.
     */
    @Test
    fun aResolverThatTimesOutOnItsOwnIsUnresolvedHost() = runTest {
        val security = PeerSecurity()
        val client = client(backgroundScope, security, EndpointResolver { withTimeout(1.milliseconds) { awaitCancellation() } })

        val error = assertFailsWith<CoapException> { security.install(client, server, protection) }
        assertEquals(CoapException.Reason.UNRESOLVED_HOST, error.reason)
        assertIs<TimeoutCancellationException>(error.cause)
        client.close()
    }

    /** The caller's own cancellation during the lookup stays a cancellation. */
    @Test
    fun theCallersCancellationDuringTheLookupPassesUnchanged() = runTest {
        val security = PeerSecurity()
        val client = client(backgroundScope, security, EndpointResolver { awaitCancellation() })
        var failure: Throwable? = null

        val install = launch {
            try {
                security.install(client, server, protection)
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        runCurrent()
        install.cancelAndJoin()
        assertIs<CancellationException>(failure, "not a CoapException")
        client.close()
    }
}
