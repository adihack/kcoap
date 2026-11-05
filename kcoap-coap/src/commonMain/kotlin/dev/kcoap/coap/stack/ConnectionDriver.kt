package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.codec.MessageCodec
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.Connection
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.PeerIdentity
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** What a [ConnectionDriver] reports to the endpoint that owns it. */
internal interface ConnectionOwner {
    /** The endpoint's clock. */
    fun now(): Duration

    /** Checks that the clock advances with the dispatcher's. */
    val clockCheck: ClockCheck

    /** Throws an exception like [CoapEndpoint.closeCause] once the endpoint is closed. */
    fun ensureOpen()

    /** A request arrived on [driver]; it is answered through [ConnectionDriver.link]. */
    suspend fun onRequest(driver: ConnectionDriver, message: CoapMessage, sequence: Long)

    /** A response or notification arrived on [driver]. */
    suspend fun onResponse(driver: ConnectionDriver, message: CoapMessage)

    /** The peer released the connection (RFC 8323 §5.5). */
    suspend fun onReleased(driver: ConnectionDriver, holdOff: Duration?)

    /** [driver]'s connection is over; called once. */
    suspend fun onClosed(driver: ConnectionDriver, reason: String)

    fun onEvent(event: EndpointEvent)
}

/**
 * Runs one connection of CoAP over a reliable transport: a reader feeds [ReliableMessaging] what
 * arrives, a writer sends what it produces in exactly that order, and a timer loop fires its deadlines.
 * All state is behind one lock that is never held across a suspension; writes are queued under it, so
 * the order on the wire is the state machine's order however many coroutines send at once.
 *
 * Sending requests and responses waits while too much is queued for a peer that reads slowly, so a
 * busy server holds back instead of buffering without bound; signaling messages are never held back.
 */
