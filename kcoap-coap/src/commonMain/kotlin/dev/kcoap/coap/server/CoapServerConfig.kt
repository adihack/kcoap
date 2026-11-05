package dev.kcoap.coap.server

import dev.kcoap.coap.security.ServerMessageSecurity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How a [CoapServer] processes requests.
 *
 * @property security message security (OSCORE) for incoming requests, called between the messaging
 *   layer and block-wise/Observe processing (RFC 8613 Figure 1); null serves unprotected requests only.
 * @property maxBlockSize the largest block the server sends or asks for (RFC 7959), 16..1024. The
 *   transport's datagram size may force smaller blocks.
 * @property maxBodySize the largest request body the server assembles from Block1 transfers; larger
 *   ones get 4.13 Request Entity Too Large with Size1 (RFC 7959 §2.9.3).
 * @property maxConcurrentTransfers how many partial Block1 uploads and cached Block2 representations
 *   the server keeps; the least recently used is dropped first (RFC 9175 §3.3).
 * @property confirmableNotificationInterval the longest a client goes without a Confirmable
 *   notification (RFC 7641 §4.5: at least every 24 hours).
 * @property nonConfirmableNotificationInterval how long a Non-confirmable notification counts as
 *   outstanding, so a client gets at most one per interval (RFC 7641 §4.5.1: one every 3 seconds
 *   without an RTT estimate). Changes in between are coalesced into the latest state.
 * @property securityReserve bytes kept free in every message for [security]'s overhead when choosing
 *   block sizes.
 * @property wellKnownCore whether to serve `/.well-known/core` (RFC 6690 §4: MUST on the default port).
 * @property maxObservers the most observers of all resources together (RFC 7641 §4.1). Every observer turns
 *   each change into a notification plus its retransmissions towards an address that, over UDP, may be
 *   spoofed: the amplification RFC 7641 §7 and RFC 7252 §11.3 warn about, so the list is bounded. A
 *   registration beyond this bound, beyond [maxObserversPerPeer] or beyond [maxObserversPerResource], is
 *   served as a plain GET or FETCH without Observe, as RFC 7641 §4.1 allows a server unwilling to add an
 *   observer, and reported as [dev.kcoap.coap.stack.EndpointEvent.LimitReached]. Re-registrations of an
 *   observer (same client endpoint and token) do not count again.
 * @property maxObserversPerPeer the most observers one client endpoint (address and port, or DTLS session)
 *   may have across all resources. One source, over UDP possibly a spoofed one, registering with ever new
 *   tokens would otherwise take all of [maxObservers], and every other client would then be served
 *   without Observe until those observers end.
 * @property maxObserversPerResource the most observers of one resource, which bounds the notifications one
 *   change sends at once and keeps one resource from taking the whole of [maxObservers].
 * @property amplification which responses wait until the client's address is verified (RFC 9175 §2.4
 *   item 3, §2.6): a client that neither OSCORE nor DTLS authenticated, on a datagram transport, gets a
 *   4.01 Unauthorized with an Echo option instead, piggybacked or Non-confirmable, never as a separate
 *   Confirmable response, whose retransmissions would amplify again. Each such answer is reported as
 *   [dev.kcoap.coap.stack.EndpointEvent.LimitReached] with `AMPLIFICATION_FACTOR`. Under
 *   [AmplificationPolicy.ALL_RESPONSES] every separate response to an address not yet verified is
 *   Non-confirmable for the same reason. The response to a POST or PATCH, which must not take effect
 *   twice, is kept for the repeat of that request with the Echo value, which gets it without running the
 *   handler again; [maxConcurrentTransfers] bounds how many are kept, and one evicted for room is reported
 *   with `HELD_BACK_RESPONSES` (its repeat then runs the handler again).
 * @property amplificationFactor the largest response, as a multiple of the size of its request, that goes to
 *   an address not yet verified (RFC 9175 §2.4 item 3 takes 3 from QUIC, RFC 9000 §8, as acceptable for
 *   unconstrained applications). Sizes are those of the CoAP messages, header, token and options included;
 *   the 4-byte Empty ACK that precedes a separate response is not counted.
 * @property maxVerifiedAddresses the most client addresses the server remembers as verified, and,
 *   separately, the most Echo challenges it keeps outstanding. Verified addresses come only from clients
 *   that received an Echo value, so they fill slowly; challenges can come from spoofed requests, so the
 *   oldest gives way to a new one. Either eviction is reported as
 *   [dev.kcoap.coap.stack.EndpointEvent.LimitReached] (`VERIFIED_ADDRESSES`, `ADDRESS_CHALLENGES`); the
 *   address is challenged again when it next needs to be verified.
 * @property verifiedAddressLifetime how long an address stays verified after it returned an Echo value,
 *   not extended by use: a NAT may hand the address to another host, and a spoofer must not keep it
 *   verified by sending in its name. RFC 9175 sets no lifetime; it needs the check once per endpoint.
 */
