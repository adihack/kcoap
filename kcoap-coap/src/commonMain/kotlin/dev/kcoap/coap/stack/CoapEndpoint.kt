package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.common.toHex
import dev.kcoap.log.LogTags
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A CoAP endpoint on a [DatagramTransport]: the RFC 7252 messaging layer and the exchange layer that
 * matches responses to requests by peer and token. [dev.kcoap.coap.client.CoapClient] and
 * [dev.kcoap.coap.server.CoapServer] are built on it and can share one endpoint, so a device can be
 * client and server on the same port.
 *
 * Creating the endpoint starts its receive and timer coroutines in [scope]; [close] stops them and closes
 * the transport. Cancelling [scope] stops them too. All state is guarded by one lock that is never held
 * across a suspension, so the endpoint is safe to use from any number of coroutines.
 *
 * ```
 * val endpoint = CoapEndpoint(KtorUdpTransport.bind(port = 5683), scope)
 * val server = CoapServer(endpoint) { resource("hello") { get { CoapResponse.content("Hello") } } }
 * val client = CoapClient(endpoint)
 * ```
 */
public class CoapEndpoint(
    public val transport: DatagramTransport,
    scope: CoroutineScope,
    public val config: CoapEndpointConfig = CoapEndpointConfig(),
) {
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

    @Volatile
    internal var requestHandler: RequestHandler? = null

    @Volatile
    public var isClosed: Boolean = false
        private set

    init {
        this.scope.launch { receiveLoop() }
        this.scope.launch { timerLoop() }
        log.info { "Endpoint started on $transport" }
    }

    /** The largest message the transport carries; block sizes are derived from it. */
    public val maxDatagramSize: Int get() = transport.maxDatagramSize

    /** The parameters messaging runs with. */
    public val parameters: TransmissionParameters get() = config.parameters

    /** Time on [CoapEndpointConfig.timeSource] since the endpoint started; every timer uses it. */
    internal fun now(): Duration = clockStart.elapsedNow()

    // ---------------------------------------------------------------- client side

    /**
     * Sends [request] (code, options and payload; header fields are set here) to [peer] and returns the
     * exchange that collects its responses. The token comes from [CoapEndpointConfig.tokenGenerator]
     * unless [token] is given, which re-registers or cancels an observation (RFC 7641 §3.3.1, §3.6) and
     * replaces the exchange that had it. The token and Message ID are assigned before [security]
     * protects the request, and kept afterwards.
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
        val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
        val (exchange, messageId) = lock.withLock {
            val chosen = token ?: allocateToken(peer)
            val exchange = ClientExchange(
                this, peer, chosen, isObservation,
                log.with("peer" to peer, "token" to chosen.toHex()),
            )
            exchanges.put(exchange.key, exchange)?.let { previous -> if (previous !== exchange) previous.markReplaced() }
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
     * Sends a CoAP ping (RFC 7252 §4.3): an Empty Confirmable message that a live peer answers with a
     * Reset. Returns true if the peer answered, false if it never did within MAX_TRANSMIT_WAIT.
     */
    public suspend fun ping(peer: Endpoint): Boolean {
        check(!isClosed) { "Endpoint is closed" }
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

    internal suspend fun forget(exchange: ClientExchange) {
        lock.withLock {
            if (exchanges[exchange.key] === exchange) exchanges.remove(exchange.key)
        }
    }

    internal suspend fun settle(transmission: Transmission) {
        withMessaging { now -> settle(now, transmission) }
    }

    private fun ClientExchange.markReplaced() {
        // The new exchange took over the token; the old one gets no more responses.
        scope.launch { cancel() }
    }

    // ---------------------------------------------------------------- server side

    internal suspend fun respond(
        request: InboundRequest,
        response: CoapMessage,
        owner: TransmissionOwner?,
        separateType: CoapMessageType,
    ): Transmission? = withMessaging { now ->
        respond(now, request.peer, request.message.messageId, request.message.type, response, owner, separateType)
    }

    internal suspend fun acknowledge(request: InboundRequest) {
        withMessaging { acknowledge(request.peer, request.message.messageId) }
    }

    internal suspend fun reject(request: InboundRequest, reason: String) {
        withMessaging { reject(request.peer, request.message.messageId, reason) }
    }

    /** Sends a notification or other server-initiated response in a message of its own. */
    internal suspend fun sendSeparately(
        peer: Endpoint,
        message: CoapMessage,
        type: CoapMessageType,
        owner: TransmissionOwner,
        slotHold: Duration? = null,
    ): Transmission = withMessaging { now ->
        send(now, peer, message.withHeader(type, allocateMessageId(peer, now)), owner, slotHold)
    }

    /** Replaces an outstanding notification with a newer one; see [UdpMessaging.supersede]. */
    internal suspend fun replaceOutstanding(transmission: Transmission, message: CoapMessage): Boolean =
        withMessaging { supersede(transmission, message) }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Stops the endpoint: fails open exchanges with [CoapException.Reason.CLOSED], stops its coroutines
     * and closes the transport. Idempotent.
     */
    public suspend fun close() {
        if (isClosed) return
        isClosed = true
        withContext(NonCancellable) {
            withMessaging { close() }
            val open = lock.withLock { exchanges.values.toList().also { exchanges.clear() } }
            val cause = CoapException("Endpoint closed", CoapException.Reason.CLOSED)
            open.forEach { it.fail(cause) }
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
        val request = InboundRequest(this, from, message)
        scope.launch(CoroutineName("coap-request")) {
            try {
                handler.handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Request handler failed on $message from $from" }
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

    private fun emit(event: EndpointEvent) {
        try {
            config.eventListener.onEvent(event)
        } catch (e: Exception) {
            log.error(e) { "Event listener failed on $event" }
        }
    }

    /** Emits a server-side [EndpointEvent]. */
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

    private companion object {
        const val TOKEN_ATTEMPTS = 16
    }
}
