package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A simulated datagram network for tests: every [InMemoryTransport] attached to it gets a UDP-style
 * address and exchanges datagrams with the others in memory.
 *
 * By default delivery is perfect and immediate. [conditions] adds loss, duplication, latency and
 * jitter (and so reordering); [filter] drops chosen datagrams deterministically, for tests such as "the
 * first ACK is lost". [tap] sees every datagram and what happened to it.
 *
 * Latency needs [scope] to run the delayed deliveries; under `runTest` pass `backgroundScope`, and the
 * delays run on virtual time. Give the CoAP endpoints on the network `testScheduler.timeSource` as their
 * `CoapEndpointConfig.timeSource` too: their timers wait on the dispatcher's clock and must read the same
 * one. Randomness comes from [random], so a seed reproduces a run.
 *
 * ```
 * val network = InMemoryNetwork(backgroundScope, Random(42))
 * network.conditions = LinkConditions(lossRate = 0.2, duplicationRate = 0.1)
 * val server = network.attach()
 * val client = network.attach()
 * ```
 *
 * Datagrams to an address nobody is attached to are dropped, as UDP does (no ICMP is simulated).
 */
public class InMemoryNetwork(
    private val scope: CoroutineScope? = null,
    private val random: Random = Random(0),
) {
    private val lock = Mutex()
    private val transports = mutableMapOf<Endpoint, InMemoryTransport>()
    private var nextPort = FIRST_PORT

    /** Loss, duplication and delay applied to every datagram sent from now on. */
    public var conditions: LinkConditions = LinkConditions.Perfect
        set(value) {
            require(value.isImmediate || scope != null) { "Latency and jitter need a CoroutineScope" }
            field = value
        }

    /** Returns false to drop a datagram; applied before [conditions]. */
    public var filter: (InMemoryDatagram) -> Boolean = { true }

    /** Sees every datagram once, with its [DatagramFate]. Called on the sending coroutine. */
    public var tap: (InMemoryDatagram, DatagramFate) -> Unit = { _, _ -> }

    /**
     * Attaches a transport at [address], by default the next free port on 127.0.0.1.
     *
     * @throws IllegalArgumentException if [address] is taken.
     */
    public suspend fun attach(
        address: Endpoint.Udp? = null,
        maxDatagramSize: Int = DEFAULT_MAX_DATAGRAM_SIZE,
    ): InMemoryTransport = lock.withLock {
        val local = address ?: Endpoint.Udp(LOOPBACK, nextPort++)
        require(local !in transports) { "Address in use: $local" }
        InMemoryTransport(this, local, maxDatagramSize).also { transports[local] = it }
    }

    internal suspend fun detach(transport: InMemoryTransport) {
        lock.withLock { transports.remove(transport.localEndpoint) }
    }

    internal suspend fun route(from: Endpoint.Udp, to: Endpoint, bytes: ByteArray) {
        val datagram = InMemoryDatagram(from, to, bytes)
        val copies = mutableListOf<Duration>()
        val receiver = lock.withLock {
            val receiver = transports[to]
            val conditions = conditions
            when {
                receiver == null -> tap(datagram, DatagramFate.UNREACHABLE)
                !filter(datagram) -> tap(datagram, DatagramFate.FILTERED)
                random.nextDouble() < conditions.lossRate -> tap(datagram, DatagramFate.LOST)
                else -> {
                    copies += delayFor(conditions)
                    if (random.nextDouble() < conditions.duplicationRate) copies += delayFor(conditions)
                    tap(datagram, if (copies.size > 1) DatagramFate.DUPLICATED else DatagramFate.DELIVERED)
                }
            }
            receiver
        } ?: return
        for (delay in copies) {
            val event = DatagramEvent.Received(from, bytes.copyOf())
            if (delay == Duration.ZERO) {
                receiver.deliver(event)
            } else {
                checkNotNull(scope).launch {
                    delay(delay)
                    receiver.deliver(event)
                }
            }
        }
    }

    private fun delayFor(conditions: LinkConditions): Duration {
        if (conditions.jitter == Duration.ZERO) return conditions.latency
        return conditions.latency + conditions.jitter * random.nextDouble()
    }

    public companion object {
        /** RFC 7252 §4.6: 1152 bytes is a good upper bound for a message when nothing is known about the path. */
        public const val DEFAULT_MAX_DATAGRAM_SIZE: Int = 1152

        private const val LOOPBACK = "127.0.0.1"
        private const val FIRST_PORT = 50000
    }
}

/** A datagram on its way through an [InMemoryNetwork]. [bytes] is the sender's copy; do not modify it. */
public class InMemoryDatagram(public val from: Endpoint.Udp, public val to: Endpoint, public val bytes: ByteArray) {
    override fun toString(): String = "$from -> $to (${bytes.size} bytes)"
}

/** What an [InMemoryNetwork] did with a datagram. */
public enum class DatagramFate {
    DELIVERED,

    /** Delivered twice. */
    DUPLICATED,

    /** Dropped by [LinkConditions.lossRate]. */
    LOST,

    /** Dropped by [InMemoryNetwork.filter]. */
    FILTERED,

    /** Nothing is attached at the destination. */
    UNREACHABLE,
}
