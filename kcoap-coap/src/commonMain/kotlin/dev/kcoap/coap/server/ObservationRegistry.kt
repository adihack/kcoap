package dev.kcoap.coap.server

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.ConnectionDriver
import dev.kcoap.coap.stack.EndpointEvent
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
 * The lists of observers of a server's resources (RFC 7641 §4.1), keyed by client endpoint, token and
 * message-security identity ([ObservationKey]).
 *
 * Changes take one [Mutex], never held across a suspension; events are reported after it is released.
 * Readers ([changed], [count], [removed]) take no lock: they read a snapshot of one resource's observers,
 * replaced on every change of that resource, so notifying the observers of a resource touches only them.
 * Once [close]d, after the server stopped, it takes no new observers. It keeps at most
 * [CoapServerConfig.maxObservers], [CoapServerConfig.maxObserversPerPeer] per client endpoint and
 * [CoapServerConfig.maxObserversPerResource] per resource.
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

        /** [CoapServerConfig.maxObserversPerPeer] reached. */
        TOO_MANY_FOR_PEER,
    }

    private val lock = Mutex()
    private val byKey = HashMap<ObservationKey, ServerObservation>()

    /** How many of [byKey] each client endpoint has; under [lock]. */
    private val perPeer = HashMap<Endpoint, Int>()
    private var isClosed = false

    /** Each resource's observers; replaced, never changed, under [lock]. */
    private val byResource = AtomicReference<Map<ObservableResource, List<ServerObservation>>>(emptyMap())

    /**
     * Adds an observer, replacing one with the same endpoint and token (RFC 7641 §4.1 MUST) and the same
     * message-security identity, unless the registry is closed or full; a replacement does not count against
     * the bounds again. The replaced one ends: nothing of its Q-Block2 body goes out any more on the token the
     * new one now uses. A registration of another identity on that endpoint and token is another entry, so a
     * client that message security did not authenticate cannot take over an authenticated one's observation
     * (ADR 0008).
     */
    suspend fun register(observation: ServerObservation): Registration {
        val config = server.config
        val previous = lock.withLock {
            if (isClosed) return Registration.CLOSED
            val previous = byKey[observation.key]
            if (previous == null && byKey.size >= config.maxObservers) return Registration.TOO_MANY_OBSERVERS
            // The key includes the peer, so a replacement is the same client's and leaves its count as it is.
            if (previous == null && (perPeer[observation.peer] ?: 0) >= config.maxObserversPerPeer) return Registration.TOO_MANY_FOR_PEER
            if (previous?.resource !== observation.resource && observersOf(observation.resource).size >= config.maxObserversPerResource) {
                return Registration.TOO_MANY_FOR_RESOURCE
            }
            byKey[observation.key] = observation
            if (previous == null) perPeer[observation.peer] = (perPeer[observation.peer] ?: 0) + 1
            previous?.let(::unindex)
            index(observation)
            previous
        }
        previous?.let {
            it.deactivate()
            server.qBlock.stop(it)
        }
        server.endpoint.report(EndpointEvent.ObserverAdded(observation.peer, observation.resource.path))
        return Registration.ADDED
    }

    /**
     * RFC 7641 §3.6 deregistration, by a request of the registration's [identity] only: a GET with Observe 1
     * from another identity, or none, cancels nothing (ADR 0008).
     */
    suspend fun remove(peer: Endpoint, token: ByteArray, identity: SecurityIdentity?, reason: String) {
        val observation = lock.withLock { byKey[ObservationKey(peer, token.toHex(), identity)] } ?: return
        remove(observation, reason)
    }

    suspend fun remove(observation: ServerObservation, reason: String) {
        val removed = lock.withLock {
            val registered = byKey[observation.key] === observation
            if (registered) {
                byKey.remove(observation.key)
                val remaining = (perPeer[observation.peer] ?: 1) - 1
                if (remaining > 0) perPeer[observation.peer] = remaining else perPeer.remove(observation.peer)
                unindex(observation)
            }
            registered
        }
        observation.deactivate()
        // RFC 9177 §4.5: the rest of a Q-Block2 notification body would only be answered with Resets. After
        // deactivate, so that a body the server installs meanwhile is either stopped here or never installed.
        server.qBlock.stop(observation)
        if (removed) server.endpoint.report(EndpointEvent.ObserverRemoved(observation.peer, observation.resource.path, reason))
    }

    fun changed(resource: ObservableResource) {
        observersOf(resource).forEach { it.schedule() }
    }

    fun count(resource: ObservableResource): Int = observersOf(resource).size

    /** How many observers [peer] has, for tests. */
    internal suspend fun count(peer: Endpoint): Int = lock.withLock { perPeer[peer] ?: 0 }

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
            perPeer.clear()
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
 *
 * An observation registered with Q-Block ([qBlockSize]) gets its notifications as Q-Block2 bodies
 * (RFC 9177 §4.5, [QBlockServer.notify]): its [latest] body is the one outstanding notification until it is
 * settled ([OutstandingBody.isSettled]), every block out, and a Non-confirmable body for at least
 * [CoapServerConfig.nonConfirmableNotificationInterval]. Changes meanwhile fold into the next body, and a body
 * is never replaced by a newer one while it goes out, so a resource that changes faster than its bodies go out
 * still has whole bodies reach the client. A body goes Confirmable, whatever the resource asks, to a client
 * whose address the server checks and has not verified, or no longer ([CoapServer.mustConfirmBodies]).
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
    /**
     * For a registration that carried Q-Block1 or Q-Block2 (RFC 9177 §4.5): the most bytes a block of its
     * notification bodies takes; null for a registration without Q-Block.
     */
    val qBlockSize: Int? = null,
) : TransmissionOwner {
    val key: ObservationKey = ObservationKey(peer, token.toHex(), identity)
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

    /**
     * What the observation keeps of the Q-Block2 body of its latest notification, or of the registration
     * response, if it has [qBlockSize]; written under [QBlockServer]'s lock.
     */
    @Volatile
    var latest: OutstandingBody? = null

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

    /**
     * The Q-Block2 [body] of this observation is settled: a change meanwhile sends the next notification
     * (RFC 9177 §4.5). Taken under [mutex], so a change that found the body outstanding is not missed.
     */
    suspend fun onBodySettled(body: OutstandingBody) {
        val changed = mutex.withLock { latest === body && changedMeanwhile }
        if (changed) schedule()
    }

    private suspend fun notifyNow() = mutex.withLock {
        scheduled = false
        if (!isActive) return@withLock
        if (!registrationSent) {
            // Sent when the registration response is out, so that it stays the oldest state.
            changedMeanwhile = true
            return@withLock
        }
        val now = server.endpoint.now()
        val body = latest
        if (body != null && !body.isSettled(now, server.config.nonConfirmableNotificationInterval)) {
            // RFC 9177 §4.5: a body is never cut short by a newer one; onBodySettled sends the latest state.
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
        // RFC 9175 §2.4 item 3: a body to an address that is not verified (any more) waits for every ACK.
        val type = if (qBlockSize != null && server.mustConfirmBodies(this)) CoapMessageType.CON else nextType(now)
        val notification = server.notification(this, type)
        if (notification == null) {
            // A Q-Block2 body went out (or the observation ended meanwhile).
            if (latest !== body) count(type, now)
            return@withLock
        }
        if (current != null && server.endpoint.replaceOutstanding(current, notification)) return@withLock
        count(type, now)
        val slotHold = if (type == CoapMessageType.NON) server.config.nonConfirmableNotificationInterval else null
        // If its outcome arrives before this assignment, isOutstanding is false and the next change sends anew.
        outstanding = server.endpoint.sendSeparately(peer, notification, type, this, slotHold, connection)
    }

    /**
     * Under [mutex]: the type of the next notification. RFC 7641 §7: without client authentication,
     * Non-confirmable notifications MUST be interspersed with Confirmable ones, whose ACKs confirm that the
     * client still wants them; a spoofed one does not.
     */
    private fun nextType(now: Duration): CoapMessageType =
        if (resource.notificationType == CoapMessageType.NON &&
            now - lastConfirmableAt < server.config.confirmableNotificationInterval &&
            (isAuthenticated || nonConfirmableInRow < UNAUTHENTICATED_NON_CONFIRMABLE_RUN)
        ) {
            CoapMessageType.NON
        } else {
            CoapMessageType.CON
        }

    /** Under [mutex]: a notification of [type] went out. */
    private fun count(type: CoapMessageType, now: Duration) {
        if (type == CoapMessageType.CON) {
            lastConfirmableAt = now
            nonConfirmableInRow = 0
        } else {
            nonConfirmableInRow++
        }
    }

    override fun toString(): String = "ServerObservation(${resource.path}, $peer, token=${key.token})"

    private companion object {
        /** At most this many Non-confirmable notifications in a row to an unauthenticated client (RFC 7641 §7). */
        const val UNAUTHENTICATED_NON_CONFIRMABLE_RUN = 8
    }
}

/**
 * What identifies an observer (RFC 7641 §4.1: the client endpoint and the token), with the message-security
 * [identity] of its registration, compared with `equals`, so that requests of one identity never replace or
 * cancel the registration of another (ADR 0008).
 */
internal data class ObservationKey(val peer: Endpoint, val token: String, val identity: SecurityIdentity?)
