package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Q-Block on a reliable transport (RFC 9177 §7, Appendix B; RFC 8323): blocks go back to back, only the final
 * response matters, and the requests in between count as answered, so a Ping with Custody (RFC 8323 §5.4.1) is
 * not held up behind an upload. First the server against an endpoint driven request by request, then kcoap's
 * client with `onReliableTransports` against it: bodies of 320 blocks both ways, and Observe (Figure 20).
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

    // ---------------------------------------------------------------- kcoap's client against kcoap's server

    /** An observable resource of [big]'s size whose content [change] turns to the next version. */
    private class Versions : ObservableResource("obs") {
        var version = 0

        override suspend fun handleGet(context: RequestContext): CoapResponse =
            CoapResponse.content(ByteArray(20 * 1024) { (it + version).toByte() }, ContentFormat.OCTET_STREAM)

        fun change() {
            version++
            notifyObservers()
        }
    }

    private class Peers(val reliable: ReliableFixture, val server: CoapServer, val client: CoapClient, val stored: List<ByteArray>) {
        /** What the server wrote, decoded. */
        val fromServer: List<CoapMessage> get() = reliable.sent(byServer = true).filter { it.code.isResponse || it.isRequest }

        /** What the client wrote, signals left out. */
        val fromClient: List<CoapMessage> get() = reliable.sent(byServer = false).filter { it.isRequest }
    }

    private suspend fun TestScope.pair(): Peers {
        val reliable = ReliableFixture(this)
        val stored = mutableListOf<ByteArray>()
        val server = reliable.server(config = CoapServerConfig(qBlock = QBlockServerConfig(isEnabled = true))) {
            resource("big") { get { CoapResponse.content(huge, ContentFormat.OCTET_STREAM) } }
            resource("store") {
                put {
                    stored += payload
                    CoapResponse.changed()
                }
                post {
                    stored += payload
                    CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray())
                }
            }
            add(Versions())
        }
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, onReliableTransports = true)
        val client = reliable.client(CoapClientConfig(preferredBlockSize = 64, qBlock = qBlock))
        return Peers(reliable, server, client, stored)
    }

    private val huge = ByteArray(20 * 1024) { (it * 13 + it / 256).toByte() }

    @Test
    fun rfc9177Figure19AnUploadGoesBackToBackAndOnlyItsFinalResponseComes() = runTest {
        val p = pair()

        val response = p.client.put(p.reliable.uri("/store"), huge)

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(huge, p.stored.single())
        val blocks = p.fromClient.filter { it.options.qBlock1 != null }
        assertEquals((0L until 320L).toList(), blocks.map { it.options.qBlock1!!.number }, "in order, each once")
        assertEquals(listOf(CoapCode.CHANGED), p.fromServer.map { it.code }, "Appendix B: no 2.31 and no 4.08, only the final response")
        assertTrue(p.fromServer.single().hasToken(blocks.last().token), "on the last block's token")
        assertEquals(0, p.client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177AppendixBADownloadGoesBackToBackWithoutContinues() = runTest {
        val p = pair()

        val response = p.client.get(p.reliable.uri("/big"))

        assertContentEquals(huge, response.payload)
        assertEquals(1, p.fromClient.size, "no Continue and no request for missing blocks")
        assertEquals((0L until 320L).toList(), p.fromServer.map { it.options.qBlock2!!.number })
        assertEquals(0, p.client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177Section4_7AnUploadAnsweredWithABodyGoesBackToBackBothWays() = runTest {
        val p = pair()

        val response = p.client.post(p.reliable.uri("/store"), huge)

        assertContentEquals(huge, p.stored.single())
        assertContentEquals(huge.reversedArray(), response.payload)
        assertEquals(320, p.fromClient.size, "the blocks of the upload and nothing more")
        assertEquals((0L until 320L).toList(), p.fromServer.map { it.options.qBlock2!!.number })
        assertTrue(p.fromServer.all { it.hasToken(p.fromClient.last().token) })
    }

    @Test
    fun rfc9177Figure20NotificationBodiesGoBackToBack() = runTest {
        val p = pair()
        val versions = p.server.root.child("obs") as Versions
        val received = mutableListOf<CoapResponse>()
        val observation = backgroundScope.launch { p.client.observe(p.reliable.uri("/obs")).collect { received += it } }
        delay(5.seconds)
        versions.change()
        delay(5.seconds)

        assertEquals(listOf(0, 1).map { version -> ByteArray(20 * 1024) { (it + version).toByte() }.toList() }, received.map { it.payload.toList() })
        val bodies = p.fromServer.groupBy { it.options.etags.single().toList() }.values
        assertEquals(2, bodies.size)
        assertTrue(bodies.all { body -> body.map { it.options.qBlock2!!.number } == (0L until 320L).toList() }, "each body back to back")
        assertEquals(listOf(listOf(0L), listOf(1L)), bodies.map { body -> body.map { it.options.observe!! }.distinct() }, "§4.5")
        assertEquals(1, p.fromClient.size, "no Continue")

        observation.cancel()
        delay(1.seconds)
        assertEquals(1L, p.fromClient.last().options.observe, "RFC 8323 §7.4: the client deregisters")
        assertEquals(0, versions.observerCount)
    }

    @Test
    fun rfc8323Section7_4AClosedConnectionEndsTheObservationAndItsBodies() = runTest {
        val p = pair()
        val versions = p.server.root.child("obs") as Versions
        backgroundScope.launch { runCatching { p.client.observe(p.reliable.uri("/obs")).collect {} } }
        delay(5.seconds)
        assertEquals(1, versions.observerCount)

        p.client.endpoint.close()
        delay(1.seconds)

        assertEquals(0, versions.observerCount)
        assertTrue(p.server.qBlock.state().isEmpty, "${p.server.qBlock.state()}")
    }
}
