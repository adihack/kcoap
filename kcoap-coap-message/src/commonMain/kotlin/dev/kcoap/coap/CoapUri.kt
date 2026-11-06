package dev.kcoap.coap

import dev.kcoap.transport.CoapScheme

/**
 * A CoAP URI (RFC 7252 §6, RFC 8323 §8) and its mapping to request options.
 *
 * [parse] follows the decomposition algorithm of RFC 7252 §6.4 and [toString] the composition of §6.5,
 * so `CoapUri.parse(s).toString()` normalizes `s`. [parse] is for URIs the application supplies and throws on a
 * malformed one; a URI a peer supplied goes through [parseOrNull], which never throws.
 *
 * @property host the host without brackets; IPv6 literals are bracketed again by [toString].
 */
public class CoapUri(
    public val scheme: CoapScheme,
    public val host: String,
    public val port: Int = scheme.defaultPort,
    pathSegments: List<String> = emptyList(),
    querySegments: List<String> = emptyList(),
) {
    /** Decoded Uri-Path values; an empty list is the root path. A copy of the list given. */
    public val pathSegments: List<String> = pathSegments.toList()

    /** Decoded Uri-Query values. A copy of the list given. */
    public val querySegments: List<String> = querySegments.toList()

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
         * Parses an absolute `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws` or `coaps+ws` URI, one the
         * application supplies; a URI from a peer goes through [parseOrNull].
         *
         * The exception is listed in `@Throws`, since the application's URIs are often typed in: on Apple
         * platforms Swift catches a malformed one as an `NSError` (`try CoapUri.Companion.shared.parse(text:)`),
         * with the Kotlin exception in its `userInfo` under `"KotlinException"`.
         *
         * @throws IllegalArgumentException for relative URIs, other schemes, fragments (RFC 7252 §6.4
         *   step 4), missing hosts, ports outside 1..65535 and malformed percent-encodings.
         */
        @Throws(IllegalArgumentException::class)
        public fun parse(text: String): CoapUri = decompose(text)

        /**
         * Parses [text] like [parse], or returns null where [parse] throws. For URIs a peer supplied, such as link
         * targets in a link-format document (RFC 6690 §2) or a URI built from Location-Path and Location-Query
         * (RFC 7252 §5.10.7), whose malformation is the peer's, not the application's.
         */
        public fun parseOrNull(text: String): CoapUri? = try {
            decompose(text)
        } catch (_: MalformedUri) {
            null
        }

        /** RFC 7252 §6.4 steps 1–8; throws only [MalformedUri]. */
        private fun decompose(text: String): CoapUri {
            val match = URI.matchEntire(text) ?: throw MalformedUri("Not an absolute URI: $text")
            val (schemeText, hostText, portText, pathText, queryText, fragment) = match.destructured
            val scheme = CoapScheme.fromScheme(schemeText) ?: throw MalformedUri("Not a CoAP scheme: $schemeText")
            if (fragment.isNotEmpty()) throw MalformedUri("CoAP URIs must not have a fragment: $text")

            val host = if (hostText.startsWith("[")) {
                hostText.removeSurrounding("[", "]").lowercase()
            } else {
                percentDecode(hostText).lowercase()
            }
            if (host.isEmpty()) throw MalformedUri("A CoAP URI needs a host: $text")
            val port = if (portText.isEmpty()) scheme.defaultPort else portText.toIntOrNull() ?: -1
            if (port !in 1..65535) throw MalformedUri("Port out of range: $portText")

            // RFC 7252 §6.4 step 8: an empty path or "/" gives no Uri-Path options.
            val path = if (pathText.isEmpty() || pathText == "/") emptyList() else pathText.removePrefix("/").split('/').map(::percentDecode)
            val query = if (match.groups[5] == null) emptyList() else queryText.split('&').map(::percentDecode)
            return CoapUri(scheme, host, port, path, query)
        }

        /**
         * Rebuilds a URI from request options (RFC 7252 §6.5), as a server or proxy sees it: Uri-Host and Uri-Port
         * where present, else [destinationHost] and [destinationPort]. For options the application built; the
         * options of a received request are a peer's input and go through [fromOptionsOrNull].
         *
         * @throws IllegalArgumentException when the host is empty or the port is outside 1..65535, such as an
         *   empty Uri-Port option, which is the value 0 (RFC 7252 §3.2).
         */
        public fun fromOptions(scheme: CoapScheme, destinationHost: String, destinationPort: Int, options: OptionSet): CoapUri =
            CoapUri(
                scheme = scheme,
                host = options.uriHost ?: destinationHost,
                port = options.uriPort ?: destinationPort,
                pathSegments = options.uriPath,
                querySegments = options.uriQuery,
            )

        /**
         * Rebuilds a URI like [fromOptions], or returns null where [fromOptions] throws: for the options of a
         * received request, whose Uri-Port of 0 (a valid option, RFC 7252 §5.10.1) or other bad value is the
         * peer's, not the application's.
         */
        public fun fromOptionsOrNull(scheme: CoapScheme, destinationHost: String, destinationPort: Int, options: OptionSet): CoapUri? {
            val host = options.uriHost ?: destinationHost
            val port = options.uriPort ?: destinationPort
            if (host.isEmpty() || port !in 1..65535) return null
            return CoapUri(scheme, host, port, options.uriPath, options.uriQuery)
        }

        private fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = mutableListOf<Byte>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    if (i + 3 > text.length) throw MalformedUri("Truncated percent-encoding in $text")
                    val hex = text.substring(i + 1, i + 3)
                    // Two hex digits only: toIntOrNull alone would take a sign, as in "%+F".
                    if (!hex.all(::isHexDigit)) throw MalformedUri("Invalid percent-encoding %$hex")
                    out += hex.toInt(16).toByte()
                    i += 3
                } else {
                    out += c.toString().encodeToByteArray().toList()
                    i++
                }
            }
            return out.toByteArray().decodeToString()
        }

        private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

        private fun percentEncode(text: String, safe: String): String = buildString {
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

/** What [CoapUri.parse] throws, and the only failure [CoapUri.parseOrNull] turns into null. */
private class MalformedUri(message: String) : IllegalArgumentException(message)
