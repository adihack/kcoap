package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.EndpointResolver
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.Connector
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest

/**
 * Which secure URIs a client sends to: `coaps+tcp` and `coaps+ws` (RFC 8323 §9) once a transport of the endpoint
 * runs TLS for the scheme, and to a peer reached over TLS only; `coaps` never while kcoap has no DTLS (RFC 7252
 * §6.2). A refused request is refused before its host is resolved or anything is sent.
 */
class SecureSchemeTest {
    /** A connector that runs no TLS: it lists the plain schemes only, whatever it would do if asked. */
    private class PlainOnly(private val inner: Connector) : Connector {
        override val schemes: Set<CoapScheme> = setOf(CoapScheme.COAP_TCP, CoapScheme.COAP_WS)

        override suspend fun connect(peer: Endpoint): Connection = inner.connect(peer)
    }

    /** RFC 8323 §9: a TLS URI on an endpoint whose transports run no TLS is refused before anything happens. */
    @Test
    fun rfc8323Section9ATlsUriIsRefusedWhenNoTransportOfTheEndpointRunsTls() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 5684, scheme = CoapScheme.COAPS_TCP) { resource("x") { get { CoapResponse.content("secret") } } }
        fixture.server(port = 443, scheme = CoapScheme.COAPS_WS) { resource("x") { get { CoapResponse.content("secret") } } }
        var lookups = 0
        val resolver = EndpointResolver { uri ->
            lookups++
            EndpointResolver.Default.resolve(uri)
        }
        val endpoint = CoapEndpoint(backgroundScope, fixture.endpointConfig(), connectors = listOf(PlainOnly(fixture.connections.connector)))
        val client = CoapClient(endpoint, CoapClientConfig { this.resolver = resolver })

        val errors = listOf(
            assertFailsWith<CoapException> { client.get("coaps+tcp://127.0.0.1/x") },
            assertFailsWith<CoapException> { client.post("coaps+tcp://127.0.0.1/x", "secret".encodeToByteArray()) },
            assertFailsWith<CoapException> { client.ping("coaps+tcp://127.0.0.1") },
            assertFailsWith<CoapException> { client.observe("coaps+tcp://127.0.0.1/x").collect() },
            assertFailsWith<CoapException> { client.discover("coaps+tcp://127.0.0.1") },
            assertFailsWith<CoapException> { client.get("coaps+ws://127.0.0.1/x") },
        )
        assertEquals(List(6) { CoapException.Reason.TRANSPORT_UNAVAILABLE }, errors.map { it.reason })
        assertTrue("coaps+tcp needs TLS (RFC 8323 §9)" in errors.first().message.orEmpty(), errors.first().message)
        assertTrue("coaps+ws needs TLS" in errors.last().message.orEmpty(), errors.last().message)
        assertEquals(0, lookups, "refused before the host is resolved")
        assertTrue(fixture.connections.connections().isEmpty(), "no connection was opened")
        assertEquals(0, endpoint.exchangeCount())
        // The plain schemes still go through the same connector.
        fixture.server(port = 5683) { resource("x") { get { CoapResponse.content("plain") } } }
        assertEquals("plain", client.get("coap+tcp://127.0.0.1/x").payloadAsText())
    }

    /** RFC 8323 §9: a connector that lists `coaps+tcp` and `coaps+ws` runs TLS for them, so the requests go out. */
    @Test
    fun rfc8323Section9ATlsUriGoesOutWhenAConnectorRunsTlsForItsScheme() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 5684, scheme = CoapScheme.COAPS_TCP) { resource("x") { get { CoapResponse.content("over tcp") } } }
        fixture.server(port = 443, scheme = CoapScheme.COAPS_WS) { resource("x") { get { CoapResponse.content("over ws") } } }
        val client = fixture.client()
        assertEquals("over tcp", client.get("coaps+tcp://127.0.0.1/x").payloadAsText())
        assertEquals("over ws", client.get("coaps+ws://127.0.0.1/x").payloadAsText())
        val remotes = fixture.connections.connections().map { it.remote }
        assertTrue(Endpoint.Tcp("127.0.0.1", 5684, usesTls = true) in remotes, "$remotes")
        assertTrue(Endpoint.WebSocket.of("127.0.0.1", 443, usesTls = true) in remotes, "$remotes")
    }

    /**
     * A listener that reports `coaps+tcp` runs TLS for it, so its endpoint may address an accepted peer by such a
     * URI; without a connection to that peer, it then fails for want of a connector, not of TLS.
     */
    @Test
    fun aListenerThatRunsTlsLetsItsEndpointUseTheScheme() = runTest {
        val fixture = ReliableFixture(this)
        val listener = fixture.listen(5684, CoapScheme.COAPS_TCP)
        val endpoint = CoapEndpoint(backgroundScope, fixture.endpointConfig(), listeners = listOf(listener))
        val error = assertFailsWith<CoapException> { CoapClient(endpoint).get("coaps+tcp://127.0.0.1:40000/x") }
        assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
        assertTrue("coaps+tcp needs a Connector" in error.message.orEmpty(), error.message)
        val noTls = assertFailsWith<CoapException> { CoapClient(endpoint).get("coaps+ws://127.0.0.1/x") }
        assertTrue("coaps+ws needs TLS" in noTls.message.orEmpty(), noTls.message)
        endpoint.close()
    }

    /** RFC 8323 §9: a resolver that maps a TLS URI to a plain peer would send it in plaintext, so the call fails. */
    @Test
    fun aTlsUriThatTheResolverMapsToAPlainPeerIsRefusedBeforeAnythingIsSent() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server(port = 5684) { resource("x") { get { CoapResponse.content("plaintext") } } }
        val downgrades = listOf(
            Endpoint.Tcp("127.0.0.1", 5684),
            Endpoint.WebSocket.of("127.0.0.1", 5684),
            Endpoint.Udp("127.0.0.1", 5684),
            Endpoint.Opaque("ble", "device-1"),
        )
        for (downgrade in downgrades) {
            val client = fixture.client(CoapClientConfig { resolver = EndpointResolver { downgrade } })
            val error = assertFailsWith<CoapException> { client.get("coaps+tcp://127.0.0.1/x") }
            assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
            assertTrue("not reached over TLS (RFC 8323 §9); nothing was sent" in error.message.orEmpty(), error.message)
        }
        assertTrue(fixture.connections.connections().isEmpty(), "nothing reached the plain server")
        // A resolver may still secure a plain scheme: coap+tcp to a TLS peer is no downgrade.
        fixture.server(port = 5685, scheme = CoapScheme.COAPS_TCP) { resource("x") { get { CoapResponse.content("secured") } } }
        val upgrading = fixture.client(CoapClientConfig { resolver = EndpointResolver { Endpoint.Tcp("127.0.0.1", 5685, usesTls = true) } })
        assertEquals("secured", upgrading.get("coap+tcp://127.0.0.1/x").payloadAsText())
    }

    /** RFC 7252 §6.2: `coaps` stays refused while kcoap has no DTLS, also on an endpoint whose connectors run TLS. */
    @Test
    fun rfc7252Section6_2CoapsStaysRefusedWhenOnlyTlsIsAvailable() = runTest {
        val fixture = ReliableFixture(this)
        val client = fixture.client()
        val error = assertFailsWith<CoapException> { client.get("coaps://127.0.0.1/x") }
        assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, error.reason)
        assertTrue("coaps needs DTLS" in error.message.orEmpty(), error.message)
        assertTrue(fixture.connections.connections().isEmpty())
    }
}
