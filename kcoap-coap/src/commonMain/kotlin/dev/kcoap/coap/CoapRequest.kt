package dev.kcoap.coap

/**
 * A request as the client application states it: method, target URI, further options and the whole body.
 * The client turns the URI into Uri-* options (RFC 7252 §6.4), splits a large body into Block1 transfers
 * and assigns token and Message ID. Immutable; build one with [Builder] or the client's method shortcuts.
 *
 * @property options options besides those the URI gives, such as Accept or Content-Format.
 * @property isConfirmable whether the request goes in a Confirmable message (RFC 7252 §4.2) or a
 *   Non-confirmable one (§4.3).
 */
public class CoapRequest(
    public val method: CoapCode,
    public val uri: CoapUri,
    public val options: OptionSet = OptionSet.EMPTY,
    payload: ByteArray = ByteArray(0),
    public val isConfirmable: Boolean = true,
) {
    private val body = payload.copyOf()

    init {
        require(method.isRequest) { "$method is not a request method" }
    }

    public val payload: ByteArray get() = body.copyOf()

    public val payloadSize: Int get() = body.size

    public fun toBuilder(): Builder = Builder(method, uri).also {
        it.options { addAll(options) }
        it.payload = body
        it.isConfirmable = isConfirmable
    }

    override fun toString(): String = buildString {
        append(method).append(' ').append(uri)
        if (!options.isEmpty()) append(' ').append(options)
        if (body.isNotEmpty()) append(' ').append(body.size).append(" bytes")
        if (!isConfirmable) append(" NON")
    }

    /** Builds a [CoapRequest]. */
    public class Builder(public val method: CoapCode, public val uri: CoapUri) {
        private val options = OptionSet.Builder()
        public var payload: ByteArray = ByteArray(0)
        public var isConfirmable: Boolean = true

        public fun options(block: OptionSet.Builder.() -> Unit): Builder = apply { options.apply(block) }

        /** Sets a UTF-8 text body. */
        public fun payload(text: String): Builder = apply { payload = text.encodeToByteArray() }

        public fun contentFormat(format: Int): Builder = options { contentFormat(format) }

        public fun accept(format: Int): Builder = options { accept(format) }

        /** Sends the request Non-confirmable (RFC 7252 §4.3). */
        public fun nonConfirmable(): Builder = apply { isConfirmable = false }

        public fun build(): CoapRequest = CoapRequest(method, uri, options.build(), payload, isConfirmable)
    }
}
