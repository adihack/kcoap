package dev.kcoap.coap.stack

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The CoAP transmission parameters of RFC 7252 §4.8 and the time values derived from them (§4.8.2).
 *
 * The defaults are the RFC's. §4.8.1 warns that decreasing [ackTimeout] or increasing [nstart] is only
 * safe with congestion-control mechanisms that measure round-trip times; kcoap allows it for tests and
 * controlled networks but does not add such mechanisms. Every endpoint that talks to another must use
 * consistent values.
 *
 * @property ackTimeout ACK_TIMEOUT: the initial retransmission timeout is drawn from
 *   [ackTimeout, ackTimeout × [ackRandomFactor]].
 * @property ackRandomFactor ACK_RANDOM_FACTOR, at least 1.0 (§4.8.1).
 * @property maxRetransmit MAX_RETRANSMIT: retransmissions of a Confirmable message before giving up.
 * @property nstart NSTART: simultaneous outstanding interactions per peer (§4.7).
 * @property defaultLeisure DEFAULT_LEISURE (§8.2), for multicast responses; kept for completeness.
 * @property probingRate PROBING_RATE in bytes per second: the average rate towards a peer that does not
 *   respond (§4.7). It bounds how long a Non-confirmable request occupies its NSTART slot.
 * @property maxLatency MAX_LATENCY: the longest a datagram is expected to be in transit.
 * @property maxPayloads MAX_PAYLOADS (RFC 9177 §7.2): the Non-confirmable Q-Block payloads sent back to back
 *   before waiting NON_TIMEOUT_RANDOM. It cannot be negotiated, so both peers must use the same value; a
 *   mismatch costs delays, not data.
 * @property nonTimeout NON_TIMEOUT (RFC 9177 §7.2): the base of NON_TIMEOUT_RANDOM, the wait between two
 *   MAX_PAYLOADS sets. Defaults to [ackTimeout], 2 s.
 * @property nonReceiveTimeout NON_RECEIVE_TIMEOUT (RFC 9177 §7.2): how long a receiver waits for a missing
 *   payload before asking for it, doubled for every repeat. RFC 9177 §7.2 requires at least
 *   NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s, so that it outlasts the sender's NON_TIMEOUT_RANDOM. Defaults to
 *   2 × NON_TIMEOUT (4 s, Table 3), or to that lower bound when it is larger, as it is when [ackTimeout] is
 *   below 2 s or [ackRandomFactor] above 1.5.
 * @property nonMaxRetransmit NON_MAX_RETRANSMIT (RFC 9177 §7.2): how often a receiver asks for the same
 *   missing payload before it gives the body up. Defaults to [maxRetransmit].
 */
