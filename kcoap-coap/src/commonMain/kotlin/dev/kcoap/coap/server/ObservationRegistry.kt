package dev.kcoap.coap.server

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.ConnectionDriver
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.ExchangeKey
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.Transmission
import dev.kcoap.coap.stack.TransmissionOutcome
import dev.kcoap.coap.stack.TransmissionOwner
import dev.kcoap.common.toHex
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lists of observers of a server's resources (RFC 7641 §4.1), keyed by client endpoint and token.
 *
 * Changes take one [Mutex], never held across a suspension; events are reported after it is released.
 * Readers ([changed], [count], [removed]) take no lock: they read a snapshot of one resource's observers,
 * replaced on every change of that resource, so notifying the observers of a resource touches only them.
 * Once [close]d, after the server stopped, it takes no new observers. It keeps at most
 * [CoapServerConfig.maxObservers], and [CoapServerConfig.maxObserversPerResource] per resource.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ObservationRegistry(private val server: CoapServer) {
    /** What [register] did. */
    enum class Registration {
        ADDED,

        /** The server stopped. */
        CLOSED,

        /** [CoapServerConfig.maxObservers] reached. */
        TOO_MANY_OBSERVERS,

        /** [CoapServerConfig.maxObserversPerResource] reached. */
        TOO_MANY_FOR_RESOURCE,
    }

    private val lock = Mutex()
    private val byKey = HashMap<ExchangeKey, ServerObservation>()
    private var isClosed = false

    /** Each resource's observers; replaced, never changed, under [lock]. */
    private val byResource = AtomicReference<Map<ObservableResource, List<ServerObservation>>>(emptyMap())

    /**
     * Adds an observer, replacing one with the same endpoint and token (RFC 7641 §4.1 MUST), unless the
     * registry is closed or full; a replacement does not count against the bounds again.
     */
    suspend fun register(observation: ServerObservation): Registration {
        val config = server.config
        val previous = lock.withLock {
            if (isClosed) return Registration.CLOSED
            val previous = byKey[observation.key]
            if (previous == null && byKey.size >= config.maxObservers) return Registration.TOO_MANY_OBSERVERS
            if (previous?.resource !== observation.resource && observersOf(observation.resource).size >= config.maxObserversPerResource) {
                return Registration.TOO_MANY_FOR_RESOURCE
            }
            byKey[observation.key] = observation
            previous?.let(::unindex)
            index(observation)
            previous
        }
        previous?.deactivate()
        server.endpoint.report(EndpointEvent.ObserverAdded(observation.peer, observation.resource.path))
        return Registration.ADDED
    }

    /** RFC 7641 §3.6 deregistration, or §3.5 cancellation by Reset. */
    suspend fun remove(peer: Endpoint, token: ByteArray, reason: String) {
        val observation = lock.withLock { byKey[ExchangeKey(peer, token.toHex())] } ?: return
        remove(observation, reason)
    }

    suspend fun remove(observation: ServerObservation, reason: String) {
        val removed = lock.withLock {
            val registered = byKey[observation.key] === observation
            if (registered) {
                byKey.remove(observation.key)
                unindex(observation)
            }
            registered
        }
        observation.deactivate()
        if (removed) server.endpoint.report(EndpointEvent.ObserverRemoved(observation.peer, observation.resource.path, reason))
    }

    fun changed(resource: ObservableResource) {
        observersOf(resource).forEach { it.schedule() }
    }

    fun count(resource: ObservableResource): Int = observersOf(resource).size

    /** RFC 7641 §3.2: a removed resource sends 4.04 to its observers and forgets them. */
    fun removed(resource: ObservableResource) {
        observersOf(resource).forEach { it.end() }
    }

    /** RFC 7252 §9.1.1, RFC 8323 §7.4: observations bound to an ended DTLS session or closed connection end with it. */
    suspend fun sessionEnded(peer: Endpoint) {
        lock.withLock { byKey.values.filter { it.peer == peer } }.forEach { remove(it, "session ended") }
    }

    /** The server stopped: every observer is dropped silently, and no new one is taken. */
    suspend fun close() {
        val dropped = lock.withLock {
            isClosed = true
            byResource.store(emptyMap())
            byKey.values.toList().also { byKey.clear() }
        }
        dropped.forEach { it.deactivate() }
    }

    private fun observersOf(resource: ObservableResource): List<ServerObservation> = byResource.load()[resource].orEmpty()

    /** Called under [lock]. */
    private fun index(observation: ServerObservation) {
        val current = byResource.load()
        byResource.store(current + (observation.resource to (current[observation.resource].orEmpty() + observation)))
    }

    /** Called under [lock]. */
    private fun unindex(observation: ServerObservation) {
        val current = byResource.load()
        val remaining = current[observation.resource].orEmpty().filter { it !== observation }
        byResource.store(if (remaining.isEmpty()) current - observation.resource else current + (observation.resource to remaining))
    }
}

