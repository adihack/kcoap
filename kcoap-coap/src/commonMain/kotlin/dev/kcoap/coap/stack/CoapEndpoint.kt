package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.common.toHex
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A CoAP endpoint: the messaging layers and the exchange layer that matches responses to requests by
 * peer and token. [dev.kcoap.coap.client.CoapClient] and [dev.kcoap.coap.server.CoapServer] are built on
 * it and can share one endpoint, so a device can be client and server on the same port.
 *
 * It runs CoAP over a [DatagramTransport] with the RFC 7252 messaging layer, and CoAP over reliable
 * transports (RFC 8323) on connections: opened on demand through [Connector]s for `coap+tcp`,
 * `coaps+tcp`, `coap+ws` and `coaps+ws` peers, reused per peer and released when idle, and accepted from
 * [ConnectionListener]s. Every connection has a messaging layer of its own, so tokens are scoped to it,
 * and its responses, notifications and failures stay with it (RFC 8323 §3.3, §7.4). One exchange layer
 * serves all of them through an internal link per peer, chosen by the kind of the peer's [Endpoint], so
 * Block-wise, Observe, Q-Block and message security behave the same on every transport.
 *
 * The endpoint runs its coroutines in [scope] and inherits its dispatcher. It starts receiving and
 * accepting once a [dev.kcoap.coap.server.CoapServer] is attached or the first request or ping goes out,
 * so a server's resources are in place before the first request arrives, whichever of a server and a
 * [dev.kcoap.coap.client.CoapClient] sharing the endpoint is created first. On Android or iOS pass a scope
 * such as `lifecycleScope + Dispatchers.Default` to keep the receive loop, timers and handlers off the main
 * thread.
 *
 * **Lifecycle.** An endpoint ends exactly once, in one terminal transition, whichever way it ends: [close],
 * cancelling [scope], or a datagram transport whose receive fails. Then open exchanges fail, observations
 * end with a failure for their collectors, connections are released (RFC 8323 §5.5), the transports are
 * closed, and every later request, ping or observation fails at once with a [CoapException] like
 * [closeCause]. [awaitClosed] waits for all of that, coroutines included.
 *
 * **Thread safety.** All state is guarded by locks that are never held across a suspension, and effects
 * such as sending and delivering run after the lock is released, so the endpoint is safe to use from any
 * number of coroutines on any dispatcher.
 *
 * ```
 * val endpoint = CoapEndpoint(KtorUdpTransport.bind(port = 5683), scope)
 * val server = CoapServer(endpoint) { resource("hello") { get { CoapResponse.content("Hello") } } }
 * val client = CoapClient(endpoint)
 * ```
 */
