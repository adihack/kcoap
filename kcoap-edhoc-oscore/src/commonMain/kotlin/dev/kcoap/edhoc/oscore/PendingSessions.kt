package dev.kcoap.edhoc.oscore

import dev.kcoap.common.toHex
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocResponder
import dev.kcoap.edhoc.oscore.EdhocServerEvent.LimitReached.Limit
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The EDHOC sessions an [EdhocServer] has accepted a message_1 for and not finished, keyed by the C_R it
 * chose. A session goes through three stages, all under one [Mutex] that no suspension happens under:
 *
 * 1. **Admitted** ([admit]): it counts against the bounds from before message_1 is processed, so a peer
 *    beyond them costs no cryptography. When all [maxSessions] places are taken, the oldest session that
 *    has waited for message_3 longer than [gracePeriod] makes room; only when there is none is the new
 *    session refused. Its C_R is reserved ([reserve]) the moment it is chosen, while message_2 is still
 *    being composed, so two concurrent sessions never get the same one (RFC 9668 §4.1.2).
 * 2. **Waiting** ([await]): message_2 is out and the session waits up to [lifetime] for message_3. Only now
 *    can a continuation find it, and only one from the peer that sent message_1 ([take]): anything else
 *    is answered as for an unknown C_R and leaves the session alone (RFC 9528 §9.7).
 * 3. **Completing**: its own peer took it. The C_R stays reserved until [release], which the server calls
 *    once the OSCORE context is in the repository or the session failed, so a concurrent allocation can
 *    never hand out a Recipient ID whose context is being added.
 *
 * Waiting sessions past their lifetime are dropped on the next access, and evicted ones when they make
 * room; either way their secrets are wiped ([EdhocResponder.abort]). Admitted and completing sessions are
 * never dropped: the coroutine that runs them releases them, also when it is cancelled. The binding to a
 * peer applies only to this unauthenticated state, never to the OSCORE context that results.
 */
