package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint

/**
 * Something that takes one of a peer's NSTART slots (RFC 7252 §4.7) in [UdpMessaging]: a [Transmission]
 * (a Confirmable message, or a Non-confirmable one with a slot hold) or a [BodyLease] (a whole
 * Non-confirmable Q-Block body). Both wait in the same per-peer queue, first come first served.
 */
internal sealed interface SlotHolder {
    /** The peer whose slot this takes, compared by [Endpoint] equality. */
    val peer: Endpoint
}
