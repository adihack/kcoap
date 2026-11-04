package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.coap.stack.TokenGenerator
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.test.TestScope

/**
 * A simulated network with helpers to start CoAP endpoints on it, all on the test's virtual time.
 * Logging runs at TRACE into a sink that drops everything, so every log statement is exercised.
 */
class StackFixture(val test: TestScope, private val seed: Int = 1) {
    val network = InMemoryNetwork(test.backgroundScope, Random(seed))

    /** Every datagram the network saw, decoded, with its fate. */
    val traffic = mutableListOf<Wire>()

    val events = mutableListOf<EndpointEvent>()

    class Wire(val from: Endpoint, val to: Endpoint, val message: CoapMessage?, val fate: DatagramFate, val at: Duration) {
        override fun toString(): String = "$at $from -> $to $message $fate"
    }

    init {
        network.tap = { datagram, fate ->
            val decoded = runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull()
            traffic += Wire(datagram.from, datagram.to, decoded, fate, now)
        }
    }

    private val start = test.testScheduler.timeSource.markNow()
    val now: Duration get() = start.elapsedNow()

    val loggerFactory = LoggerFactory(
        LogConfig.build {
            level = LogLevel.TRACE
            sink = LogSink.None
            payloadDumps = true
        },
    )

    fun endpointConfig(
        separateResponseDelay: Duration? = CoapEndpointConfig().separateResponseDelay,
        interceptors: List<MessageInterceptor> = emptyList(),
        tokenGenerator: TokenGenerator = TokenGenerator.sessionSequence(random = Random(seed + 7)),
        parameters: dev.kcoap.coap.stack.TransmissionParameters = dev.kcoap.coap.stack.TransmissionParameters.Default,
    ) = CoapEndpointConfig(
        parameters = parameters,
        separateResponseDelay = separateResponseDelay,
        tokenGenerator = tokenGenerator,
        timeSource = test.testScheduler.timeSource,
        random = Random(seed + 3),
        interceptors = interceptors,
        eventListener = { events += it },
        loggerFactory = loggerFactory,
    )

    suspend fun transport(maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE): InMemoryTransport =
        network.attach(maxDatagramSize = maxDatagramSize)

    suspend fun endpoint(
        config: CoapEndpointConfig = endpointConfig(),
        maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE,
    ): CoapEndpoint = CoapEndpoint(transport(maxDatagramSize), test.backgroundScope, config)

    suspend fun server(
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = endpointConfig(),
        maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE,
        routes: ResourceBuilder.() -> Unit,
    ): CoapServer = CoapServer(endpoint(endpointConfig, maxDatagramSize), config, routes)

    suspend fun client(
        config: CoapClientConfig = CoapClientConfig(),
        endpointConfig: CoapEndpointConfig = endpointConfig(),
        maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE,
    ): CoapClient = CoapClient(endpoint(endpointConfig, maxDatagramSize), config)

    /** `coap://<server address><path>`. */
    fun uri(server: CoapServer, path: String = ""): String {
        val address = (server.endpoint.transport as InMemoryTransport).localEndpoint
        return "coap://${address.host}:${address.port}$path"
    }

    fun address(endpoint: CoapEndpoint): Endpoint.Udp = (endpoint.transport as InMemoryTransport).localEndpoint

    /** Messages that were sent from [from] (and delivered or not). */
    fun sentBy(from: CoapEndpoint): List<CoapMessage> = traffic.filter { it.from == address(from) }.mapNotNull { it.message }
}
