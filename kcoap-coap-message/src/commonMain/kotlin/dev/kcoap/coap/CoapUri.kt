package dev.kcoap.coap

import dev.kcoap.transport.CoapScheme

/**
 * A CoAP URI (RFC 7252 §6, RFC 8323 §8) and its mapping to request options.
 *
 * [parse] follows the decomposition algorithm of RFC 7252 §6.4 and [toString] the composition of §6.5,
 * so `CoapUri.parse(s).toString()` normalizes `s`.
 *
 * @property host the host without brackets; IPv6 literals are bracketed again by [toString].
 * @property pathSegments decoded Uri-Path values; an empty list is the root path.
 * @property querySegments decoded Uri-Query values.
 */
public class CoapUri(
    public val scheme: CoapScheme,
    public val host: String,
    public val port: Int = scheme.defaultPort,
    public val pathSegments: List<String> = emptyList(),
    public val querySegments: List<String> = emptyList(),
) {
    init {
        require(host.isNotEmpty()) { "A CoAP URI needs a host" }
        require(port in 1..65535) { "Port out of range: $port" }
    }

    /** True for IPv4 and IPv6 literals, which RFC 7252 §6.4 step 5 sends without a Uri-Host option. */
    public val isIpLiteral: Boolean get() = ':' in host || IPV4.matches(host)

    /** "/a/b", or "/" for the root. */
    public val path: String get() = pathSegments.joinToString("/", prefix = "/") { percentEncode(it, PATH_SAFE) }

    /**
     * The request options this URI turns into (RFC 7252 §6.4 steps 5–8). Uri-Port is included only when
     * [destinationPort] differs from [port], which happens when sending to a proxy.
     */
    public fun toOptions(destinationPort: Int = port): OptionSet = OptionSet.build {
        if (!isIpLiteral) uriHost(host)
        if (port != destinationPort) uriPort(port)
        uriPathSegments(pathSegments)
        querySegments.forEach { uriQuery(it) }
    }

    override fun toString(): String = buildString {
        append(scheme.scheme).append("://")
        if (':' in host) append('[').append(host).append(']') else append(percentEncode(host, HOST_SAFE))
        if (port != scheme.defaultPort) append(':').append(port)
        append(path)
        if (querySegments.isNotEmpty()) {
            append('?').append(querySegments.joinToString("&") { percentEncode(it, QUERY_SAFE) })
        }
    }

    override fun equals(other: Any?): Boolean = other is CoapUri && toString() == other.toString()

    override fun hashCode(): Int = toString().hashCode()

    public companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        // Brackets are escaped even where Java's syntax would not need it: JavaScript compiles regexes in
        // Unicode mode, which rejects a lone "]".
        private val URI = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(\[[^\]]*\]|[^/:?#\[]*)(?::(\d*))?([^?#]*)(?:\?([^#]*))?(#.*)?$""")

        private const val UNRESERVED = "-._~"
        private const val SUB_DELIMS = "!$&'()*+,;="
        private const val PATH_SAFE = "$UNRESERVED$SUB_DELIMS:@"
        private const val HOST_SAFE = "$UNRESERVED$SUB_DELIMS"

        // "&" separates query segments, so it must be escaped inside one.
        private val QUERY_SAFE = "$UNRESERVED$SUB_DELIMS:@/?".replace("&", "")

        /**
         * Parses an absolute `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws` or `coaps+ws` URI.
         *
         * @throws IllegalArgumentException for relative URIs, other schemes, fragments (RFC 7252 §6.4
         *   step 4), missing hosts and malformed percent-encodings.
         */
        public fun parse(text: String): CoapUri {
            val match = URI.matchEntire(text) ?: throw IllegalArgumentException("Not an absolute URI: $text")
            val (schemeText, hostText, portText, pathText, queryText, fragment) = match.destructured
            val scheme = CoapScheme.fromScheme(schemeText) ?: throw IllegalArgumentException("Not a CoAP scheme: $schemeText")
            require(fragment.isEmpty()) { "CoAP URIs must not have a fragment: $text" }

            val host = if (hostText.startsWith("[")) {
                hostText.removeSurrounding("[", "]").lowercase()
            } else {
                percentDecode(hostText).lowercase()
            }
            require(host.isNotEmpty()) { "A CoAP URI needs a host: $text" }
            val port = if (portText.isEmpty()) scheme.defaultPort else portText.toInt()

            // RFC 7252 §6.4 step 8: an empty path or "/" gives no Uri-Path options.
            val path = if (pathText.isEmpty() || pathText == "/") emptyList() else pathText.removePrefix("/").split('/').map(::percentDecode)
            val query = if (match.groups[5] == null) emptyList() else queryText.split('&').map(::percentDecode)
            return CoapUri(scheme, host, port, path, query)
        }

        /** Rebuilds a URI from received request options (RFC 7252 §6.5), as a server or proxy sees it. */
        public fun fromOptions(scheme: CoapScheme, destinationHost: String, destinationPort: Int, options: OptionSet): CoapUri =
            CoapUri(
                scheme = scheme,
                host = options.uriHost ?: destinationHost,
                port = options.uriPort ?: destinationPort,
                pathSegments = options.uriPath,
                querySegments = options.uriQuery,
            )

        internal fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    require(i + 3 <= text.length) { "Truncated percent-encoding in $text" }
                    val hex = text.substring(i + 1, i + 3)
                    out += (hex.toIntOrNull(16) ?: throw IllegalArgumentException("Invalid percent-encoding %$hex")).toByte()
                    i += 3
                } else {
                    out += c.toString().encodeToByteArray().toList()
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }

        internal fun percentEncode(text: String, safe: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val c = (byte.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in safe) {
                    append(c)
                } else {
                    append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}
