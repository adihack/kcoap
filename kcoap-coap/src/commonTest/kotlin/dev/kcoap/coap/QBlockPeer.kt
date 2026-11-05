package dev.kcoap.coap

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Q-Block client scripted by hand on an [InMemoryTransport], for testing the server's rules (RFC 9177) one
 * message at a time: it sends whatever the test builds, including what a correct client never would, and
 * collects every datagram the server sends back, on the test's virtual time.
 */
class QBlockPeer(private val transport: InMemoryTransport, private val server: Endpoint, scope: CoroutineScope) {
    private val inbox = Channel<CoapMessage>(Channel.UNLIMITED)
    private var nextMessageId = 0x1000
    private var nextToken = 0x40

    init {
        scope.launch {
            while (true) {
                val event = transport.receive() as? DatagramEvent.Received ?: continue
                inbox.send(UdpMessageCodec.decode(event.datagram))
            }
        }
    }

    val address: Endpoint.Udp get() = transport.localEndpoint

    /** A fresh token for a new request of the body (RFC 9177 §4.3: every block on a new token). */
    fun token(): ByteArray = byteArrayOf(0x7E, (nextToken++).toByte())

    /** Sends [message] with a Message ID of its own unless it has one. */
    suspend fun send(message: CoapMessage): CoapMessage {
        val withId = if (message.messageId != 0) message else message.toBuilder().apply { messageId = nextMessageId++ }.build()
        transport.send(server, UdpMessageCodec.encode(withId))
        return withId
    }

    /** The next message from the server, or null when none comes within [timeout]. */
    suspend fun next(timeout: Duration = 10.seconds): CoapMessage? = withTimeoutOrNull(timeout) { inbox.receive() }

    /** Every message that arrives until none has for [quiet]. */
    suspend fun drain(quiet: Duration = 100.milliseconds): List<CoapMessage> {
        val messages = mutableListOf<CoapMessage>()
        while (true) messages += next(quiet) ?: return messages
    }

    /** Acknowledges a Confirmable message from the server, as a client does. */
    suspend fun acknowledge(message: CoapMessage) {
        transport.send(server, UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.ACK, message.messageId)))
    }

    /** Rejects a message from the server with a Reset (RFC 7252 §4.2, §4.3). */
    suspend fun reset(message: CoapMessage) {
        transport.send(server, UdpMessageCodec.encode(CoapMessage.empty(CoapMessageType.RST, message.messageId)))
    }

    companion object {
        /** A peer attached to [fixture]'s network, talking to [server]. */
        suspend fun of(fixture: StackFixture, server: CoapServer): QBlockPeer =
            QBlockPeer(fixture.transport(), fixture.address(server.endpoint), fixture.test.backgroundScope)
    }
}
