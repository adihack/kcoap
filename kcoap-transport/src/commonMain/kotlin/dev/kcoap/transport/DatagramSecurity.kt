package dev.kcoap.transport

import dev.kcoap.common.ExperimentalKcoapApi

/**
 * Adds DTLS to a datagram transport, which is what `coaps` needs (RFC 7252 §9.1). kcoap-dtls will
 * implement it; until then no implementation exists, `coaps` is unavailable, and the interface is
 * [ExperimentalKcoapApi] because its shape may change with that first implementation.
 *
 * TLS for `coaps+tcp` and `coaps+ws` is not a decorator like this one: each runtime's connector and
 * listener does it with the TLS stack of its platform (docs/adr/0003-tls-in-connectors.md).
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
@ExperimentalKcoapApi
public interface DatagramSecurity {
    /** The modes this implementation is configured for. Never contains [SecurityMode.NO_SEC]. */
    public val modes: Set<SecurityMode>

    /** Wraps [transport] so that everything it sends and receives goes through DTLS, as described above. */
    public fun secure(transport: DatagramTransport): DatagramTransport
}
