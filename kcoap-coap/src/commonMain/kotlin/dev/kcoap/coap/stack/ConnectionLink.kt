package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.log.LogTags
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import kotlin.time.Duration

/**
 * The [PeerLink] of one connection of CoAP over a reliable transport (RFC 8323), on its [ConnectionDriver];
 * [ReliableConnections] opens, accepts, reuses and releases the connections.
 *
 * A connection has no message types, Message IDs, ACKs or Resets (§3.1, §3.2): a request or response is
 * written in order and done, every request is answered on the connection it came on (§3.3), and Custody and
 * Release count the requests still unanswered (§5.4.1, §5.5). Block sizes follow the peer's Max-Message-Size,
 * with BERT where both CSMs indicated it (§5.3.1, §6). There is no NSTART (§2), so a body lease paces nothing.
 */
internal class ConnectionLink(
    private val driver: ConnectionDriver,
    private val owner: ConnectionOwner,
    config: CoapEndpointConfig,
) : PeerLink {
    private val reliable = config.reliable
    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE).with("peer" to driver.peer)

    override val peer: Endpoint get() = driver.peer

    override val limits: MessageLimits
        get() {
            val settings = driver.settings
            return MessageLimits(
                maxMessageSize = settings.maxMessageSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                codec = driver.codec,
                // RFC 8323 §6: BERT only when both CSMs indicated it.
                allowsBert = settings.supportsBert && reliable.announcesBert,
                isReliable = true,
            )
        }

    override val capabilities: LinkCapabilities get() = LinkCapabilities.CONNECTION

    override val peerIdentity: PeerIdentity get() = driver.peerIdentity

    // ---------------------------------------------------------------- requests this side sends

    /** Nothing to check: RFC 8323 has no NSTART, so no lease paces a message here. */
    override fun checkPacing(confirmable: Boolean, pacing: Pacing) = Unit

    /** RFC 8323 §3.2: no Type and no Message ID, only the token, which is unique among the connection's exchanges. */
    override suspend fun address(request: CoapMessage, token: ByteArray, confirmable: Boolean): CoapMessage =
        request.toBuilder().apply { this.token = token }.build()

    override suspend fun sendRequest(request: CoapMessage, pacing: Pacing, owner: DeliveryOwner, queued: (Delivery) -> Unit) {
        driver.send(request)
    }

    /** A request on a connection is written and done; nothing to settle. */
    override suspend fun settle(delivery: Delivery) = Unit

    override suspend fun exchangeClosed(token: String?) {
        driver.exchangeClosed(token)
    }

    /** RFC 8323 §5.4: a Ping, true if the Pong came within [ReliableTransportConfig.pingTimeout]. */
    override suspend fun ping(custody: Boolean): Boolean = try {
        driver.ping(custody, reliable.pingTimeout)
    } catch (e: CoapException) {
        log.debug { "Ping of $peer failed: ${e.message}" }
        false
    }

    /** RFC 8323 §2 has no NSTART: the lease is granted at once and holds nothing ([lease]). */
    override suspend fun leaseBody(bodyBytes: Int, nonTimeoutRandom: Duration?): BodyLease {
        owner.ensureOpen()
        return lease(peer, nonTimeoutRandom)
    }

    // ---------------------------------------------------------------- responses to them

    /** RFC 8323 §3.1: nothing acknowledges a response on a connection. */
    override suspend fun acceptResponse(response: CoapMessage) = Unit

    /** RFC 8323 §3.1: nothing acknowledges a response on a connection, and nothing sends one again. */
    override suspend fun deferResponse(response: CoapMessage) = Unit

    /**
     * RFC 8323 §7.2 lets a client abort on a notification with an unknown token; here the response is only
     * dropped, and reported.
     */
    override suspend fun rejectResponse(response: CoapMessage, reason: EndpointEvent.MessageRejected.Reason, detail: String?) {
        log.debug { "Dropped a response from $peer ($reason)" + (detail?.let { ": $it" } ?: "") }
        owner.onEvent(EndpointEvent.MessageRejected(peer, 0, reason, detail))
    }

    // ---------------------------------------------------------------- requests this side receives

    /** RFC 8323 §3.3: on the connection the request came on, in order; written and done. */
    override suspend fun respond(request: InboundRequest, response: CoapMessage, owner: DeliveryOwner?, confirmable: Boolean): Delivery? {
        driver.respond(request.sequence, response)
        return null
    }

    /** RFC 8323 §3.1: there are no Empty ACKs; the response itself acknowledges. */
    override suspend fun acknowledge(request: InboundRequest) = Unit

    override suspend fun finishWithoutResponse(request: InboundRequest) {
        driver.answered(request.sequence)
    }

    /** RFC 8323 has no Reset; the request is only dropped, and counts as answered for Custody and Release. */
    override suspend fun reject(request: InboundRequest, reason: EndpointEvent.MessageRejected.Reason, detail: String?) {
        log.debug { "Dropped a request from $peer ($reason)" + (detail?.let { ": $it" } ?: "") }
        driver.answered(request.sequence)
    }

    /** Every request on a connection is answered (RFC 8323 §3.3), so none is left unanswered. */
    override suspend fun leaveUnanswered(request: InboundRequest) = Unit

    // ---------------------------------------------------------------- messages this side starts

    /**
     * RFC 8323 §7.2: a notification has no message type; it is written on the connection and done. A connection
     * that closed meanwhile is reported to [owner] as a failure.
     */
    override suspend fun notify(message: CoapMessage, confirmable: Boolean, owner: DeliveryOwner, slotHold: Duration?): Delivery {
        this.owner.ensureOpen()
        try {
            driver.send(message)
        } catch (e: CoapException) {
            owner.onOutcome(Delivery.Written, DeliveryOutcome.Failed(e))
        }
        return Delivery.Written
    }

    /** Nothing waits on a connection, so nothing is superseded. */
    override suspend fun supersede(delivery: Delivery, message: CoapMessage): Boolean = false

    override fun notificationPolicy(registeredAt: Duration, isAuthenticated: Boolean, confirmableInterval: Duration): NotificationPolicy =
        NotificationPolicy.Untyped

    override fun toString(): String = "ConnectionLink($peer)"

    companion object {
        /**
         * The body lease towards [peer] on a connection, whether or not the connection is open yet: RFC 8323 §2
         * has no NSTART, so it is granted at once, holds no slot and paces nothing, and keeps the body's
         * [nonTimeoutRandom] (zero when there is none, since nothing waits on it).
         */
        fun lease(peer: Endpoint, nonTimeoutRandom: Duration?): BodyLease = BodyLease.unpaced(peer, nonTimeoutRandom ?: Duration.ZERO)

        /** The body of a [lease] ended: it holds no slot to give back, so it is only marked released. */
        fun release(lease: BodyLease) {
            lease.state = BodyLease.State.RELEASED
        }
    }
}
