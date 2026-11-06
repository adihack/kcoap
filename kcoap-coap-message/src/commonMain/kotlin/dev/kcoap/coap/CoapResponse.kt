package dev.kcoap.coap

import dev.kcoap.common.InternalKcoapApi

/**
 * A response as the application sees it: code, options and the whole body. Block-wise transfers are
 * already reassembled, and the messaging-layer fields (type, Message ID, token) are not part of it.
 *
 * A server resource returns one; a client receives one. Immutable.
 */
public class CoapResponse(
    public val code: CoapCode,
    public val options: OptionSet = OptionSet.EMPTY,
    payload: ByteArray = ByteArray(0),
) {
    private val body = payload.copyOf()

    init {
        require(code.isResponse) { "$code is not a response code" }
    }

    /** The whole body; a copy. */
    public val payload: ByteArray get() = body.copyOf()

    public val payloadSize: Int get() = body.size

    /** Whether the code is 2.xx. */
    public val isSuccess: Boolean get() = code.isSuccess

    /** The Content-Format option, or null. */
    public val contentFormat: Int? get() = options.contentFormat

    /** The body as UTF-8 text. */
    public fun payloadAsText(): String = body.decodeToString()

    /** A copy with [block] applied to its options. */
    public fun withOptions(block: OptionSet.Builder.() -> Unit): CoapResponse =
        CoapResponse(code, options.toBuilder().apply(block).build(), body)

    /**
     * This response as a message with [token], for the stack to send: the type and Message ID are placeholders
     * that the messaging layer replaces (RFC 7252 §4), and a reliable transport has neither (RFC 8323 §3.2).
     */
    @InternalKcoapApi
    public fun toMessage(token: ByteArray): CoapMessage = CoapMessage(CoapMessageType.CON, code, 0, token, options, body)

    override fun equals(other: Any?): Boolean =
        other is CoapResponse && code == other.code && options == other.options && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * (31 * code.value + options.hashCode()) + body.contentHashCode()

    /** `2.05 Content [Content-Format: 0] 12 bytes`; the payload itself is never shown. */
    override fun toString(): String = buildString {
        append(code)
        if (!options.isEmpty()) append(' ').append(options)
        if (body.isNotEmpty()) append(' ').append(body.size).append(" bytes")
    }

    public companion object {
        /** 2.05 Content with [payload] in [contentFormat]. */
        public fun content(payload: ByteArray, contentFormat: Int? = null): CoapResponse =
            CoapResponse(CoapCode.CONTENT, contentFormatOption(contentFormat), payload)

        /** 2.05 Content with UTF-8 [text], `text/plain` unless another [contentFormat] is given. */
        public fun content(text: String, contentFormat: Int = ContentFormat.TEXT_PLAIN): CoapResponse =
            content(text.encodeToByteArray(), contentFormat)

        /** 2.01 Created (RFC 7252 §5.9.1.1), with the Location-Path of the new resource. */
        public fun created(vararg locationPath: String): CoapResponse =
            CoapResponse(CoapCode.CREATED, OptionSet.build { locationPath.forEach { locationPath(it) } })

        /** 2.04 Changed (RFC 7252 §5.9.1.4). */
        public fun changed(): CoapResponse = CoapResponse(CoapCode.CHANGED)

        /** 2.02 Deleted (RFC 7252 §5.9.1.2). */
        public fun deleted(): CoapResponse = CoapResponse(CoapCode.DELETED)

        /**
         * An error response; [diagnostic] becomes the RFC 7252 §5.5.2 diagnostic payload, which is UTF-8
         * text for developers and SHOULD be empty when it adds nothing to the code.
         */
        public fun error(code: CoapCode, diagnostic: String? = null): CoapResponse {
            require(code.isClientError || code.isServerError) { "$code is not an error code" }
            return CoapResponse(code, OptionSet.EMPTY, diagnostic?.encodeToByteArray() ?: ByteArray(0))
        }

        /**
         * The response [message] carries: its code, options and payload, without the messaging-layer fields.
         *
         * @throws IllegalArgumentException when [message] has no response code (RFC 7252 §5.9), such as a request
         *   or an Empty message.
         */
        public fun from(message: CoapMessage): CoapResponse =
            CoapResponse(message.code, message.options, message.payload)

        private fun contentFormatOption(format: Int?): OptionSet =
            if (format == null) OptionSet.EMPTY else OptionSet.build { contentFormat(format) }
    }
}