internal class ConnectionDriver(
    val connection: Connection,
    val isInitiator: Boolean,
    private val owner: ConnectionOwner,
    private val scope: CoroutineScope,
    config: CoapEndpointConfig,
) {
    val peer: Endpoint = connection.remote

    /** How the exchange layer sends and answers on this connection. */
    val link: ConnectionLink = ConnectionLink(this, owner, config)
    private val reliableConfig = config.reliable
    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE).with("peer" to peer)
    private val lock = Mutex()
    private val framing = when (connection) {
        is ByteStreamConnection -> Framing.BYTE_STREAM
        is MessageConnection -> Framing.MESSAGE_STREAM
    }
    private val messaging = ReliableMessaging(peer, framing, isInitiator, reliableConfig, config.interceptors, config.loggerFactory)
    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)
    private val queuedBytes = MutableStateFlow(0L)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val pings = HashMap<PendingPing, CompletableDeferred<Boolean>>()
    private var writer: Job? = null

    /** Completes when the peer's first CSM arrived; fails if the connection closes before. */
    val established: CompletableDeferred<Unit> = CompletableDeferred()

    /** Completes with the reason once the connection is over. */
    val closed: CompletableDeferred<String> = CompletableDeferred()

    /** Completes once the owner cleaned up after the close, so the peer's slot is free. */
    val retired: CompletableDeferred<Unit> = CompletableDeferred()

    /** The peer's settings from its CSMs. */
    @Volatile
    var settings: PeerSettings = PeerSettings.BASE
        private set

    /** Whether new requests may go out here: the CSMs are exchanged and nobody released or closed it. */
    @Volatile
    var isUsable: Boolean = false
        private set

    val peerIdentity: PeerIdentity get() = connection.peerIdentity

    /** The message framing, for sizing blocks. */
    val codec: MessageCodec get() = framing.codec

    /**
     * Queues the CSM, then starts writing, reading and the timers. The state machine starts first, since
     * a peer may send its own CSM at once, and on a multi-threaded dispatcher the reader would see it. A
     * connection this side opens starts with one exchange counted for its opener, so it is not released
     * as idle before [handOver].
     *
     * Not suspending, so that the owner registers and starts a driver in one step under its own lock, and
     * a close in between can never find it registered but not started. Until this returns nothing else can
     * reach the driver, so its lock is free; starting only queues the CSM, which [absorb] does.
     */
    fun start() {
        check(lock.tryLock()) { "$this is in use before it started" }
        try {
            messaging.start(owner.now())
            if (isInitiator) messaging.exchangeOpened()
            absorb(messaging.takeEffects())
        } finally {
            lock.unlock()
        }
        wakeups.trySend(Unit)
        writer = scope.launch(CoroutineName("coap-connection-writer")) { writeLoop() }
        scope.launch(CoroutineName("coap-connection-reader")) { readLoop() }
        scope.launch(CoroutineName("coap-connection-timers")) { timerLoop() }
    }

    /** Waits until the connection is established, or else gone. */
    suspend fun awaitSettled() {
        if (!established.isCompleted) {
            try {
                established.await()
                return
            } catch (e: CoapException) {
                // Closed before the CSM exchange: wait until it is gone.
            }
        }
        retired.await()
    }

    // ---------------------------------------------------------------- what the endpoint asks for

    /**
     * Sends a request or a response.
     *
     * @throws CoapException with [CoapException.Reason.CONNECTION_CLOSED] or
     *   [CoapException.Reason.MESSAGE_TOO_LARGE].
     */
    suspend fun send(message: CoapMessage) {
        queuedBytes.first { it < HIGH_WATER_MARK || closed.isCompleted }
        withMessaging { now -> send(now, message) }
    }

    /**
     * Answers the request delivered with [sequence]: RFC 8323 §3.3 requires the response on the same
     * connection. A response for a connection that closed meanwhile is lost, as messages in flight may
     * be (RFC 8323 §3.4). The request counts as answered however this ends, cancelled included, so Pongs
     * with Custody and a Release drain never wait for it.
     */
    suspend fun respond(sequence: Long, response: CoapMessage) {
        try {
            send(response)
        } catch (e: CoapException) {
            if (e.reason != CoapException.Reason.CONNECTION_CLOSED) throw e
            log.debug { "Response ${response.code} not sent: ${e.message}" }
        } finally {
            withContext(NonCancellable) { answered(sequence) }
        }
    }

    /** The request delivered with [sequence] is done without a response (RFC 7967), for Custody and Release. */
    suspend fun answered(sequence: Long) {
        withMessaging { answered(sequence) }
    }

    /**
     * The opener hands the connection over to the [count] callers that waited for it: each gets an exchange
     * counted, in one step with dropping the opener's own (see [start]), so the idle Release (RFC 8323 §5.5)
     * cannot start before they ran, however late a single-threaded event loop resumes them. False when the
     * connection no longer takes requests; the opener's count goes either way.
     */
    suspend fun handOver(count: Int): Boolean = withMessaging {
        val usable = isEstablished && !isReleasing
        if (usable) repeat(count) { exchangeOpened() }
        exchangeClosed(null)
        usable
    }

    /**
     * Counts a new exchange on this connection, if it still takes new requests: checked and counted under
     * the lock, so an idle Release (RFC 8323 §5.5) cannot start between the two. False when the connection
     * is being released or is gone; the caller then waits for the next one.
     */
    suspend fun reserve(): Boolean = withMessaging(signal = false) {
        (isEstablished && !isReleasing).also { if (it) exchangeOpened() }
    }

    /** An exchange ended; [tokenHex] is its token if no newer exchange took it over. */
    suspend fun exchangeClosed(tokenHex: String?) {
        withMessaging { exchangeClosed(tokenHex) }
    }

    /** Sends a Ping (RFC 8323 §5.4); true when the Pong arrives within [timeout]. */
    suspend fun ping(custody: Boolean, timeout: Duration): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val ping = withMessaging { now -> ping(now, custody).also { pings[it] = answer } }
        var answered: Boolean? = null
        try {
            answered = withTimeoutOrNull(timeout) { answer.await() }
            return answered == true
        } finally {
            // Timed out or cancelled: the ping is forgotten either way, so it holds nothing.
            if (answered == null) {
                withContext(NonCancellable) {
                    withMessaging {
                        cancelPing(ping)
                        pings.remove(ping)
                    }
                }
            }
        }
    }

    /** Sends a Release (RFC 8323 §5.5); the peer is expected to close. */
    suspend fun release(diagnostic: String) {
        withMessaging { now -> release(now, diagnostic) }
    }

    /** Sends an Abort (RFC 8323 §5.6) and closes. */
    suspend fun abort(diagnostic: String) {
        withMessaging { abort(diagnostic) }
    }

    /** Releases and closes at once, then waits up to [flushTimeout] for what is queued to be written. */
    suspend fun shutdown(diagnostic: String, flushTimeout: Duration) {
        withMessaging { now -> shutdown(now, diagnostic) }
        withTimeoutOrNull(flushTimeout) { writer?.join() }
    }

    // ---------------------------------------------------------------- driving the state machine

    private suspend fun <T> withMessaging(signal: Boolean = true, block: ReliableMessaging.(now: Duration) -> T): T {
        var effects: List<ConnectionEffect> = emptyList()
        val result = try {
            lock.withLock {
                try {
                    messaging.block(owner.now())
                } finally {
                    effects = messaging.takeEffects()
                    absorb(effects)
                }
            }
        } finally {
            // The state machine committed to these effects, which may be another exchange's or the close of
            // the connection, so they run to the end even when the caller was cancelled meanwhile.
            if (effects.isNotEmpty()) withContext(NonCancellable) { execute(effects) }
            if (signal) wakeups.trySend(Unit)
        }
        return result
    }

    /** The effects that must happen under the lock: writes in order, and state the endpoint reads without it. */
    private fun absorb(effects: List<ConnectionEffect>) {
        for (effect in effects) {
            when (effect) {
                is ConnectionEffect.Write -> {
                    queuedBytes.update { it + effect.bytes.size }
                    outbound.trySend(effect.bytes)
                }
                is ConnectionEffect.Established -> settings = effect.settings
                is ConnectionEffect.PingAnswered -> pings.remove(effect.ping)?.complete(true)
                is ConnectionEffect.PingFailed -> pings.remove(effect.ping)?.complete(false)
                is ConnectionEffect.Closed -> {
                    outbound.close()
                    queuedBytes.value = 0
                }
                else -> Unit
            }
        }
        if (messaging.isEstablished) settings = messaging.peerSettings
        isUsable = messaging.isEstablished && !messaging.isReleasing
    }

    private suspend fun execute(effects: List<ConnectionEffect>) {
        for (effect in effects) {
            when (effect) {
                is ConnectionEffect.DeliverRequest -> owner.onRequest(this, effect.message, effect.sequence)
                is ConnectionEffect.DeliverResponse -> owner.onResponse(this, effect.message)
                is ConnectionEffect.Established -> established.complete(Unit)
                is ConnectionEffect.PeerReleased -> owner.onReleased(this, effect.holdOff)
                is ConnectionEffect.Closed -> {
                    established.completeExceptionally(
                        CoapException("The connection to $peer closed: ${effect.reason}", CoapException.Reason.CONNECTION_CLOSED),
                    )
                    if (closed.complete(effect.reason)) owner.onClosed(this, effect.reason)
                }
                is ConnectionEffect.Event -> owner.onEvent(effect.event)
                is ConnectionEffect.Write, is ConnectionEffect.PingAnswered, is ConnectionEffect.PingFailed -> Unit
            }
        }
    }

    /**
     * Reads until the connection ends. After each read it yields, so the handlers of the requests it delivered
     * get to run before the next read on a dispatcher of one thread: a peer that writes many requests back to
     * back, such as the blocks of a Q-Block1 body (RFC 9177 Appendix B), then does not run the endpoint into
     * [CoapEndpointConfig.maxConcurrentRequests] while every request is waiting to start. The reader still never
     * waits for a handler.
     */
    private suspend fun readLoop() {
        val reason = try {
            when (connection) {
                is ByteStreamConnection -> {
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    while (!closed.isCompleted) {
                        val count = connection.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count > 0) withMessaging { now -> onReceived(now, buffer.copyOf(count)) }
                        yield()
                    }
                }
                is MessageConnection -> {
                    while (!closed.isCompleted) {
                        val message = connection.receive() ?: break
                        withMessaging { now -> onReceived(now, message) }
                        yield()
                    }
                }
            }
            "closed by the peer"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.isCompleted) log.debug { "Reading failed: ${e.message}" }
            "reading failed: ${e.message}"
        }
        withMessaging { onTransportClosed(reason) }
    }

    private suspend fun writeLoop() {
        try {
            for (bytes in outbound) {
                when (connection) {
                    is ByteStreamConnection -> connection.write(bytes)
                    is MessageConnection -> connection.send(bytes)
                }
                queuedBytes.update { it - bytes.size }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "Writing failed: ${e.message}" }
            withMessaging { onTransportClosed("writing failed: ${e.message}") }
        } finally {
            withContext(NonCancellable) {
                try {
                    connection.close()
                } catch (e: Exception) {
                    log.debug { "Closing failed: ${e.message}" }
                }
            }
        }
    }

    private suspend fun timerLoop() {
        while (!closed.isCompleted) {
            val next = lock.withLock { messaging.nextDeadline() }
            val wait = next?.minus(owner.now())
            when {
                wait == null -> wakeups.receive()
                wait.isPositive() -> {
                    val before = owner.now()
                    if (withTimeoutOrNull(wait) { wakeups.receive() } == null) owner.clockCheck.afterTimeout(wait, owner.now() - before)
                }
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

    override fun toString(): String = "ConnectionDriver($peer, initiator=$isInitiator)"

    private companion object {
        const val READ_BUFFER_SIZE = 8192

        /** Requests and responses wait while this much is queued; one larger message still passes. */
        const val HIGH_WATER_MARK = 256L * 1024
    }
}
