package dev.kcoap.transport

/**
 * Adds DTLS to a datagram transport, which is what `coaps` needs. kcoap-dtls will implement it; until
 * then no implementation exists and `coaps` is unavailable.
 *
 * The transport returned by [secure] must:
 * - deliver [DatagramEvent.Received] only from [Endpoint.Secure] endpoints, never plaintext;
 * - on [DatagramTransport.send] to an [Endpoint.Secure], use exactly that session and epoch, and throw
 *   [SessionEndedException] if it has ended;
 * - on send to a plain endpoint, use the established session with that address or start a handshake
 *   as DTLS client (RFC 7252 §9.1: the CoAP client should be the DTLS client) and send once it is up;
 * - report every session end as [DatagramEvent.SessionEnded];
 * - refuse multicast destinations, because DTLS is point to point;
 * - report a [DatagramTransport.maxDatagramSize] reduced by the record overhead.
 */
public interface DatagramSecurity {
    /** The modes this implementation is configured for. Never contains [SecurityMode.NO_SEC]. */
    public val modes: Set<SecurityMode>

    public fun secure(transport: DatagramTransport): DatagramTransport
}

/**
 * Adds TLS to a byte stream for `coaps+tcp`. Certificate mode normally uses the platform TLS inside the
 * transport adapter; this is for the modes platform TLS lacks (PSK, raw public keys), which kcoap-dtls
 * will provide in stream mode. TLS clients offer ALPN [CoapScheme.ALPN].
 */
public interface StreamSecurity {
    public val modes: Set<SecurityMode>

    /** Runs the TLS client handshake. [serverName] goes into Server Name Indication when not null. */
    public suspend fun client(connection: ByteStreamConnection, serverName: String?): ByteStreamConnection

    public suspend fun server(connection: ByteStreamConnection): ByteStreamConnection
}
