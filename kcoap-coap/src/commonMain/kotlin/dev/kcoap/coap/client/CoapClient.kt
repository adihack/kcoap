package dev.kcoap.coap.client

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.LinkFormat
import dev.kcoap.coap.WebLink
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.ClientExchange
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.ResponseMode
import dev.kcoap.common.KcoapException
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ChannelSecurityKind
import dev.kcoap.transport.Connector
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A CoAP client on a [CoapEndpoint].
 *
 * Every call suspends until the whole response is there: block-wise transfers are done in both
 * directions (RFC 7959, or Q-Block of RFC 9177 when [CoapClientConfig.qBlock] turns it on and the server
 * supports it), separate responses are awaited (RFC 7252 §5.2.2), a 4.01 with Echo is answered
 * once with that Echo value (RFC 9175 §2.3), and message security protects each message
 * ([CoapClientConfig.security]).
 *
 * **Errors.** Error codes (4.xx, 5.xx) are returned as responses, not thrown. A call fails with:
 * - [CoapException] for everything that goes wrong at runtime, whatever the layer; its [CoapException.reason]
 *   says what: the peer did not answer ([CoapException.Reason.TRANSMISSION_TIMEOUT],
 *   [CoapException.Reason.RESPONSE_TIMEOUT]) or refused ([CoapException.Reason.RESET]), the DTLS session or
 *   connection ended ([CoapException.Reason.SESSION_ENDED], [CoapException.Reason.CONNECTION_CLOSED]), the
 *   response broke the protocol ([CoapException.Reason.BAD_RESPONSE], [CoapException.Reason.BLOCKWISE_FAILED]),
 *   message security could not protect the request ([CoapException.Reason.SECURITY]) or verify its only answer
 *   ([CoapException.Reason.UNVERIFIED_RESPONSE]), the host could not be resolved
 *   ([CoapException.Reason.UNRESOLVED_HOST]), a limit was reached ([CoapException.Reason.MESSAGE_TOO_LARGE],
 *   [CoapException.Reason.IDENTIFIERS_EXHAUSTED]), or the endpoint cannot serve it
 *   ([CoapException.Reason.TRANSPORT_UNAVAILABLE], [CoapException.Reason.TRANSPORT_FAILED],
 *   [CoapException.Reason.CLOSED]). Lower layers' exceptions arrive as its cause, never on their own.
 * - `IllegalArgumentException` or `IllegalStateException` only for a mistake of the caller: a URI string that
 *   is not a CoAP URI, observing with a method other than GET or FETCH, or a host the configured
 *   [EndpointResolver] cannot resolve by design, such as a name given to [EndpointResolver.Default].
 * - `CancellationException` only when the calling coroutine is cancelled, which cancels the exchange. A lower
 *   layer that cancels itself while the caller is still active, as a resolver whose own `withTimeout` expires
 *   does, fails the call with [CoapException] like any other failure of that layer.
 *
 * Every request, [ping] and [discover] lists [KcoapException], the root of [CoapException], and
 * `CancellationException` in `@Throws`, so on Apple platforms Swift catches these failures as an `NSError`, with the
 * Kotlin exception in its `userInfo` under `"KotlinException"`. A mistake of the caller is not listed and ends the
 * process there, as a failed precondition does in Swift: a Swift caller parses a URI it did not write itself with
 * [CoapUri.parse], which lists `IllegalArgumentException`, and passes the [CoapUri].
 *
 * Under message security the client acts only on responses it verified (ADR 0008). A protected request answered
 * only by an error that came without message security, such as OSCORE's unprotected 4.01 (RFC 8613 §8.2), is
 * not repeated for its Echo option nor continued block-wise: it throws [CoapException] with
 * [CoapException.Reason.UNVERIFIED_RESPONSE], whose [CoapException.response] is that error. A security layer
 * that cannot protect a request it should, such as kcoap-oscore's without a context for the peer, fails it
 * before anything is sent, with [CoapException.Reason.SECURITY] and the layer's `MessageSecurityException` as
 * cause.
 *
 * Once the endpoint is closed, by [close], by its scope or by a failed transport, every call throws
 * [CoapException] with [CoapEndpoint.closeCause]'s reason, at once and without resolving the host, and every
 * [observe] flow fails with it. A call racing [CoapEndpoint.close] fails the same way: whether the endpoint is
 * closed is decided under the lock that registers the exchange, so no request waits for a response after it.
 *
 * The client is safe to use from any number of coroutines; it runs on the dispatcher of the endpoint's
 * scope, so on Android or iOS give it one such as `lifecycleScope + Dispatchers.Default`. Creating it
 * does not start the endpoint receiving; its first request or ping does, so on an endpoint shared with
 * a [dev.kcoap.coap.server.CoapServer] the two may be created in either order.
 *
 * The URI's scheme picks the transport: `coap` goes over the endpoint's datagram transport, `coap+tcp`,
 * `coaps+tcp`, `coap+ws` and `coaps+ws` (RFC 8323 §8) over connections that its connectors open on
 * demand. On connections, message sizes and block sizes follow the server's Max-Message-Size, with BERT
 * blocks when both sides support them (RFC 8323 §6). A `coaps` URI needs DTLS (RFC 7252 §6.2, §9.1), which
 * kcoap does not have yet, so every call with one fails with [CoapException.Reason.TRANSPORT_UNAVAILABLE] before
 * its host is resolved: nothing goes out in plaintext. A `coaps+tcp` or `coaps+ws` URI needs TLS (RFC 8323 §9)
 * and fails the same way unless a connector or listener of the endpoint serves its scheme, which a transport
 * does only when it runs TLS for it, as kcoap-transport-ktor's `KtorTlsConnector` (`coaps+tcp`, on the JVM) and
 * the WebSocket connector (`coaps+ws`, where its engine authenticates the server: Apple, browsers, Node.js, or
 * an `HttpClient` the application passes) do.
 *
 * ```
 * val client = CoapClient(KtorUdpTransport.bind(), scope)
 * val response = client.get("coap://192.0.2.1/sensors/temp")
 * client.put("coap://192.0.2.1/config", "on".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
 * client.observe("coap://192.0.2.1/time").take(3).collect { println(it.payloadAsText()) }
 *
 * val tcp = CoapClient(listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope)
 * tcp.get("coap+tcp://192.0.2.1/sensors/temp")
 * tcp.get("coap+ws://example.org/sensors/temp")
 * ```
 */
