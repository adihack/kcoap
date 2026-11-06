package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.log.LogTags
import dev.kcoap.transport.Connection
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The connections of a [CoapEndpoint] for CoAP over reliable transports (RFC 8323): opened on demand
 * through [connectors] when a request needs one, reused for every exchange with the same peer, released
 * when idle, and accepted from [listeners]. Each one is a [ConnectionLink] to its peer, on a
 * [ConnectionDriver].
 *
 * RFC 8323 §3 leaves connection management to the implementation; this one keeps at most one
 * connection per peer [Endpoint]. A connection that is being released takes no new requests, and a new
 * one to the same peer opens only after it closed, which [ReliableTransportConfig.releaseTimeout]
 * bounds. Hold-Off from a Release (§5.5) stops new connections to that peer for its duration.
 */
internal class ReliableConnections(
    private val endpoint: CoapEndpoint,
    private val connectors: List<Connector>,
    private val listeners: List<ConnectionListener>,
    private val scope: CoroutineScope,
    private val config: CoapEndpointConfig,
) : ConnectionOwner {
    private val log = config.loggerFactory.logger(LogTags.TRANSPORT)
    private val lock = Mutex()
    private val drivers = HashMap<Endpoint, ConnectionDriver>()
    private val connecting = HashMap<Endpoint, Attempt>()
    private val holdOffUntil = HashMap<Endpoint, Duration>()

    /** One permit per accepted connection still waiting for its peer's CSM. */
    private val pendingAccepted = Semaphore(config.reliable.maxPendingConnections)
    @Volatile
    private var isClosed = false

    /** The schemes this endpoint can open connections for. */
    val schemes: Set<CoapScheme> = connectors.flatMap { it.schemes }.toSet()

    /**
     * Whether a connector or a listener of this endpoint serves [scheme]. For `coaps+tcp` and `coaps+ws` that is
     * the transport's word that it runs TLS: a [Connector] lists, and a [ConnectionListener] reports, a TLS scheme
     * only when its connections are secured.
     */
    fun serves(scheme: CoapScheme): Boolean = scheme in schemes || listeners.any { it.scheme == scheme }

    fun start() {
        for (listener in listeners) scope.launch(CoroutineName("coap-accept-${listener.scheme.scheme}")) { acceptLoop(listener) }
    }

    /**
     * The link of the usable connection to [peer], opened if there is none: the transport connection, its
     * handshakes and the CSM exchange, within [ReliableTransportConfig.connectTimeout]. Concurrent
     * callers share one attempt.
     *
     * @throws CoapException with [CoapException.Reason.TRANSPORT_UNAVAILABLE] when [peer] has no connection
     *   and no connector serves its scheme; with [CoapException.Reason.CONNECTION_CLOSED] when the connection
     *   cannot be opened, or the peer asked not to reconnect yet (Hold-Off, §5.5); like
     *   [CoapEndpoint.closeCause] once the endpoint is closed.
     */
    suspend fun linkFor(peer: Endpoint): ConnectionLink = find(peer, reserve = false).link

    /**
     * Like [linkFor], with an exchange already counted on the connection for the caller, so its idle
     * Release (§5.5) cannot start before the caller's request goes out; the caller counts it out again with
     * [PeerLink.exchangeClosed]. A connection that started releasing meanwhile is skipped, and the next one
     * opens once it is gone.
     */
    suspend fun reservedLinkFor(peer: Endpoint): ConnectionLink = find(peer, reserve = true).link

    private suspend fun find(peer: Endpoint, reserve: Boolean): ConnectionDriver {
        val scheme = schemeOf(peer)
        while (true) {
            val step = lock.withLock {
                // The endpoint's own state is set first in its terminal transition, so no attempt starts after it.
                if (isClosed || endpoint.isClosed) throw endpoint.closedError()
                connecting[peer]?.let { attempt ->
                    if (reserve) attempt.reservations++
                    return@withLock Step.Join(attempt)
                }
                val existing = drivers[peer]
                when {
                    // An accepted connection serves requests to its peer too (RFC 8323 §3.3); a connector is
                    // needed only to open one.
                    existing == null -> {
                        val connector = connectors.firstOrNull { scheme in it.schemes } ?: throw CoapException(
                            "${scheme.scheme} needs a Connector; this endpoint has none for it",
                            CoapException.Reason.TRANSPORT_UNAVAILABLE,
                        )
                        Step.Join(startConnecting(peer, connector).also { if (reserve) it.reservations++ })
                    }
                    existing.isUsable -> Step.Use(existing)
                    else -> Step.Wait(existing)
                }
            }
            when (step) {
                is Step.Join -> {
                    val driver = join(step.attempt, reserve)
                    if (!reserve || step.attempt.isReserved) return driver
                }
                is Step.Use -> if (!reserve || step.driver.reserve()) return step.driver
                // Accepted and not established yet, or released: a new connection opens once this one is gone.
                is Step.Wait -> step.driver.awaitSettled()
            }
        }
    }

    /** Waits for [attempt]; a caller cancelled meanwhile gives its reservation back. */
    private suspend fun join(attempt: Attempt, reserved: Boolean): ConnectionDriver {
        try {
            return attempt.result.await()
        } catch (e: CancellationException) {
            if (reserved) withContext(NonCancellable) { giveBack(attempt) }
            throw e
        }
    }

    private suspend fun giveBack(attempt: Attempt) {
        val pending = lock.withLock { (!attempt.isSettled).also { if (it) attempt.reservations-- } }
        if (pending) return
        // The opener counted it already: wait for the hand-over, then count it out.
        val driver = try {
            attempt.result.await()
        } catch (e: CoapException) {
            return
        }
        if (attempt.isReserved) driver.exchangeClosed(null)
    }

    private sealed interface Step {
        class Join(val attempt: Attempt) : Step

        class Use(val driver: ConnectionDriver) : Step

        class Wait(val driver: ConnectionDriver) : Step
    }

    /**
     * One attempt to open a connection, which concurrent callers share. Those that will send an exchange on
     * it are counted in [reservations] while they wait, and the opener hands the connection over to all of
     * them in one step ([ConnectionDriver.handOver]), so none finds it released as idle. Fields other than
     * [result] and [isReserved] are guarded by [lock].
     */
    private class Attempt {
        val result = CompletableDeferred<ConnectionDriver>()
        var reservations = 0

        /** Set once the opener took [reservations]; a caller cancelled after that counts itself out on the connection. */
        var isSettled = false

        /** Whether the hand-over counted [reservations] on the connection; set before [result] completes. */
        @Volatile
        var isReserved = false
    }

    /** Called under [lock]. */
    private fun startConnecting(peer: Endpoint, connector: Connector): Attempt {
        holdOffUntil[peer]?.let { until ->
            val now = endpoint.now()
            if (now < until) {
                throw CoapException("$peer asked not to reconnect for ${until - now} (RFC 8323 §5.5 Hold-Off)", CoapException.Reason.CONNECTION_CLOSED)
            }
            holdOffUntil.remove(peer)
        }
        val attempt = Attempt()
        connecting[peer] = attempt
        val job = scope.launch(CoroutineName("coap-connect")) {
            val result = try {
                Result.success(open(peer, connector))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            // Every caller waits on the attempt, so it is settled even when the endpoint is closing.
            val reservations = withContext(NonCancellable) {
                lock.withLock {
                    connecting.remove(peer)
                    attempt.isSettled = true
                    attempt.reservations
                }
            }
            result.fold(
                onSuccess = { driver ->
                    attempt.isReserved = withContext(NonCancellable) { driver.handOver(reservations) }
                    attempt.result.complete(driver)
                },
                onFailure = { error ->
                    val cause = when {
                        error is CoapException -> error
                        // Cancelled itself, by the closing endpoint; a timeout of the attempt is a failure to connect.
                        error is CancellationException && !isActive -> endpoint.closedError()
                        else -> CoapException("Cannot connect to $peer: ${error.message}", CoapException.Reason.CONNECTION_CLOSED, error)
                    }
                    log.debug { cause.message ?: "Cannot connect to $peer" }
                    attempt.result.completeExceptionally(cause)
                },
            )
        }
        // A launch into a closing endpoint may never run its body; its callers must not wait forever.
        job.invokeOnCompletion { attempt.result.completeExceptionally(endpoint.closedError()) }
        return attempt
    }

    private suspend fun open(peer: Endpoint, connector: Connector): ConnectionDriver = withTimeout(config.reliable.connectTimeout) {
        val connection = connector.connect(peer)
        check(connection.remote == peer) { "The connector opened a connection to ${connection.remote}, not $peer" }
        val opened = withContext(NonCancellable) {
            val created = ConnectionDriver(connection, isInitiator = true, this@ReliableConnections, scope, config)
            if (registerAndStart(created) { null } != null) {
                connection.close()
                throw endpoint.closedError()
            }
            log.debug { "Connected to $peer" }
            created
        }
        try {
            // RFC 8323 §3.3 allows requests before the peer's CSM; waiting for it sizes them right from the start.
            opened.established.await()
        } catch (e: Throwable) {
            // No CSM in time, or the connection closed first: a started connection is aborted, never left open.
            withContext(NonCancellable) { opened.abort("Connection setup failed: ${e.message}") }
            throw e
        }
        opened
    }

    private suspend fun acceptLoop(listener: ConnectionListener) {
        while (true) {
            val connection = try {
                listener.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isClosed) log.error(e) { "Accepting ${listener.scheme.scheme} connections failed; the listener stops" }
                return
            } ?: return
            accepted(connection)
        }
    }

    private suspend fun accepted(connection: Connection) {
        val peer = connection.remote
        if (!pendingAccepted.tryAcquire()) {
            // RFC 7252 §11.3 for connections: peers that connect and never send a CSM cannot pile up.
            log.debug { "Refused a connection from $peer: ${config.reliable.maxPendingConnections} await their CSM" }
            onEvent(EndpointEvent.LimitReached(peer, EndpointEvent.LimitReached.Limit.PENDING_CONNECTIONS))
            closeQuietly(connection)
            return
        }
        val driver = ConnectionDriver(connection, isInitiator = false, this, scope, config)
        val refusal = registerAndStart(driver) {
            when {
                peer in drivers -> Refusal.DUPLICATE
                // RFC 7252 §11.3 for connections: established ones cannot pile up either.
                drivers.size >= config.reliable.maxConnections -> Refusal.FULL
                else -> null
            }
        }
        if (refusal != null) {
            pendingAccepted.release()
            if (refusal == Refusal.FULL) {
                log.debug { "Refused a connection from $peer: ${config.reliable.maxConnections} connections are open" }
                onEvent(EndpointEvent.LimitReached(peer, EndpointEvent.LimitReached.Limit.CONNECTIONS))
            } else {
                log.warn { "Refused a connection from $peer: ${refusal.reason}" }
            }
            closeQuietly(connection)
            return
        }
        // Established or closed, the connection no longer waits for its CSM.
        driver.established.invokeOnCompletion { pendingAccepted.release() }
        log.debug { "Accepted a connection from $peer" }
    }

    /**
     * Registers [driver] for its peer and starts it, in one step under [lock], unless the endpoint is closed
     * or [refusal] names a reason, which is returned. [close] therefore finds every driver it can see
     * started, and shuts it down; a driver it closed before its start would have failed that start with
     * "Already started" and never closed its connection, which only the started writer does.
     */
    private suspend fun registerAndStart(driver: ConnectionDriver, refusal: () -> Refusal?): Refusal? = lock.withLock {
        val reason = if (isClosed) Refusal.CLOSED else refusal()
        if (reason == null) {
            drivers[driver.peer] = driver
            onEvent(EndpointEvent.ConnectionOpened(driver.peer, driver.isInitiator))
            driver.start()
        }
        reason
    }

    /** Why a new connection is closed at once instead of registered. */
    private enum class Refusal(val reason: String) {
        CLOSED("the endpoint is closed"),
        DUPLICATE("a second connection from the same address"),
        FULL("the connection bound is reached"),
    }

    private suspend fun closeQuietly(connection: Connection) {
        try {
            connection.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug { "Closing a refused connection failed: ${e.message}" }
        }
    }

    /** Releases every connection and closes listeners and connectors. */
    suspend fun close() {
        val (open, attempts) = lock.withLock {
            isClosed = true
            drivers.values.toList() to connecting.values.toList()
        }
        val closed = endpoint.closedError()
        attempts.forEach { it.result.completeExceptionally(closed) }
        for (listener in listeners) runCatching { listener.close() }
        for (driver in open) {
            try {
                driver.shutdown("endpoint closed", SHUTDOWN_FLUSH_TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One connection's cleanup failing must not keep the others open.
                log.error(e) { "Closing the connection to ${driver.peer} failed" }
            }
        }
        for (connector in connectors) runCatching { connector.close() }
    }

    // ---------------------------------------------------------------- ConnectionOwner

    override fun now(): Duration = endpoint.now()

    override val clockCheck: ClockCheck get() = endpoint.clockCheck

    override fun ensureOpen() {
        endpoint.ensureOpen()
    }

    override suspend fun onRequest(driver: ConnectionDriver, message: CoapMessage, sequence: Long) {
        endpoint.onRequest(driver.link, message, sequence)
    }

    override suspend fun onResponse(driver: ConnectionDriver, message: CoapMessage) {
        endpoint.onResponse(driver.link, message)
    }

    override suspend fun onReleased(driver: ConnectionDriver, holdOff: Duration?) {
        if (holdOff == null || !holdOff.isPositive() || !driver.isInitiator) return
        lock.withLock { holdOffUntil[driver.peer] = endpoint.now() + holdOff }
    }

    override suspend fun onClosed(driver: ConnectionDriver, reason: String) {
        try {
            // RFC 8323 §3.4, §7.4: the connection's exchanges fail, and its observers and transfers are dropped,
            // before the slot frees, so a new connection never sees their state.
            log.debug { "Connection to ${driver.peer} closed: $reason" }
            val cause = CoapException("The connection to ${driver.peer} closed: $reason", CoapException.Reason.CONNECTION_CLOSED)
            endpoint.onLinkEnded(driver.link, cause)
        } finally {
            // However that ended: a slot that stayed taken would keep the peer unreachable for good.
            withContext(NonCancellable) { lock.withLock { if (drivers[driver.peer] === driver) drivers.remove(driver.peer) } }
            driver.retired.complete(Unit)
        }
    }

    override fun onEvent(event: EndpointEvent) {
        endpoint.report(event)
    }

    private companion object {
        val SHUTDOWN_FLUSH_TIMEOUT = 1.seconds

        /** The RFC 8323 scheme of [peer]; the endpoint sends only peers on connections here. */
        fun schemeOf(peer: Endpoint): CoapScheme = when (peer) {
            is Endpoint.Tcp -> peer.scheme
            is Endpoint.WebSocket -> peer.scheme
            is Endpoint.Udp, is Endpoint.Secure, is Endpoint.Opaque ->
                throw IllegalArgumentException("$peer is not reached over a reliable transport")
        }
    }
}
