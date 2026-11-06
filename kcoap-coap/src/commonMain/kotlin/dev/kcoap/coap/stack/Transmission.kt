package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * An outbound Confirmable or Non-confirmable message and its retransmission state (RFC 7252 §4.2).
 * Owned and mutated only by [UdpMessaging]; the layers above a [DatagramLink] hold it as a [Delivery].
 */
internal class Transmission(
    override val peer: Endpoint,
    message: CoapMessage,
    /** The encoded message, or null when an interceptor dropped it: then nothing is ever sent. */
    bytes: ByteArray?,
    val owner: TransmissionOwner?,
    /**
     * For Non-confirmable messages: how long the message occupies an NSTART slot, or null for none, as for
     * a message of a body whose [BodyLease] holds the slot.
     */
    val slotHold: Duration?,
) : SlotHolder, Delivery {
    var message: CoapMessage = message
        private set
    var bytes: ByteArray? = bytes
        private set

    override val isConfirmable: Boolean get() = message.type == CoapMessageType.CON

    /** Confirmable messages always count against NSTART (RFC 7252 §4.7). */
    val holdsSlot: Boolean get() = isConfirmable || slotHold != null

    @Volatile
    var state: State = State.CREATED

    /** Retransmissions so far; RFC 7252 §4.2's retransmission counter. */
    var retransmissions: Int = 0

    /** The current timeout, doubled after each retransmission. */
    var timeout: Duration = Duration.ZERO

    /** When the retransmission timer fires, or when a Non-confirmable message releases its slot. */
    var deadline: Duration = Duration.ZERO

    /** When the message was first sent, for the MAX_TRANSMIT_SPAN envelope. */
    var firstSentAt: Duration = Duration.ZERO

    /** Whether this transmission still occupies an NSTART slot. */
    @Volatile
    var occupiesSlot: Boolean = false

    /**
     * Whether the message still counts as outstanding (RFC 7641 §4.5.1): queued, or a Confirmable one
     * awaiting its ACK, or a Non-confirmable one holding its slot. Safe to read from any coroutine.
     */
    override val isOutstanding: Boolean
        get() = state == State.QUEUED || (state == State.IN_FLIGHT && (isConfirmable || occupiesSlot))

    /** A newer message that replaces this one at the next timeout (RFC 7641 §4.5.2). */
    var replacement: CoapMessage? = null

    override val isDone: Boolean get() = state == State.DONE

    override val isSuperseded: Boolean get() = replacement != null

    /** Every Message ID this transmission was sent with; more than one after a replacement. */
    val messageIds: MutableList<Int> = mutableListOf(message.messageId)

    fun replaceWith(newMessage: CoapMessage, newBytes: ByteArray?) {
        message = newMessage
        bytes = newBytes
        messageIds += newMessage.messageId
    }

    enum class State {
        CREATED,

        /** Waiting for an NSTART slot. */
        QUEUED,

        /** Sent; a Confirmable message awaits its ACK, a Non-confirmable one stays matchable to a Reset. */
        IN_FLIGHT,

        DONE,
    }

    override fun toString(): String = "Transmission($peer, $message, $state, retransmissions=$retransmissions)"
}
