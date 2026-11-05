package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlinx.coroutines.selects.select

/**
 * A [DatagramTransport] whose source address changes in the middle of a conversation, as a NAT rebinding
 * does to a client behind it: datagrams go out from [first] until [switchAt] of them were sent, and from
 * [second] after that, so the peer sees the later ones come from another address. Datagrams arrive on both
 * addresses, unless [oldAddressReceives] is false: then whatever reaches [first] after the switch is lost,
 * as when the old NAT binding has expired.
 *
 * ```
 * val client = RebindingTransport(network.attach(), network.attach(), switchAt = 5)
 * ```
 *
 * [close] closes both transports.
 */
@OptIn(ExperimentalAtomicApi::class)
public class RebindingTransport(
    public val first: InMemoryTransport,
    public val second: InMemoryTransport,
    public val switchAt: Int,
    public val oldAddressReceives: Boolean = true,
) : DatagramTransport {
    init {
        require(switchAt >= 0) { "The switch must come at a datagram index of 0 or more: $switchAt" }
        require(first.localEndpoint != second.localEndpoint) { "The two transports share ${first.localEndpoint}" }
    }

    private val sentCount = AtomicInt(0)

    override val maxDatagramSize: Int = minOf(first.maxDatagramSize, second.maxDatagramSize)

    /** How many datagrams were sent so far, from either address. */
    public val sent: Int get() = sentCount.load()

    /** Whether the switch has happened: the next datagram goes out from [second]. */
    public val isRebound: Boolean get() = sent >= switchAt

    /** The address datagrams go out from now. */
    public val localEndpoint: Endpoint.Udp get() = if (isRebound) second.localEndpoint else first.localEndpoint

    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        val index = sentCount.fetchAndIncrement()
        (if (index >= switchAt) second else first).send(to, datagram)
    }

    override suspend fun receive(): DatagramEvent {
        while (true) {
            val (event, onFirst) = select {
                first.onReceive { it to true }
                second.onReceive { it to false }
            }
            if (onFirst && isRebound && !oldAddressReceives) continue
            return event
        }
    }

    override suspend fun close() {
        first.close()
        second.close()
    }

    override fun toString(): String = "RebindingTransport(${first.localEndpoint} -> ${second.localEndpoint} at $switchAt)"
}
