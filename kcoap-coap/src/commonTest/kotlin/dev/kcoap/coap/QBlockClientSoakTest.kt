package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.testkit.RebindingTransport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The Q-Block client (RFC 9177) under loss, with several bodies at once, behind a NAT that rebinds, and when
 * the application gives a body up, against [QBlockTestServer] on virtual time. Every run ends with the whole
 * body or a clean [CoapException], leaves no exchange, lease or partial body behind, and never puts a Block
 * and a Q-Block option into one message (§4.1).
 */
class QBlockClientSoakTest {
    private val parameters = TransmissionParameters()
    private val blockOptions = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }

    /** RFC 9177 §4.1: no message mixes Block and Q-Block options. */
    private fun assertNoMessageMixesBlockAndQBlock(fixture: StackFixture) {
        val mixed = fixture.traffic.mapNotNull { it.message }.filter { message ->
            message.options.any { it.number in blockOptions } && message.options.any { it.number in qBlockOptions }
        }
        assertEquals(emptyList(), mixed, "messages with Block and Q-Block options")
    }

    private enum class Direction { UPLOAD, DOWNLOAD }

    private suspend fun TestScope.soak(direction: Direction, lossRate: Double) {
        var completed = 0
        var failed = 0
        for (seed in 1..SEEDS) {
            for (size in listOf(3000, 20 * 1024)) {
                val fixture = StackFixture(this, seed)
                fixture.network.conditions = LinkConditions(lossRate = lossRate)
                val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
                val client = fixture.client(
                    CoapClientConfig(preferredBlockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)),
                    fixture.endpointConfig(parameters = parameters),
                )
                val expected = body(size, seed)
                server.resources["/big"] = expected
                val result = runCatching {
                    when (direction) {
                        Direction.UPLOAD -> client.put(server.uri("/store"), expected) { nonConfirmable() }
                        Direction.DOWNLOAD -> client.get(server.uri("/big")) { nonConfirmable() }
                    }
                }
                val run = "seed $seed, $size bytes, ${(lossRate * 100).toInt()} % loss"
                val response = result.getOrNull()
                if (response != null) {
                    when (direction) {
                        Direction.UPLOAD -> {
                            assertEquals(CoapCode.CHANGED, response.code, run)
                            assertContentEquals(expected, server.resources["/store"], run)
                        }
                        Direction.DOWNLOAD -> assertContentEquals(expected, response.payload, run)
                    }
                    completed++
                } else {
                    val error = result.exceptionOrNull()
                    if (error !is CoapException || error.reason !in CLEAN_FAILURES) fail("$run ended with $error")
                    failed++
                }
                assertEquals(0, client.endpoint.exchangeCount(), "$run: exchanges left")
                assertNoMessageMixesBlockAndQBlock(fixture)
                assertEquals(emptyList(), server.errors, run)
                delay(parameters.nonPartialTimeout + 1.seconds)
                assertEquals(0, client.endpoint.leaseCount(), "$run: leases left")
                assertEquals(0, server.partialBodies, "$run: partial bodies left on the server")
                client.endpoint.close()
            }
        }
        assertTrue(completed >= failed * 4, "most runs complete: $completed completed, $failed failed")
    }

    @Test
    fun soakUploadsWith10PercentLoss() = runTest(timeout = 5.minutes) { soak(Direction.UPLOAD, 0.1) }

    @Test
    fun soakUploadsWith30PercentLoss() = runTest(timeout = 5.minutes) { soak(Direction.UPLOAD, 0.3) }

    @Test
    fun soakDownloadsWith10PercentLoss() = runTest(timeout = 5.minutes) { soak(Direction.DOWNLOAD, 0.1) }

    @Test
    fun soakDownloadsWith30PercentLoss() = runTest(timeout = 5.minutes) { soak(Direction.DOWNLOAD, 0.3) }

    // ---------------------------------------------------------------- NAT rebinding

    @Test
    fun rfc9177Section4_3ABodyWhoseClientRebindsIsCompletedFromItsNewAddress() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
        val rebinding = RebindingTransport(fixture.transport(), fixture.transport(), switchAt = 5, oldAddressReceives = false)
        val client = CoapClient(
            CoapEndpoint(rebinding, backgroundScope, fixture.endpointConfig()),
            CoapClientConfig(preferredBlockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)),
        )
        val upload = body(1000, 7)

        val response = client.put(server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, server.resources["/store"])
        val toNewAddress = fixture.traffic.filter { it.from == server.address && it.to == rebinding.second.localEndpoint }
            .mapNotNull { it.message }
        val asked = toNewAddress.filter { it.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }.map { MissingBlocks.decode(it.payload) }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), asked.first(), "the new partial body asks for the blocks sent from the old address")
        assertEquals(0, client.endpoint.exchangeCount())
        delay(parameters.nonPartialTimeout)
        assertEquals(0, server.partialBodies, "the partial body of the old address was discarded")
        assertEquals(1, server.discarded.size)
    }

    // ---------------------------------------------------------------- several bodies

    @Test
    fun rfc9177Section7_2ASecondNonConfirmableBodyToTheSamePeerWaitsForTheFirst() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
        val client = fixture.client(CoapClientConfig(preferredBlockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)))

        val first = async { client.put(server.uri("/a"), body(1500, 1)) { nonConfirmable() } }
        val second = async { client.put(server.uri("/b"), body(1500, 2)) { nonConfirmable() } }
        val confirmable = async { client.put(server.uri("/c"), body(1500, 3)) }

        assertEquals(listOf(CoapCode.CHANGED, CoapCode.CHANGED, CoapCode.CHANGED), listOf(first, second, confirmable).map { it.await().code })
        val traffic = fixture.traffic.mapNotNull { it.message }
        val firstFinal = traffic.indexOfFirst { it.code == CoapCode.CHANGED && it.type == CoapMessageType.NON }
        val secondStart = traffic.indexOfFirst { it.options.path == "/b" }
        assertTrue(secondStart > firstFinal, "§7.2: one NSTART slot per body, so /b starts after /a ended")
        assertContentEquals(body(1500, 3), server.resources["/c"], "a Confirmable body does not deadlock next to them")
        assertEquals(0, client.endpoint.exchangeCount())
        assertEquals(0, client.endpoint.leaseCount())
    }

    // ---------------------------------------------------------------- an abandoned body

    @Test
    fun rfc9177Section4_3AnAbandonedUploadForgetsItsTokensSoTheServerDropsItsPartialBody() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
        val client = fixture.client(CoapClientConfig(preferredBlockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)))
        fixture.network.filter = { datagram ->
            runCatching { dev.kcoap.coap.codec.UdpMessageCodec.decode(datagram.bytes) }.getOrNull()?.options?.qBlock1?.number != 3L
        }

        val upload = launch { runCatching { client.put(server.uri("/store"), body(3000, 1)) { nonConfirmable() } } }
        delay(1.seconds)
        upload.cancel()
        delay(10.seconds)

        assertEquals(0, client.endpoint.exchangeCount())
        assertEquals(0, client.endpoint.leaseCount())
        val missing = fixture.traffic.mapNotNull { it.message }.first { it.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
        val reset = fixture.sentBy(client.endpoint).single { it.type == CoapMessageType.RST }
        assertEquals(missing.messageId, reset.messageId, "the 4.08 on a forgotten token is rejected")
        assertEquals(0, server.partialBodies)
        assertEquals(1, server.discarded.size, "§4.3: the Reset made the server delete the partial body")
    }

    private companion object {
        const val SEEDS = 20

        /** The ways a run may fail cleanly under heavy loss. */
        val CLEAN_FAILURES = setOf(
            CoapException.Reason.RESPONSE_TIMEOUT,
            CoapException.Reason.BLOCKWISE_FAILED,
            CoapException.Reason.TRANSMISSION_TIMEOUT,
        )
    }
}
