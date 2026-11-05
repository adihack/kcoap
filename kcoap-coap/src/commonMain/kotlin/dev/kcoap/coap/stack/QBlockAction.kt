package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage

/**
 * What a Q-Block state machine ([QBlock1Sender], [QBlock1Receiver], [QBlock2Sender], [QBlock2Receiver]) asks
 * its driver to do. The machines never perform I/O and never build messages from scratch: they say which
 * block or answer goes on which request, and the driver adds the options of the body (Request-Tag, Size1 or
 * Size2, ETag, Observe, Content-Format) and the token.
 *
 * [R] is how a machine names a request: the sending sequence number for the client machines, which assign one
 * to every message they send (each on a new token, RFC 9177 §4.3), and the driver's handle of a received
 * request for the server machines.
 */
internal sealed interface QBlockAction<out R> {
    /**
     * Send block [num] of the body with M = [more]: as a new request named [ref] (Q-Block1), or as a
     * response on the request [ref] (Q-Block2).
     */
    class SendBlock<out R>(val num: Long, val more: Boolean, val ref: R) : QBlockAction<R> {
        override fun toString(): String = "SendBlock($num/${if (more) 1 else 0} on $ref)"
    }

    /**
     * Answer [ref] with 2.31 (Continue) carrying Q-Block1 [num] with M set (RFC 9177 §4.3): every block up to
     * [num] has arrived.
     */
    class Send231<out R>(val ref: R, val num: Long) : QBlockAction<R> {
        override fun toString(): String = "Send231($num on $ref)"
    }

    /**
     * Answer [ref] with a Non-confirmable 4.08 (Request Entity Incomplete) whose Content-Format 272 payload
     * lists [missing] (RFC 9177 §4.3, §5; [MissingBlocks.encode]).
     */
    class Send408<out R>(val ref: R, val missing: List<Long>) : QBlockAction<R> {
        override fun toString(): String = "Send408($missing on $ref)"
    }

    /**
     * The request [ref] gets no answer of its own: nothing for a Non-confirmable one, an Empty ACK for a
     * Confirmable one, and on a reliable transport it counts as answered (RFC 9177 Appendix B).
     */
    class NoResponse<out R>(val ref: R) : QBlockAction<R> {
        override fun toString(): String = "NoResponse($ref)"
    }

    /** Answer [ref] with [code] and [diagnostic]: the request does not fit the body it belongs to. */
    class Reject<out R>(val ref: R, val code: CoapCode, val diagnostic: String) : QBlockAction<R> {
        override fun toString(): String = "Reject($ref, $code $diagnostic)"
    }

    /**
     * Send the Continue of RFC 9177 §4.4 on a new token: the original request without Observe, ETag, payload
     * or Q-Block1, with Q-Block2 [num] (the first block of the next set), M set.
     */
    class SendContinue(val num: Long) : QBlockAction<Nothing> {
        override fun toString(): String = "SendContinue($num)"
    }

    /**
     * Ask for missing blocks on a new token (RFC 9177 §4.4): the original request without Observe, ETag,
     * payload or Q-Block1, with one Q-Block2 option per entry of [blocks], which are strictly ascending.
     */
    class RequestMissing(val blocks: List<BlockOption>) : QBlockAction<Nothing> {
        override fun toString(): String = "RequestMissing(${blocks.joinToString()})"
    }

    /**
     * The body is complete: [message] is the assembled request (Q-Block1, answered on [ref]) or response
     * (Q-Block2, [ref] null), or the final response to an upload, which arrived on [ref].
     */
    class Deliver<out R>(val ref: R?, val message: CoapMessage) : QBlockAction<R> {
        override fun toString(): String = "Deliver($message on $ref)"
    }

    /** The client gives the body up: fail the operation with [reason]. */
    class Fail(val reason: CoapException.Reason, val diagnostic: String) : QBlockAction<Nothing> {
        override fun toString(): String = "Fail($reason: $diagnostic)"
    }

    /** Start the upload again with blocks of [blockSize] bytes and a new Request-Tag (RFC 7959 §2.9.3). */
    class Restart(val blockSize: Int) : QBlockAction<Nothing> {
        override fun toString(): String = "Restart($blockSize)"
    }

    /** The partial body is given up and its state can go ([reason] for the log). */
    class Discard(val reason: String) : QBlockAction<Nothing> {
        override fun toString(): String = "Discard($reason)"
    }

    /** The tokens of the requests [refs] are no longer needed for this body (RFC 9177 §4.3). */
    class Release<out R>(val refs: List<R>) : QBlockAction<R> {
        override fun toString(): String = "Release($refs)"
    }

    /** A message was ignored ([reason] for the log), as RFC 9177 asks for duplicates and bad blocks. */
    class Drop(val reason: String) : QBlockAction<Nothing> {
        override fun toString(): String = "Drop($reason)"
    }
}
