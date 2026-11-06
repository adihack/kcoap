package dev.kcoap.testkit

import kotlin.time.Duration

/**
 * How an [InMemoryNetwork] treats each datagram: it may lose it, deliver it twice, or deliver it late.
 * Different delays for consecutive datagrams reorder them, which is how reordering is simulated.
 *
 * Decisions are drawn from the network's seeded random source, so a test run is reproducible.
 *
 * @property lossRate probability in 0..1 that a datagram is dropped.
 * @property duplicationRate probability in 0..1 that a delivered datagram arrives a second time.
 * @property latency fixed delay before delivery.
 * @property jitter extra delay drawn uniformly from 0..jitter for each copy of a datagram.
 */
public class LinkConditions(
    public val lossRate: Double = 0.0,
    public val duplicationRate: Double = 0.0,
    public val latency: Duration = Duration.ZERO,
    public val jitter: Duration = Duration.ZERO,
) {
    init {
        require(lossRate in 0.0..1.0) { "Loss rate must be in 0..1: $lossRate" }
        require(duplicationRate in 0.0..1.0) { "Duplication rate must be in 0..1: $duplicationRate" }
        require(!latency.isNegative()) { "Latency must not be negative: $latency" }
        require(!jitter.isNegative()) { "Jitter must not be negative: $jitter" }
    }

    /** True when datagrams are delivered immediately, without a coroutine per datagram. */
    public val isImmediate: Boolean get() = latency == Duration.ZERO && jitter == Duration.ZERO

    override fun toString(): String =
        "LinkConditions(loss=$lossRate, duplication=$duplicationRate, latency=$latency, jitter=$jitter)"

    public companion object {
        /** Every datagram arrives once, immediately, in order. */
        public val Perfect: LinkConditions = LinkConditions()
    }
}
