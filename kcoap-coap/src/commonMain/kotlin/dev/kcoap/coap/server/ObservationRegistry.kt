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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lists of observers of a server's resources (RFC 7641 §4.1), keyed by client endpoint and token.
 * Lock free for readers; changes replace the whole map.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ObservationRegistry(private val server: CoapServer) {
    private val entries = AtomicReference<Map<ExchangeKey, ServerObservation>>(emptyMap())

    /** Adds an observer, replacing one with the same endpoint and token (RFC 7641 §4.1 MUST). */
    fun register(observation: ServerObservation) {
        val previous = update { it + (observation.key to observation) }[observation.key]
        previous?.deactivate()
        server.endpoint.report(EndpointEvent.ObserverAdded(observation.peer, observation.resource.path))
    }

    /** RFC 7641 §3.6 deregistration, or §3.5 cancellation by Reset. */
    fun remove(peer: Endpoint, token: ByteArray, reason: String) {
        entries.load()[ExchangeKey(peer, token.toHex())]?.let { remove(it, reason) }
    }

    fun remove(observation: ServerObservation, reason: String) {
        var removed = false
        update { current ->
            removed = current[observation.key] === observation
            if (removed) current - observation.key else current
        }
        observation.deactivate()
        if (removed) server.endpoint.report(EndpointEvent.ObserverRemoved(observation.peer, observation.resource.path, reason))
    }

    fun changed(resource: ObservableResource) {
        entries.load().values.filter { it.resource === resource }.forEach { it.schedule() }
    }

    fun count(resource: ObservableResource): Int = entries.load().values.count { it.resource === resource }

    /** RFC 7641 §3.2: a removed resource sends 4.04 to its observers and forgets them. */
    fun removed(resource: ObservableResource) {
        entries.load().values.filter { it.resource === resource }.forEach { it.end() }
    }

    /** RFC 7252 §9.1.1, RFC 8323 §7.4: observations bound to an ended DTLS session or closed connection end with it. */
    fun sessionEnded(peer: Endpoint) {
        entries.load().values.filter { it.peer == peer }.forEach { remove(it, "session ended") }
    }

    fun clear() {
        update { emptyMap() }.values.forEach { it.deactivate() }
    }

    /** Applies [change] atomically and returns the map before it. */
    private inline fun update(change: (Map<ExchangeKey, ServerObservation>) -> Map<ExchangeKey, ServerObservation>): Map<ExchangeKey, ServerObservation> {
        while (true) {
            val current = entries.load()
            if (entries.compareAndSet(current, change(current))) return current
        }
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

    /** Arranges for a notification with the current state; calls that overlap are coalesced. */
    fun schedule() {
        if (!isActive || scheduled) return
        scheduled = true
        server.endpoint.scope.launch { notifyNow() }
    }

    /** Sends a final 4.04 notification and removes the observer (RFC 7641 §3.2). */
    fun end() {
        server.endpoint.scope.launch {
            mutex.withLock {
                if (!isActive) return@withLock
                server.observations.remove(this@ServerObservation, "resource removed")
                server.sendRemovalNotice(this@ServerObservation)
            }
        }
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
        val type = if (resource.notificationType == CoapMessageType.NON &&
            now - lastConfirmableAt < server.config.confirmableNotificationInterval
        ) {
            CoapMessageType.NON
        } else {
            CoapMessageType.CON
        }
        if (type == CoapMessageType.CON) lastConfirmableAt = now
        val slotHold = if (type == CoapMessageType.NON) server.config.nonConfirmableNotificationInterval else null
        // If its outcome arrives before this assignment, isOutstanding is false and the next change sends anew.
        outstanding = server.endpoint.sendSeparately(peer, notification, type, this, slotHold, connection)
    }

    override fun toString(): String = "ServerObservation(${resource.path}, $peer, token=${key.token})"
}
