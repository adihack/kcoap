package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import java.util.Collections

/**
 * A [DatagramTransport] that loses chosen datagrams on their way out, for loss on kcoap's side of an interop
 * test (libcoap's `-l` loses on its own side). [drop] sees each message with its copy number: 1 for the first
 * message of its code and Q-Block number, 2 for the next one, and so on. [onSent] hears of each message that
 * went out, and [holdIncoming] can hold a message that arrived back until something else happened, as a
 * network that delays it would, so a test can fix the order in which kcoap sees and sends datagrams.
 */
class DroppingTransport(private val delegate: DatagramTransport) : DatagramTransport {
    @Volatile
    var drop: (message: CoapMessage, copy: Int) -> Boolean = { _, _ -> false }

    /** Called with each message, and its copy number, once it went out. */
    @Volatile
    var onSent: (message: CoapMessage, copy: Int) -> Unit = { _, _ -> }

    /** Called with each message that arrived before kcoap sees it; kcoap receives nothing while it suspends. */
    @Volatile
    var holdIncoming: suspend (message: CoapMessage) -> Unit = {}

    /** Every message dropped. */
    val dropped: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())

    private val copies = HashMap<Pair<Int, Long>, Int>()

    override val maxDatagramSize: Int get() = delegate.maxDatagramSize

    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        val message = runCatching { UdpMessageCodec.decode(datagram) }.getOrNull()
        var copy = 1
        if (message != null) {
            val number = (message.options.qBlock2 ?: message.options.qBlock1)?.number
            copy = if (number == null) 1 else synchronized(copies) { copies.merge(message.code.value to number, 1, Int::plus)!! }
            if (drop(message, copy)) {
                dropped += message
                return
            }
        }
        delegate.send(to, datagram)
        if (message != null) onSent(message, copy)
    }

    override suspend fun receive(): DatagramEvent {
        val event = delegate.receive()
        if (event is DatagramEvent.Received) {
            runCatching { UdpMessageCodec.decode(event.datagram) }.getOrNull()?.let { holdIncoming(it) }
        }
        return event
    }

    override suspend fun close() {
        delegate.close()
    }

    /** Starts counting copies again, for the next test. */
    fun reset() {
        drop = { _, _ -> false }
        onSent = { _, _ -> }
        holdIncoming = {}
        dropped.clear()
        synchronized(copies) { copies.clear() }
    }
}
