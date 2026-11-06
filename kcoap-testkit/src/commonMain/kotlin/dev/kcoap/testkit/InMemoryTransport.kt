package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1

/**
 * A [DatagramTransport] attached to an [InMemoryNetwork] at [localEndpoint]. Obtain one from
 * [InMemoryNetwork.attach].
 *
 * [send] copies the datagram, so neither side can change what the other sees. After [close], [send]
 * throws and [receive] throws `ClosedReceiveChannelException` once queued events are drained.
 */
public class InMemoryTransport internal constructor(
    private val network: InMemoryNetwork,
    public val localEndpoint: Endpoint.Udp,
    override val maxDatagramSize: Int,
) : DatagramTransport {
    private val inbox = Channel<DatagramEvent>(Channel.UNLIMITED)

    init {
        require(maxDatagramSize > 0) { "Maximum datagram size must be positive: $maxDatagramSize" }
    }

    /** True once [close] was called. */
    public var isClosed: Boolean = false
        private set

    /**
     * @throws IllegalArgumentException when [datagram] exceeds [maxDatagramSize].
     * @throws IllegalStateException after [close].
     */
    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        require(datagram.size <= maxDatagramSize) { "Datagram of ${datagram.size} bytes exceeds $maxDatagramSize" }
        check(!isClosed) { "Transport $localEndpoint is closed" }
        network.route(localEndpoint, to, datagram.copyOf())
    }

    override suspend fun receive(): DatagramEvent = inbox.receive()

    /** [receive] as a select clause, so that one coroutine can wait on several transports ([RebindingTransport]). */
    internal val onReceive: SelectClause1<DatagramEvent> get() = inbox.onReceive

    override suspend fun close() {
        if (isClosed) return
        isClosed = true
        network.detach(this)
        inbox.close()
    }

    /**
     * Hands [event] to the receiver of this transport as if it had come from the network, or from a DTLS
     * layer (for [DatagramEvent.SessionEnded]). Tests use it to simulate what the network cannot.
     */
    public fun inject(event: DatagramEvent) {
        inbox.trySend(event)
    }

    internal fun deliver(event: DatagramEvent) {
        inbox.trySend(event)
    }

    override fun toString(): String = "InMemoryTransport($localEndpoint)"
}
