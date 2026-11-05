package dev.kcoap.coap

import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.stack.ClientExchange
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.ExchangeEvent
import dev.kcoap.coap.stack.ExchangeInbox
import dev.kcoap.coap.stack.ResponseMode
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Q-Block server on a reliable transport (RFC 9177 §7, Appendix B; RFC 8323): blocks go back to back,
 * only the final response matters, and the requests in between count as answered, so a Ping with Custody
 * (RFC 8323 §5.4.1) is not held up behind an upload. The client is an endpoint driven request by request.
 */
class QBlockReliableTest {
    private val big = ByteArray(3000) { (it * 17 + 3).toByte() }
    private val address = Endpoint.Tcp("127.0.0.1", 5683)
    private val tag = byteArrayOf(5, 6, 7, 8)
    private val qBlockOptions = ClientExchange.BLOCKWISE_OPTIONS + setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    private class Fixture(val reliable: ReliableFixture, val server: CoapServer, val client: CoapEndpoint, val stored: List<ByteArray>) {
        val inbox = ExchangeInbox(256)
    }

    private suspend fun TestScope.fixture(qBlock: QBlockServerConfig = QBlockServerConfig(isEnabled = true)): Fixture {
        val reliable = ReliableFixture(this)
        val stored = mutableListOf<ByteArray>()
        val server = reliable.server(config = CoapServerConfig(qBlock = qBlock)) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
            resource("store") {
                put {
                    stored += payload
                    CoapResponse.changed()
                }
            }
        }
        return Fixture(reliable, server, reliable.endpoint(), stored)
    }

    private fun upload(num: Long, size: Int = 64): CoapMessage {
        val offset = (num * size).toInt()
        val end = minOf(big.size, offset + size)
        return CoapMessage.request(CoapCode.PUT) {
            payload = big.copyOfRange(offset, end)
            options {
                uriPath("store")
                requestTag(tag)
                size1(big.size.toLong())
                qBlock1(BlockOption(num, end < big.size, BlockOption.sizeExponentFor(size)))
            }
        }
    }

    private suspend fun Fixture.send(request: CoapMessage): ClientExchange =
        client.openExchange(address, request, false, null, ResponseMode.Inbox(inbox), recognizedOptions = qBlockOptions)

    @Test
    fun rfc9177AppendixBAnUploadGetsOnlyItsFinalResponseAndCustodyIsNotHeldUp() = runTest {
        val f = fixture()
        repeat(10) { f.send(upload(it.toLong())) }
        delay(1.seconds)
        assertEquals(1, f.server.qBlock.state().partialBodies)

        val start = testScheduler.currentTime
        assertTrue(f.client.ping(address, custody = true), "RFC 8323 §5.4.1: every request before the Ping counts as answered")
        assertTrue(testScheduler.currentTime - start < 1000, "the Pong came at once")

        val last = (10L..46L).map { f.send(upload(it)) }.last()
        val event = assertIs<ExchangeEvent.Response>(f.inbox.receive())
        assertEquals(CoapCode.CHANGED, event.message.code)
        assertTrue(event.exchange === last, "the final response on the request that completed the body")
        assertNull(withTimeoutOrNull(10.seconds) { f.inbox.receive() }, "no 2.31 and no 4.08 on a connection")
        assertContentEquals(big, f.stored.single())
        assertTrue(f.reliable.sent(CoapCode.CONTINUE, byServer = true).isEmpty())
    }

    @Test
    fun rfc9177AppendixBABodyGoesBackToBackWithoutWaitingForContinues() = runTest {
        val f = fixture()
        val request = CoapMessage.request(CoapCode.GET) {
            options {
                uriPath("big")
                qBlock2(BlockOption(0, true, BlockOption.sizeExponentFor(64)))
            }
        }
        val exchange = f.send(request)
        val blocks = List(47) { assertIs<ExchangeEvent.Response>(f.inbox.receive()).message }

        assertTrue(testScheduler.currentTime < 1000, "no NON_TIMEOUT_RANDOM between sets on a connection")
        assertEquals((0L..46L).toList(), blocks.map { it.options.qBlock2!!.number })
        assertTrue(blocks.all { it.hasToken(exchange.token) && it.options.etags.size == 1 && it.options.size2 == 3000L })
        assertContentEquals(big, blocks.fold(ByteArray(0)) { body, block -> body + block.payload })
        assertNull(withTimeoutOrNull(10.seconds) { f.inbox.receive() })
    }

    @Test
    fun rfc9177AppendixBQBlockOverConnectionsCanBeTurnedOff() = runTest {
        val f = fixture(QBlockServerConfig(isEnabled = true, onReliableTransports = false))
        f.send(CoapMessage.request(CoapCode.GET) { options { uriPath("big").qBlock2(BlockOption(0, true, 2)) } })

        val answer = assertIs<ExchangeEvent.Response>(f.inbox.receive()).message
        assertEquals(CoapCode.BAD_OPTION, answer.code)
    }

    @Test
    fun rfc8323Section7_4AClosedConnectionDropsThePeersBodies() = runTest {
        val f = fixture()
        repeat(5) { f.send(upload(it.toLong())) }
        f.send(CoapMessage.request(CoapCode.GET) { options { uriPath("big").qBlock2(BlockOption(5, false, 2)) } })
        delay(1.seconds)
        val busy = f.server.qBlock.state()
        assertEquals(1, busy.partialBodies)
        assertEquals(1, busy.servedBodies)

        f.client.close()
        delay(1.seconds)

        assertTrue(f.server.qBlock.state().isEmpty, "${f.server.qBlock.state()}")
        assertEquals("the session ended", f.reliable.events.filterIsInstance<EndpointEvent.PartialBodyDropped>().single().reason)
    }
}