/**
 * One entry in a resource's list of observers and its notification state.
 *
 * At most one notification is outstanding per observation (RFC 7641 §4.5.1, enforced per client by the
 * messaging layer's NSTART). A change while one is outstanding is not queued: a Confirmable notification
 * is superseded at its next timeout (§4.5.2), otherwise the latest state is sent once the outstanding one
 * completes, so intermediate states are skipped and the client eventually sees the latest (§4.5).
 *
 * No notification goes out before the registration response: on reliable transports the client ignores
 * Observe values and takes notifications in the order they arrive (RFC 8323 §7.1), so the order of
 * sending is the order of freshness. There, notifications have no message type (§7.2) and go on the
 * registration's [connection].
 */
internal class ServerObservation(
    private val server: CoapServer,
    val resource: ObservableResource,
    val peer: Endpoint,
    val token: ByteArray,
    /** The registration request, inner if protected; every notification is generated from it. */
    val request: CoapMessage,
    /** Protects every notification with the context of the registration (RFC 8613 §8.3). */
    val unprotected: UnprotectedRequest?,
    val identity: SecurityIdentity?,
    /** The block size the client asked for in the registration (RFC 7959 §2.6), if any. */
    val blockSize: Int?,
    val operationKey: OperationKey,
    registeredAt: Duration,
    /** The connection the registration came on (RFC 8323); null for datagrams. */
    val connection: ConnectionDriver? = null,
    /** What channel security authenticated when the client registered. */
    val peerIdentity: PeerIdentity = PeerIdentity.None,
) : TransmissionOwner {
    val key: ExchangeKey = ExchangeKey(peer, token.toHex())
    private val mutex = Mutex()

    @Volatile
    var isActive: Boolean = true
        private set

    @Volatile
    private var outstanding: Transmission? = null

    @Volatile
    private var scheduled = false

    @Volatile
    private var changedMeanwhile = false

    @Volatile
    private var registrationSent = false

    private var lastConfirmableAt: Duration = registeredAt

    /** Non-confirmable notifications since the last Confirmable one; under [mutex]. */
    private var nonConfirmableInRow = 0

    /** Whether message or channel security authenticated the client when it registered. */
    private val isAuthenticated: Boolean get() = identity != null || peerIdentity != PeerIdentity.None

    /** The registration response was sent; a separate one is the first outstanding notification. */
    suspend fun onRegistrationSent(transmission: Transmission?, type: CoapMessageType, now: Duration) {
        val changed = mutex.withLock {
            // On a multi-threaded dispatcher the ACK may already have been processed.
            outstanding = transmission?.takeIf { it.isOutstanding }
            if (type == CoapMessageType.CON) lastConfirmableAt = now
            registrationSent = true
            changedMeanwhile
        }
        if (changed) schedule()
    }

    override suspend fun onOutcome(transmission: Transmission, outcome: TransmissionOutcome) {
        when (outcome) {
            is TransmissionOutcome.Acknowledged, TransmissionOutcome.SlotReleased -> {
                if (outstanding !== transmission) return
                outstanding = null
                // A replacement that never went out, or a change while a NON held the slot: send the latest.
                if (transmission.replacement != null || changedMeanwhile) schedule()
            }
            // §4.5: a Reset or a timed-out Confirmable notification removes the observer.
            TransmissionOutcome.Reset -> server.observations.remove(this, "reset by client")
            TransmissionOutcome.TimedOut -> server.observations.remove(this, "notification not acknowledged")
            is TransmissionOutcome.Failed -> server.observations.remove(this, outcome.cause.message ?: "failed")
        }
    }

    /**
     * Arranges for a notification with the current state; calls that overlap are coalesced. A notification
     * that cannot be produced, protected or sent (a failing handler aside, which ends the observation with
     * 5.00) removes the observer instead of escaping: the security layer or its sequence-number store may
     * fail, or the notification may not fit the datagram.
     */
    fun schedule() {
        if (!isActive || scheduled) return
        scheduled = true
        server.endpoint.launchGuarded("notify ${resource.path}", onFailure = ::failed) { notifyNow() }
    }

    /** Sends a final 4.04 notification and removes the observer (RFC 7641 §3.2). */
    fun end() {
        server.endpoint.launchGuarded("send the removal notice of ${resource.path}", onFailure = ::failed) {
            mutex.withLock {
                if (!isActive) return@withLock
                server.observations.remove(this@ServerObservation, "resource removed")
                server.sendRemovalNotice(this@ServerObservation)
            }
        }
    }

    private suspend fun failed(error: Exception) {
        scheduled = false
        server.observations.remove(this, "notification failed: ${error.message}")
    }

    fun deactivate() {
        isActive = false
    }

    private suspend fun notifyNow() = mutex.withLock {
        scheduled = false
        if (!isActive) return@withLock
        if (!registrationSent) {
            // Sent when the registration response is out, so that it stays the oldest state.
            changedMeanwhile = true
            return@withLock
        }
        // Recorded transmissions that completed meanwhile (an outcome raced the recording) no longer count.
        val current = outstanding?.takeIf { it.isOutstanding }
        if (current != null && !current.isConfirmable) {
            changedMeanwhile = true
            return@withLock
        }
        changedMeanwhile = false
        val notification = server.notification(this) ?: return@withLock
        if (current != null && server.endpoint.replaceOutstanding(current, notification)) return@withLock
        val now = server.endpoint.now()
        // RFC 7641 §7: without client authentication, Non-confirmable notifications MUST be interspersed with
        // Confirmable ones, whose ACKs confirm that the client still wants them; a spoofed one does not.
        val type = if (resource.notificationType == CoapMessageType.NON &&
            now - lastConfirmableAt < server.config.confirmableNotificationInterval &&
            (isAuthenticated || nonConfirmableInRow < UNAUTHENTICATED_NON_CONFIRMABLE_RUN)
        ) {
            CoapMessageType.NON
        } else {
            CoapMessageType.CON
        }
        if (type == CoapMessageType.CON) {
            lastConfirmableAt = now
            nonConfirmableInRow = 0
        } else {
            nonConfirmableInRow++
        }
        val slotHold = if (type == CoapMessageType.NON) server.config.nonConfirmableNotificationInterval else null
        // If its outcome arrives before this assignment, isOutstanding is false and the next change sends anew.
        outstanding = server.endpoint.sendSeparately(peer, notification, type, this, slotHold, connection)
    }

    override fun toString(): String = "ServerObservation(${resource.path}, $peer, token=${key.token})"

    private companion object {
        /** At most this many Non-confirmable notifications in a row to an unauthenticated client (RFC 7641 §7). */
        const val UNAUTHENTICATED_NON_CONFIRMABLE_RUN = 8
    }
}
