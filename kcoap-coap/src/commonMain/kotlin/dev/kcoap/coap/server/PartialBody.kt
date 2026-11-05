package dev.kcoap.coap.server

import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.QBlock1Receiver
import dev.kcoap.transport.Endpoint

/**
 * A Q-Block1 upload the server is assembling (RFC 9177 §4.3): the [receiver] that holds its blocks and decides
 * every answer, the operation it belongs to ([key]: peer, security identity, method and options, Request-Tag
 * included) and the timer that runs the receiver's NON_RECEIVE_TIMEOUT ladder or NON_PARTIAL_TIMEOUT. Guarded
 * by [QBlockServer]'s lock.
 */
internal class PartialBody(
    val key: OperationKey,
    val receiver: QBlock1Receiver<QBlockRequest>,
) : TimedBody() {
    val peer: Endpoint get() = key.peer

    override fun toString(): String = "PartialBody($peer, $receiver)"
}
