package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint

/**
 * A CoAP peer driven by hand on an [InMemoryTransport]: it reads what the endpoint under test sends and
 * answers with whatever the test builds, so tests can send what a kcoap server never would, such as
 * several responses to one request or a response with an unknown critical option.
 */
internal class RawPeer(private val transport: InMemoryTransport) {
    val address: Endpoint.Udp get() = transport.localEndpoint

    /** `coap://<address><path>`. */
    fun uri(path: String): String = "coap://${address.host}:${address.port}$path"

    private var client: Endpoint? = null
    private var nextMessageId = 0x7000

    /** The next message from the endpoint under test, which [send] then answers. */
    suspend fun receive(): CoapMessage {
        val event = transport.receive() as DatagramEvent.Received
        client = event.from
        return UdpMessageCodec.decode(event.datagram)
    }

    suspend fun send(message: CoapMessage) {
        transport.send(checkNotNull(client) { "Nothing received yet" }, UdpMessageCodec.encode(message))
    }

    /** A separate 2.05 to [request] of [type], with a Message ID of its own and [text] as payload. */
    fun response(request: CoapMessage, type: CoapMessageType, text: String, block: CoapMessage.Builder.() -> Unit = {}): CoapMessage =
        CoapMessage.response(request, CoapCode.CONTENT) {
            this.type = type
            messageId = nextMessageId++
            payload(text)
            block()
        }
}
