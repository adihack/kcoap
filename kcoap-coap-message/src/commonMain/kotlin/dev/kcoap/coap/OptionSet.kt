package dev.kcoap.coap

/**
 * The options of a message, kept in ascending number order as they are encoded. Repeated options
 * keep their relative order, which matters for Uri-Path and the like (RFC 7252 §5.4.5).
 *
 * Typed properties decode the options this library knows; [first] and [all] give raw access to any
 * option, including ones registered by the application.
 */
public class OptionSet private constructor(private val options: List<CoapOption>) : Iterable<CoapOption> {

    public val size: Int get() = options.size

    public fun isEmpty(): Boolean = options.isEmpty()

    override fun iterator(): Iterator<CoapOption> = options.iterator()

    public operator fun contains(number: Int): Boolean = options.any { it.number == number }

    public fun first(number: Int): CoapOption? = options.firstOrNull { it.number == number }

    public fun all(number: Int): List<CoapOption> = options.filter { it.number == number }

    public val uriHost: String? get() = first(CoapOptions.URI_HOST.number)?.stringValue()
    public val uriPort: Int? get() = first(CoapOptions.URI_PORT.number)?.uintValue()?.toInt()
    public val uriPath: List<String> get() = all(CoapOptions.URI_PATH.number).map { it.stringValue() }
    public val uriQuery: List<String> get() = all(CoapOptions.URI_QUERY.number).map { it.stringValue() }
    public val locationPath: List<String> get() = all(CoapOptions.LOCATION_PATH.number).map { it.stringValue() }
    public val locationQuery: List<String> get() = all(CoapOptions.LOCATION_QUERY.number).map { it.stringValue() }
    public val proxyUri: String? get() = first(CoapOptions.PROXY_URI.number)?.stringValue()
    public val proxyScheme: String? get() = first(CoapOptions.PROXY_SCHEME.number)?.stringValue()

    /** Uri-Path joined with "/" and a leading "/", or "/" when there is none. */
    public val path: String get() = uriPath.joinToString(separator = "/", prefix = "/")

    public val contentFormat: Int? get() = first(CoapOptions.CONTENT_FORMAT.number)?.uintValue()?.toInt()
    public val accept: Int? get() = first(CoapOptions.ACCEPT.number)?.uintValue()?.toInt()

    /** Max-Age in seconds; RFC 7252 §5.10.5 defaults it to 60 when absent. */
    public val maxAge: Long get() = first(CoapOptions.MAX_AGE.number)?.uintValue() ?: 60
    public val etags: List<ByteArray> get() = all(CoapOptions.ETAG.number).map { it.value }
    public val ifMatch: List<ByteArray> get() = all(CoapOptions.IF_MATCH.number).map { it.value }
    public val ifNoneMatch: Boolean get() = CoapOptions.IF_NONE_MATCH.number in this

    public val observe: Long? get() = first(CoapOptions.OBSERVE.number)?.uintValue()
    public val block1: BlockOption? get() = first(CoapOptions.BLOCK1.number)?.let { BlockOption.fromUint(it.uintValue()) }
    public val block2: BlockOption? get() = first(CoapOptions.BLOCK2.number)?.let { BlockOption.fromUint(it.uintValue()) }
    public val size1: Long? get() = first(CoapOptions.SIZE1.number)?.uintValue()

    /** The Q-Block1 option (RFC 9177 §4.2), which a message carries at most once. */
    public val qBlock1: BlockOption? get() = first(CoapOptions.Q_BLOCK1.number)?.let { BlockOption.fromUint(it.uintValue()) }

    /** The first Q-Block2 option (RFC 9177 §4.2); a request for missing blocks repeats it, see [qBlock2List]. */
    public val qBlock2: BlockOption? get() = first(CoapOptions.Q_BLOCK2.number)?.let { BlockOption.fromUint(it.uintValue()) }

    /** Every Q-Block2 option in order: one per block a request for missing blocks asks for (RFC 9177 §4.4). */
    public val qBlock2List: List<BlockOption> get() = all(CoapOptions.Q_BLOCK2.number).map { BlockOption.fromUint(it.uintValue()) }
    public val size2: Long? get() = first(CoapOptions.SIZE2.number)?.uintValue()

    public val oscore: ByteArray? get() = first(CoapOptions.OSCORE.number)?.value
    public val edhoc: Boolean get() = CoapOptions.EDHOC.number in this
    public val echo: ByteArray? get() = first(CoapOptions.ECHO.number)?.value
    public val requestTags: List<ByteArray> get() = all(CoapOptions.REQUEST_TAG.number).map { it.value }
    public val hopLimit: Int? get() = first(CoapOptions.HOP_LIMIT.number)?.uintValue()?.toInt()
    public val noResponse: Int? get() = first(CoapOptions.NO_RESPONSE.number)?.uintValue()?.toInt()

    public fun toBuilder(): Builder = Builder(options)

    override fun equals(other: Any?): Boolean = other is OptionSet && options == other.options

    override fun hashCode(): Int = options.hashCode()

    override fun toString(): String = options.joinToString(prefix = "[", postfix = "]")

    /**
     * Collects options; [build] sorts them by number, keeping the insertion order of repeated ones.
     * Typed setters replace earlier values of a non-repeatable option.
     */
    public class Builder internal constructor(initial: List<CoapOption>) {
        private val options = initial.toMutableList()

