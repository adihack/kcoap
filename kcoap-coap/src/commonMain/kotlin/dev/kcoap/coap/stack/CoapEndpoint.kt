package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.common.toHex
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.DatagramEvent
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
 * and its responses, notifications and failures stay with it (RFC 8323 §3.3, §7.4).
 *
 * The endpoint runs its coroutines in [scope] and inherits its dispatcher. It starts receiving and
 * accepting once a [dev.kcoap.coap.server.CoapServer] is attached or the first request or ping goes out,
 * so a server's resources are in place before the first request arrives, whichever of a server and a
 * [dev.kcoap.coap.client.CoapClient] sharing the endpoint is created first. On Android or iOS pass a scope such as `lifecycleScope + Dispatchers.Default` to keep the
 * receive loop, timers and handlers off the main thread.
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
    /** An endpoint on [transport], for `coap` peers. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapEndpointConfig = CoapEndpointConfig(),
    ) : this(transport, emptyList(), emptyList(), scope, config)

    /**
     * An endpoint on any combination of transports: [transport] for `coap` peers, [connectors] to open
     * connections to peers of the RFC 8323 schemes they serve, [listeners] to accept connections.
     * [close] closes all of them.
     */
    public constructor(
        scope: CoroutineScope,
        config: CoapEndpointConfig = CoapEndpointConfig(),
        transport: DatagramTransport? = null,
        connectors: List<Connector> = emptyList(),
        listeners: List<ConnectionListener> = emptyList(),
    ) : this(transport, connectors, listeners, scope, config)

    /**
     * The datagram transport. An endpoint created without one has a placeholder that refuses to send, and
     * requests to datagram peers fail at once.
     */
    public val transport: DatagramTransport = datagramTransport ?: NoDatagramTransport

    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE)

    /** Guards the exchanges and the closed state; never held across a suspension. Internal for tests. */
    internal val lock = Mutex()
    private val exchanges = HashMap<ExchangeKey, ClientExchange>()

    /** How many of [exchanges] each peer has, for [CoapEndpointConfig.maxExchangesPerPeer]; under [lock]. */
    private val exchangesPerPeer = HashMap<Endpoint, Int>()

    /** One permit per running request handler, [CoapEndpointConfig.maxConcurrentRequests] in all. */
    private val handlerPermits = Semaphore(config.maxConcurrentRequests)
    private val clockStart = config.timeSource.markNow()

    /** Warns when the time source and the dispatcher's clock disagree; shared with the connections. */
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

    private val reliable = ReliableConnections(this, connectors, listeners, this.scope, config)

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
                reliable.start()
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

    /** The largest message the datagram transport carries; block sizes towards datagram peers follow it. */
    public val maxDatagramSize: Int get() = transport.maxDatagramSize

    /** The parameters messaging runs with. */
    public val parameters: TransmissionParameters get() = config.parameters

    /** Time on [CoapEndpointConfig.timeSource] since the endpoint started; every timer uses it. */
    internal fun now(): Duration = clockStart.elapsedNow()

    /** Whether [peer] is reached over a reliable transport (RFC 8323) rather than datagrams. */
    internal fun isReliable(peer: Endpoint): Boolean = ReliableConnections.schemeOrNull(peer) != null

    /**
     * What one message to [peer] may be: the datagram size, or the peer's Max-Message-Size on its
     * connection (RFC 8323 §5.3.1), which this opens if needed.
     */
    internal suspend fun limits(peer: Endpoint): MessageLimits =
        if (isReliable(peer)) limits(reliable.connectionFor(peer)) else datagramLimits

    /** The limits of messages on [connection], or of datagrams when it is null. */
    internal fun limits(connection: ConnectionDriver?): MessageLimits {
        if (connection == null) return datagramLimits
        val settings = connection.settings
        return MessageLimits(
            maxMessageSize = settings.maxMessageSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            codec = connection.codec,
            // RFC 8323 §6: BERT only when both CSMs indicated it.
            allowsBert = settings.supportsBert && config.reliable.announcesBert,
            isReliable = true,
        )
    }

    private val datagramLimits: MessageLimits
        get() = datagram?.limits ?: MessageLimits(transport.maxDatagramSize, UdpMessageCodec, allowsBert = false, isReliable = false)

    // ---------------------------------------------------------------- client side

    /**
     * Sends [request] (code, options and payload; header fields are set here) to [peer] and returns the
     * exchange that collects its responses as [mode] says. The token comes from
     * [CoapEndpointConfig.tokenGenerator] unless [token] is given, which re-registers or cancels an
     * observation (RFC 7641 §3.3.1, §3.6) and replaces the exchange that had it. The token and Message ID
     * are assigned before [security] protects the request, and kept afterwards. A response with a critical
     * option outside [recognizedOptions] is rejected (RFC 7252 §5.4.1). Over a reliable transport,
     * [confirmable] and [pacing] do not apply.
     *
     * @param pacing [Pacing.PerMessage], or [Pacing.Body] for a Non-confirmable message of a Q-Block body
     *   whose granted [BodyLease] towards [peer] holds the slot (RFC 9177 §7.2).
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
        if (isReliable(peer)) return openReliableExchange(peer, request, security, mode, token, recognizedOptions)
        val link = requireDatagramTransport(peer)
        if (pacing is Pacing.Body) {
            require(!confirmable) { "A Confirmable body takes a slot per message (RFC 9177 §7.1), not a body lease" }
            require(pacing.lease.peer == peer) { "The body lease is towards ${pacing.lease.peer}, not $peer" }
        }
        val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
        val exchange = lock.withLock {
            // Checked under the lock that the terminal transition takes, so no exchange is added after they
            // were all failed: it fails at once instead of waiting for a response that never comes.
            ensureOpen()
            register(peer, token, mode, recognizedOptions)
        } ?: throw tooManyExchanges(peer)
        try {
            val messageId = link.allocateMessageId(peer)
            val plain = request.toBuilder().apply {
                this.type = type
                this.messageId = messageId
                this.token = exchange.token
            }.build()
            val protected = security?.protectRequest(plain, peer)
            exchange.protectedRequest = protected
            val wire = protected?.message?.toBuilder()?.apply {
                this.type = type
                this.messageId = messageId
                this.token = exchange.token
            }?.build() ?: plain
            exchange.request = wire
            // A message of a body takes no slot of its own: the body's lease holds one for all of them.
            val slotHold = if (confirmable || pacing is Pacing.Body) null else link.nonConfirmableSlotHold(wire)
            link.send(peer, wire, exchange, slotHold, (pacing as? Pacing.Body)?.lease) { exchange.transmission = it }
            log.with("peer" to peer, "token" to exchange.key.token).debug { "Request ${plain.code} ${plain.options.path} as $type" }
        } catch (e: Throwable) {
            withContext(NonCancellable) { exchange.cancel() }
            throw e
        }
        return exchange
    }

    /**
     * RFC 8323 §3.3: the request goes on the connection to [peer], opened if needed, without Type or
     * Message ID; the token is unique among the exchanges with [peer], so on its connection.
     */
    private suspend fun openReliableExchange(
        peer: Endpoint,
        request: CoapMessage,
        security: ClientMessageSecurity?,
        mode: ResponseMode,
        token: ByteArray?,
        recognizedOptions: Set<Int>,
    ): ClientExchange {
        lock.withLock { ensureOpen() }
        // Counted on the connection before the request is, so its idle Release cannot start in between.
        val connection = reliable.reservedConnectionFor(peer)
        val exchange = try {
            lock.withLock {
                ensureOpen()
                register(peer, token, mode, recognizedOptions)?.also { it.connection = connection }
            } ?: throw tooManyExchanges(peer)
        } catch (e: Throwable) {
            withContext(NonCancellable) { connection.exchangeClosed(null) }
            throw e
        }
        val plain = request.toBuilder().apply { this.token = exchange.token }.build()
        try {
            val protected = security?.protectRequest(plain, peer)
            exchange.protectedRequest = protected
            val wire = protected?.message?.toBuilder()?.apply { this.token = exchange.token }?.build() ?: plain
            exchange.request = wire
            connection.send(wire)
            log.with("peer" to peer, "token" to exchange.key.token).debug { "Request ${plain.code} ${plain.options.path}" }
        } catch (e: Throwable) {
            withContext(NonCancellable) { exchange.cancel() }
            throw e
        }
        return exchange
    }

    /**
     * Called under [lock]: a new exchange with [token] or a fresh one, replacing an exchange that had it;
     * null when [peer] has [CoapEndpointConfig.maxExchangesPerPeer] already.
     */
    private fun register(
        peer: Endpoint,
        token: ByteArray?,
        mode: ResponseMode,
        recognizedOptions: Set<Int>,
    ): ClientExchange? {
        val replacing = token != null && ExchangeKey(peer, token.toHex()) in exchanges
        if (!replacing && (exchangesPerPeer[peer] ?: 0) >= config.maxExchangesPerPeer) return null
        val chosen = token ?: allocateToken(peer)
        val logger = log.with("peer" to peer, "token" to chosen.toHex())
        val exchange = ClientExchange(this, peer, chosen, mode, recognizedOptions, logger)
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

    private fun requireDatagramTransport(peer: Endpoint): DatagramLink =
        requireNotNull(datagram) { "$peer needs a DatagramTransport; this endpoint has none" }

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
     */
    public suspend fun ping(peer: Endpoint): Boolean = ping(peer, custody = false)

    /**
     * Like [ping]. To a reliable peer, [custody] asks it to answer only once it has processed every
     * request it received before the Ping (RFC 8323 §5.4.1); datagram pings ignore it.
     */
    public suspend fun ping(peer: Endpoint, custody: Boolean): Boolean {
        attach()
        if (isReliable(peer)) {
            lock.withLock { ensureOpen() }
            return try {
                reliable.connectionFor(peer).ping(custody, config.reliable.pingTimeout)
            } catch (e: CoapException) {
                log.debug { "Ping of $peer failed: ${e.message}" }
                false
            }
        }
        return requireDatagramTransport(peer).ping(peer)
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
     * The exchange ends: its token is free again, and its connection no longer counts it as busy. Cleanup,
     * so it runs to the end in a cancelled coroutine too.
     */
    internal suspend fun forget(exchange: ClientExchange): Unit = withContext(NonCancellable) {
        var first = false
        val registered = lock.withLock {
            first = !exchange.isForgotten
            exchange.isForgotten = true
            (exchanges[exchange.key] === exchange).also { if (it) unregister(exchange) }
        }
        if (first) exchange.connection?.exchangeClosed(exchange.key.token.takeIf { registered })
    }

    /** Stops working on [transmission]; cleanup, so it runs to the end in a cancelled coroutine too. */
    internal suspend fun settle(transmission: Transmission): Unit = withContext(NonCancellable) {
        datagram?.settle(transmission)
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
     * Towards a reliable peer the lease is granted at once and paces nothing (RFC 8323 has no NSTART). A
     * caller cancelled while waiting leaves the queue.
     *
     * @throws CoapException like [closeCause] once the endpoint is closed, with
     *   [CoapException.Reason.SESSION_ENDED] when the peer's DTLS session ends while it waits, or with
     *   [CoapException.Reason.IDENTIFIERS_EXHAUSTED] beyond [CoapEndpointConfig.maxExchangesPerPeer].
     */
    internal suspend fun leaseBody(peer: Endpoint, bodyBytes: Int, nonTimeoutRandom: Duration? = null): BodyLease {
        require(bodyBytes >= 0) { "A body cannot have a negative size: $bodyBytes" }
        attach()
        if (isReliable(peer)) {
            lock.withLock { ensureOpen() }
            return BodyLease.unpaced(peer, nonTimeoutRandom ?: Duration.ZERO)
        }
        return requireDatagramTransport(peer).lease(peer, bodyBytes, nonTimeoutRandom)
    }

    /**
     * The body of [lease] ended: its slot is free at once (RFC 7252 §4.7), and later messages may no longer
     * use the lease. Idempotent; cleanup, so it runs to the end in a cancelled coroutine too.
     */
    internal suspend fun releaseBody(lease: BodyLease): Unit = withContext(NonCancellable) {
        // A lease that paces nothing, towards a reliable peer, holds no slot to give back.
        if (lease.isPaced) datagram?.release(lease) else lease.state = BodyLease.State.RELEASED
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

    // ---------------------------------------------------------------- server side

    /**
     * Answers [request]: on its connection when it came over one (RFC 8323 §3.3), else piggybacked or
     * separately; returns the transmission of a separate datagram response.
     */
    internal suspend fun respond(
        request: InboundRequest,
        response: CoapMessage,
        owner: TransmissionOwner?,
        separateType: CoapMessageType,
    ): Transmission? {
        val connection = request.connection
        if (connection != null) {
            connection.respond(request.sequence, response)
            return null
        }
        return requireDatagramTransport(request.peer)
            .respond(request.peer, request.message.messageId, request.message.type, response, owner, separateType)
    }

    /** Sends an Empty ACK; reliable transports have none, the response itself acknowledges (RFC 8323 §3.1). */
    internal suspend fun acknowledge(request: InboundRequest) {
        if (request.connection != null) return
        requireDatagramTransport(request.peer).acknowledge(request.peer, request.message.messageId)
    }

    /** The request is done without a response (RFC 7967): an Empty ACK, or on a connection a note for Custody. */
    internal suspend fun finishWithoutResponse(request: InboundRequest) {
        val connection = request.connection ?: return acknowledge(request)
        connection.answered(request.sequence)
    }

    internal suspend fun reject(request: InboundRequest, reason: String) {
        val connection = request.connection
        if (connection != null) {
            // RFC 8323 has no Reset; the request is only dropped.
            log.debug { "Dropped a request from ${request.peer}: $reason" }
            connection.answered(request.sequence)
            return
        }
        requireDatagramTransport(request.peer).reject(request.peer, request.message.messageId, reason)
    }

    /**
     * Sends a notification or other server-initiated response in a message of its own. To a reliable peer
     * it goes on [connection], or the peer's connection, as it is: there are no message types
     * (RFC 8323 §7.2), so the returned transmission is already done, and a closed connection is reported to
     * [owner] as a failure.
     */
    internal suspend fun sendSeparately(
        peer: Endpoint,
        message: CoapMessage,
        type: CoapMessageType,
        owner: TransmissionOwner,
        slotHold: Duration? = null,
        connection: ConnectionDriver? = null,
    ): Transmission {
        if (isReliable(peer)) {
            val transmission = Transmission(peer, message, null, owner, null).apply { state = Transmission.State.DONE }
            lock.withLock { ensureOpen() }
            try {
                val target = connection ?: reliable.existing(peer)
                    ?: throw CoapException("No connection to $peer", CoapException.Reason.CONNECTION_CLOSED)
                target.send(message)
            } catch (e: CoapException) {
                owner.onOutcome(transmission, TransmissionOutcome.Failed(e))
            }
            return transmission
        }
        return requireDatagramTransport(peer).sendSeparately(peer, message, type, owner, slotHold)
    }

    /** Replaces an outstanding notification with a newer one; see [UdpMessaging.supersede]. */
    internal suspend fun replaceOutstanding(transmission: Transmission, message: CoapMessage): Boolean =
        datagram?.supersede(transmission, message) ?: false

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
     * so nothing new is registered or sent after it, and the messaging layer fails its transmissions; then,
     * outside the lock, exchanges fail, the server drops its observers, connections are released, the
     * transport is closed and the coroutines are cancelled. Runs to the end even in a cancelled coroutine.
     */
    private suspend fun shutdown(cause: CoapException): Unit = withContext(NonCancellable) {
        val open = lock.withLock {
            if (closeCause != null) return@withContext
            closeCause = cause
            exchangesPerPeer.clear()
            exchanges.values.toList().also { exchanges.clear() }
        }
        // The datagram link checks the closed state under its own lock before it sends anything new, and takes
        // that lock here after closeCause is set, so once it failed what it had nothing new goes out.
        datagram?.close(cause)
        open.forEach { it.fail(cause) }
        // Each step runs even when one before it failed, so the endpoint always ends.
        closingStep("stop the server") { requestHandler?.onEndpointClosed() }
        closingStep("close the connections") { reliable.close() }
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

    internal fun closedError(cause: CoapException): CoapException =
        CoapException(cause.message ?: "Endpoint closed", cause.reason, cause.cause)

    // ---------------------------------------------------------------- what the links deliver

    /** A request in a datagram from [from], new (not a duplicate). */
    internal suspend fun onDatagramRequest(from: Endpoint, message: CoapMessage) {
        val handler = requestHandler
        if (handler == null) {
            // RFC 7252 §4.2: a recipient that lacks the context to process a message rejects it.
            requireDatagramTransport(from).reject(from, message.messageId, "no server on this endpoint")
            return
        }
        launchHandler(handler, InboundRequest(this, from, message))
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
     * Over datagrams nothing is owed: the messaging layer acknowledges a Confirmable request on its own.
     */
    private suspend fun answerAbandoned(request: InboundRequest) {
        val connection = request.connection ?: return
        if (request.isAnswered) return
        log.debug { "Answering an abandoned request from ${request.peer} with 5.03" }
        try {
            connection.respond(request.sequence, CoapMessage.response(request.message, CoapCode.SERVICE_UNAVAILABLE))
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

    /** A response in a datagram of its own from [from], Confirmable or Non-confirmable, new (not a duplicate). */
    internal suspend fun onSeparateResponse(from: Endpoint, message: CoapMessage) {
        val link = requireDatagramTransport(from)
        val exchange = lock.withLock { exchanges[ExchangeKey(from, message.tokenHex)] }
        if (exchange == null) {
            // RFC 7252 §5.3.2 and RFC 7641 §3.5: an unexpected response is rejected, which also cancels an
            // observation the client forgot (RFC 7641 §3.6).
            link.reject(from, message.messageId, "no exchange for token ${message.tokenHex}")
            return
        }
        // RFC 7252 §5.4.1: whether a Confirmable response is acknowledged or rejected depends on its critical
        // options, which only the verified response shows; so it is answered after both, and before it is
        // handed on. No timer answers it meanwhile (UdpMessaging), so every way out of here answers it.
        val inspection = try {
            exchange.inspect(message)
        } catch (e: Throwable) {
            // An unexpected failure of the message security: the response is dropped, as one that failed
            // verification, but acknowledged, so the peer does not retransmit it until it gives up.
            link.acknowledge(from, message.messageId)
            throw e
        }
        when (inspection) {
            is ClientExchange.Inspection.Accepted -> {
                link.acknowledge(from, message.messageId)
                exchange.accept(inspection.response)
            }
            is ClientExchange.Inspection.Rejected -> {
                val reason = inspection.cause.message ?: "unrecognized critical option"
                link.reject(from, message.messageId, reason)
                exchange.refuse(inspection.cause)
            }
            is ClientExchange.Inspection.Unverified -> {
                link.acknowledge(from, message.messageId)
                exchange.acceptUnverified(inspection.response, inspection.reason)
            }
            ClientExchange.Inspection.Dropped -> link.acknowledge(from, message.messageId)
        }
    }

    /** RFC 7252 §9.1.1: the DTLS session to [peer] ended, after the datagram link failed what it had for it. */
    internal suspend fun onSessionEnded(peer: Endpoint) {
        val bound = lock.withLock {
            tokens.forget(peer)
            exchanges.values.filter { it.peer == peer }
        }
        val cause = CoapException("DTLS session to $peer ended", CoapException.Reason.SESSION_ENDED)
        forgetPeer(peer)
        bound.forEach { it.fail(cause) }
        requestHandler?.onSessionEnded(peer)
    }

    // ---------------------------------------------------------------- reliable transports

    /**
     * A request on a connection. RFC 8323 §3.3: a side without a CoAP server MUST answer every request with
     * an error; 5.01 Not Implemented is the simplest.
     */
    internal suspend fun onReliableRequest(connection: ConnectionDriver, message: CoapMessage, sequence: Long) {
        val request = InboundRequest(this, connection.peer, message, connection, sequence)
        val handler = requestHandler
        if (handler == null) {
            request.respond(CoapMessage.response(message, CoapCode.NOT_IMPLEMENTED))
            return
        }
        launchHandler(handler, request)
    }

    /**
     * A response on a connection, matched by token among that connection's exchanges. RFC 8323 §7.2 lets a
     * client abort on a notification with an unknown token; here it is only dropped.
     */
    internal suspend fun onReliableResponse(connection: ConnectionDriver, message: CoapMessage) {
        val exchange = lock.withLock { exchanges[ExchangeKey(connection.peer, message.tokenHex)] }
            ?.takeIf { it.connection === connection }
        if (exchange == null) {
            log.debug { "Dropped a response from ${connection.peer} that matches no exchange" }
            emit(EndpointEvent.MessageRejected(connection.peer, 0, "no exchange for token ${message.tokenHex}"))
            return
        }
        exchange.onResponse(message)
    }

    /** RFC 8323 §3.4, §7.4: the connection's exchanges fail, and its observers and transfers are dropped. */
    internal suspend fun onConnectionClosed(connection: ConnectionDriver, reason: String) {
        val peer = connection.peer
        log.debug { "Connection to $peer closed: $reason" }
        val bound = lock.withLock {
            tokens.forget(peer)
            exchanges.values.filter { it.connection === connection }
        }
        val cause = CoapException("The connection to $peer closed: $reason", CoapException.Reason.CONNECTION_CLOSED)
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

    /** Emits an [EndpointEvent] from the server side or a connection. */
    internal fun report(event: EndpointEvent) {
        emit(event)
    }

    override fun toString(): String = "CoapEndpoint($transport)"

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

/** The datagram transport of an endpoint that has none: nothing ever arrives, and sending is refused. */
private object NoDatagramTransport : DatagramTransport {
    override val maxDatagramSize: Int = 1152

    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        throw IllegalStateException("This endpoint has no DatagramTransport")
    }

    override suspend fun receive(): DatagramEvent = awaitCancellation()

    override suspend fun close() {}

    override fun toString(): String = "no datagram transport"
}
