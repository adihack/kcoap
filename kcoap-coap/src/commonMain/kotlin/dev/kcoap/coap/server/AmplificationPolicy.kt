package dev.kcoap.coap.server

/**
 * Which responses a [CoapServer] holds back from a client whose address it has not verified, answering
 * with a short 4.01 Unauthorized carrying an Echo option instead (RFC 9175 §2.4 item 3). RFC 9175 §2.6
 * updates RFC 7252 §11.3: a server SHOULD mitigate amplification this way towards unauthenticated
 * clients, since over UDP the source address of a request proves nothing and a large answer to a spoofed
 * one lands on the victim the address names.
 *
 * The check applies only to clients that neither message security (OSCORE) nor channel security (DTLS)
 * authenticated, and only on datagram transports: a connection's handshake has already shown that the
 * client receives at its address. A client proves its address by repeating its request with the Echo
 * value, which is bound to that address; it then stays verified for
 * [CoapServerConfig.verifiedAddressLifetime]. A response up to [CoapServerConfig.amplificationFactor] times
 * the size of its request goes out at once; only an Observe registration under [ALL_RESPONSES] is
 * challenged whatever its size.
 */
public enum class AmplificationPolicy {
    /**
     * Only Q-Block2 bursts (RFC 9177 §4.4): the sets of Non-confirmable blocks a server sends in answer to
     * one request without waiting for the client. Every other response is sent as before. The default,
     * since a burst is the largest answer a single request gets.
     */
    Q_BLOCK_BURSTS,

    /**
     * Every response a resource gives, block-wise blocks included, larger than the amplification factor
     * allows. An Observe registration is challenged whatever the size of its first response, since it
     * makes the server send notifications to the address for as long as it lasts (RFC 7641 §7).
     */
    ALL_RESPONSES,

    /** No address verification: every response is sent to whatever address the request came from. */
    OFF,
}
