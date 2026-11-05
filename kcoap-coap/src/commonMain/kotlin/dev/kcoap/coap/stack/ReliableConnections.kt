package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
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
 * when idle, and accepted from [listeners].
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
    private val connecting = HashMap<Endpoint, CompletableDeferred<ConnectionDriver>>()
    private val holdOffUntil = HashMap<Endpoint, Duration>()

    /** One permit per accepted connection still waiting for its peer's CSM. */
    private val pendingAccepted = Semaphore(config.reliable.maxPendingConnections)
    @Volatile
    private var isClosed = false

    /** The schemes this endpoint can open connections for. */
    val schemes: Set<CoapScheme> = connectors.flatMap { it.schemes }.toSet()

    fun start() {
        for (listener in listeners) scope.launch(CoroutineName("coap-accept-${listener.scheme.scheme}")) { acceptLoop(listener) }
    }

    /**
     * The usable connection to [peer], opened if there is none: the transport connection, its
     * handshakes and the CSM exchange, within [ReliableTransportConfig.connectTimeout]. Concurrent
     * callers share one attempt.
     *
     * @throws IllegalArgumentException when no connector serves the peer's scheme.
     * @throws CoapException with [CoapException.Reason.CONNECTION_CLOSED] when the connection cannot be
     *   opened, or the peer asked not to reconnect yet (Hold-Off, §5.5).
     */
    suspend fun connectionFor(peer: Endpoint): ConnectionDriver {
        val scheme = schemeOf(peer)
        while (true) {
            val step = lock.withLock {
                if (isClosed) throw CoapException("Endpoint closed", CoapException.Reason.CLOSED)
                connecting[peer]?.let { return@withLock Step.Join(it) }
                val existing = drivers[peer]
                when {
                    // An accepted connection serves requests to its peer too (RFC 8323 §3.3); a connector is
                    // needed only to open one.
                    existing == null -> {
                        val connector = connectors.firstOrNull { scheme in it.schemes }
                            ?: throw IllegalArgumentException("${scheme.scheme} needs a Connector; this endpoint has none for it")
                        Step.Join(startConnecting(peer, connector))
                    }
                    existing.isUsable -> return existing
                    else -> Step.Wait(existing)
                }
            }
            when (step) {
                is Step.Join -> return step.attempt.await()
                // Accepted and not established yet, or released: a new connection opens once this one is gone.
                is Step.Wait -> step.driver.awaitSettled()
            }
        }
    }

    private sealed interface Step {
        class Join(val attempt: CompletableDeferred<ConnectionDriver>) : Step

        class Wait(val driver: ConnectionDriver) : Step
    }

    /** The connection to [peer] if there is one, for notifications to observers. */
    suspend fun existing(peer: Endpoint): ConnectionDriver? = lock.withLock { drivers[peer] }

    /** Called under [lock]. */
    private fun startConnecting(peer: Endpoint, connector: Connector): CompletableDeferred<ConnectionDriver> {
        holdOffUntil[peer]?.let { until ->
            val now = endpoint.now()
            if (now < until) {
                throw CoapException("$peer asked not to reconnect for ${until - now} (RFC 8323 §5.5 Hold-Off)", CoapException.Reason.CONNECTION_CLOSED)
            }
            holdOffUntil.remove(peer)
        }
        val attempt = CompletableDeferred<ConnectionDriver>()
        connecting[peer] = attempt
        val job = scope.launch(CoroutineName("coap-connect")) {
            val result = try {
                Result.success(open(peer, connector))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            // Every caller waits on the attempt, so it is settled even when the endpoint is closing.
            withContext(NonCancellable) { lock.withLock { connecting.remove(peer) } }
            result.fold(
                onSuccess = { attempt.complete(it) },
                onFailure = { error ->
                    val cause = when {
                        error is CoapException -> error
                        // Cancelled itself, by the closing endpoint; a timeout of the attempt is a failure to connect.
                        error is CancellationException && !isActive ->
                            CoapException("Endpoint closed", CoapException.Reason.CLOSED, error)
                        else -> CoapException("Cannot connect to $peer: ${error.message}", CoapException.Reason.CONNECTION_CLOSED, error)
                    }
                    log.debug { cause.message ?: "Cannot connect to $peer" }
                    attempt.completeExceptionally(cause)
                },
            )
        }
        // A launch into a closing endpoint may never run its body; its callers must not wait forever.
        job.invokeOnCompletion { attempt.completeExceptionally(CoapException("Endpoint closed", CoapException.Reason.CLOSED, it)) }
        return attempt
    }

    private suspend fun open(peer: Endpoint, connector: Connector): ConnectionDriver {
        var driver: ConnectionDriver? = null
        try {
            return withTimeout(config.reliable.connectTimeout) {
                val connection = connector.connect(peer)
                check(connection.remote == peer) { "The connector opened a connection to ${connection.remote}, not $peer" }
                // Registered and started as one step, so no caller ever waits on a driver that never runs.
                val opened = withContext(NonCancellable) {
                    val created = ConnectionDriver(connection, isInitiator = true, this@ReliableConnections, scope, config)
                    val registered = lock.withLock { (!isClosed).also { if (it) drivers[peer] = created } }
                    if (!registered) {
                        connection.close()
                        throw CoapException("Endpoint closed", CoapException.Reason.CLOSED)
                    }
                    onEvent(EndpointEvent.ConnectionOpened(peer, isInitiator = true))
                    log.debug { "Connected to $peer" }
                    created.start()
                    created
                }
                driver = opened
                // RFC 8323 §3.3 allows requests before the peer's CSM; waiting for it sizes them right from the start.
                opened.established.await()
                opened
            }
        } catch (e: Throwable) {
            driver?.let { withContext(NonCancellable) { it.abort("Connection setup failed: ${e.message}") } }
            throw e
        }
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
        val duplicate = lock.withLock {
            val duplicate = isClosed || peer in drivers
            if (!duplicate) drivers[peer] = driver
            duplicate
        }
        if (duplicate) {
            pendingAccepted.release()
            log.warn { "Refused a second connection from $peer" }
            closeQuietly(connection)
            return
        }
        // Established or closed, the connection no longer waits for its CSM.
        driver.established.invokeOnCompletion { pendingAccepted.release() }
        log.debug { "Accepted a connection from $peer" }
        onEvent(EndpointEvent.ConnectionOpened(peer, isInitiator = false))
        driver.start()
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
        val closed = CoapException("Endpoint closed", CoapException.Reason.CLOSED)
        attempts.forEach { it.completeExceptionally(closed) }
        for (listener in listeners) runCatching { listener.close() }
        for (driver in open) driver.shutdown("endpoint closed", SHUTDOWN_FLUSH_TIMEOUT)
        for (connector in connectors) runCatching { connector.close() }
    }

    // ---------------------------------------------------------------- ConnectionOwner

    override fun now(): Duration = endpoint.now()

    override suspend fun onRequest(driver: ConnectionDriver, message: CoapMessage, sequence: Long) {
        endpoint.onReliableRequest(driver, message, sequence)
    }

    override suspend fun onResponse(driver: ConnectionDriver, message: CoapMessage) {
        endpoint.onReliableResponse(driver, message)
    }

    override suspend fun onReleased(driver: ConnectionDriver, holdOff: Duration?) {
        if (holdOff == null || !holdOff.isPositive() || !driver.isInitiator) return
        lock.withLock { holdOffUntil[driver.peer] = endpoint.now() + holdOff }
    }

    override suspend fun onClosed(driver: ConnectionDriver, reason: String) {
        // Exchanges and observations end before the slot frees, so a new connection never sees their state.
        endpoint.onConnectionClosed(driver, reason)
        lock.withLock { if (drivers[driver.peer] === driver) drivers.remove(driver.peer) }
        driver.retired.complete(Unit)
    }

    override fun onEvent(event: EndpointEvent) {
        endpoint.report(event)
    }

    companion object {
        private val SHUTDOWN_FLUSH_TIMEOUT = 1.seconds

        /** The reliable scheme of [peer], or null for datagram peers. */
        fun schemeOrNull(peer: Endpoint): CoapScheme? = when (peer) {
            is Endpoint.Tcp -> peer.scheme
            is Endpoint.WebSocket -> peer.scheme
            else -> null
        }

        fun schemeOf(peer: Endpoint): CoapScheme =
            schemeOrNull(peer) ?: throw IllegalArgumentException("$peer is not reached over a reliable transport")
    }
}
