package dev.kcoap.coap

import dev.kcoap.common.InternalKcoapApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /**
     * How long the server asks the client to wait before it sends this request, or a similar one, again: the
     * Max-Age of a 4.29 Too Many Requests (RFC 8516 §4) or of a 5.03 Service Unavailable (RFC 7252
     * §5.9.3.4), or 60 seconds, Max-Age's default (RFC 7252 §5.10.5), when it carries none; null for every other
     * code. RFC 8516 §1 counts a request with the same method and URI as similar, and lets the application
     * decide for others. `RetryAfter` in kcoap-coap waits it out and repeats the request.
     */
    public val retryAfter: Duration?
        get() = if (code == CoapCode.TOO_MANY_REQUESTS || code == CoapCode.SERVICE_UNAVAILABLE) options.maxAge.seconds else null

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
         * 4.29 Too Many Requests (RFC 8516 §3), for a client that sends requests more often than the server can or
         * will handle: its Max-Age is [retryAfter] in whole seconds, rounded up so the client never comes back too
         * early, and at most 2^32 - 1 (RFC 7252 §5.10.5); the client should not send a similar request before that
         * (RFC 8516 §4). [diagnostic] becomes the diagnostic payload (RFC 7252 §5.5.2), which may say more about
         * the overload. RFC 8516 §3 keeps 4.29 for the clients that send too often; an overload that all clients
         * cause gets 5.03 Service Unavailable, with Max-Age as well.
         *
         * @throws IllegalArgumentException when [retryAfter] is negative.
         */
        public fun tooManyRequests(retryAfter: Duration, diagnostic: String? = null): CoapResponse {
            require(!retryAfter.isNegative()) { "Retry-after must not be negative: $retryAfter" }
            val whole = retryAfter.inWholeSeconds
            val seconds = if (retryAfter.isInfinite() || whole >= MAX_AGE_LIMIT) {
                MAX_AGE_LIMIT
            } else {
                whole + if (retryAfter > whole.seconds) 1 else 0
            }
            return error(CoapCode.TOO_MANY_REQUESTS, diagnostic).withOptions { maxAge(seconds) }
        }

        /**
         * The response [message] carries: its code, options and payload, without the messaging-layer fields.
         *
         * @throws IllegalArgumentException when [message] has no response code (RFC 7252 §5.9), such as a request
         *   or an Empty message.
         */
        public fun from(message: CoapMessage): CoapResponse =
            CoapResponse(message.code, message.options, message.payload)

        /** RFC 7252 §5.10.5: Max-Age is an unsigned integer of up to 4 bytes. */
        private const val MAX_AGE_LIMIT: Long = 0xFFFF_FFFFL

        private fun contentFormatOption(format: Int?): OptionSet =
            if (format == null) OptionSet.EMPTY else OptionSet.build { contentFormat(format) }
    }
}