        public constructor() : this(emptyList())

        public fun add(option: CoapOption): Builder = apply { options += option }

        public fun add(number: Int, value: ByteArray): Builder = add(CoapOption(number, value))

        /** Replaces every option with [option]'s number by [option]. */
        public fun set(option: CoapOption): Builder = apply {
            remove(option.number)
            options += option
        }

        public fun remove(number: Int): Builder = apply { options.removeAll { it.number == number } }

        public fun addAll(other: Iterable<CoapOption>): Builder = apply { options += other }

        public fun clear(): Builder = apply { options.clear() }

        /** Splits [path] on "/" into Uri-Path options; empty segments around slashes are dropped. */
        public fun uriPath(path: String): Builder = apply {
            remove(CoapOptions.URI_PATH.number)
            path.split('/').filter { it.isNotEmpty() }.forEach { add(CoapOption.string(CoapOptions.URI_PATH.number, it)) }
        }

        public fun uriPathSegments(segments: List<String>): Builder = apply {
            remove(CoapOptions.URI_PATH.number)
            segments.forEach { add(CoapOption.string(CoapOptions.URI_PATH.number, it)) }
        }

        public fun uriQuery(query: String): Builder = add(CoapOption.string(CoapOptions.URI_QUERY.number, query))
        public fun uriHost(host: String): Builder = set(CoapOption.string(CoapOptions.URI_HOST.number, host))
        public fun uriPort(port: Int): Builder = set(CoapOption.uint(CoapOptions.URI_PORT.number, port.toLong()))
        public fun locationPath(segment: String): Builder = add(CoapOption.string(CoapOptions.LOCATION_PATH.number, segment))
        public fun locationQuery(query: String): Builder = add(CoapOption.string(CoapOptions.LOCATION_QUERY.number, query))
        public fun proxyUri(uri: String): Builder = set(CoapOption.string(CoapOptions.PROXY_URI.number, uri))
        public fun proxyScheme(scheme: String): Builder = set(CoapOption.string(CoapOptions.PROXY_SCHEME.number, scheme))
        public fun contentFormat(format: Int): Builder = set(CoapOption.uint(CoapOptions.CONTENT_FORMAT.number, format.toLong()))
        public fun accept(format: Int): Builder = set(CoapOption.uint(CoapOptions.ACCEPT.number, format.toLong()))
        public fun maxAge(seconds: Long): Builder = set(CoapOption.uint(CoapOptions.MAX_AGE.number, seconds))
        public fun etag(tag: ByteArray): Builder = add(CoapOption(CoapOptions.ETAG.number, tag))
        public fun ifMatch(tag: ByteArray): Builder = add(CoapOption(CoapOptions.IF_MATCH.number, tag))
        public fun ifNoneMatch(): Builder = set(CoapOption.empty(CoapOptions.IF_NONE_MATCH.number))
        public fun observe(value: Long): Builder = set(CoapOption.uint(CoapOptions.OBSERVE.number, value))
        public fun block1(block: BlockOption): Builder = set(CoapOption.uint(CoapOptions.BLOCK1.number, block.toUint()))
        public fun block2(block: BlockOption): Builder = set(CoapOption.uint(CoapOptions.BLOCK2.number, block.toUint()))
        public fun size1(size: Long): Builder = set(CoapOption.uint(CoapOptions.SIZE1.number, size))

        /** Sets the Q-Block1 option (RFC 9177 §4.2), replacing an earlier one. */
        public fun qBlock1(block: BlockOption): Builder = set(CoapOption.uint(CoapOptions.Q_BLOCK1.number, block.toUint()))

        /** Sets a single Q-Block2 option (RFC 9177 §4.2), replacing every earlier one. */
        public fun qBlock2(block: BlockOption): Builder = set(CoapOption.uint(CoapOptions.Q_BLOCK2.number, block.toUint()))

        /**
         * Adds one more Q-Block2 option, as a request for missing blocks does for each block it asks for; RFC
         * 9177 §4.4 wants them in strictly ascending block order with one size.
         */
        public fun addQBlock2(block: BlockOption): Builder = add(CoapOption.uint(CoapOptions.Q_BLOCK2.number, block.toUint()))
        public fun size2(size: Long): Builder = set(CoapOption.uint(CoapOptions.SIZE2.number, size))
        public fun oscore(value: ByteArray): Builder = set(CoapOption(CoapOptions.OSCORE.number, value))
        public fun edhoc(): Builder = set(CoapOption.empty(CoapOptions.EDHOC.number))
        public fun echo(value: ByteArray): Builder = set(CoapOption(CoapOptions.ECHO.number, value))
        public fun requestTag(tag: ByteArray): Builder = add(CoapOption(CoapOptions.REQUEST_TAG.number, tag))
        public fun hopLimit(limit: Int): Builder = set(CoapOption.uint(CoapOptions.HOP_LIMIT.number, limit.toLong()))
        public fun noResponse(value: Int): Builder = set(CoapOption.uint(CoapOptions.NO_RESPONSE.number, value.toLong()))

        public fun build(): OptionSet = OptionSet(options.sortedBy { it.number })
    }

    public companion object {
        public val EMPTY: OptionSet = OptionSet(emptyList())

        public fun of(options: Iterable<CoapOption>): OptionSet = Builder().addAll(options).build()

        public fun build(block: Builder.() -> Unit): OptionSet = Builder().apply(block).build()
    }
}
