package dev.kcoap.edhoc.oscore

import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.transport.Endpoint

/**
 * Something an [EdhocServer] did to a handshake that is not complete yet, for metrics and diagnostics, like
 * the CoAP endpoint's `EndpointEvent`. The events cover what the server refuses or drops to bound the state
 * unauthenticated clients can make it keep (RFC 9528 §9.7); completed sessions go to [EdhocSessionListener].
 */
public sealed interface EdhocServerEvent {
    /** The peer the event concerns. */
    public val peer: Endpoint

    /**
     * A message_1 from [peer] was refused before any processing, because [limit] sessions were already
     * pending. It was answered with an EDHOC error in a 5.00 response (RFC 9528 Appendix A.2.3). For
     * [Limit.PENDING_SESSIONS], no pending session had waited for message_3 longer than
     * `EdhocServer(pendingSessionGracePeriod)`, so none was evicted for it ([SessionEvicted]).
     */
    public class LimitReached(override val peer: Endpoint, public val limit: Limit) : EdhocServerEvent {
        /** The bounds an [EdhocServer] keeps on pending sessions. */
        public enum class Limit {
            /** `EdhocServer(maxPendingSessions)`: sessions pending for all peers together. */
            PENDING_SESSIONS,

            /** `EdhocServer(maxPendingSessionsPerPeer)`: sessions pending for one peer. */
            PENDING_SESSIONS_PER_PEER,
        }

        override fun toString(): String = "LimitReached(peer=$peer, limit=$limit)"
    }

    /**
     * The session [responderId] waited for message_3 longer than `EdhocServer(pendingSessionLifetime)`. It was
     * dropped and its secrets wiped; a message_3 for it now gets the answer for an unknown C_R.
     */
    public class SessionExpired(override val peer: Endpoint, public val responderId: ConnectionIdentifier) :
        EdhocServerEvent {
        override fun toString(): String = "SessionExpired(peer=$peer, C_R=$responderId)"
    }

    /**
     * All `EdhocServer(maxPendingSessions)` places were taken when a new message_1 came, and the session
     * [responderId] of [peer] was the oldest waiting for message_3, for longer than
     * `EdhocServer(pendingSessionGracePeriod)`. It was dropped for the new session and its secrets wiped; a
     * message_3 for it now gets the answer for an unknown C_R. Many of these events mean the server is
     * flooded with message_1 (RFC 9528 §9.7) or the bound is too low.
     */
    public class SessionEvicted(override val peer: Endpoint, public val responderId: ConnectionIdentifier) :
        EdhocServerEvent {
        override fun toString(): String = "SessionEvicted(peer=$peer, C_R=$responderId)"
    }

    /**
     * A message_3, an error message or an EDHOC + OSCORE request from [peer] named the C_R [responderId] of a
     * session that another peer started. The server takes it for forged (RFC 9528 §9.7): it answers as for an
     * unknown C_R and runs no cryptography, and the session keeps waiting for the peer that sent message_1.
     */
    public class ContinuationFromAnotherPeer(
        override val peer: Endpoint,
        public val responderId: ConnectionIdentifier,
    ) : EdhocServerEvent {
        override fun toString(): String = "ContinuationFromAnotherPeer(peer=$peer, C_R=$responderId)"
    }
}

/** Receives the [EdhocServerEvent]s of an [EdhocServer]. Called outside the server's locks; keep it short. */
public fun interface EdhocServerEventListener {
    /** Called for every event. */
    public fun onEvent(event: EdhocServerEvent)

    public companion object {
        /** Ignores all events. */
        public val None: EdhocServerEventListener = EdhocServerEventListener { }
    }
}
