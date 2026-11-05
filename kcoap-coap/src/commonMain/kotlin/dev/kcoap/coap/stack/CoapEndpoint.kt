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
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
 * Creating the endpoint starts its receive, accept and timer coroutines in [scope]; [close] stops them
 * and closes the transports. Cancelling [scope] stops them too. All state is guarded by locks that are
 * never held across a suspension, so the endpoint is safe to use from any number of coroutines.
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
    private val lock = Mutex()
    private val messaging = UdpMessaging(
        config.parameters,
        transport.maxDatagramSize,
        config.separateResponseDelay,
        config.random,
        config.interceptors,
        config.loggerFactory,
    )
    private val exchanges = HashMap<ExchangeKey, ClientExchange>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val clockStart = config.timeSource.markNow()
    private val job = SupervisorJob(scope.coroutineContext[Job])

    /** Where the endpoint runs its own coroutines and those of request handlers. */
    internal val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + job + CoroutineName("coap-endpoint"))

    private val reliable = ReliableConnections(this, connectors, listeners, this.scope, config)

    @Volatile
    internal var requestHandler: RequestHandler? = null

    @Volatile
    /** True once [close] was called. */
    public var isClosed: Boolean = false
        private set

    init {
        if (hasDatagramTransport) {
            this.scope.launch { receiveLoop() }
            this.scope.launch { timerLoop() }
        }
        reliable.start()
        log.info { "Endpoint started on ${describeTransports(connectors, listeners)}" }
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
        check(!isClosed) { "Endpoint is closed" }
        if (isReliable(peer)) return openReliableExchange(peer, request, security, isObservation, token)
        requireDatagramTransport(peer)
        val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
        val (exchange, messageId) = lock.withLock {
            val exchange = register(peer, token, isObservation)
            exchange to messaging.allocateMessageId(peer, now())
        }
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
            exchange.transmission = withMessaging { now -> send(now, peer, wire, exchange, slotHold) }
            log.with("peer" to peer, "token" to exchange.key.token).debug { "Request ${plain.code} ${plain.options.path} as $type" }
        } catch (e: Throwable) {
            forget(exchange)
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
        val connection = reliable.connectionFor(peer)
        val exchange = lock.withLock { register(peer, token, isObservation).also { it.connection = connection } }
        connection.exchangeOpened()
        val plain = request.toBuilder().apply { this.token = exchange.token }.build()
        try {
            val protected = security?.protectRequest(plain, peer)
            exchange.protectedRequest = protected
            val wire = protected?.message?.toBuilder()?.apply { this.token = exchange.token }?.build() ?: plain
            exchange.request = wire
            connection.send(wire)
            log.with("peer" to peer, "token" to exchange.key.token).debug { "Request ${plain.code} ${plain.options.path}" }
        } catch (e: Throwable) {
            forget(exchange)
            throw e
        }
        return exchange
    }

    /** Called under [lock]: a new exchange with [token] or a fresh one, replacing an exchange that had it. */
    private fun register(peer: Endpoint, token: ByteArray?, isObservation: Boolean): ClientExchange {
        val chosen = token ?: allocateToken(peer)
        val exchange = ClientExchange(this, peer, chosen, isObservation, log.with("peer" to peer, "token" to chosen.toHex()))
        exchanges.put(exchange.key, exchange)?.let { previous -> if (previous !== exchange) previous.markReplaced() }
        return exchange
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
            val token = config.tokenGenerator.next(peer)
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
        check(!isClosed) { "Endpoint is closed" }
        if (isReliable(peer)) {
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
        val transmission = withMessaging { now ->
            send(now, peer, CoapMessage.empty(CoapMessageType.CON, allocateMessageId(peer, now)), owner)
        }
        try {
            return answer.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { settle(transmission) }
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

    /** The exchange ends: its token is free again, and its connection no longer counts it as busy. */
    internal suspend fun forget(exchange: ClientExchange) {
        var first = false
        val registered = lock.withLock {
            first = !exchange.isForgotten
            exchange.isForgotten = true
            (exchanges[exchange.key] === exchange).also { if (it) exchanges.remove(exchange.key) }
        }
        if (first) exchange.connection?.exchangeClosed(exchange.key.token.takeIf { registered })
    }

    internal suspend fun settle(transmission: Transmission) {
        withMessaging { now -> settle(now, transmission) }
    }

    private fun ClientExchange.markReplaced() {
        // The new exchange took over the token; the old one gets no more responses.
        scope.launch { cancel() }
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
            send(now, peer, message.withHeader(type, allocateMessageId(peer, now)), owner, slotHold)
        }
    }

    /** Replaces an outstanding notification with a newer one; see [UdpMessaging.supersede]. */
    internal suspend fun replaceOutstanding(transmission: Transmission, message: CoapMessage): Boolean =
        withMessaging { supersede(transmission, message) }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Stops the endpoint: fails open exchanges with [CoapException.Reason.CLOSED], releases its
     * connections (RFC 8323 §5.5), stops its coroutines and closes the transports. Idempotent.
     */
    public suspend fun close() {
        if (isClosed) return
        isClosed = true
        withContext(NonCancellable) {
            withMessaging { close() }
            val open = lock.withLock { exchanges.values.toList().also { exchanges.clear() } }
            val cause = CoapException("Endpoint closed", CoapException.Reason.CLOSED)
            open.forEach { it.fail(cause) }
            reliable.close()
            // Not joined: close() may run on one of the endpoint's own coroutines, such as a handler.
            job.cancel()
            runCatching { transport.close() }
        }
        log.info { "Endpoint closed" }
    }

    // ---------------------------------------------------------------- driving the state machine

    /** Runs [block] on the state machine under the lock, then executes its effects outside the lock. */
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
            execute(effects)
            if (signal) wakeups.trySend(Unit)
        }
        return result
    }

    private suspend fun execute(effects: List<MessagingEffect>) {
        for (effect in effects) {
            when (effect) {
                is MessagingEffect.Transmit -> transmit(effect.to, effect.bytes)
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

    private suspend fun transmit(to: Endpoint, bytes: ByteArray) {
        try {
            transport.send(to, bytes)
        } catch (e: SessionEndedException) {
            onSessionEnded(e.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // UDP gives no delivery guarantee anyway; retransmission covers a failed send.
            log.warn(e) { "Sending ${bytes.size} bytes to $to failed" }
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

    private fun launchHandler(handler: RequestHandler, request: InboundRequest) {
        scope.launch(CoroutineName("coap-request")) {
            try {
                handler.handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Request handler failed on ${request.message} from ${request.peer}" }
            }
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
            config.tokenGenerator.forget(peer)
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
            config.tokenGenerator.forget(peer)
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

    private suspend fun receiveLoop() {
        while (true) {
            val event = try {
                transport.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isClosed) log.error(e) { "Transport receive failed; the endpoint stops receiving" }
                return
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

    private suspend fun timerLoop() {
        while (true) {
            val next = lock.withLock { messaging.nextDeadline() }
            val wait = next?.minus(now())
            when {
                wait == null -> wakeups.receive()
                wait.isPositive() -> withTimeoutOrNull(wait) { wakeups.receive() }
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
