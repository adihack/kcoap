package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.codec.MessageCodec

/**
 * What one message to a peer may be, as its [PeerLink] reports it: at most [maxMessageSize] bytes in
 * [codec]'s framing. That is the transport's datagram size on a [DatagramLink] (RFC 7252 §4.6), the peer's
 * Max-Message-Size on a [ConnectionLink] (RFC 8323 §5.3.1). Block sizes follow it, so they are chosen the
 * same way on every transport.
 */
internal class MessageLimits(
    val maxMessageSize: Int,
    private val codec: MessageCodec,
    /** RFC 8323 §6: both CSMs indicated BERT, so one message may carry several 1024-byte blocks. */
    val allowsBert: Boolean,
    /**
     * Whether messages go in a stream framing (RFC 8323 §3.2, §4.2), whose length field can grow, and where
     * SZX 7 means BERT; on datagrams it is reserved (RFC 7959 §2.2).
     */
    val isReliable: Boolean,
) {
    /** The encoded size of [message]. */
    fun size(message: CoapMessage): Int = codec.encode(message).size

    /** Whether [message] fits with [reserve] bytes to spare for message security. */
    fun fits(message: CoapMessage, reserve: Int): Boolean = size(message) + reserve <= maxMessageSize

    /**
     * Payload bytes that fit next to [header] (its payload is ignored) and [reserve]: the header, token,
     * options and payload marker come off, and on a stream the 4 bytes the length field can grow by.
     */
    fun payloadRoom(header: CoapMessage, reserve: Int): Int {
        val bare = size(header.toBuilder().apply { payload = ByteArray(0) }.build())
        return maxMessageSize - reserve - bare - 1 - (if (isReliable) LENGTH_GROWTH else 0)
    }

    override fun toString(): String = "MessageLimits(maxMessageSize=$maxMessageSize, bert=$allowsBert)"

    private companion object {
        /** RFC 8323 §3.2: the extended length takes at most 4 bytes. */
        const val LENGTH_GROWTH = 4
    }
}
