package dev.kcoap.coap.stack

import dev.kcoap.coap.SignalingOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a [CoapEndpoint] runs CoAP over reliable transports (RFC 8323): what its Capabilities and Settings
 * Message announces, and how it manages connections, which RFC 8323 §3 leaves to the implementation.
 *
 * @property maxMessageSize the largest message this endpoint receives, announced as Max-Message-Size in
 *   its CSM (§5.3.1) and enforced: a larger message aborts the connection (§5.6). Above 1152, together
 *   with [blockWiseTransfer], it also announces BERT (§5.3.2). The default follows the §5.3.2
 *   implementation note: 8 KiB BERT blocks plus 512 bytes for header and options.
 * @property blockWiseTransfer whether the CSM carries Block-Wise-Transfer (§5.3.2). The stack implements
 *   RFC 7959 and BERT, so it is on by default.
 * @property csmTimeout how long to wait for the peer's CSM, which MUST be its first message; without one
 *   the connection is aborted (§3.3). A client sends its first request once the CSM is there, so block
 *   sizes follow the peer's Max-Message-Size from the start.
 * @property connectTimeout how long opening a connection may take, transport and TLS or WebSocket
 *   handshakes included.
 * @property idleTimeout how long a connection this endpoint opened stays open without exchanges, pending
 *   requests or traffic before the endpoint releases it (§5.5), the dynamic connection management of §3;
 *   null keeps it open. Accepted connections are left to the peer that opened them.
 * @property releaseTimeout after sending a Release, how long to wait for the peer to close before
 *   closing anyway; after receiving one, how long to wait for pending requests to be answered and own
 *   requests to be answered before closing (§5.5).
 * @property pingTimeout how long a Ping waits for its Pong, which §5.4 leaves to the application; it
 *   bounds [CoapEndpoint.ping] and keep-alive pings.
 * @property keepAliveInterval send a Ping after this long without receiving anything, and abort the
 *   connection when nothing arrives within [pingTimeout] after it (§3.4, §4.4). Null disables it.
 * @property maxPendingConnections the most accepted connections whose peer has not sent its CSM yet
 *   (§3.3), each held for at most [csmTimeout]. A connection beyond it is closed at once and reported as
 *   [EndpointEvent.LimitReached], so peers that connect and never speak cannot exhaust memory, as RFC 7252
 *   §11.3 asks of any state an unauthenticated peer can create. Established connections do not count.
 */
public class ReliableTransportConfig(
    public val maxMessageSize: Long = DEFAULT_MAX_MESSAGE_SIZE,
    public val blockWiseTransfer: Boolean = true,
    public val csmTimeout: Duration = 10.seconds,
    public val connectTimeout: Duration = 30.seconds,
    public val idleTimeout: Duration? = 30.seconds,
    public val releaseTimeout: Duration = 5.seconds,
    public val pingTimeout: Duration = 30.seconds,
    public val keepAliveInterval: Duration? = null,
    public val maxPendingConnections: Int = DEFAULT_MAX_PENDING_CONNECTIONS,
) {
    init {
        require(maxMessageSize in MIN_MAX_MESSAGE_SIZE..MAX_MAX_MESSAGE_SIZE) {
            "Max-Message-Size must be in $MIN_MAX_MESSAGE_SIZE..$MAX_MAX_MESSAGE_SIZE: $maxMessageSize"
        }
        require(csmTimeout.isPositive()) { "CSM timeout must be positive: $csmTimeout" }
        require(connectTimeout.isPositive()) { "Connect timeout must be positive: $connectTimeout" }
        require(idleTimeout == null || idleTimeout.isPositive()) { "Idle timeout must be positive: $idleTimeout" }
        require(!releaseTimeout.isNegative()) { "Release timeout must not be negative: $releaseTimeout" }
        require(pingTimeout.isPositive()) { "Ping timeout must be positive: $pingTimeout" }
        require(keepAliveInterval == null || keepAliveInterval.isPositive()) { "Keep-alive interval must be positive" }
        require(maxPendingConnections > 0) { "At least one pending connection must be allowed: $maxPendingConnections" }
    }

    /** Whether the CSM announces BERT: Block-Wise-Transfer with a Max-Message-Size above 1152 (§5.3.2). */
    public val announcesBert: Boolean get() = blockWiseTransfer && maxMessageSize > SignalingOptions.BASE_MAX_MESSAGE_SIZE

    override fun toString(): String =
        "ReliableTransportConfig(maxMessageSize=$maxMessageSize, blockWiseTransfer=$blockWiseTransfer, " +
            "idleTimeout=$idleTimeout, keepAliveInterval=$keepAliveInterval)"

    public companion object {
        /** 8 KiB BERT blocks with 512 bytes for header and options (RFC 8323 §5.3.2 implementation note). */
        public const val DEFAULT_MAX_MESSAGE_SIZE: Long = 8192 + 512

        /** Enough for a header, a token, a few options and a 16-byte block. */
        public const val MIN_MAX_MESSAGE_SIZE: Long = 64

        /** Max-Message-Size is a uint of at most 4 bytes (§5.3.1); kcoap keeps messages within an array. */
        public const val MAX_MAX_MESSAGE_SIZE: Long = Int.MAX_VALUE.toLong()

        /** See [maxPendingConnections]. */
        public const val DEFAULT_MAX_PENDING_CONNECTIONS: Int = 128
    }
}
