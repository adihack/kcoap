package dev.kcoap.transport

/**
 * Datagrams in, datagrams out: UDP, multicast, non-IP links, and DTLS through [DatagramSecurity].
 * Used by the RFC 7252 messaging layer (CON/NON/ACK/RST, deduplication, retransmission).
 *
 * Byte arrays passed in either direction belong to the receiver afterwards; callers must not reuse them.
 */
public interface DatagramTransport {
    /**
     * Largest datagram [send] accepts. Channel security lowers it by the record overhead, and the
     * messaging layer derives the maximum message size and block size from it, never from a constant.
     */
    public val maxDatagramSize: Int

    public suspend fun send(to: Endpoint, datagram: ByteArray)

    public suspend fun receive(): DatagramEvent

    public suspend fun close()
}

/** What a [DatagramTransport] hands to the messaging layer. */
public sealed interface DatagramEvent {
    public class Received(public val from: Endpoint, public val datagram: ByteArray) : DatagramEvent

    /**
     * A DTLS session ended or was replaced by a new handshake; [endpoint] is dead.
     *
     * RFC 7252 §9.1.1 forbids retransmission across epochs, so the messaging layer must fail, not
     * resend, pending Confirmable messages and open exchanges for [endpoint], and end observations
     * bound to it. The client re-registers through a new session.
     */
    public class SessionEnded(public val endpoint: Endpoint.Secure, public val reason: SessionEndReason) : DatagramEvent
}

public enum class SessionEndReason {
    /** The peer sent `close_notify`. */
    CLOSED_BY_PEER,

    /** This side closed it, for example to free resources (RFC 7252 §9.1.1 allows this). */
    CLOSED_LOCALLY,

    /** A fatal alert was sent or received. */
    ALERT,

    /** Handshake retransmissions or idle time ran out. */
    TIMEOUT,

    /** A new handshake with the same peer replaced the session. */
    REPLACED,
}

/** Thrown by [DatagramTransport.send] when the target [Endpoint.Secure] has ended. */
public class SessionEndedException(
    public val endpoint: Endpoint.Secure,
    public val reason: SessionEndReason,
) : Exception("DTLS session ended ($reason): $endpoint")

/**
 * A connection for CoAP over a reliable transport (RFC 8323): a [ByteStreamConnection] (TCP, TLS) or a
 * [MessageConnection] (WebSocket). The CoAP endpoint runs one reliable messaging layer per connection,
 * so tokens and signaling state (CSM, Ping, Release, Abort) are scoped to it (RFC 8323 §3.3, §4.3).
 *
 * Opened by a [Connector] or accepted by a [ConnectionListener]; [close] may be called more than once.
 */
public sealed interface Connection {
    /** The peer. For a connection a [Connector] opened, the [Endpoint] it was asked to connect to. */
    public val remote: Endpoint

    /** The peer as authenticated by TLS, or [PeerIdentity.None] without TLS. */
    public val peerIdentity: PeerIdentity

    /** Closes the connection; afterwards reading reports its end. Idempotent. */
    public suspend fun close()
}

/**
 * A byte stream: TCP or TLS (RFC 8323 `coap+tcp`, `coaps+tcp`). The reliable messaging layer does the
 * Len/TKL framing on top. One messaging-layer instance per connection, so tokens are scoped to it.
 */
public interface ByteStreamConnection : Connection {
    override val remote: Endpoint

    /** The peer as authenticated by TLS, or [PeerIdentity.None] on plain TCP. */
    override val peerIdentity: PeerIdentity

    /** Reads up to [length] bytes into [into] at [offset]. Returns the count, or -1 at end of stream. */
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Writes all of [bytes]; the bytes belong to the connection afterwards. */
    public suspend fun write(bytes: ByteArray)

    override suspend fun close()
}

/**
 * A message stream: WebSocket (RFC 8323 `coap+ws`, `coaps+ws`), one CoAP message per binary frame. TLS
 * for `coaps+ws` is always the WebSocket client's own (`wss://`), also in browsers.
 */
public interface MessageConnection : Connection {
    override val remote: Endpoint

    /** The peer as authenticated by TLS, or [PeerIdentity.None] on plain WebSocket. */
    override val peerIdentity: PeerIdentity

    /** Sends [message] as one binary frame; the bytes belong to the connection afterwards. */
    public suspend fun send(message: ByteArray)

    /** The next message (a whole binary frame, reassembled if fragmented), or null once the connection has closed. */
    public suspend fun receive(): ByteArray?

    override suspend fun close()
}

/**
 * Opens connections for CoAP over reliable transports (RFC 8323 §8): `coap+tcp` and `coaps+tcp` to an
 * [Endpoint.Tcp], `coap+ws` and `coaps+ws` to an [Endpoint.WebSocket]. A CoAP endpoint calls it when a
 * request needs a connection to a peer it has none to, and closes it when the endpoint closes.
 */
public interface Connector {
    /** The schemes whose endpoints [connect] accepts. */
    public val schemes: Set<CoapScheme>

    /**
     * Opens a connection to [peer], whose [Connection.remote] is [peer]. A WebSocket connector includes
     * the opening handshake with the subprotocol `coap` at `/.well-known/coap` (RFC 8323 §4.1, §8.3);
     * a TLS connector the handshake, offering ALPN [CoapScheme.ALPN] (RFC 8323 §8.2).
     *
     * @throws IllegalArgumentException for an endpoint of a scheme not in [schemes].
     * @throws Exception of the transport's own kind when the connection cannot be opened.
     */
    public suspend fun connect(peer: Endpoint): Connection

    /** Releases what the connector holds, such as a socket selector. Open connections stay open. */
    public suspend fun close() {}
}

/**
 * Accepts connections for CoAP over reliable transports: a TCP server socket, or a WebSocket endpoint at
 * `/.well-known/coap` that requires the subprotocol `coap` (RFC 8323 §4.1).
 */
public interface ConnectionListener {
    /** The scheme of the accepted connections. */
    public val scheme: CoapScheme

    /** The next connection, or null once the listener is closed. */
    public suspend fun accept(): Connection?

    /** Stops accepting. Connections already accepted stay open. Idempotent. */
    public suspend fun close()
}
