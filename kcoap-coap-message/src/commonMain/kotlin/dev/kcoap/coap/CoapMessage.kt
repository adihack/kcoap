package dev.kcoap.coap

import dev.kcoap.common.toHex

/**
 * A CoAP message (RFC 7252 §3): header fields, token, options and payload. Immutable; derive changed
 * copies with [toBuilder].
 *
 * [type] and [messageId] belong to the UDP messaging layer. On reliable transports (RFC 8323) they do
 * not exist on the wire: the reliable codec ignores them when encoding and sets `CON` and 0 when
 * decoding.
 */
public class CoapMessage(
    public val type: CoapMessageType,
    public val code: CoapCode,
    public val messageId: Int,
    token: ByteArray,
    public val options: OptionSet,
    payload: ByteArray,
) {
    private val tokenBytes = token.copyOf()
    private val payloadBytes = payload.copyOf()

    init {
        require(messageId in 0..0xFFFF) { "Message ID out of range: $messageId" }
        require(tokenBytes.size <= MAX_TOKEN_LENGTH) { "Token of ${tokenBytes.size} bytes exceeds $MAX_TOKEN_LENGTH" }
        if (code.isEmpty) {
            // RFC 7252 §4.1: an Empty message has no token, options or payload.
            require(tokenBytes.isEmpty() && options.isEmpty() && payloadBytes.isEmpty()) {
                "An Empty message carries no token, options or payload"
            }
        }
    }

    public val token: ByteArray get() = tokenBytes.copyOf()

    public val payload: ByteArray get() = payloadBytes.copyOf()

    public val payloadSize: Int get() = payloadBytes.size

    /** The token as hex, handy as a map key and in logs. */
    public val tokenHex: String get() = tokenBytes.toHex()

    public val isRequest: Boolean get() = code.isRequest
    public val isResponse: Boolean get() = code.isResponse
    public val isEmpty: Boolean get() = code.isEmpty
    public val isSignaling: Boolean get() = code.isSignaling

    public fun hasToken(token: ByteArray): Boolean = tokenBytes.contentEquals(token)

    public fun payloadAsText(): String = payloadBytes.decodeToString()

    public fun toBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean =
        other is CoapMessage && type == other.type && code == other.code && messageId == other.messageId &&
            tokenBytes.contentEquals(other.tokenBytes) && options == other.options &&
            payloadBytes.contentEquals(other.payloadBytes)

    override fun hashCode(): Int = 31 * (31 * code.value + messageId) + tokenBytes.contentHashCode()

    /**
     * `CON 0.01 GET mid=23 token=4a7b [Uri-Path: tv1] 5 bytes`; payload content is never shown. Signaling
     * messages show their options by the signaling registry (RFC 8323 §5.2): `7.01 CSM [Max-Message-Size: 1152]`.
     */
    override fun toString(): String = buildString {
        if (isSignaling) {
            append(code)
        } else {
            append(type).append(' ').append(code)
            append(" mid=").append(messageId)
        }
        if (tokenBytes.isNotEmpty()) append(" token=").append(tokenHex)
        if (!options.isEmpty()) {
            append(' ')
            if (isSignaling) {
                options.joinTo(this, prefix = "[", postfix = "]") { SignalingOptions.describe(code, it) }
            } else {
                append(options)
            }
        }
        if (payloadBytes.isNotEmpty()) append(' ').append(payloadBytes.size).append(" bytes")
    }

    /** Builds messages; every field has a neutral default. */
    public class Builder internal constructor(source: CoapMessage?) {
        public var type: CoapMessageType = source?.type ?: CoapMessageType.CON
        public var code: CoapCode = source?.code ?: CoapCode.EMPTY
        public var messageId: Int = source?.messageId ?: 0
        public var token: ByteArray = source?.token ?: ByteArray(0)
        public var payload: ByteArray = source?.payload ?: ByteArray(0)
        private val options: OptionSet.Builder = source?.options?.toBuilder() ?: OptionSet.Builder()

        public constructor() : this(null)

        /** Changes the options in place. */
        public fun options(block: OptionSet.Builder.() -> Unit): Builder = apply { options.apply(block) }

        public fun replaceOptions(newOptions: OptionSet): Builder = apply {
            options.clear()
            options.addAll(newOptions)
        }

        public fun payload(text: String): Builder = apply { payload = text.encodeToByteArray() }

        public fun build(): CoapMessage = CoapMessage(type, code, messageId, token, options.build(), payload)
    }

    public companion object {
        /** RFC 7252 §3: tokens are 0 to 8 bytes. */
        public const val MAX_TOKEN_LENGTH: Int = 8

        public fun build(block: Builder.() -> Unit): CoapMessage = Builder().apply(block).build()

        /** A request with [method]; set the token, Message ID and options in [block]. */
        public fun request(method: CoapCode, block: Builder.() -> Unit = {}): CoapMessage {
            require(method.isRequest) { "$method is not a request method" }
            return Builder().apply { code = method }.apply(block).build()
        }

        /** A response to [request], with its token; the type and Message ID are left to the caller. */
        public fun response(request: CoapMessage, code: CoapCode, block: Builder.() -> Unit = {}): CoapMessage {
            require(code.isResponse) { "$code is not a response code" }
            return Builder().apply {
                this.code = code
                token = request.token
            }.apply(block).build()
        }

        /** An Empty ACK or RST for [messageId] (RFC 7252 §4.2, §4.3). */
        public fun empty(type: CoapMessageType, messageId: Int): CoapMessage =
            CoapMessage(type, CoapCode.EMPTY, messageId, ByteArray(0), OptionSet.EMPTY, ByteArray(0))
    }
}
