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
 * message of its code and Q-Block number, 2 for the next one, and so on.
 */
class DroppingTransport(private val delegate: DatagramTransport) : DatagramTransport {
    @Volatile
    var drop: (message: CoapMessage, copy: Int) -> Boolean = { _, _ -> false }

    /** Every message dropped. */
    val dropped: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())

    private val copies = HashMap<Pair<Int, Long>, Int>()

    override val maxDatagramSize: Int get() = delegate.maxDatagramSize

    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        val message = runCatching { UdpMessageCodec.decode(datagram) }.getOrNull()
        if (message != null) {
            val number = (message.options.qBlock2 ?: message.options.qBlock1)?.number
            val copy = if (number == null) 1 else synchronized(copies) { copies.merge(message.code.value to number, 1, Int::plus)!! }
            if (drop(message, copy)) {
                dropped += message
                return
            }
        }
        delegate.send(to, datagram)
    }

    override suspend fun receive(): DatagramEvent = delegate.receive()

    override suspend fun close() {
        delegate.close()
    }

    /** Starts counting copies again, for the next test. */
    fun reset() {
        drop = { _, _ -> false }
        dropped.clear()
        synchronized(copies) { copies.clear() }
    }
}