internal class PendingSessions(
    private val maxSessions: Int,
    private val maxSessionsPerPeer: Int,
    private val lifetime: Duration,
    private val gracePeriod: Duration,
    private val timeSource: TimeSource,
    private val events: EdhocServerEventListener,
    private val log: Logger,
) {
    private val lock = Mutex()

    /** Every admitted session, by the C_R reserved for it. */
    private val reserved = HashMap<ConnectionIdentifier, Session>()

    /** Waiting sessions, oldest first; with one lifetime and grace period for all, so are their time marks. */
    private val waiting = LinkedHashSet<Session>()

    /** Admitted sessions per peer ([origin]). */
    private val perPeer = HashMap<Any, Int>()
    private var admitted = 0

    /** One session; [peer] is all the server may read, the rest is guarded by the lock. */
    class Session internal constructor(val peer: Endpoint) {
        var stage = Stage.ADMITTED
        var responderId: ConnectionIdentifier? = null
        var responder: EdhocResponder? = null
        var deadline: TimeMark? = null
        var evictableAt: TimeMark? = null
    }

    internal enum class Stage { ADMITTED, WAITING, COMPLETING, RELEASED }

    /** A session its own peer continues, and the responder that waited for it. */
    class Taken(val session: Session, val responder: EdhocResponder)

    /** Sessions waiting for message_3. */
    suspend fun waitingCount(): Int = locked { waiting.size }

    /**
     * Admits a new session for [peer], or returns null and reports [EdhocServerEvent.LimitReached] when
     * [maxSessionsPerPeer] are in progress for this peer, or [maxSessions] in total and none of them has
     * waited for message_3 longer than [gracePeriod]. Otherwise, when all places are taken, the oldest
     * waiting session is wiped and dropped for the new one, and reported as [EdhocServerEvent.SessionEvicted].
     */
    suspend fun admit(peer: Endpoint): Session? = locked { report ->
        val limit = when {
            (perPeer[peer.origin()] ?: 0) >= maxSessionsPerPeer -> Limit.PENDING_SESSIONS_PER_PEER
            admitted < maxSessions -> null
            evictOldest(report) -> null
            else -> Limit.PENDING_SESSIONS
        }
        if (limit != null) {
            log.warn { "Refused message_1 from $peer: $limit reached" }
            report(EdhocServerEvent.LimitReached(peer, limit))
            return@locked null
        }
        admitted++
        perPeer[peer.origin()] = (perPeer[peer.origin()] ?: 0) + 1
        Session(peer)
    }

    /**
     * Reserves [responderId] for [session] unless another session holds it. A session holds at most one
     * reservation; [unreserve] gives it back to try another.
     */
    suspend fun reserve(session: Session, responderId: ConnectionIdentifier): Boolean = locked {
        check(session.stage == Stage.ADMITTED && session.responderId == null) { "C_R is already reserved" }
        if (responderId in reserved) return@locked false
        reserved[responderId] = session
        session.responderId = responderId
        true
    }

    /** Gives back the C_R [session] reserved, which turned out to be unusable. */
    suspend fun unreserve(session: Session): Unit = locked {
        session.responderId?.let { reserved.remove(it) }
        session.responderId = null
    }

    /** message_2 for [session] is out: from now on its peer's message_3 finds [responder]. */
    suspend fun await(session: Session, responder: EdhocResponder): Unit = locked {
        check(session.stage == Stage.ADMITTED && session.responderId != null) { "No C_R reserved" }
        session.stage = Stage.WAITING
        session.responder = responder
        val now = timeSource.markNow()
        session.deadline = now + lifetime
        session.evictableAt = now + gracePeriod
        waiting += session
    }

    /**
     * The waiting session [responderId] for [peer], which is no longer waiting afterwards, or null when there
     * is none. A session started by another peer stays waiting and is reported as
     * [EdhocServerEvent.ContinuationFromAnotherPeer]; the caller answers as for an unknown C_R.
     */
    suspend fun take(responderId: ConnectionIdentifier, peer: Endpoint): Taken? = locked { report ->
        val session = reserved[responderId]?.takeIf { it.stage == Stage.WAITING } ?: return@locked null
        if (session.peer.origin() != peer.origin()) {
            log.warn { "Ignored a continuation of session ${responderId.hex()} from $peer, started by ${session.peer}" }
            report(EdhocServerEvent.ContinuationFromAnotherPeer(peer, responderId))
            return@locked null
        }
        waiting -= session
        session.stage = Stage.COMPLETING
        Taken(session, session.responder!!)
    }

    /** Ends [session]: frees its C_R and its place under the bounds. Calling it again does nothing. */
    suspend fun release(session: Session): Unit = locked { remove(session) }

    /**
     * Runs [block] under the lock after dropping expired sessions, then hands the events it reported, and
     * those of the expiry, to [events] outside the lock. A failing listener is logged: it must not leave a
     * session admitted that its caller never learns about.
     */
    private suspend fun <T> locked(block: ((EdhocServerEvent) -> Unit) -> T): T {
        val reported = mutableListOf<EdhocServerEvent>()
        val result = lock.withLock {
            expire(reported)
            block { reported += it }
        }
        for (event in reported) {
            try {
                events.onEvent(event)
            } catch (e: Exception) {
                log.error(e) { "Event listener failed on $event" }
            }
        }
        return result
    }

    private fun expire(reported: MutableList<EdhocServerEvent>) {
        while (true) {
            val oldest = waiting.firstOrNull() ?: return
            if (!oldest.deadline!!.hasPassedNow()) return
            val responderId = drop(oldest, "EDHOC session expired")
            log.info { "Session ${responderId.hex()} of ${oldest.peer} expired before message_3" }
            reported += EdhocServerEvent.SessionExpired(oldest.peer, responderId)
        }
    }

    /**
     * Drops the oldest waiting session if it has waited longer than [gracePeriod], so the bound makes room
     * for a new one, and returns whether it did. A refusal-only bound would let a few message_1 per
     * lifetime, from spoofed addresses, keep every new client out; with eviction, keeping them out takes
     * [maxSessions] message_1 per [gracePeriod], each costing the server its cryptography only.
     */
    private fun evictOldest(report: (EdhocServerEvent) -> Unit): Boolean {
        val oldest = waiting.firstOrNull()?.takeIf { it.evictableAt!!.hasPassedNow() } ?: return false
        val responderId = drop(oldest, "EDHOC session evicted")
        log.warn { "Evicted session ${responderId.hex()} of ${oldest.peer}: $maxSessions sessions pending" }
        report(EdhocServerEvent.SessionEvicted(oldest.peer, responderId))
        return true
    }

    /** Drops a waiting session and wipes its secrets; returns its C_R. */
    private fun drop(session: Session, diagnostic: String): ConnectionIdentifier {
        val responderId = session.responderId!!
        remove(session)
        // Wipes Y and PRK_3e2m; a waiting responder belongs to this table alone, so nothing else uses it.
        session.responder!!.abort(diagnostic)
        return responderId
    }

    private fun remove(session: Session) {
        if (session.stage == Stage.RELEASED) return
        session.responderId?.let { if (reserved[it] === session) reserved.remove(it) }
        waiting -= session
        admitted--
        val origin = session.peer.origin()
        val count = (perPeer[origin] ?: 1) - 1
        if (count == 0) perPeer.remove(origin) else perPeer[origin] = count
        session.stage = Stage.RELEASED
    }

    private companion object {
        /**
         * What identifies the peer that sent message_1: the DTLS session, not its epoch, which a renegotiation
         * changes; otherwise the endpoint itself, address and port. A host that sends from many ports is many
         * peers to [maxSessionsPerPeer], which keeps clients behind one NAT apart; the global bound and its
         * eviction cover such a host.
         */
        fun Endpoint.origin(): Any = if (this is Endpoint.Secure) session else this

        fun ConnectionIdentifier.hex(): String = bytes.toHex()
    }
}
