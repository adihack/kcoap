package com.example.kcoap.transport

public enum class TransportKind {
    /** [DatagramTransport]. */
    DATAGRAM,

    /** [ByteStreamConnection]. */
    BYTE_STREAM,

    /** [MessageConnection]. */
    MESSAGE_STREAM,
}

public enum class ChannelSecurityKind { NONE, DTLS, TLS }

/** The CoAP URI schemes and what each needs from the transport layer. */
public enum class CoapScheme(
    public val scheme: String,
    public val defaultPort: Int,
    public val kind: TransportKind,
    public val security: ChannelSecurityKind,
    public val reference: String,
) {
    COAP("coap", 5683, TransportKind.DATAGRAM, ChannelSecurityKind.NONE, "RFC 7252 §6.1"),
    COAPS("coaps", 5684, TransportKind.DATAGRAM, ChannelSecurityKind.DTLS, "RFC 7252 §6.2"),
    COAP_TCP("coap+tcp", 5683, TransportKind.BYTE_STREAM, ChannelSecurityKind.NONE, "RFC 8323 §8.1"),
    COAPS_TCP("coaps+tcp", 5684, TransportKind.BYTE_STREAM, ChannelSecurityKind.TLS, "RFC 8323 §8.2"),
    COAP_WS("coap+ws", 80, TransportKind.MESSAGE_STREAM, ChannelSecurityKind.NONE, "RFC 8323 §8.3"),
    COAPS_WS("coaps+ws", 443, TransportKind.MESSAGE_STREAM, ChannelSecurityKind.TLS, "RFC 8323 §8.4"),
    ;

    public companion object {
        /** TLS ALPN protocol identifier for CoAP over TLS (RFC 8323 §8.2 and §11.7). */
        public const val ALPN: String = "coap"

        /** URI schemes are case insensitive (RFC 3986 §3.1). */
        public fun fromScheme(scheme: String): CoapScheme? =
            entries.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
    }
}
