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
 * A byte stream: TCP or TLS (RFC 8323 `coap+tcp`, `coaps+tcp`). The reliable messaging layer does the
 * Len/TKL framing on top. One messaging-layer instance per connection, so tokens are scoped to it.
 */
public interface ByteStreamConnection {
    public val remote: Endpoint

    /** The peer as authenticated by TLS, or [PeerIdentity.None] on plain TCP. */
    public val peerIdentity: PeerIdentity

    /** Reads up to [length] bytes into [into] at [offset]. Returns the count, or -1 at end of stream. */
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int

    public suspend fun write(bytes: ByteArray)

    public suspend fun close()
}

/**
 * A message stream: WebSocket (RFC 8323 `coap+ws`, `coaps+ws`), one CoAP message per frame. TLS for
 * `coaps+ws` is always the WebSocket client's own (`wss://`), also in browsers.
 */
public interface MessageConnection {
    public val remote: Endpoint

    /** The peer as authenticated by TLS, or [PeerIdentity.None] on plain WebSocket. */
    public val peerIdentity: PeerIdentity

    public suspend fun send(message: ByteArray)

    /** The next message, or null once the connection has closed. */
    public suspend fun receive(): ByteArray?

    public suspend fun close()
}
