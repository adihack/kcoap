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
import dev.kcoap.transport.SessionEndedException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
import kotlinx.coroutines.channels.Channel
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
 * accepting once a [dev.kcoap.coap.server.CoapServer] or [dev.kcoap.coap.client.CoapClient] is attached
 * (or the first request or ping goes out), so a server's resources are in place before the first request
 * arrives. On Android or iOS pass a scope such as `lifecycleScope + Dispatchers.Default` to keep the
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

    private val hasDatagramTransport = datagramTransport != null
    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE)
    /** Guards the messaging state machine and the exchanges; never held across a suspension. Internal for tests. */
    internal val lock = Mutex()
    private val messaging = UdpMessaging(
        config.parameters,
        transport.maxDatagramSize,
        config.separateResponseDelay,
        config.random,
        config.interceptors,
        config.loggerFactory,
        config.maxDeduplicationEntries,
        config.maxExchangesPerPeer,
    )
    private val exchanges = HashMap<ExchangeKey, ClientExchange>()

    /** How many of [exchanges] each peer has, for [CoapEndpointConfig.maxExchangesPerPeer]; under [lock]. */
    private val exchangesPerPeer = HashMap<Endpoint, Int>()

    /** One permit per running request handler, [CoapEndpointConfig.maxConcurrentRequests] in all. */
    private val handlerPermits = Semaphore(config.maxConcurrentRequests)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
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

    private val reliable = ReliableConnections(this, connectors, listeners, this.scope, config)

    @Volatile
    internal var requestHandler: RequestHandler? = null

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

    /** Completes when a server or client attaches; only then does the endpoint receive and accept. */
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
                if (hasDatagramTransport) {
                    val timers = launch(CoroutineName("coap-timers")) { timerLoop() }
                    failure = receiveLoop()
                    timers.cancel()
                } else {
                    awaitCancellation()
                }
            } finally {
                shutdown(failure ?: CoapException("Endpoint closed: its scope was cancelled", CoapException.Reason.CLOSED))
            }
        }
    }

    /**
     * Starts receiving and accepting, once: called when a server or client attaches, and before the first
     * request or ping. Until then datagrams and connections wait in the transports.
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
        get() = MessageLimits(transport.maxDatagramSize, UdpMessageCodec, allowsBert = false, isReliable = false)

    // ---------------------------------------------------------------- client side

    /**
     * Sends [request] (code, options and payload; header fields are set here) to [peer] and returns the
     * exchange that collects its responses. The token comes from [CoapEndpointConfig.tokenGenerator]
     * unless [token] is given, which re-registers or cancels an observation (RFC 7641 §3.3.1, §3.6) and
     * replaces the exchange that had it. The token and Message ID are assigned before [security]
     * protects the request, and kept afterwards. Over a reliable transport, [confirmable] does not apply.
     */
    internal suspend fun openExchange(
        peer: Endpoint,
        request: CoapMessage,
        confirmable: Boolean,
        security: ClientMessageSecurity?,
        isObservation: Boolean,
        token: ByteArray? = null,
    ): ClientExchange {
        attach()
        if (isReliable(peer)) return openReliableExchange(peer, request, security, isObservation, token)
        requireDatagramTransport(peer)
        val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
        val (exchange, messageId) = lock.withLock {
            // Checked under the lock that the terminal transition takes, so no exchange is added after they
            // were all failed: it fails at once instead of waiting for a response that never comes.
            ensureOpen()
            register(peer, token, isObservation)?.let { it to messaging.allocateMessageId(peer, now()) }
        } ?: throw tooManyExchanges(peer)
        val plain = request.toBuilder().apply {
            this.type = type
            this.messageId = messageId
            this.token = exchange.token
        }.build()
        try {
            val protected = security?.protectRequest(plain, peer)
            exchange.protectedRequest = protected
            val wire = protected?.message?.toBuilder()?.apply {
                this.type = type
                this.messageId = messageId
                this.token = exchange.token
            }?.build() ?: plain
            exchange.request = wire
            val slotHold = if (confirmable) null else nonConfirmableSlotHold(wire)
            // Recorded under the lock, before the datagram goes out: a cancellation while sending can then
            // still settle the transmission, which would otherwise retransmit for nobody and hold NSTART.
            withMessaging { now ->
                ensureOpen()
                exchange.transmission = send(now, peer, wire, exchange, slotHold)
            }
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
        isObservation: Boolean,
        token: ByteArray?,
    ): ClientExchange {
        lock.withLock { ensureOpen() }
        // Counted on the connection before the request is, so its idle Release cannot start in between.
        val connection = reliable.reservedConnectionFor(peer)
        val exchange = try {
            lock.withLock {
                ensureOpen()
                register(peer, token, isObservation)?.also { it.connection = connection }
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
    private fun register(peer: Endpoint, token: ByteArray?, isObservation: Boolean): ClientExchange? {
        val replacing = token != null && ExchangeKey(peer, token.toHex()) in exchanges
        if (!replacing && (exchangesPerPeer[peer] ?: 0) >= config.maxExchangesPerPeer) return null
        val chosen = token ?: allocateToken(peer)
        val exchange = ClientExchange(this, peer, chosen, isObservation, log.with("peer" to peer, "token" to chosen.toHex()))
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

    private fun requireDatagramTransport(peer: Endpoint) {
        require(hasDatagramTransport) { "$peer needs a DatagramTransport; this endpoint has none" }
    }

    /**
     * RFC 7252 §4.7: a Non-confirmable request occupies its NSTART slot until its response arrives, or
     * for as long as sending it took at PROBING_RATE, so an endpoint that never answers is probed no
     * faster than that.
     */
    private fun nonConfirmableSlotHold(message: CoapMessage): Duration =
        (UdpMessageCodec.encode(message).size.toDouble() / config.parameters.probingRate).seconds

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
        requireDatagramTransport(peer)
        val answer = CompletableDeferred<Boolean>()
        val owner = TransmissionOwner { _, outcome ->
            when (outcome) {
                // A peer that acknowledges instead of resetting is alive as well.
                TransmissionOutcome.Reset, is TransmissionOutcome.Acknowledged -> answer.complete(true)
                TransmissionOutcome.TimedOut -> answer.complete(false)
                is TransmissionOutcome.Failed -> answer.completeExceptionally(outcome.cause)
                TransmissionOutcome.SlotReleased -> Unit
            }
        }
        var transmission: Transmission? = null
        try {
            withMessaging { now ->
                ensureOpen()
                transmission = send(now, peer, CoapMessage.empty(CoapMessageType.CON, allocateMessageId(peer, now)), owner)
            }
            return answer.await()
        } catch (e: Throwable) {
            transmission?.let { settle(it) }
            throw e
        }
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
        withMessaging { now -> settle(now, transmission) }
    }

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
        return withMessaging { now ->
            respond(now, request.peer, request.message.messageId, request.message.type, response, owner, separateType)
        }
    }

    /** Sends an Empty ACK; reliable transports have none, the response itself acknowledges (RFC 8323 §3.1). */
    internal suspend fun acknowledge(request: InboundRequest) {
        if (request.connection != null) return
        withMessaging { acknowledge(request.peer, request.message.messageId) }
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
        withMessaging { reject(request.peer, request.message.messageId, reason) }
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
        return withMessaging { now ->
            ensureOpen()
            send(now, peer, message.withHeader(type, allocateMessageId(peer, now)), owner, slotHold)
        }
    }

    /** Replaces an outstanding notification with a newer one; see [UdpMessaging.supersede]. */
    internal suspend fun replaceOutstanding(transmission: Transmission, message: CoapMessage): Boolean =
        withMessaging { supersede(transmission, message) }

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
        var effects: List<MessagingEffect> = emptyList()
        val open = lock.withLock {
            if (closeCause != null) return@withContext
            closeCause = cause
            messaging.close(cause)
            effects = messaging.takeEffects()
            exchangesPerPeer.clear()
            exchanges.values.toList().also { exchanges.clear() }
        }
        execute(effects)
        open.forEach { it.fail(cause) }
        requestHandler?.onEndpointClosed()
        reliable.close()
        try {
            transport.close()
        } catch (e: Exception) {
            log.debug { "Closing the transport failed: ${e.message}" }
        }
        // Not joined: this may run on one of the endpoint's own coroutines, such as a handler.
        job.cancel()
        closed.complete(cause)
        log.info { "Endpoint closed: ${cause.message}" }
    }

    /** Called under [lock]: throws an exception like [closeCause] once the endpoint is closed. */
    private fun ensureOpen() {
        closeCause?.let { throw closedError(it) }
    }

    /** What a call fails with once the endpoint is closed: like [closeCause], or CLOSED while it is open. */
    internal fun closedError(): CoapException =
        closedError(closeCause ?: CoapException("Endpoint closed", CoapException.Reason.CLOSED))

    private fun closedError(cause: CoapException): CoapException =
        CoapException(cause.message ?: "Endpoint closed", cause.reason, cause.cause)

    // ---------------------------------------------------------------- driving the state machine

    /**
     * Runs [block] on the state machine under the lock, then executes its effects outside the lock. The
     * effects run to the end even when the calling coroutine is cancelled meanwhile: the state machine has
     * committed to them, and they may belong to other exchanges, such as a queued request that a released
     * NSTART slot started. A cancelled caller sees its cancellation at its next suspension.
     */
    private suspend fun <T> withMessaging(signal: Boolean = true, block: UdpMessaging.(now: Duration) -> T): T {
        var effects: List<MessagingEffect> = emptyList()
        val result = try {
            lock.withLock {
                try {
                    messaging.block(now())
                } finally {
                    effects = messaging.takeEffects()
                }
            }
        } finally {
            if (effects.isNotEmpty()) withContext(NonCancellable) { execute(effects) }
            if (signal) wakeups.trySend(Unit)
        }
        return result
    }

    private suspend fun execute(effects: List<MessagingEffect>) {
        for (effect in effects) {
            when (effect) {
                is MessagingEffect.Transmit -> transmit(effect)
                is MessagingEffect.DeliverRequest -> onRequest(effect.from, effect.message)
                is MessagingEffect.DeliverResponse -> onSeparateResponse(effect.from, effect.message)
                is MessagingEffect.Completed -> effect.transmission.owner?.let { owner ->
                    try {
                        owner.onOutcome(effect.transmission, effect.outcome)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "Transmission owner failed on ${effect.outcome}" }
                    }
                }
                is MessagingEffect.Event -> emit(effect.event)
            }
        }
    }

    /**
     * Sends one datagram. A [SessionEndedException] ends the peer's DTLS session. An
     * [IllegalArgumentException] means the transport cannot carry this datagram to this peer at all, such
     * as an endpoint type it does not serve: a local error that no retransmission fixes, so the message
     * fails at once with [CoapException.Reason.TRANSPORT_FAILED] instead of looking like a silent peer
     * after MAX_TRANSMIT_WAIT. Any other failure is transient, and retransmission covers it, as it covers a
     * datagram the network lost.
     */
    private suspend fun transmit(effect: MessagingEffect.Transmit) {
        val to = effect.to
        try {
            transport.send(to, effect.bytes)
        } catch (e: SessionEndedException) {
            onSessionEnded(e.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            log.error(e) { "The transport refused to send ${effect.bytes.size} bytes to $to" }
            val transmission = effect.transmission ?: return
            val cause = CoapException("The transport cannot send to $to: ${e.message}", CoapException.Reason.TRANSPORT_FAILED, e)
            withMessaging { now -> abandon(now, transmission, cause) }
        } catch (e: Exception) {
            // UDP gives no delivery guarantee anyway; retransmission covers a failed send.
            log.warn(e) { "Sending ${effect.bytes.size} bytes to $to failed" }
        }
    }

    private suspend fun onRequest(from: Endpoint, message: CoapMessage) {
        val handler = requestHandler
        if (handler == null) {
            // RFC 7252 §4.2: a recipient that lacks the context to process a message rejects it.
            withMessaging { reject(from, message.messageId, "no server on this endpoint") }
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
        val job = scope.launch(handler.context + CoroutineName("coap-request")) {
            try {
                handler.handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Request handler failed on ${request.message} from ${request.peer}" }
            }
        }
        // Also when the coroutine never ran, cancelled with its server before it started.
        job.invokeOnCompletion { handlerPermits.release() }
    }

    /**
     * RFC 7252 §5.9.3.4: 5.03 Service Unavailable, with Max-Age saying when to try again. Piggybacked on the
     * ACK, it is no larger than the request, so it amplifies nothing (§11.3), and a duplicate of the request
     * gets the same answer from the deduplication cache. A Non-confirmable request is dropped.
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

    private suspend fun onSeparateResponse(from: Endpoint, message: CoapMessage) {
        val exchange = lock.withLock { exchanges[ExchangeKey(from, message.tokenHex)] }
        if (exchange == null) {
            // RFC 7252 §5.3.2 and RFC 7641 §3.5: an unexpected response is rejected, which also cancels an
            // observation the client forgot (RFC 7641 §3.6).
            withMessaging { reject(from, message.messageId, "no exchange for token ${message.tokenHex}") }
            return
        }
        withMessaging { acknowledge(from, message.messageId) }
        exchange.onSeparateResponse(message)
    }

    private suspend fun onSessionEnded(peer: Endpoint) {
        log.info { "Session to $peer ended" }
        withMessaging { onSessionEnded(peer) }
        val bound = lock.withLock {
            tokens.forget(peer)
            exchanges.values.filter { it.peer == peer }
        }
        val cause = CoapException("DTLS session to $peer ended", CoapException.Reason.SESSION_ENDED)
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
        exchange.onSeparateResponse(message)
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

    /**
     * Receives until the endpoint closes. Returns null when it closed, or the failure when the transport's
     * receive failed: a transport that cannot receive is gone for good (a socket the system closed, a
     * network that went away), so the endpoint closes with [CoapException.Reason.TRANSPORT_FAILED] instead
     * of looking alive while every request runs into a timeout.
     */
    private suspend fun receiveLoop(): CoapException? {
        while (true) {
            val event = try {
                transport.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isClosed) return null
                log.error(e) { "The datagram transport failed; the endpoint closes" }
                return CoapException("The datagram transport failed: ${e.message}", CoapException.Reason.TRANSPORT_FAILED, e)
            }
            try {
                when (event) {
                    is DatagramEvent.Received -> withMessaging { now -> onDatagram(now, event.from, event.datagram) }
                    is DatagramEvent.SessionEnded -> onSessionEnded(event.endpoint)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Failed to process a datagram" }
            }
        }
    }

    /**
     * Fires the messaging layer's timers. It waits with the dispatcher's delays for deadlines read on the
     * time source, so the two must be one clock ([clockCheck] warns when they are not). A wakeup means the
     * state changed: the next deadline is recomputed, and the timers run only once one is due.
     */
    private suspend fun timerLoop() {
        while (true) {
            val next = lock.withLock { messaging.nextDeadline() }
            val wait = next?.minus(now())
            if (wait == null) {
                wakeups.receive()
                continue
            }
            if (wait.isPositive()) {
                val before = now()
                if (withTimeoutOrNull(wait) { wakeups.receive() } != null) continue
                clockCheck.afterTimeout(wait, now() - before)
            }
            try {
                withMessaging(signal = false) { now -> onTimer(now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Timer processing failed" }
            }
        }
    }

    override fun toString(): String = "CoapEndpoint($transport)"

    private fun describeTransports(connectors: List<Connector>, listeners: List<ConnectionListener>): String =
        listOfNotNull(
            transport.takeIf { hasDatagramTransport }?.toString(),
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
