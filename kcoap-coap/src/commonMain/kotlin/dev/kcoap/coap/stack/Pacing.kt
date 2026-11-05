package dev.kcoap.coap.stack

/** How the NSTART limit (RFC 7252 §4.7) applies to a request sent to a datagram peer. */
internal sealed interface Pacing {
    /**
     * The request takes a slot of its own: a Confirmable one until it is acknowledged, a Non-confirmable one
     * until its response arrives or for as long as sending it took at PROBING_RATE. Confirmable Q-Block
     * bodies use this too, so NSTART runs their blocks in lock-step (RFC 9177 §7.1).
     */
    data object PerMessage : Pacing

    /**
     * A Non-confirmable message of a Q-Block body whose [lease] holds the body's slot: it takes none of its
     * own (RFC 9177 §7.2). The lease must be granted, for the same peer, and not released.
     */
    class Body(val lease: BodyLease) : Pacing {
        override fun toString(): String = "Body($lease)"
    }
}
