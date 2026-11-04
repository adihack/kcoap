package dev.kcoap.coap.client

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.LinkFormat
import dev.kcoap.coap.WebLink
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.log.LogTags
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A CoAP client on a [CoapEndpoint].
 *
 * Every call suspends until the whole response is there: block-wise transfers are done in both
 * directions (RFC 7959), separate responses are awaited (RFC 7252 §5.2.2), a 4.01 with Echo is answered
 * once with that Echo value (RFC 9175 §2.3), and message security protects each message
 * ([CoapClientConfig.security]). Error codes are returned as responses; a request that gets no response
 * at all throws [CoapException]. Cancelling the calling coroutine cancels the exchange.
 *
 * ```
 * val client = CoapClient(KtorUdpTransport.bind(), scope)
 * val response = client.get("coap://192.0.2.1/sensors/temp")
 * client.put("coap://192.0.2.1/config", "on".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
 * client.observe("coap://192.0.2.1/time").take(3).collect { println(it.payloadAsText()) }
 * ```
 */
public class CoapClient private constructor(
    public val endpoint: CoapEndpoint,
    public val config: CoapClientConfig,
    private val ownsEndpoint: Boolean,
) {
    /** A client on an existing [endpoint], which a [dev.kcoap.coap.server.CoapServer] may share. */
    public constructor(endpoint: CoapEndpoint, config: CoapClientConfig = CoapClientConfig()) : this(endpoint, config, false)

    /** A client on a new endpoint over [transport]; [close] closes both. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapClientConfig = CoapClientConfig(),
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig(),
    ) : this(CoapEndpoint(transport, scope, endpointConfig), config, true)

    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_CLIENT)
    internal val blockwise = BlockwiseClient(this)
    private val observationLock = Mutex()
    private val observations = HashMap<OperationKey, ClientObservation>()

    internal val requestTimeout: Duration get() = config.requestTimeout ?: endpoint.parameters.exchangeLifetime
    internal val securityReserve: Int get() = if (config.security != null) config.securityReserve else 0

    // ---------------------------------------------------------------- methods

    /** Sends [request] and returns its whole response. */
    public suspend fun request(request: CoapRequest): CoapResponse {
        val peer = config.resolver.resolve(request.uri)
        log.debug { "$request to $peer" }
        return blockwise.send(peer, message(request), request.isConfirmable)
    }

    /** Sends a [method] request to [uri], set up by [configure]. */
    public suspend fun request(method: CoapCode, uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapRequest.Builder(method, uri).apply(configure).build())

    /** GET (RFC 7252 §5.8.1) of [uri]; [configure] adds options or makes it Non-confirmable. */
    public suspend fun get(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapCode.GET, uri, configure)

    /** GET of the CoAP URI [uri]. */
    public suspend fun get(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        get(CoapUri.parse(uri), configure)

    /** DELETE (RFC 7252 §5.8.4) of [uri]. */
    public suspend fun delete(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapCode.DELETE, uri, configure)

    /** DELETE of the CoAP URI [uri]. */
    public suspend fun delete(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        delete(CoapUri.parse(uri), configure)

    /** POST (RFC 7252 §5.8.2) of [payload] in [contentFormat] to [uri]; large bodies go in Block1 blocks. */
    public suspend fun post(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.POST, uri, payload, contentFormat, configure)

    /** POST to the CoAP URI [uri]. */
    public suspend fun post(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = post(CoapUri.parse(uri), payload, contentFormat, configure)

    /** PUT (RFC 7252 §5.8.3) of [payload] in [contentFormat] to [uri]; large bodies go in Block1 blocks. */
    public suspend fun put(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.PUT, uri, payload, contentFormat, configure)

    /** PUT to the CoAP URI [uri]. */
    public suspend fun put(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = put(CoapUri.parse(uri), payload, contentFormat, configure)

    /** FETCH (RFC 8132 §2): a GET with a request body. */
    public suspend fun fetch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.FETCH, uri, payload, contentFormat, configure)

    /** FETCH to the CoAP URI [uri]. */
    public suspend fun fetch(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = fetch(CoapUri.parse(uri), payload, contentFormat, configure)

    /** PATCH (RFC 8132 §3). */
    public suspend fun patch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.PATCH, uri, payload, contentFormat, configure)

    /** PATCH to the CoAP URI [uri]. */
    public suspend fun patch(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = patch(CoapUri.parse(uri), payload, contentFormat, configure)

    /** iPATCH (RFC 8132 §3): an idempotent PATCH. */
    public suspend fun ipatch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.IPATCH, uri, payload, contentFormat, configure)

    /** iPATCH to the CoAP URI [uri]. */
    public suspend fun ipatch(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = ipatch(CoapUri.parse(uri), payload, contentFormat, configure)

    private suspend fun withBody(
        method: CoapCode,
        uri: CoapUri,
        payload: ByteArray,
        contentFormat: Int?,
        configure: CoapRequest.Builder.() -> Unit,
    ): CoapResponse = request(method, uri) {
        this.payload = payload
        contentFormat?.let { contentFormat(it) }
        configure()
    }

    /**
     * Observes [request]'s target (RFC 7641): the flow registers when collected, emits the first response
     * and every fresher notification, and completes after a response that ends the observation (one
     * without Observe, or an error code, which is emitted last). It fails with [CoapException] when the
     * registration gets no response or a Reset.
     *
     * Collectors of the same target share one registration (RFC 7641 §3.1); when the last one stops, the
     * observation is cancelled as [CoapClientConfig.observeCancellation] says. A slow collector misses
     * intermediate notifications rather than holding up the client.
     */
    public fun observe(request: CoapRequest): Flow<CoapResponse> = flow {
        require(request.method == CoapCode.GET || request.method == CoapCode.FETCH) { "Only GET and FETCH can be observed" }
        val peer = config.resolver.resolve(request.uri)
        val registration = message(request).toBuilder().options { observe(0) }.build()
        val observation = acquire(peer, registration, request.isConfirmable)
        try {
            emitAll(
                observation.events.transformWhile { event ->
                    when (event) {
                        is ClientObservation.Event.Notification -> {
                            emit(event.response)
                            true
                        }
                        is ClientObservation.Event.Final -> {
                            emit(event.response)
                            false
                        }
                        is ClientObservation.Event.Failure -> throw event.cause
                    }
                },
            )
        } finally {
            release(observation)
        }
    }

    /** Observes [uri] with GET; see [observe]. */
    public fun observe(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): Flow<CoapResponse> =
        observe(CoapRequest.Builder(CoapCode.GET, uri).apply(configure).build())

    /** Observes the CoAP URI [uri] with GET; see [observe]. */
    public fun observe(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): Flow<CoapResponse> =
        observe(CoapUri.parse(uri), configure)

    /** RFC 7252 §4.3 CoAP ping of the server at [uri]: true if it answered with a Reset (or ACK). */
    public suspend fun ping(uri: CoapUri): Boolean = endpoint.ping(config.resolver.resolve(uri))

    /** CoAP ping of the server at the CoAP URI [uri]. */
    public suspend fun ping(uri: String): Boolean = ping(CoapUri.parse(uri))

    /**
     * Resource discovery (RFC 6690 §4): GETs `/.well-known/core` from [server] (any URI of the server; its
     * path is replaced), with [query] as RFC 6690 §4.1 filter such as `rt=temperature-c`, and parses it.
     *
     * @throws CoapException with [CoapException.Reason.BAD_RESPONSE] unless the answer is 2.05 link format.
     */
    public suspend fun discover(server: CoapUri, query: String? = null): List<WebLink> {
        val uri = CoapUri(server.scheme, server.host, server.port, listOf(".well-known", "core"), listOfNotNull(query))
        val response = get(uri) { accept(ContentFormat.LINK_FORMAT) }
        if (response.code != CoapCode.CONTENT) {
            throw CoapException("Discovery answered ${response.code}", CoapException.Reason.BAD_RESPONSE)
        }
        return try {
            LinkFormat.parse(response.payloadAsText())
        } catch (e: IllegalArgumentException) {
            throw CoapException("Discovery returned no valid link format: ${e.message}", CoapException.Reason.BAD_RESPONSE, e)
        }
    }

    /** Resource discovery at the server of the CoAP URI [server]; see [discover]. */
    public suspend fun discover(server: String, query: String? = null): List<WebLink> = discover(CoapUri.parse(server), query)

    /** Closes the endpoint if the client created it. */
    public suspend fun close() {
        if (ownsEndpoint) endpoint.close()
    }

    // ---------------------------------------------------------------- internals

    /** The request message without token and Message ID: URI options (RFC 7252 §6.4), options, body. */
    private fun message(request: CoapRequest): CoapMessage = CoapMessage.request(request.method) {
        options {
            addAll(request.uri.toOptions())
            addAll(request.options)
        }
        payload = request.payload
    }

    /**
     * One request and its response, without block-wise processing: the exchange layer, message security,
     * and the Echo retry of RFC 9175 §2.3.
     */
    internal suspend fun exchange(peer: Endpoint, request: CoapMessage, confirmable: Boolean): CoapMessage {
        var current = request
        var echoed = false
        while (true) {
            val exchange = endpoint.openExchange(peer, current, confirmable, config.security, isObservation = false)
            val response = endpoint.awaitResponse(exchange, requestTimeout)
            checkResponse(response)
            val echo = response.options.echo
            if (response.code == CoapCode.UNAUTHORIZED && echo != null && config.echoRetry && !echoed) {
                log.debug { "4.01 with Echo from $peer; repeating the request with it" }
                current = current.toBuilder().options { echo(echo) }.build()
                echoed = true
                continue
            }
            return response
        }
    }

    /**
     * RFC 7252 §5.4.1: a response with an unrecognized critical option is rejected. The client processes
     * Block1 and Block2; OSCORE is gone after verification.
     */
    internal fun checkResponse(response: CoapMessage) {
        val unrecognized = response.options.filter { it.number % 2 == 1 && it.number !in RESPONSE_RECOGNIZED }
        if (unrecognized.isNotEmpty()) {
            throw CoapException(
                "Response with unrecognized critical options ${unrecognized.joinToString { it.number.toString() }}",
                CoapException.Reason.BAD_RESPONSE,
            )
        }
    }

    private suspend fun acquire(peer: Endpoint, registration: CoapMessage, confirmable: Boolean): ClientObservation {
        val key = OperationKey(peer, null, registration.code.value, Blockwise.operationOptions(registration.options))
        return observationLock.withLock {
            val observation = observations.getOrPut(key) {
                ClientObservation(this, peer, key, registration, confirmable, log.with("peer" to peer, "path" to registration.options.path))
                    .also { it.start() }
            }
            observation.subscribers++
            observation
        }
    }

    private suspend fun release(observation: ClientObservation) {
        val last = observationLock.withLock {
            observation.subscribers--
            val last = observation.subscribers == 0
            if (last && observations[observation.key] === observation) observations.remove(observation.key)
            last
        }
        if (last) observation.stop()
    }

    internal suspend fun observationEnded(observation: ClientObservation) {
        observationLock.withLock {
            if (observations[observation.key] === observation) observations.remove(observation.key)
        }
    }

    override fun toString(): String = "CoapClient($endpoint)"

    private companion object {
        val RESPONSE_RECOGNIZED = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)
    }
}
