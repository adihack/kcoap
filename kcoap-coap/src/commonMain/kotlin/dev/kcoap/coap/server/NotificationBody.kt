package dev.kcoap.coap.server

import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.stack.BodyLease

/**
 * A notification of an [observation] registered with Q-Block2 (RFC 7641 §4.2, RFC 9177 §4.5), as the target of
 * its body's blocks: they go in messages of their own on the observation's token, every one of [type] (§7: a
 * body is all Confirmable or all Non-confirmable) and with the notification's Observe value, also those sent on
 * a Continue (§7.2). A Non-confirmable body holds the [lease] of one NSTART slot towards the client until it has
 * gone out (RFC 7641 §4.5.1, RFC 9177 §7.2); a Confirmable one takes a slot per block.
 */
internal class NotificationBody(
    val observation: ServerObservation,
    val type: CoapMessageType,
    val lease: BodyLease?,
) : QBlockTarget {
    override val token: ByteArray get() = observation.token

    override val carriesObserve: Boolean get() = true

    override fun toString(): String = "NotificationBody(${observation.peer}, ${observation.key.token}, $type)"
}