public class CoapServerConfig(
    public val security: ServerMessageSecurity? = null,
    public val maxBlockSize: Int = 1024,
    public val maxBodySize: Int = 1 shl 20,
    public val maxConcurrentTransfers: Int = 64,
    public val confirmableNotificationInterval: Duration = 24.hours,
    public val nonConfirmableNotificationInterval: Duration = 3.seconds,
    public val securityReserve: Int = 64,
    public val wellKnownCore: Boolean = true,
    public val maxObservers: Int = DEFAULT_MAX_OBSERVERS,
    public val maxObserversPerResource: Int = DEFAULT_MAX_OBSERVERS_PER_RESOURCE,
    public val maxObserversPerPeer: Int = DEFAULT_MAX_OBSERVERS_PER_PEER,
    public val amplification: AmplificationPolicy = AmplificationPolicy.Q_BLOCK_BURSTS,
    public val amplificationFactor: Int = DEFAULT_AMPLIFICATION_FACTOR,
    public val maxVerifiedAddresses: Int = DEFAULT_MAX_VERIFIED_ADDRESSES,
    public val verifiedAddressLifetime: Duration = DEFAULT_VERIFIED_ADDRESS_LIFETIME,
) {
    init {
        require(maxBlockSize in 16..1024 && maxBlockSize and (maxBlockSize - 1) == 0) {
            "Block size must be a power of two in 16..1024: $maxBlockSize"
        }
        require(maxBodySize > 0) { "Maximum body size must be positive: $maxBodySize" }
        require(maxConcurrentTransfers > 0) { "At least one block-wise transfer must be allowed" }
        require(confirmableNotificationInterval.isPositive()) { "Confirmable notification interval must be positive" }
        require(!nonConfirmableNotificationInterval.isNegative()) { "Non-confirmable notification interval must not be negative" }
        require(securityReserve >= 0) { "Security reserve must not be negative: $securityReserve" }
        require(maxObservers > 0) { "At least one observer must be allowed: $maxObservers" }
        require(maxObserversPerResource > 0) { "At least one observer per resource must be allowed: $maxObserversPerResource" }
        require(maxObserversPerPeer > 0) { "At least one observer per peer must be allowed: $maxObserversPerPeer" }
        require(amplificationFactor >= 1) { "The amplification factor must be at least 1: $amplificationFactor" }
        require(maxVerifiedAddresses > 0) { "At least one verified address must be kept: $maxVerifiedAddresses" }
        require(verifiedAddressLifetime.isPositive()) { "Verified address lifetime must be positive: $verifiedAddressLifetime" }
    }

    public companion object {
        /** See [maxObservers]. */
        public const val DEFAULT_MAX_OBSERVERS: Int = 10_000

        /** See [maxObserversPerResource]. */
        public const val DEFAULT_MAX_OBSERVERS_PER_RESOURCE: Int = 1_000

        /** See [maxObserversPerPeer]. */
        public const val DEFAULT_MAX_OBSERVERS_PER_PEER: Int = 32

        /** See [amplificationFactor]: 3, as RFC 9175 §2.4 item 3 suggests. */
        public const val DEFAULT_AMPLIFICATION_FACTOR: Int = 3

        /** See [maxVerifiedAddresses]. */
        public const val DEFAULT_MAX_VERIFIED_ADDRESSES: Int = 10_000

        /** See [verifiedAddressLifetime]: one hour. */
        public val DEFAULT_VERIFIED_ADDRESS_LIFETIME: Duration = 1.hours
    }

    override fun toString(): String =
        "CoapServerConfig(security=${security != null}, maxBlockSize=$maxBlockSize, maxBodySize=$maxBodySize, " +
            "amplification=$amplification)"
}