public class CoapEndpoint private constructor(
    datagramTransport: DatagramTransport?,
    connectors: List<Connector>,
    listeners: List<ConnectionListener>,
    scope: CoroutineScope,
    public val config: CoapEndpointConfig,
) {
    /** An endpoint on [transport], for `coap` peers; it has no connections. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapEndpointConfig = CoapEndpointConfig(),
    ) : this(transport, emptyList(), emptyList(), scope, config)

    /**
     * An endpoint on any combination of transports: [transport] for `coap` peers, [connectors] to open
     * connections to peers of the RFC 8323 schemes they serve, [listeners] to accept connections.
     * [close] closes all of them. A request to a peer the endpoint has no transport for fails with
     * [CoapException.Reason.TRANSPORT_UNAVAILABLE]: a `coap` peer without [transport], or a peer of a scheme
     * no connector serves, unless that peer already has a connection open, as one a listener accepted.
     */
    public constructor(
        scope: CoroutineScope,
        config: CoapEndpointConfig = CoapEndpointConfig(),
        transport: DatagramTransport? = null,
        connectors: List<Connector> = emptyList(),
        listeners: List<ConnectionListener> = emptyList(),
    ) : this(transport, connectors, listeners, scope, config)

    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE)

    /** Guards the exchanges and the closed state; never held across a suspension. Internal for tests. */
    internal val lock = Mutex()
    private val exchanges = HashMap<ExchangeKey, ClientExchange>()

    /** How many of [exchanges] each peer has, for [CoapEndpointConfig.maxExchangesPerPeer]; under [lock]. */
    private val exchangesPerPeer = HashMap<Endpoint, Int>()

    /** One permit per running request handler, [CoapEndpointConfig.maxConcurrentRequests] in all. */
    private val handlerPermits = Semaphore(config.maxConcurrentRequests)
    private val clockStart = config.timeSource.markNow()

    /** Warns when the time source and the dispatcher's clock disagree; shared with the links. */
    internal val clockCheck = ClockCheck(log)

    /** This endpoint's own token generator unless the configuration names one (RFC 9175 §4.2 state is per endpoint). */
    private val tokens: TokenGenerator = config.tokenGenerator ?: TokenGenerator.sessionSequence()
    private val job = SupervisorJob(scope.coroutineContext[Job])

    /**
     * Where the endpoint runs its own coroutines and those of request handlers. Every coroutine it launches
     * handles its own failures; as a last resort, when the application's scope has no
     * [CoroutineExceptionHandler], one that logs keeps an escaped exception from reaching the platform's
     * handler, which ends the process on Android and Kotlin/Native. One the application installed is kept.
     */
    internal val scope: CoroutineScope = CoroutineScope(
        scope.coroutineContext + job + CoroutineName("coap-endpoint") +
            (scope.coroutineContext[CoroutineExceptionHandler] ?: CoroutineExceptionHandler { _, e ->
                log.error(e) { "A coroutine of the endpoint failed" }
            }),
    )

    /** RFC 7252 messaging with every datagram peer, when the endpoint has a datagram transport. */
    private val datagram: DatagramLink? = datagramTransport?.let { DatagramLink(it, this, config) }

    /** The datagram transport, if the endpoint has one; for tests, which read its address. */
    internal val transport: DatagramTransport? get() = datagram?.transport

    /** RFC 8323 connections: a [ConnectionLink] per peer, opened through [connectors] or accepted from [listeners]. */
    private val connections = ReliableConnections(this, connectors, listeners, this.scope, config)

    @Volatile
    internal var requestHandler: RequestHandler? = null

    /**
     * Whether the peers support Q-Block (RFC 9177 §4.1), as the clients of this endpoint found out; shared by
     * them, and forgotten for a peer when its DTLS session ends or its connection closes.
     */
    internal val qBlockCapabilities = QBlockCapabilities()

    /** What the clients learned about [peer] goes with its session or connection. */
    private suspend fun forgetPeer(peer: Endpoint) {
        if (qBlockCapabilities.invalidate(peer)) log.debug { "Forgot whether $peer supports Q-Block" }
    }

    /**
     * Why the endpoint closed, or null while it is open: [CoapException.Reason.CLOSED] after [close] or when
     * its scope was cancelled; [CoapException.Reason.TRANSPORT_FAILED], with the transport's exception as
     * cause, when the datagram transport failed. Set once, in the terminal transition.
     */
    @Volatile
    public var closeCause: CoapException? = null
        private set

    /** True once the endpoint is closed, however that happened; see [closeCause]. */
    public val isClosed: Boolean get() = closeCause != null

    /** Completes once the terminal transition is done. */
    private val closed = CompletableDeferred<CoapException>()

    /** Completes when a server attaches or the first request goes out; only then does the endpoint receive. */
    private val attached = CompletableDeferred<Unit>()

    init {
        log.info { "Endpoint started on ${describeTransports(connectors, listeners)}" }
        // Keep this last: it starts undispatched and reads every field above. Undispatched, the try below
        // is entered before the constructor returns, so even a scope that is already cancelled ends in the
        // terminal transition, never in an endpoint that looks open with its transport still open.
        this.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: CoapException? = null
            try {
                attached.await()
                connections.start()
                failure = datagram?.run() ?: awaitCancellation()
            } finally {
                shutdown(failure ?: CoapException("Endpoint closed: its scope was cancelled", CoapException.Reason.CLOSED))
            }
        }
    }

    /**
     * Starts receiving and accepting, once: called when a server attaches, and before the first request or
     * ping. Until then datagrams and connections wait in the transports.
     */
    internal fun attach() {
        attached.complete(Unit)
    }

    /** The parameters messaging runs with. */
    public val parameters: TransmissionParameters get() = config.parameters

    /** Time on [CoapEndpointConfig.timeSource] since the endpoint started; every timer uses it. */
    internal fun now(): Duration = clockStart.elapsedNow()

    // ---------------------------------------------------------------- links

    /**
     * Whether [peer] is reached on a connection (RFC 8323 §8 schemes) rather than over datagrams: the one place
     * the endpoint tells the two apart, by the kind of the [Endpoint] (no scheme registry, ADR 0004).
     */
    private fun isOnConnection(peer: Endpoint): Boolean = when (peer) {
        is Endpoint.Tcp, is Endpoint.WebSocket -> true
        is Endpoint.Udp, is Endpoint.Secure, is Endpoint.Opaque -> false
    }

    /** What the link to [peer] can do, known without opening it. */
    internal fun capabilities(peer: Endpoint): LinkCapabilities =
        if (isOnConnection(peer)) LinkCapabilities.CONNECTION else LinkCapabilities.DATAGRAM

    /**
     * The link to [peer]: the datagram link, or the connection to [peer], which this opens if needed.
     *
     * @throws CoapException with [CoapException.Reason.TRANSPORT_UNAVAILABLE] when the endpoint has no
     *   transport for [peer], or as [ReliableConnections.linkFor] throws.
     */
    internal suspend fun linkFor(peer: Endpoint): PeerLink =
        if (isOnConnection(peer)) connections.linkFor(peer) else datagramLinkTo(peer)

    /**
     * The datagram link to [peer]; for a request that came in a datagram, and for tests.
     *
     * @throws CoapException with [CoapException.Reason.TRANSPORT_UNAVAILABLE] when the endpoint has no
     *   datagram transport.
     */
    internal fun datagramLinkTo(peer: Endpoint): PeerLink {
        val link = datagram ?: throw CoapException(
            "${schemeOf(peer)} needs a DatagramTransport; this endpoint has none for $peer",
            CoapException.Reason.TRANSPORT_UNAVAILABLE,
        )
        return link.to(peer)
    }

    /** The scheme a datagram [peer] is reached by, for messages: `coaps` over DTLS, else `coap`. */
    private fun schemeOf(peer: Endpoint): String = if (peer is Endpoint.Secure) "coaps" else "coap"

    /**
     * What one message to [peer] may be: the datagram size, or the peer's Max-Message-Size on its
     * connection (RFC 8323 §5.3.1), which this opens if needed.
     */
    internal suspend fun limits(peer: Endpoint): MessageLimits = linkFor(peer).limits

    // ---------------------------------------------------------------- client side

    /**
     * Sends [request] (code, options and payload; header fields are set here) to [peer] and returns the
     * exchange that collects its responses as [mode] says. The token comes from
     * [CoapEndpointConfig.tokenGenerator] unless [token] is given, which re-registers or cancels an
     * observation (RFC 7641 §3.3.1, §3.6) and replaces the exchange that had it. The token, and on a datagram
     * link the message type and Message ID, are assigned before [security] protects the request, and kept
     * afterwards. A response with a critical option outside [recognizedOptions] is rejected
     * (RFC 7252 §5.4.1). On a link without message types, [confirmable] and [pacing] do not apply.
     *
     * @param pacing [Pacing.PerMessage], or [Pacing.Body] for a Non-confirmable message of a Q-Block body
     *   whose granted [BodyLease] towards [peer] holds the slot (RFC 9177 §7.2).
     * @throws CoapException with [CoapException.Reason.TRANSPORT_UNAVAILABLE] when the endpoint has no
     *   transport for [peer], with [CoapException.Reason.CONNECTION_CLOSED] when its connection cannot be
     *   opened, with [CoapException.Reason.SECURITY] when [security] fails to protect the request, which is then
     *   not sent, or like [closeCause] once the endpoint is closed.
     * @throws IllegalArgumentException for a Confirmable request with [Pacing.Body], which would deadlock
     *   at NSTART 1 (RFC 9177 §7.1), or a lease towards another peer.
     * @throws IllegalStateException for a lease not yet granted or already released.
     */
    internal suspend fun openExchange(
        peer: Endpoint,
        request: CoapMessage,
        confirmable: Boolean,
        security: ClientMessageSecurity?,
        mode: ResponseMode = ResponseMode.Single,
        token: ByteArray? = null,
        pacing: Pacing = Pacing.PerMessage,
        recognizedOptions: Set<Int> = ClientExchange.BLOCKWISE_OPTIONS,
    ): ClientExchange {
        attach()
        // On a connection the exchange is counted before it is registered, so its idle Release (RFC 8323 §5.5)
        // cannot start in between.
        val link = if (isOnConnection(peer)) connections.reservedLinkFor(peer) else datagramLinkTo(peer)
        val exchange = try {
            link.checkPacing(confirmable, pacing)
            lock.withLock {
                // Checked under the lock that the terminal transition takes, so no exchange is added after they
                // were all failed: it fails at once instead of waiting for a response that never comes.
                ensureOpen()
                register(link, token, mode, recognizedOptions)
            } ?: throw tooManyExchanges(peer)
        } catch (e: Throwable) {
            withContext(NonCancellable) { link.exchangeClosed(null) }
            throw e
        }
        try {
            val plain = link.address(request, exchange.token, confirmable)
            val protected = security?.let { protect(it, plain, peer) }
            exchange.protectedRequest = protected
            val wire = protected?.message?.toBuilder()?.apply {
                type = plain.type
                messageId = plain.messageId
                this.token = plain.token
            }?.build() ?: plain
            exchange.request = wire
            link.sendRequest(wire, pacing, exchange) { exchange.delivery = it }
            log.with("peer" to peer, "token" to exchange.key.token).debug {
                "Request ${plain.code} ${plain.options.path}" + if (link.capabilities.hasMessageTypes) " as ${plain.type}" else ""
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) { exchange.cancel() }
            throw e
        }
        return exchange
    }

    /**
     * [security]'s protection of [request] for [peer]. Its failures are translated at this boundary, as for every
     * client call (CoapException): any exception but a cancellation becomes [CoapException.Reason.SECURITY],
     * with the security layer's own as cause, whatever its type, so a failing sequence-number store under OSCORE
     * is reported like a missing context.
     */
    private suspend fun protect(
        security: ClientMessageSecurity,
        request: CoapMessage,
        peer: Endpoint,
    ): ProtectedRequest? {
        try {
            return security.protectRequest(request, peer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "Message security did not protect ${request.code} to $peer: ${e.message}" }
            throw CoapException(
                "Message security could not protect ${request.code} to $peer: ${e.message}",
                CoapException.Reason.SECURITY,
                e,
            )
        }
    }

    /**
     * Called under [lock]: a new exchange on [link] with [token] or a fresh one, replacing an exchange that had
     * it; null when the peer has [CoapEndpointConfig.maxExchangesPerPeer] already.
     */
    private fun register(
        link: PeerLink,
        token: ByteArray?,
        mode: ResponseMode,
        recognizedOptions: Set<Int>,
    ): ClientExchange? {
        val peer = link.peer
        val replacing = token != null && ExchangeKey(peer, token.toHex()) in exchanges
        if (!replacing && (exchangesPerPeer[peer] ?: 0) >= config.maxExchangesPerPeer) return null
        val chosen = token ?: allocateToken(peer)
        val logger = log.with("peer" to peer, "token" to chosen.toHex())
        val exchange = ClientExchange(this, link, chosen, mode, recognizedOptions, logger)
        val previous = exchanges.put(exchange.key, exchange)
        if (previous == null) {
            exchangesPerPeer[peer] = (exchangesPerPeer[peer] ?: 0) + 1
        } else if (previous !== exchange) {
            previous.markReplaced()
        }
        return exchange
    }

    /** Called under [lock]: [exchange] leaves the registry. */
    private fun unregister(exchange: ClientExchange) {
        exchanges.remove(exchange.key)
        val remaining = (exchangesPerPeer[exchange.peer] ?: 1) - 1
        if (remaining > 0) exchangesPerPeer[exchange.peer] = remaining else exchangesPerPeer.remove(exchange.peer)
    }

    /** RFC 7252 §11.3: the request is refused rather than queued without bound. */
    private fun tooManyExchanges(peer: Endpoint): CoapException {
        emit(EndpointEvent.LimitReached(peer, EndpointEvent.LimitReached.Limit.EXCHANGES_PER_PEER))
        return CoapException(
            "$peer has ${config.maxExchangesPerPeer} exchanges in progress (maxExchangesPerPeer)",
            CoapException.Reason.IDENTIFIERS_EXHAUSTED,
        )
    }

    private fun allocateToken(peer: Endpoint): ByteArray {
        repeat(TOKEN_ATTEMPTS) {
            val token = tokens.next(peer)
            require(token.size <= CoapMessage.MAX_TOKEN_LENGTH) { "Token generator returned ${token.size} bytes" }
            if (ExchangeKey(peer, token.toHex()) !in exchanges) return token
        }
        throw CoapException("No free token towards $peer", CoapException.Reason.IDENTIFIERS_EXHAUSTED)
    }

    /**
     * Sends a CoAP ping. To a datagram peer (RFC 7252 §4.3) an Empty Confirmable message, which a live
     * peer answers with a Reset: true if it answered, false if it never did within MAX_TRANSMIT_WAIT. To a
     * reliable peer (RFC 8323 §5.4) a Ping on its connection, opened if needed: true if the Pong came
     * within [ReliableTransportConfig.pingTimeout], false if not or if the connection failed.
     *
     * @throws CoapException with [CoapException.Reason.TRANSPORT_UNAVAILABLE] when the endpoint has no
     *   transport for [peer], or like [closeCause] once the endpoint is closed.
     */
    public suspend fun ping(peer: Endpoint): Boolean = ping(peer, custody = false)

    /**
     * Like [ping]. To a reliable peer, [custody] asks it to answer only once it has processed every
     * request it received before the Ping (RFC 8323 §5.4.1); datagram pings ignore it.
     */
    public suspend fun ping(peer: Endpoint, custody: Boolean): Boolean {
        attach()
        lock.withLock { ensureOpen() }
        val link = try {
            linkFor(peer)
        } catch (e: CoapException) {
            if (e.reason == CoapException.Reason.TRANSPORT_UNAVAILABLE) throw e
            // A connection that cannot be opened is a peer that does not answer.
            log.debug { "Ping of $peer failed: ${e.message}" }
            return false
        }
        return link.ping(custody)
    }

    /** Waits for the exchange's next response, at most [timeout]; cancels the exchange on timeout or cancellation. */
    internal suspend fun awaitResponse(exchange: ClientExchange, timeout: Duration): CoapMessage {
        try {
            return withTimeoutOrNull(timeout) { exchange.awaitResponse() }
                ?: throw CoapException("No response from ${exchange.peer} within $timeout", CoapException.Reason.RESPONSE_TIMEOUT)
        } catch (e: Throwable) {
            withContext(NonCancellable) { exchange.cancel() }
            throw e
        }
    }

    /**
     * The exchange ends: its token is free again, and its link no longer counts it. Cleanup, so it runs to the
     * end in a cancelled coroutine too.
     */
    internal suspend fun forget(exchange: ClientExchange): Unit = withContext(NonCancellable) {
        var first = false
        val registered = lock.withLock {
            first = !exchange.isForgotten
            exchange.isForgotten = true
            (exchanges[exchange.key] === exchange).also { if (it) unregister(exchange) }
        }
        if (first) exchange.link.exchangeClosed(exchange.key.token.takeIf { registered })
    }

    /**
     * Takes the NSTART slot (RFC 7252 §4.7) of a whole Non-confirmable Q-Block body of [bodyBytes] bytes
     * towards [peer] (RFC 9177 §7.2), waiting while the peer's slots are taken, as a request would. The
     * body's messages then go out with [Pacing.Body] and take no slot of their own. The lease holds the slot
     * until [releaseBody], or for min([bodyBytes] ÷ PROBING_RATE, NON_PROBING_WAIT) at most, with the body's
     * NON_TIMEOUT_RANDOM [nonTimeoutRandom], drawn from [CoapEndpointConfig.random] when null and kept in
     * [BodyLease.nonTimeoutRandom]. A body that ends because the peer never answered may leave the lease to
     * expire instead of releasing it, so the next body to that peer waits as PROBING_RATE asks.
     *
     * The datagram link owns the leases. Towards a peer on a connection the lease is granted at once and paces
     * nothing (RFC 8323 has no NSTART), without opening the connection. A caller cancelled while waiting leaves
     * the queue.
     *
     * @throws CoapException like [closeCause] once the endpoint is closed, with
     *   [CoapException.Reason.SESSION_ENDED] when the peer's DTLS session ends while it waits, or with
     *   [CoapException.Reason.IDENTIFIERS_EXHAUSTED] beyond [CoapEndpointConfig.maxExchangesPerPeer].
     */
    internal suspend fun leaseBody(peer: Endpoint, bodyBytes: Int, nonTimeoutRandom: Duration? = null): BodyLease {
        require(bodyBytes >= 0) { "A body cannot have a negative size: $bodyBytes" }
        attach()
        if (isOnConnection(peer)) {
            // Without opening the connection, which the body's first message does.
            lock.withLock { ensureOpen() }
            return ConnectionLink.lease(peer, nonTimeoutRandom)
        }
        return datagramLinkTo(peer).leaseBody(bodyBytes, nonTimeoutRandom)
    }

    /**
     * The body of [lease] ended: its slot is free at once (RFC 7252 §4.7), and later messages may no longer
     * use the lease. Idempotent; cleanup, so it runs to the end in a cancelled coroutine too.
     */
    internal suspend fun releaseBody(lease: BodyLease): Unit = withContext(NonCancellable) {
        if (lease.isPaced) datagram?.release(lease) else ConnectionLink.release(lease)
    }

    /** Body leases that hold a slot or wait for one, for tests. */
    internal suspend fun leaseCount(): Int = datagram?.leaseCount() ?: 0

    /** Exchanges in the registry, for tests. */
    internal suspend fun exchangeCount(): Int = lock.withLock { exchanges.size }

    private fun ClientExchange.markReplaced() {
        // The new exchange took over the token; the old one gets no more responses.
        launchGuarded("cancel a replaced exchange") { cancel() }
    }

    /**
     * Launches [block] in the endpoint's scope as a failure boundary: an exception is logged and handed to
     * [onFailure], and never escapes to the scope's exception handler.
     */
    internal fun launchGuarded(
        name: String,
        onFailure: suspend (Exception) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Job = scope.launch(CoroutineName(name)) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Failed to $name" }
            try {
                withContext(NonCancellable) { onFailure(e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Cleaning up after a failure to $name failed" }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Closes the endpoint: the terminal transition, with [CoapException.Reason.CLOSED]. Open exchanges fail
     * with it, observations end, connections are released (RFC 8323 §5.5), the endpoint's coroutines are
     * cancelled and the transports closed. Returns once that is done; it does not wait for the cancelled
     * coroutines, so a request handler may call it. Idempotent: after any terminal transition it only
     * waits for that one to finish.
     */
    public suspend fun close() {
        shutdown(CoapException("Endpoint closed", CoapException.Reason.CLOSED))
        closed.await()
    }

    /**
     * Waits until the endpoint is closed, by any path, and every coroutine it ran has finished, request
     * handlers included; returns [closeCause]. For graceful shutdown and tests. Called from one of the
     * endpoint's own coroutines, such as a request handler, it never returns.
     */
    public suspend fun awaitClosed(): CoapException {
        val cause = closed.await()
        job.join()
        return cause
    }

    /**
     * The terminal transition; only the first call does anything. Under the lock the state becomes closed,
     * so no exchange is registered after it; then the datagram link fails its transmissions, which it checks
     * the closed state for before it sends anything new, exchanges fail, the server drops its observers,
     * connections are released, the transport is closed and the coroutines are cancelled. Runs to the end
     * even in a cancelled coroutine.
     */
    private suspend fun shutdown(cause: CoapException): Unit = withContext(NonCancellable) {
        val open = lock.withLock {
            if (closeCause != null) return@withContext
            closeCause = cause
            exchangesPerPeer.clear()
            exchanges.values.toList().also { exchanges.clear() }
        }
        // The datagram link takes its lock here after closeCause is set, so once it failed what it had, nothing
        // new goes out.
        datagram?.close(cause)
        open.forEach { it.fail(cause) }
        // Each step runs even when one before it failed, so the endpoint always ends.
        closingStep("stop the server") { requestHandler?.onEndpointClosed() }
        closingStep("close the connections") { connections.close() }
        datagram?.closeTransport()
        // Not joined: this may run on one of the endpoint's own coroutines, such as a handler.
        job.cancel()
        closed.complete(cause)
        log.info { "Endpoint closed: ${cause.message}" }
    }

    private suspend fun closingStep(name: String, step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Failed to $name while closing" }
        }
    }

    /**
     * Throws an exception like [closeCause] once the endpoint is closed: called under [lock], or under a link's
     * lock before it sends anything new, which [shutdown] takes after it set [closeCause].
     */
    internal fun ensureOpen() {
        closeCause?.let { throw closedError(it) }
    }

    /** What a call fails with once the endpoint is closed: like [closeCause], or CLOSED while it is open. */
    internal fun closedError(): CoapException =
        closedError(closeCause ?: CoapException("Endpoint closed", CoapException.Reason.CLOSED))

    /** A new exception like [cause], for one more caller that fails with it. */
    internal fun closedError(cause: CoapException): CoapException =
        CoapException(cause.message ?: "Endpoint closed", cause.reason, cause.cause)

    // ---------------------------------------------------------------- what the links deliver

    /**
     * A request on [link], new (not a duplicate); [sequence] is its number on a connection. A side without a
     * server rejects it (RFC 7252 §4.2), or on a link without Resets answers it with 5.01 Not Implemented,
     * since RFC 8323 §3.3 has every request on a connection answered with an error at least.
     */
    internal suspend fun onRequest(link: PeerLink, message: CoapMessage, sequence: Long = -1) {
        val request = InboundRequest(link, message, sequence)
        val handler = requestHandler
        if (handler == null) {
            if (link.capabilities.hasReset) {
                request.reject("no server on this endpoint")
            } else {
                request.respond(CoapMessage.response(message, CoapCode.NOT_IMPLEMENTED))
            }
            return
        }
        launchHandler(handler, request)
    }

    /**
     * Runs [handler] on [request] in a coroutine of its own, while fewer than
     * [CoapEndpointConfig.maxConcurrentRequests] run; otherwise the request is refused at once, so neither
     * the receive loop nor the reader of a connection ever waits for a handler.
     */
    private suspend fun launchHandler(handler: RequestHandler, request: InboundRequest) {
        if (!handlerPermits.tryAcquire()) return refuseOverloaded(request)
        var ran = false
        val job = scope.launch(handler.context + CoroutineName("coap-request")) {
            ran = true
            try {
                handler.handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Request handler failed on ${request.message} from ${request.peer}" }
            } finally {
                withContext(NonCancellable) { answerAbandoned(request) }
            }
        }
        // Also when the coroutine never ran, cancelled with its server before it started.
        job.invokeOnCompletion {
            handlerPermits.release()
            if (!ran) launchGuarded("answer a request whose handler never ran") { answerAbandoned(request) }
        }
    }

    /**
     * RFC 8323 §3.3: every request on a connection is answered. One whose handler ended without answering,
     * cancelled with its server or never started, gets 5.03 Service Unavailable, so the peer does not wait
     * for its own timeout and Pongs with Custody (§5.4.1) and a Release drain (§5.5) do not wait for it.
     * On a link with message types nothing is owed: the messaging layer acknowledges a Confirmable request on
     * its own.
     */
    private suspend fun answerAbandoned(request: InboundRequest) {
        if (request.link.capabilities.hasMessageTypes || request.isAnswered) return
        log.debug { "Answering an abandoned request from ${request.peer} with 5.03" }
        try {
            request.respond(CoapMessage.response(request.message, CoapCode.SERVICE_UNAVAILABLE))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "Could not answer an abandoned request from ${request.peer}: ${e.message}" }
        }
    }

    /**
     * RFC 7252 §5.9.3.4: 5.03 Service Unavailable, with Max-Age saying when to try again. Piggybacked on the
     * ACK, it is at most 3 bytes larger than the request (the Max-Age option), an amplification factor below
     * 2 even for the smallest request (§11.3), and a duplicate of the request gets the same answer from the
     * deduplication cache. A Non-confirmable request is dropped.
     */
    private suspend fun refuseOverloaded(request: InboundRequest) {
        emit(EndpointEvent.LimitReached(request.peer, EndpointEvent.LimitReached.Limit.CONCURRENT_REQUESTS))
        log.debug { "Refused a request from ${request.peer}: ${config.maxConcurrentRequests} handlers are running" }
        if (!request.isConfirmable) return
        try {
            request.respond(CoapMessage.response(request.message, CoapCode.SERVICE_UNAVAILABLE) { options { maxAge(OVERLOAD_MAX_AGE) } })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "Could not refuse a request from ${request.peer}: ${e.message}" }
        }
    }

    /**
     * A response on [link] that did not come piggybacked, new (not a duplicate), matched by token among the
     * exchanges with its peer, and on a connection among that connection's (RFC 8323 §3.3).
     *
     * A Confirmable response is acknowledged only once its exchange took it. An ACK tells the sender that the
     * message arrived, so it is never sent again (RFC 7252 §4.2); one that the full [ExchangeInbox] of a Q-Block
     * body has no room for is therefore left unacknowledged ([PeerLink.deferResponse]), and its retransmission
     * after ACK_TIMEOUT is delivered again. Whether it fits is decided before it is verified, with an
     * [ExchangeInbox.Reservation], so the security layer records nothing for it: OSCORE would otherwise have its
     * Partial IV in the replay window and refuse the retransmission, which carries the same one (RFC 8613 §7.4).
     * A Non-confirmable one that does not fit counts as lost.
     */
    internal suspend fun onResponse(link: PeerLink, message: CoapMessage) {
        val exchange = lock.withLock { exchanges[ExchangeKey(link.peer, message.tokenHex)] }?.takeIf { it.link == link }
        if (exchange == null) {
            // RFC 7252 §5.3.2 and RFC 7641 §3.5: an unexpected response is rejected, which also cancels an
            // observation the client forgot (RFC 7641 §3.6); a connection has no Reset and drops it.
            link.rejectResponse(message, "no exchange for token ${message.tokenHex}")
            return
        }
        // RFC 8323 §3.1: on a connection there is nothing to answer at the messaging layer.
        if (!link.capabilities.hasMessageTypes) return exchange.onResponse(message)
        // A Confirmable response that the body's inbox has no room for is left for its retransmission before
        // anything verifies it, so the security layer records nothing for it (RFC 8613 §7.4).
        val inbox = (exchange.mode as? ResponseMode.Inbox)?.inbox?.takeIf { message.type == CoapMessageType.CON }
        val reservation = inbox?.let {
            it.tryReserve() ?: return link.deferResponse(message)
        }
        // RFC 7252 §5.4.1: whether a Confirmable response is acknowledged or rejected depends on its critical
        // options, which only the verified response shows; so it is answered after both, and an accepted one
        // only once it was handed on: if it was not taken, it is left for its sender to send again (§4.2). No
        // timer answers it meanwhile (UdpMessaging), so every way out of here answers or defers it.
        val taken = try {
            when (val inspection = exchange.inspect(message)) {
                is ClientExchange.Inspection.Accepted -> exchange.accept(inspection.response, reservation)
                is ClientExchange.Inspection.Rejected -> {
                    // The exchange ends, and its last event takes the room back.
                    reservation?.release()
                    link.rejectResponse(message, inspection.cause.message ?: "unrecognized critical option")
                    exchange.refuse(inspection.cause)
                    return
                }
                is ClientExchange.Inspection.Unverified -> exchange.acceptUnverified(inspection.response, inspection.reason, reservation)
                ClientExchange.Inspection.Dropped -> true
            }
        } catch (e: Throwable) {
            // An unexpected failure, of the message security or while the response was handed on: it is dropped,
            // as one that failed verification, but acknowledged, so the peer does not retransmit it until it gives
            // up and, for a notification, removes the observer (RFC 7641 §4.5). An answered one stays answered.
            withContext(NonCancellable) { link.acceptResponse(message) }
            throw e
        } finally {
            reservation?.release()
        }
        if (taken) link.acceptResponse(message) else link.deferResponse(message)
    }

    /**
     * Everything bound to [link] ends with [cause]: the DTLS session to its peer ended (RFC 7252 §9.1.1), or
     * its connection closed (RFC 8323 §3.4, §7.4). Its exchanges fail, what the clients learned about the peer
     * and its token state go, and the server drops its observers and transfers.
     */
    internal suspend fun onLinkEnded(link: PeerLink, cause: CoapException) {
        val peer = link.peer
        val bound = lock.withLock {
            tokens.forget(peer)
            exchanges.values.filter { it.link == link }
        }
        forgetPeer(peer)
        bound.forEach { it.fail(cause) }
        requestHandler?.onSessionEnded(peer)
    }

    private fun emit(event: EndpointEvent) {
        try {
            config.eventListener.onEvent(event)
        } catch (e: Exception) {
            log.error(e) { "Event listener failed on $event" }
        }
    }

    /** Emits an [EndpointEvent] from the server side or a link. */
    internal fun report(event: EndpointEvent) {
        emit(event)
    }

    override fun toString(): String = "CoapEndpoint(${datagram?.transport ?: "no datagram transport"})"

    private fun describeTransports(connectors: List<Connector>, listeners: List<ConnectionListener>): String =
        listOfNotNull(
            datagram?.transport?.toString(),
            connectors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "connectors for ") { connector ->
                connector.schemes.joinToString("/") { it.scheme }
            },
            listeners.takeIf { it.isNotEmpty() }?.joinToString(prefix = "listeners ") { it.toString() },
        ).joinToString(", ").ifEmpty { "no transport" }

    private companion object {
        const val TOKEN_ATTEMPTS = 16

        /** Seconds after which a client refused for overload may try again. */
        const val OVERLOAD_MAX_AGE = 2L
    }
}
