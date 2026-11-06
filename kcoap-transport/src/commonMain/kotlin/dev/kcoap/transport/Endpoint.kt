package dev.kcoap.transport

/**
 * The other side of a message.
 *
 * On datagram transports the CoAP messaging layer matches responses, ACKs and RSTs to requests and
 * deduplicates by endpoint equality, so each variant defines equality the way the RFCs scope messages, and
 * a variant never equals another: a UDP peer, a TCP peer and a WebSocket on the same host and port are three
 * peers. Protocol code must compare endpoints, never host and port, so that DTLS can be added without
 * touching it (see [Secure]).
 *
 * The variants are final classes with deliberate equality, not data classes: the fields a variant compares
 * are part of the matching rule, and a field added later (an IPv6 zone, say) must not change it by accident.
 */
public sealed interface Endpoint {

    /**
     * A UDP peer or group: an IP address and a port.
     *
     * [host] is an IP literal; names are resolved before a transport sees them. It is kept in [IpLiteral]
     * form whatever text was given, so one address has one [host] and one endpoint: `FE80::0:1`, `fe80::1` and
     * `[fe80::1]` name the same peer, and so do `::ffff:192.0.2.1` and `192.0.2.1`, as a dual-stack socket
     * reports them.
     *
     * **Equality** is the matching rule of RFC 7252 §5.3.2 (a response comes from the endpoint the request went
     * to) and the scope of Message IDs (§4.4, §4.5): the same address and the same port. The address compares as
     * RFC 7252 §6.6 compares URI hosts, case-insensitively and with IP literals in their RFC 5952 form. Text
     * that is not an IP literal, which only a test or a link without IP should use, compares case-insensitively
     * (RFC 3986 §3.2.2).
     */
    public class Udp(host: String, public val port: Int) : Endpoint {
        /** The address, in [IpLiteral] form (or in lower case, for text that is not an IP literal). */
        public val host: String = normalizedHost(host)

        init {
            require(port in 0..65535) { "Port out of range: $port" }
        }

        override fun equals(other: Any?): Boolean =
            this === other || other is Udp && port == other.port && host == other.host

        override fun hashCode(): Int = 31 * host.hashCode() + port

        override fun toString(): String = "Udp(host=$host, port=$port)"
    }

    /**
     * The remote side of a TCP or TLS connection (RFC 8323). Messages are scoped to the connection, not
     * to the address, so this only describes the peer; a CoAP endpoint keeps at most one connection per
     * value.
     *
     * [host] is an IP literal or, for a connection this side opens, a host name the connector resolves (and
     * TLS checks the certificate against). [usesTls] tells `coaps+tcp` from `coap+tcp`.
     *
     * **Equality** is the authority of the URI that names the peer, compared as RFC 7252 §6.6 compares URIs
     * (which RFC 8323 §8.1 and §8.2 adopt): the host case-insensitively, IP literals in their RFC 5952 form
     * ([IpLiteral]), the port, and the scheme. `coap+tcp` and `coaps+tcp` are distinct namespaces even on the
     * same host and port (RFC 8323 §8.2), so endpoints that differ only in [usesTls] are different peers, with a
     * connection each. A host name and the address it resolves to are different peers too: URIs compare
     * without resolving names.
     */
    public class Tcp(host: String, public val port: Int, public val usesTls: Boolean = false) : Endpoint {
        /** The host, in [IpLiteral] form if it is an IP literal, else as a name in lower case. */
        public val host: String = normalizedHost(host)

        init {
            require(port in 0..65535) { "Port out of range: $port" }
        }

        /** `coap+tcp` or `coaps+tcp`. */
        public val scheme: CoapScheme get() = if (usesTls) CoapScheme.COAPS_TCP else CoapScheme.COAP_TCP

        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Tcp && port == other.port && usesTls == other.usesTls && host == other.host

        override fun hashCode(): Int = (31 * host.hashCode() + port) * 31 + usesTls.hashCode()

        override fun toString(): String = "Tcp(host=$host, port=$port, usesTls=$usesTls)"
    }

