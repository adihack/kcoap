package dev.kcoap.coap.server

/**
 * Where the blocks of a [ServedBody] go, as its [dev.kcoap.coap.stack.QBlock2Sender] names them (`R` of
 * [dev.kcoap.coap.stack.QBlockAction]): in answer to a request ([QBlockRequest]), or as a notification of an
 * observation on its token, which no request asked for ([NotificationBody]; RFC 7641 §4.2, RFC 9177 §4.5).
 */
internal sealed interface QBlockTarget {
    /** The token the blocks go with. */
    val token: ByteArray

    /**
     * Whether blocks to this target carry the body's Observe value (RFC 9177 §4.5): those of a notification and
     * those answering the registration, which carries Observe itself, but never the answer to a request for
     * missing blocks or a Continue's own request, which carry none.
     */
    val carriesObserve: Boolean
}