public class CoapClient private constructor(
    public val endpoint: CoapEndpoint,
    public val config: CoapClientConfig,
    private val ownsEndpoint: Boolean,
) {
    /** A client on an existing [endpoint], which a [dev.kcoap.coap.server.CoapServer] may share. */
    public constructor(endpoint: CoapEndpoint, config: CoapClientConfig = CoapClientConfig.Default) : this(endpoint, config, false)

    /** A client on a new endpoint over [transport]; [close] closes both. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapClientConfig = CoapClientConfig.Default,
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig.Default,
    ) : this(CoapEndpoint(transport, scope, endpointConfig), config, true)

    /**
     * A client for CoAP over reliable transports (RFC 8323) on a new endpoint: requests to `coap+tcp`,
     * `coaps+tcp`, `coap+ws` or `coaps+ws` URIs open connections through [connectors], reuse them per peer
     * and release them when idle ([dev.kcoap.coap.stack.ReliableTransportConfig.idleTimeout]). [close]
     * closes the endpoint and its connections.
     */
    public constructor(
        connectors: List<Connector>,
        scope: CoroutineScope,
        config: CoapClientConfig = CoapClientConfig.Default,
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig.Default,
    ) : this(CoapEndpoint(scope, endpointConfig, connectors = connectors), config, true)

    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_CLIENT)
    internal val blockwise = BlockwiseClient(this)

    /** Q-Block (RFC 9177), when [CoapClientConfig.qBlock] turns it on. */
    internal val qBlock: QBlockClient? = if (config.qBlock.isEnabled) QBlockClient(this) else null
    /** Guards [observations]; internal for tests. */
    internal val observationLock = Mutex()
    private val observations = HashMap<OperationKey, ClientObservation>()

    internal val requestTimeout: Duration get() = config.requestTimeout ?: endpoint.parameters.exchangeLifetime
    internal val securityReserve: Int get() = if (config.security != null) config.securityReserve else 0

    /**
     * The critical options the client processes in a response: Block1 and Block2 (RFC 7959); OSCORE is gone
     * after verification. The endpoint rejects a response with any other one (RFC 7252 §5.4.1): a separate
     * response with a Reset, and the request fails with [CoapException.Reason.BAD_RESPONSE]. Requests sent
     * with Q-Block recognize Q-Block1 and Q-Block2 as well ([QBlockClient.RECOGNIZED_OPTIONS]); every other
     * request rejects them, since a server sends them only to a request that carried one (RFC 9177 §4.1).
     */
    internal val recognizedOptions: Set<Int> = ClientExchange.BLOCKWISE_OPTIONS

    // ---------------------------------------------------------------- methods

    /**
     * Sends [request] and returns its whole response: with Q-Block (RFC 9177) where [CoapClientConfig.qBlock]
     * allows it and the server supports it, otherwise with Block1 and Block2 (RFC 7959) where the body needs
     * them.
     */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun request(request: CoapRequest): CoapResponse {
        val peer = resolve(request.uri)
        log.debug { "$request to $peer" }
        val message = message(request)
        qBlock?.send(peer, message, request.isConfirmable)?.let { return it }
        return blockwise.send(peer, message, request.isConfirmable)
    }

    /** Sends a [method] request to [uri], set up by [configure]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun request(method: CoapCode, uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapRequest.Builder(method, uri).apply(configure).build())

    /** GET (RFC 7252 §5.8.1) of [uri]; [configure] adds options or makes it Non-confirmable. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun get(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapCode.GET, uri, configure)

    /** GET of the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun get(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        get(CoapUri.parse(uri), configure)

    /** DELETE (RFC 7252 §5.8.4) of [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun delete(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        request(CoapCode.DELETE, uri, configure)

    /** DELETE of the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun delete(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): CoapResponse =
        delete(CoapUri.parse(uri), configure)

    /** POST (RFC 7252 §5.8.2) of [payload] in [contentFormat] to [uri]; large bodies go in Block1 blocks. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun post(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.POST, uri, payload, contentFormat, configure)

    /** POST to the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun post(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = post(CoapUri.parse(uri), payload, contentFormat, configure)

    /** PUT (RFC 7252 §5.8.3) of [payload] in [contentFormat] to [uri]; large bodies go in Block1 blocks. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun put(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.PUT, uri, payload, contentFormat, configure)

    /** PUT to the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun put(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = put(CoapUri.parse(uri), payload, contentFormat, configure)

    /** FETCH (RFC 8132 §2): a GET with a request body. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun fetch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.FETCH, uri, payload, contentFormat, configure)

    /** FETCH to the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun fetch(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = fetch(CoapUri.parse(uri), payload, contentFormat, configure)

    /** PATCH (RFC 8132 §3). */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun patch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.PATCH, uri, payload, contentFormat, configure)

    /** PATCH to the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun patch(
        uri: String,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = patch(CoapUri.parse(uri), payload, contentFormat, configure)

    /** iPATCH (RFC 8132 §3): an idempotent PATCH. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun ipatch(
        uri: CoapUri,
        payload: ByteArray = ByteArray(0),
        contentFormat: Int? = null,
        configure: CoapRequest.Builder.() -> Unit = {},
    ): CoapResponse = withBody(CoapCode.IPATCH, uri, payload, contentFormat, configure)

    /** iPATCH to the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
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
     * registration gets no response or a Reset, or, under message security, only a response that could not be
     * verified ([CoapException.Reason.UNVERIFIED_RESPONSE]). Once established, the observation ignores such
     * responses: they neither end it nor reach the collectors (ADR 0008).
     *
     * Collectors of the same target share one registration (RFC 7641 §3.1); when the last one stops, the
     * observation is cancelled as [CoapClientConfig.observeCancellation] says. A slow collector misses
     * intermediate notifications rather than holding up the client.
     *
     * With [CoapClientConfig.qBlock] on and a server that supports it, notification bodies come in Q-Block2
     * (RFC 9177 §4.5) and each is emitted once, whole; a FETCH registration whose body needs blocks is
     * uploaded in Q-Block1 and, when cancelled, forgets its token instead of deregistering. Under message
     * security ([CoapClientConfig.security]) that takes [QBlockClientConfig.withMessageSecurity], and the
     * security layer orders the notifications, taking the blocks of a body in any order (RFC 8613 §4.1.3.5.2,
     * §7.4.1); otherwise observations keep RFC 7959.
     */
    public fun observe(request: CoapRequest): Flow<CoapResponse> = flow {
        require(request.method == CoapCode.GET || request.method == CoapCode.FETCH) { "Only GET and FETCH can be observed" }
        val peer = resolve(request.uri)
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
            // A cancelled collector must still count itself out, or the observation never stops.
            withContext(NonCancellable) { release(observation) }
        }
    }

    /** Observes [uri] with GET; see [observe]. */
    public fun observe(uri: CoapUri, configure: CoapRequest.Builder.() -> Unit = {}): Flow<CoapResponse> =
        observe(CoapRequest.Builder(CoapCode.GET, uri).apply(configure).build())

    /** Observes the CoAP URI [uri] with GET; see [observe]. */
    public fun observe(uri: String, configure: CoapRequest.Builder.() -> Unit = {}): Flow<CoapResponse> =
        observe(CoapUri.parse(uri), configure)

    /**
     * CoAP ping of the server at [uri]: an Empty Confirmable message over UDP (RFC 7252 §4.3), true if it
     * answered with a Reset (or ACK); a Ping over a reliable transport (RFC 8323 §5.4), true if the Pong came.
     */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun ping(uri: CoapUri): Boolean = endpoint.ping(resolve(uri))

    /** CoAP ping of the server at the CoAP URI [uri]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun ping(uri: String): Boolean = ping(CoapUri.parse(uri))

    /**
     * Resource discovery (RFC 6690 §4): GETs `/.well-known/core` from [server] (any URI of the server; its
     * path is replaced), with [query] as RFC 6690 §4.1 filter such as `rt=temperature-c`, and parses it. The
     * links are the server's input: their targets are text, to be parsed with [CoapUri.parseOrNull].
     *
     * @throws CoapException with [CoapException.Reason.BAD_RESPONSE] unless the answer is 2.05 link format; for a
     *   malformed document, its message says where, and the parser's exception is its cause.
     */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun discover(server: CoapUri, query: String? = null): List<WebLink> {
        val uri = CoapUri(server.scheme, server.host, server.port, listOf(".well-known", "core"), listOfNotNull(query))
        val response = get(uri) { accept(ContentFormat.LINK_FORMAT) }
        if (response.code != CoapCode.CONTENT) {
            throw CoapException("Discovery answered ${response.code}", CoapException.Reason.BAD_RESPONSE)
        }
        // The server's document is peer input: its malformation is a bad response, translated here with the reason.
        return try {
            LinkFormat.parse(response.payloadAsText())
        } catch (e: IllegalArgumentException) {
            throw CoapException("Discovery returned no valid link format: ${e.message}", CoapException.Reason.BAD_RESPONSE, e)
        }
    }

    /** Resource discovery at the server of the CoAP URI [server]; see [discover]. */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun discover(server: String, query: String? = null): List<WebLink> = discover(CoapUri.parse(server), query)

    /** Closes the endpoint if the client created it. */
    public suspend fun close() {
        if (ownsEndpoint) endpoint.close()
    }

    // ---------------------------------------------------------------- internals

    /**
     * The peer for [uri], through [CoapClientConfig.resolver], with its failures translated as its contract
     * says ([resolvePeer]): a lookup that fails at runtime, a cancellation of the resolver's own included, is
     * [CoapException.Reason.UNRESOLVED_HOST] with it as cause, while a [CoapException], an
     * `IllegalArgumentException` and the caller's cancellation pass unchanged.
     *
     * Nothing is looked up on a closed endpoint, where the call fails like [CoapEndpoint.closeCause] at once, nor
     * for a URI whose scheme needs channel security the endpoint does not run, since its request would go out in
     * plaintext; such a call fails closed with [CoapException.Reason.TRANSPORT_UNAVAILABLE]. That is every `coaps`
     * URI: without DTLS (RFC 7252 §9.1) it would break RFC 7252 §6.2. A `coaps+tcp` or `coaps+ws` URI needs TLS
     * (RFC 8323 §9), and goes ahead only when a connector or listener of the endpoint serves its scheme, which is
     * the transport's word that it runs TLS; and the resolver must then map it to a TLS peer, or the call fails
     * the same way before anything is sent.
     */
    private suspend fun resolve(uri: CoapUri): Endpoint {
        endpoint.closeCause?.let { throw endpoint.closedError(it) }
        when (uri.scheme.security) {
            ChannelSecurityKind.DTLS -> throw CoapException(
                "${uri.scheme.scheme} needs DTLS (RFC 7252 §9.1), which this endpoint does not have; nothing was sent to ${uri.host}",
                CoapException.Reason.TRANSPORT_UNAVAILABLE,
            )
            ChannelSecurityKind.TLS -> if (!endpoint.servesConnections(uri.scheme)) {
                throw CoapException(
                    "${uri.scheme.scheme} needs TLS (RFC 8323 §9), which no connector or listener of this endpoint runs; " +
                        "nothing was sent to ${uri.host}",
                    CoapException.Reason.TRANSPORT_UNAVAILABLE,
                )
            }
            ChannelSecurityKind.NONE -> Unit
        }
        val peer = resolvePeer(uri)
        if (uri.scheme.security == ChannelSecurityKind.TLS && !peer.usesTls()) {
            throw CoapException(
                "The resolver mapped the ${uri.scheme.scheme} URI of ${uri.host} to $peer, which is not reached over TLS " +
                    "(RFC 8323 §9); nothing was sent",
                CoapException.Reason.TRANSPORT_UNAVAILABLE,
            )
        }
        return peer
    }

    /** Whether [this] peer is reached over TLS: `coaps+tcp` or `coaps+ws`. */
    private fun Endpoint.usesTls(): Boolean = when (this) {
        is Endpoint.Tcp -> usesTls
        is Endpoint.WebSocket -> usesTls
        is Endpoint.Udp, is Endpoint.Secure, is Endpoint.Opaque -> false
    }

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
     * and the Echo retry of RFC 9175 §2.3. Under message security the response is a verified one: an unverified
     * answer ends the exchange with [CoapException.Reason.UNVERIFIED_RESPONSE] before anything here sees it, so
     * neither the Echo retry nor the block-wise decisions of the callers act on it (ADR 0008).
     */
    internal suspend fun exchange(
        peer: Endpoint,
        request: CoapMessage,
        confirmable: Boolean,
        recognized: Set<Int> = recognizedOptions,
    ): CoapMessage {
        var current = request
        var echoed = false
        while (true) {
            val exchange = endpoint.openExchange(
                peer, current, confirmable, config.security, ResponseMode.Single, recognizedOptions = recognized,
            )
            val response = endpoint.awaitResponse(exchange, requestTimeout)
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

    /** Registrations the client keeps, for tests. */
    internal suspend fun observationCount(): Int = observationLock.withLock { observations.size }

    override fun toString(): String = "CoapClient($endpoint)"
}
