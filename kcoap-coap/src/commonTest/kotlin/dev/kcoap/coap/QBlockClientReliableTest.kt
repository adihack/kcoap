package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.codec.ReliableStreamDecoder
import dev.kcoap.coap.stack.Signal
import dev.kcoap.testkit.InMemoryByteStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The Q-Block client on a reliable transport (RFC 9177 Appendix B, RFC 8323) against a peer scripted by hand
 * on an in-memory connection: bodies of more blocks than the endpoint has exchanges per peer, and than a
 * body's inbox holds on an unreliable transport, back to back.
 */
class QBlockClientReliableTest {
    /** The server side of one connection: it sends its CSM and hands every request to the script. */
    private class Peer(private val connection: InMemoryByteStream) {
        /** Requests in the order they came. */
        val requests = mutableListOf<CoapMessage>()

        /** Writes [messages] in one write, as a server that sends blocks back to back fills its socket. */
        suspend fun write(messages: List<CoapMessage>) {
            connection.write(messages.map { ReliableMessageCodec.Tcp.encode(it) }.reduce { all, next -> all + next })
        }
    }

    private suspend fun TestScope.peer(fixture: ReliableFixture, script: suspend Peer.(CoapMessage) -> Unit): Lazy<Peer> {
        val listener = fixture.listen()
        var peer: Peer? = null
        backgroundScope.launch {
            val connection = listener.accept() as InMemoryByteStream
            val accepted = Peer(connection).also { peer = it }
            connection.write(ReliableMessageCodec.Tcp.encode(Signal.Csm(null, false).toMessage()))
            val decoder = ReliableStreamDecoder()
            val buffer = ByteArray(READ_SIZE)
            while (true) {
                val count = connection.read(buffer, 0, buffer.size)
                if (count < 0) break
                for (message in decoder.feed(buffer.copyOf(count))) {
                    if (!message.isRequest) continue
                    accepted.requests += message
                    accepted.script(message)
                }
            }
        }
        return lazy { checkNotNull(peer) { "No connection was accepted" } }
    }

    private fun body(size: Int): ByteArray = ByteArray(size) { (it * 13 + it / 256).toByte() }

    private fun ReliableFixture.qBlockClient(): CoapClient = client(
        CoapClientConfig(blockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, onReliableTransports = true)),
    )

    @Test
    fun rfc9177AppendixBAnUploadOfMoreBlocksThanExchangesPerPeerReleasesEachTokenAsItGoes() = runTest {
        val fixture = ReliableFixture(this)
        val upload = body(300 * 64)
        val stored = ByteArray(upload.size)
        var most = 0
        lateinit var client: CoapClient
        val peer = peer(fixture) { request ->
            most = maxOf(most, client.endpoint.exchangeCount())
            val block = request.options.qBlock1 ?: return@peer
            request.payload.copyInto(stored, (block.number * 64).toInt())
            // Appendix B: only the last block is answered.
            if (!block.more) write(listOf(CoapMessage.response(request, CoapCode.CHANGED)))
        }
        client = fixture.qBlockClient()

        val response = client.put(fixture.uri("/store"), upload)

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, stored)
        assertEquals((0L until 300L).toList(), peer.value.requests.map { it.options.qBlock1!!.number })
        assertTrue(most <= 3, "block 0, the block before and the block being sent: $most")
        assertEquals(0, client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177AppendixBAResponseBodyOfMoreBlocksThanAnInboxHoldsOnDatagramsArrivesWhole() = runTest {
        val fixture = ReliableFixture(this)
        val whole = body(20 * 1024)
        val peer = peer(fixture) { request ->
            val count = whole.size / 64
            // Every block back to back in one write: the client reads them all before its driver runs.
            write(
                (0 until count).map { num ->
                    CoapMessage.response(request, CoapCode.CONTENT) {
                        payload = whole.copyOfRange(num * 64, num * 64 + 64)
                        options {
                            etag(byteArrayOf(9))
                            size2(whole.size.toLong())
                            qBlock2(BlockOption(num.toLong(), num < count - 1, 2))
                        }
                    }
                },
            )
        }
        val client = fixture.qBlockClient()

        val response = client.get(fixture.uri("/big"))

        assertEquals(CoapCode.CONTENT, response.code)
        assertContentEquals(whole, response.payload)
        assertEquals(listOf(listOf(0L)), peer.value.requests.map { request -> request.options.qBlock2List.map { it.number } }, "no Continue")
        assertEquals(0, client.endpoint.exchangeCount())
    }

    private companion object {
        const val READ_SIZE = 8192
    }
}