    /**
     * The remote side of a WebSocket (RFC 8323 §4). Like [Tcp], messages are scoped to the connection.
     *
     * For a connection this side opens, [uri] is the `ws` or `wss` URI of the WebSocket endpoint (see
     * [of]); for an accepted one, the scheme and the address of the WebSocket client. [uri] is kept as given.
     *
     * **Equality** compares [uri] as RFC 7252 §6.6 compares URIs (RFC 3986 §6.2.2 and §6.2.3), with the default
     * ports of RFC 6455 §3, which RFC 8323 §8.3 and §8.4 use: the scheme and the host case-insensitively, IP
     * literals in their RFC 5952 form, a missing port as 80 for `ws` and 443 for `wss`, an empty path as `/`,
     * percent-encoded unreserved characters as the characters and other percent-encodings with upper-case hex
     * digits; the path and the query are otherwise compared exactly. `ws` and `wss` are distinct even on the same
     * host and port (RFC 8323 §8.4), so endpoints that differ only in [usesTls] are different peers. Text that is
     * not a `ws` or `wss` URI with a host compares exactly.
     */
    public class WebSocket(public val uri: String) : Endpoint {
        /** [uri] in the normal form that equality compares; [uri] itself when it is no `ws` or `wss` URI. */
        private val normalized: String = normalizedWebSocketUri(uri)

        /** Whether the WebSocket runs over TLS (`wss`, RFC 8323 §8.4). */
        public val usesTls: Boolean get() = uri.startsWith("wss:", ignoreCase = true)

        /** `coap+ws` or `coaps+ws`. */
        public val scheme: CoapScheme get() = if (usesTls) CoapScheme.COAPS_WS else CoapScheme.COAP_WS

        override fun equals(other: Any?): Boolean =
            this === other || other is WebSocket && normalized == other.normalized

        override fun hashCode(): Int = normalized.hashCode()

        override fun toString(): String = "WebSocket(uri=$uri)"

        public companion object {
            /**
             * The WebSocket endpoint of a `coap+ws` or `coaps+ws` URI's authority: `ws://host:port` or
             * `wss://host:port` with the path `/.well-known/coap` (RFC 8323 §8.3, §8.4). IPv6 literals are
             * bracketed.
             */
            public fun of(host: String, port: Int, usesTls: Boolean = false): WebSocket {
                require(port in 0..65535) { "Port out of range: $port" }
                val authority = if (':' in host) "[$host]" else host
                return WebSocket("${if (usesTls) "wss" else "ws"}://$authority:$port${CoapScheme.WEBSOCKET_PATH}")
            }
        }
    }

    /**
     * A non-IP link such as BLE, or LoRa with SCHC (RFC 8824). [address] is link specific.
     *
     * **Equality** is the same [link] and the same [address], compared exactly: no RFC defines a normal form for
     * them, so a link adapter hands out one textual form per peer, as [IpLiteral] does for IP.
     */
    public class Opaque(public val link: String, public val address: String) : Endpoint {
        override fun equals(other: Any?): Boolean =
            this === other || other is Opaque && link == other.link && address == other.address

        override fun hashCode(): Int = 31 * link.hashCode() + address.hashCode()

        override fun toString(): String = "Opaque(link=$link, address=$address)"
    }

    /**
     * A peer reached through a DTLS session.
     *
     * RFC 7252 §9.1.1 and §9.1.2: a response, ACK or RST matches a request only if the DTLS session
     * and the epoch are the same. Equality is therefore (session, epoch) and nothing else:
     * - a plaintext message never matches a DTLS one, because a plain endpoint is never equal to this;
     * - [via] is excluded, because with a Connection ID (RFC 9146) the peer's address can change while
     *   the session stays the same. [via] is the last address seen and may be stale; the DTLS layer
     *   sends to the session's current address.
     * - [identity] is excluded, because it is fixed for a session.
     *
     * When a session ends or a new handshake replaces it, the DTLS layer emits
     * [DatagramEvent.SessionEnded] and this endpoint never delivers or accepts messages again.
     */
    public class Secure(
        public val session: SessionId,
        public val epoch: Int,
        public val via: Endpoint,
        public val identity: PeerIdentity,
    ) : Endpoint {
        init {
            require(epoch in 0..0xFFFF) { "DTLS epoch is a 16-bit value: $epoch" }
            require(via !is Secure) { "DTLS inside DTLS is not supported" }
        }

        override fun equals(other: Any?): Boolean =
            other is Secure && session == other.session && epoch == other.epoch

        override fun hashCode(): Int = 31 * session.hashCode() + epoch

        override fun toString(): String = "Secure(${session.value}, epoch=$epoch, via=$via, ${identity.mode})"
    }
}

