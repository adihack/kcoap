package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.ReliableTransportConfig
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryConnectionListener
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope

/**
 * In-memory connections with helpers to run CoAP clients and servers over them (RFC 8323), on the test's
 * virtual time, logging at TRACE into nothing so every log statement runs.
 */
class ReliableFixture(val test: TestScope, chunkSize: Int = 7) {
    val connections = InMemoryConnections(chunkSize)

    /** Every message written, decoded, with who wrote it. */
    val wire = mutableListOf<Wire>()

    val events = mutableListOf<EndpointEvent>()

    class Wire(val from: Endpoint, val to: Endpoint, val message: CoapMessage, val at: Duration) {
        override fun toString(): String = "$at $from -> $to $message"
    }

    private val start = test.testScheduler.timeSource.markNow()
    val now: Duration get() = start.elapsedNow()

    init {
        connections.tap = { from, to, bytes ->
            val codec = if (to is Endpoint.WebSocket) ReliableMessageCodec.WebSocket else ReliableMessageCodec.Tcp
            runCatching { codec.decode(bytes) }.getOrNull()?.let { wire += Wire(from, to, it, now) }
        }
    }

    val loggerFactory = LoggerFactory(
        LogConfig.build {
            level = LogLevel.TRACE
            sink = LogSink.None
            payloadDumps = true
        },
    )

    fun endpointConfig(
        reliable: ReliableTransportConfig = ReliableTransportConfig(),
        interceptors: List<MessageInterceptor> = emptyList(),
    ) = CoapEndpointConfig {
        timeSource = test.testScheduler.timeSource
        this.interceptors = interceptors
        eventListener = EndpointEventListener { events += it }
        loggerFactory = this@ReliableFixture.loggerFactory
        this.reliable = reliable
    }

    suspend fun listen(
        port: Int = 5683,
        scheme: CoapScheme = CoapScheme.COAP_TCP,
        clientIdentity: PeerIdentity = PeerIdentity.None,
    ): InMemoryConnectionListener = connections.listen(port, scheme, clientIdentity = clientIdentity)

    suspend fun server(
        port: Int = 5683,
        scheme: CoapScheme = CoapScheme.COAP_TCP,
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = endpointConfig(),
        clientIdentity: PeerIdentity = PeerIdentity.None,
        routes: ResourceBuilder.() -> Unit,
    ): CoapServer = CoapServer(listOf(listen(port, scheme, clientIdentity)), test.backgroundScope, config, endpointConfig, routes)

    fun endpoint(endpointConfig: CoapEndpointConfig = endpointConfig()): CoapEndpoint =
        CoapEndpoint(test.backgroundScope, endpointConfig, connectors = listOf(connections.connector))

    fun client(config: CoapClientConfig = CoapClientConfig.Default, endpointConfig: CoapEndpointConfig = endpointConfig()): CoapClient =
        CoapClient(endpoint(endpointConfig), config)

    fun uri(path: String = "", port: Int = 5683, scheme: CoapScheme = CoapScheme.COAP_TCP): String =
        "${scheme.scheme}://127.0.0.1:$port$path"

    /** Messages written by the side at [from]'s address, or by the client side when null. */
    fun sent(code: CoapCode? = null, byServer: Boolean? = null): List<CoapMessage> = wire
        .filter { byServer == null || isServer(it.from) == byServer }
        .map { it.message }
        .filter { code == null || it.code == code }

    /** Listeners get ports below 40000, where the in-memory connector starts numbering clients. */
    private fun isServer(endpoint: Endpoint): Boolean = when (endpoint) {
        is Endpoint.Tcp -> endpoint.port < 40000
        is Endpoint.WebSocket -> endpoint.uri.substringAfterLast(':').substringBefore('/').toInt() < 40000
        else -> false
    }
}
