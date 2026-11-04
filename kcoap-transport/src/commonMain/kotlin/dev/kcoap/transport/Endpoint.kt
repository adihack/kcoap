package dev.kcoap.transport

/**
 * The other side of a message.
 *
 * On datagram transports the CoAP messaging layer matches responses, ACKs and RSTs to requests and
 * deduplicates by endpoint equality, so each variant defines equality the way the RFCs scope messages.
 * Protocol code must compare endpoints, never host and port, so that DTLS can be added without
 * touching it (see [Secure]).
 */
public sealed interface Endpoint {

    /** A UDP peer or group. [host] is an IP literal; names are resolved before a transport sees them. */
    public data class Udp(public val host: String, public val port: Int) : Endpoint {
        init {
            require(port in 0..65535) { "Port out of range: $port" }
        }
    }

    /**
     * The remote side of a TCP or TLS connection (RFC 8323). Messages are scoped to the connection, not
     * to the address, so this only describes the peer.
     */
    public data class Tcp(public val host: String, public val port: Int) : Endpoint {
        init {
            require(port in 0..65535) { "Port out of range: $port" }
        }
    }

    /** The remote side of a WebSocket (RFC 8323 §4). Like [Tcp], messages are scoped to the connection. */
    public data class WebSocket(public val uri: String) : Endpoint

    /** A non-IP link such as BLE, or LoRa with SCHC (RFC 8824). [address] is link specific. */
    public data class Opaque(public val link: String, public val address: String) : Endpoint

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
