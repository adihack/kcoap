package dev.kcoap.coap.server

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.QBlockAction
import dev.kcoap.coap.stack.TransmissionOwner

/** What answers one request in a [CoapServer]'s pipeline. */
internal sealed interface Reply {
    /**
     * One [response]. [observation] is the observer a registration added; [isChallenge] marks a 4.01 with Echo
     * that verifies the client's address (RFC 9175 §2.4); [owner] learns how a separate response ended, as a
     * Q-Block1 upload learns of a Reset to its 4.08 (RFC 9177 §4.3).
     */
    class Single(
        val response: CoapResponse,
        val observation: ServerObservation? = null,
        val isChallenge: Boolean = false,
        val owner: TransmissionOwner? = null,
    ) : Reply

    /**
     * No response of its own, as for a block of a Q-Block1 upload in the middle of a set (RFC 9177 §4.3):
     * nothing for a Non-confirmable request, an Empty ACK for a Confirmable one, and on a connection the
     * request counts as answered (RFC 9177 Appendix B), so a Pong with Custody is not held up by it.
     */
    data object None : Reply

    /**
     * Blocks of the Q-Block2 [body] (RFC 9177 §4.4): [actions] of its sender, which send blocks on this request
     * or others of the body and say how this request is answered. [isNew] when this request created the body,
     * which a 4.01 with Echo then replaces (RFC 9175 §2.4). A registration's body names its observation
     * ([ServedBody.observation]; RFC 7641 §4.1, RFC 9177 §4.5).
     */
    class Body(
        val body: ServedBody,
        val actions: List<QBlockAction<QBlockTarget>>,
        val isNew: Boolean,
    ) : Reply
}
