package dev.kcoap.coap.stack

import kotlin.random.Random
import kotlin.time.Duration

/**
 * The Q-Block timing of RFC 9177 §7.2 resolved from [parameters] (Table 3 and the derived values of
 * Table 4), shared by the four Q-Block state machines.
 */
internal class QBlockTiming(val parameters: TransmissionParameters) {
    val maxPayloads: Int get() = parameters.maxPayloads
    val nonReceiveTimeout: Duration get() = parameters.nonReceiveTimeout
    val nonMaxRetransmit: Int get() = parameters.nonMaxRetransmit
    val nonPartialTimeout: Duration get() = parameters.nonPartialTimeout

    /** The MAX_PAYLOADS_SETs of this configuration. */
    val sets: QBlockSets = QBlockSets(parameters.maxPayloads)

    /**
     * NON_TIMEOUT_RANDOM: uniform in [NON_TIMEOUT, NON_TIMEOUT × ACK_RANDOM_FACTOR] (2 to 3 s by default).
     * A sender draws it once per body and waits that long after every set (§7.2).
     */
    fun drawNonTimeoutRandom(random: Random): Duration =
        parameters.nonTimeout * (1.0 + random.nextDouble() * (parameters.ackRandomFactor - 1.0))

    /**
     * NON_PROBING_WAIT for a body that drew [nonTimeoutRandom]:
     * `NON_TIMEOUT × (2^NON_MAX_RETRANSMIT − 1) × ACK_RANDOM_FACTOR + 2 × MAX_LATENCY + NON_TIMEOUT_RANDOM`,
     * 247 to 248 s by default (§7.2, Table 4).
     */
    fun nonProbingWait(nonTimeoutRandom: Duration): Duration =
        parameters.nonTimeout * (((1 shl parameters.nonMaxRetransmit) - 1) * parameters.ackRandomFactor) +
            parameters.maxLatency * 2 + nonTimeoutRandom

    /**
     * How long a receiver waits before asking for missing blocks after [retries] earlier requests for them:
     * `NON_RECEIVE_TIMEOUT × 2^retries`, so 4, 8, 16, 32 and 64 s by default (§7.2, Figure 6).
     */
    fun receiveWait(retries: Int): Duration = nonReceiveTimeout * (1 shl retries)

    /**
     * The whole retry ladder, from the last block to giving up after NON_MAX_RETRANSMIT requests:
     * `NON_RECEIVE_TIMEOUT × (2^(NON_MAX_RETRANSMIT + 1) − 1)`, 124 s by default (Figure 6).
     */
    val receiveLadder: Duration get() = nonReceiveTimeout * ((1 shl (parameters.nonMaxRetransmit + 1)) - 1)

    override fun toString(): String = "QBlockTiming($parameters)"
}
