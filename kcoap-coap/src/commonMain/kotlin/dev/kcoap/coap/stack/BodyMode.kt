package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessageType

/**
 * How the blocks of one Q-Block body travel (RFC 9177 §7). Fixed for the whole body: §7 requires all
 * blocks of a body to be Confirmable or all Non-confirmable, and a body follows the message type of the
 * request that started it.
 */
internal enum class BodyMode {
    /**
     * Non-confirmable blocks over UDP (§7.2): bursts of MAX_PAYLOADS blocks with NON_TIMEOUT_RANDOM between
     * them, 2.31 and Continue to skip the wait, and missing blocks found by NON_RECEIVE_TIMEOUT timers.
     */
    NON,

    /**
     * Confirmable blocks over UDP (§7.1, Appendix A): the messaging layer acknowledges and retransmits each
     * block under NSTART, so there are no sets, no 2.31, no missing-block lists and no Q-Block timers.
     */
    CON,

    /**
     * A reliable transport (RFC 8323; RFC 9177 §7, Appendix B): blocks go back to back in order and cannot
     * be lost, so only the final response matters.
     */
    RELIABLE,
    ;

    companion object {
        /** The mode of a body started by a message of [type], on a reliable transport if [isReliable]. */
        fun of(type: CoapMessageType, isReliable: Boolean): BodyMode = when {
            isReliable -> RELIABLE
            type == CoapMessageType.NON -> NON
            else -> CON
        }
    }
}