public class TransmissionParameters(
    public val ackTimeout: Duration = 2.seconds,
    public val ackRandomFactor: Double = 1.5,
    public val maxRetransmit: Int = 4,
    public val nstart: Int = 1,
    public val defaultLeisure: Duration = 5.seconds,
    public val probingRate: Int = 1,
    public val maxLatency: Duration = 100.seconds,
    public val maxPayloads: Int = 10,
    public val nonTimeout: Duration = ackTimeout,
    public val nonReceiveTimeout: Duration = maxOf(nonTimeout * 2, nonTimeout * ackRandomFactor + 1.seconds),
    public val nonMaxRetransmit: Int = maxRetransmit,
) {
    init {
        require(ackTimeout.isPositive()) { "ACK_TIMEOUT must be positive: $ackTimeout" }
        require(ackRandomFactor >= 1.0) { "ACK_RANDOM_FACTOR must not be below 1.0 (RFC 7252 §4.8.1): $ackRandomFactor" }
        require(maxRetransmit in 0..MAX_SUPPORTED_RETRANSMIT) { "MAX_RETRANSMIT out of range: $maxRetransmit" }
        require(nstart >= 1) { "NSTART must be at least 1: $nstart" }
        require(!defaultLeisure.isNegative()) { "DEFAULT_LEISURE must not be negative: $defaultLeisure" }
        require(probingRate > 0) { "PROBING_RATE must be positive: $probingRate" }
        require(!maxLatency.isNegative()) { "MAX_LATENCY must not be negative: $maxLatency" }
        require(maxPayloads >= 1) { "MAX_PAYLOADS must be at least 1: $maxPayloads" }
        require(nonTimeout.isPositive()) { "NON_TIMEOUT must be positive: $nonTimeout" }
        require(nonMaxRetransmit in 0..MAX_SUPPORTED_RETRANSMIT) { "NON_MAX_RETRANSMIT out of range: $nonMaxRetransmit" }
        require(nonReceiveTimeout >= nonTimeout * ackRandomFactor + 1.seconds) {
            "NON_RECEIVE_TIMEOUT must be at least NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s (RFC 9177 §7.2): $nonReceiveTimeout"
        }
    }

    /** MAX_TRANSMIT_SPAN: first transmission to last retransmission, `ACK_TIMEOUT × (2^MAX_RETRANSMIT − 1) × ACK_RANDOM_FACTOR`. */
    public val maxTransmitSpan: Duration get() = ackTimeout * (((1 shl maxRetransmit) - 1) * ackRandomFactor)

    /** MAX_TRANSMIT_WAIT: first transmission to giving up, `ACK_TIMEOUT × (2^(MAX_RETRANSMIT+1) − 1) × ACK_RANDOM_FACTOR`. */
    public val maxTransmitWait: Duration get() = ackTimeout * (((1 shl (maxRetransmit + 1)) - 1) * ackRandomFactor)

    /** PROCESSING_DELAY: set equal to ACK_TIMEOUT, as §4.8.2 assumes. */
    public val processingDelay: Duration get() = ackTimeout

    /** MAX_RTT: `2 × MAX_LATENCY + PROCESSING_DELAY`. */
    public val maxRtt: Duration get() = maxLatency * 2 + processingDelay

    /** EXCHANGE_LIFETIME: `MAX_TRANSMIT_SPAN + 2 × MAX_LATENCY + PROCESSING_DELAY`, 247 s by default. */
    public val exchangeLifetime: Duration get() = maxTransmitSpan + maxLatency * 2 + processingDelay

    /** NON_LIFETIME: `MAX_TRANSMIT_SPAN + MAX_LATENCY`, 145 s by default. */
    public val nonLifetime: Duration get() = maxTransmitSpan + maxLatency

    /**
     * NON_PARTIAL_TIMEOUT (RFC 9177 §7.2): how long a partially received Q-Block body is kept,
     * `NON_TIMEOUT × (2^NON_MAX_RETRANSMIT − 1) × ACK_RANDOM_FACTOR + 2 × MAX_LATENCY + NON_TIMEOUT`,
     * 247 s by default.
     */
    public val nonPartialTimeout: Duration
        get() = nonTimeout * (((1 shl nonMaxRetransmit) - 1) * ackRandomFactor) + maxLatency * 2 + nonTimeout

    override fun toString(): String =
        "TransmissionParameters(ACK_TIMEOUT=$ackTimeout, ACK_RANDOM_FACTOR=$ackRandomFactor, " +
            "MAX_RETRANSMIT=$maxRetransmit, NSTART=$nstart, PROBING_RATE=$probingRate B/s, MAX_LATENCY=$maxLatency, " +
            "MAX_PAYLOADS=$maxPayloads, NON_TIMEOUT=$nonTimeout, NON_RECEIVE_TIMEOUT=$nonReceiveTimeout, " +
            "NON_MAX_RETRANSMIT=$nonMaxRetransmit)"

    public companion object {
        /** The defaults of RFC 7252 Table 2 and RFC 9177 Table 3. */
        public val Default: TransmissionParameters = TransmissionParameters()

        private const val MAX_SUPPORTED_RETRANSMIT = 20
    }
}