/**
 * A handle the local DTLS layer assigns to a session, unique within one transport for its lifetime.
 * Not the DTLS `session_id`, which may be empty and is shared by resumed connections.
 */
public data class SessionId(public val value: Long)

/**
 * The host of a URI or an endpoint in the form RFC 7252 §6.6 compares: an IP literal (bracketed or not) in its
 * [IpLiteral] form, anything else in lower case (RFC 3986 §3.2.2). An IPv6 zone (RFC 6874) is kept as given
 * after the canonical address, since only the host knows whether its interface names are case sensitive.
 */
private fun normalizedHost(host: String): String {
    val bare = if (host.length >= 2 && host.first() == '[' && host.last() == ']') host.substring(1, host.length - 1) else host
    val zone = bare.indexOf('%')
    val address = if (zone < 0) bare else bare.substring(0, zone)
    val literal = IpLiteral.canonical(address) ?: return host.lowercase()
    return if (zone < 0) literal else literal + bare.substring(zone)
}

/**
 * The normal form of a `ws` or `wss` URI (RFC 6455 §3) for comparison, as RFC 7252 §6.6 normalizes URIs; [uri]
 * itself when it is not one: another scheme, no `//` authority, userinfo (which a `ws` URI does not have), an
 * unclosed IP literal or a port that is no number in 0..65535.
 */
private fun normalizedWebSocketUri(uri: String): String {
    val schemeEnd = uri.indexOf("://")
    if (schemeEnd < 0) return uri
    val scheme = uri.substring(0, schemeEnd).lowercase()
    val defaultPort = when (scheme) {
        "ws" -> CoapScheme.COAP_WS.defaultPort
        "wss" -> CoapScheme.COAPS_WS.defaultPort
        else -> return uri
    }
    val afterScheme = schemeEnd + 3
    val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), afterScheme).let { if (it < 0) uri.length else it }
    val authority = uri.substring(afterScheme, authorityEnd)
    if ('@' in authority) return uri
    val portStart = if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close < 0) return uri
        if (close + 1 < authority.length && authority[close + 1] != ':') return uri
        close + 1
    } else {
        authority.lastIndexOf(':').let { if (it < 0) authority.length else it }
    }
    val host = authority.substring(0, portStart)
    if (host.isEmpty()) return uri
    val portText = authority.substring(portStart).removePrefix(":")
    // RFC 3986 §6.2.3: an empty port is the default port.
    val port = if (portText.isEmpty()) defaultPort else portText.toPortOrNull() ?: return uri
    val resource = uri.substring(authorityEnd).let { if (it.startsWith('/')) it else "/$it" }
    val normalHost = normalizedHost(host).let { if (':' in it) "[$it]" else it }
    return "$scheme://$normalHost:$port${normalizedPercentEncoding(resource)}"
}

private fun String.toPortOrNull(): Int? =
    if (length in 1..5 && all { it in '0'..'9' }) toInt().takeIf { it <= 65535 } else null

/**
 * RFC 3986 §6.2.2.1 and §6.2.2.2, as RFC 7252 §6.6 asks: a percent-encoded unreserved character is the
 * character, and the hex digits of every other percent-encoding are upper case.
 */
private fun normalizedPercentEncoding(text: String): String {
    if ('%' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val hex = if (text[index] == '%' && index + 2 < text.length) text.substring(index + 1, index + 3) else ""
        val value = hex.takeIf { it.isNotEmpty() && it.all { digit -> digit.isHexDigit() } }?.toInt(16)
        when {
            value == null -> out.append(text[index])
            value.toChar().isUnreserved() -> out.append(value.toChar())
            else -> out.append('%').append(hex.uppercase())
        }
        index += if (value == null) 1 else 3
    }
    return out.toString()
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** RFC 3986 §2.3. */
private fun Char.isUnreserved(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '.' || this == '_' || this == '~'
