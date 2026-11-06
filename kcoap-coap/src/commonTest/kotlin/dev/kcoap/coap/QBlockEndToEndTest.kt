package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.LinkConditions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * kcoap's Q-Block client (QB-4) against kcoap's Q-Block server (QB-3), both with Q-Block on, on the in-memory
 * network and virtual time (RFC 9177): bodies in both directions, Non-confirmable and Confirmable, the
 * server's address check before a burst, and loss. Each run ends with equal bodies or a clean
 * [CoapException], uses Q-Block options only, and leaves no exchange, lease or body state on either side.
 */
class QBlockEndToEndTest {
    private val parameters = TransmissionParameters.Default
    private val blockOptions = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }

    private class Setup(val fixture: StackFixture, val server: CoapServer, val client: CoapClient, val stored: MutableList<ByteArray>) {
        fun uri(path: String): String = fixture.uri(server, path)

        val messages: List<CoapMessage> get() = fixture.traffic.mapNotNull { it.message }
    }

    private suspend fun TestScope.setup(
        big: ByteArray,
        seed: Int = 1,
        mode: QBlockMode = QBlockMode.PROBE,
        amplification: AmplificationPolicy = AmplificationPolicy.OFF,
    ): Setup {
        val fixture = StackFixture(this, seed)
        val stored = mutableListOf<ByteArray>()
        val server = fixture.server(CoapServerConfig(amplification = amplification, qBlock = QBlockServerConfig(isEnabled = true))) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
            resource("store") {
                put {
                    stored += payload
                    CoapResponse.changed()
                }
                // Answers with the body turned around, so an upload gets a body back (RFC 9177 §4.7).
                post {
                    stored += payload
                    CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray())
                }
            }
        }
        val client = fixture.client(CoapClientConfig {
            preferredBlockSize = 64
            qBlock = QBlockClientConfig(mode)
        }, fixture.endpointConfig(parameters = parameters))
        return Setup(fixture, server, client, stored)
    }

    /** RFC 9177 §4.1: no message mixes Block and Q-Block options, and with both sides on none uses Block. */
    private fun assertOnlyQBlock(setup: Setup, run: String = "") {
        val withBlock = setup.messages.filter { message -> message.options.any { it.number in blockOptions } }
        assertEquals(emptyList(), withBlock, "$run: messages with RFC 7959 Block options")
    }

    /** Nothing is left once NON_PARTIAL_TIMEOUT has passed: no exchange, lease or body on either side. */
    private suspend fun assertNothingLeft(setup: Setup, run: String = "") {
        assertEquals(0, setup.client.endpoint.exchangeCount(), "$run: client exchanges left")
        delay(parameters.nonPartialTimeout + 1.seconds)
        assertEquals(0, setup.client.endpoint.leaseCount(), "$run: client leases left")
        assertEquals(0, setup.server.endpoint.leaseCount(), "$run: server leases left")
        val state = setup.server.qBlock.state()
        assertTrue(state.isEmpty, "$run: server Q-Block state left: $state")
    }

    @Test
    fun rfc9177Section4_1AProbedClientUploadsAndDownloadsNonConfirmableBodiesInQBlock() = runTest {
        val big = body(3000, 1)
        val setup = setup(big)

        val put = setup.client.put(setup.uri("/store"), big) { nonConfirmable() }
        val get = setup.client.get(setup.uri("/big")) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        assertEquals(CoapCode.CONTENT, get.code)
        assertContentEquals(big, get.payload)
        val probes = setup.messages.filter { it.isRequest && it.options.path == "/.well-known/core" }
        assertEquals(1, probes.size, "one probe for both bodies")
        assertTrue(setup.messages.count { it.options.qBlock1 != null && it.isRequest } >= 47, "the upload went in Q-Block1")
        assertTrue(setup.messages.count { it.options.qBlock2 != null && !it.isRequest } >= 47, "the download came in Q-Block2")
        assertTrue(setup.fixture.events.none { it is EndpointEvent.QBlockFallback })
        assertOnlyQBlock(setup)
        assertNothingLeft(setup)
    }

    @Test
    fun rfc9177Section4_7AnUploadIsAnsweredWithAQBlock2Body() = runTest {
        val big = body(3000, 2)
        val setup = setup(big, mode = QBlockMode.ASSUME_SUPPORTED)

        val response = setup.client.post(setup.uri("/store"), big) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(big, setup.stored.single())
        assertContentEquals(big.reversedArray(), response.payload)
        assertTrue(setup.messages.any { !it.isRequest && it.options.qBlock2 != null }, "the answer came in Q-Block2")
        assertOnlyQBlock(setup)
        assertNothingLeft(setup)
    }

    @Test
    fun rfc9177Section7_1ConfirmableBodiesGoInLockStep() = runTest {
        val big = body(3000, 3)
        val setup = setup(big, mode = QBlockMode.ASSUME_SUPPORTED)

        val put = setup.client.put(setup.uri("/store"), big)
        val get = setup.client.get(setup.uri("/big"))

        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big, setup.stored.single())
        assertContentEquals(big, get.payload)
        val uploadBlocks = setup.messages.filter { it.isRequest && it.options.qBlock1 != null }
        assertTrue(uploadBlocks.all { it.type == CoapMessageType.CON }, "Confirmable Q-Block1 blocks")
        assertOnlyQBlock(setup)
        assertNothingLeft(setup)
    }

    @Test
    fun rfc9175Section2_4TheFirstBurstWaitsForTheClientToReturnTheEcho() = runTest {
        val big = body(3000, 4)
        val setup = setup(big, mode = QBlockMode.ASSUME_SUPPORTED, amplification = AmplificationPolicy.Q_BLOCK_BURSTS)

        val get = setup.client.get(setup.uri("/big")) { nonConfirmable() }

        assertContentEquals(big, get.payload)
        val challenge = setup.messages.filter { it.code == CoapCode.UNAUTHORIZED }
        assertEquals(1, challenge.size, "one 4.01 with Echo before the first burst")
        assertTrue(challenge.single().options.echo != null)
        assertTrue(setup.messages.any { it.isRequest && it.options.echo != null }, "the client returned the Echo value")
        assertOnlyQBlock(setup)
        assertNothingLeft(setup)
    }

    private enum class Direction { UPLOAD, DOWNLOAD }

    private suspend fun TestScope.lossy(direction: Direction, lossRate: Double) {
        var completed = 0
        var failed = 0
        var lost = 0
        var recoveries = 0
        for (seed in 1..SEEDS) {
            val big = body(3000, seed)
            val setup = setup(big, seed = seed, mode = QBlockMode.ASSUME_SUPPORTED)
            setup.fixture.network.conditions = LinkConditions(lossRate = lossRate)
            val result = runCatching {
                when (direction) {
                    Direction.UPLOAD -> setup.client.put(setup.uri("/store"), big) { nonConfirmable() }
                    Direction.DOWNLOAD -> setup.client.get(setup.uri("/big")) { nonConfirmable() }
                }
            }
            val run = "seed $seed, ${(lossRate * 100).toInt()} % loss, $direction"
            val response = result.getOrNull()
            if (response != null) {
                when (direction) {
                    Direction.UPLOAD -> {
                        assertEquals(CoapCode.CHANGED, response.code, run)
                        assertContentEquals(big, setup.stored.last(), run)
                    }
                    Direction.DOWNLOAD -> assertContentEquals(big, response.payload, run)
                }
                completed++
            } else {
                val error = result.exceptionOrNull()
                if (error !is CoapException || error.reason !in CLEAN_FAILURES) fail("$run ended with $error")
                failed++
            }
            assertTrue(setup.stored.all { it.contentEquals(big) }, "$run: only whole bodies reach the handler")
            lost += setup.fixture.traffic.count { it.fate == DatagramFate.LOST }
            recoveries += setup.fixture.events.count { it is EndpointEvent.QBlockRecovery }
            assertOnlyQBlock(setup, run)
            assertNothingLeft(setup, run)
            setup.client.endpoint.close()
            setup.server.endpoint.close()
        }
        assertTrue(completed >= failed * 4, "most runs complete: $completed completed, $failed failed")
        assertTrue(lost > 0 && recoveries > 0, "datagrams were lost ($lost) and blocks recovered ($recoveries)")
    }

    @Test
    fun rfc9177Section7_2UploadsCompleteWith10PercentLoss() = runTest(timeout = 5.minutes) { lossy(Direction.UPLOAD, 0.1) }

    @Test
    fun rfc9177Section7_2DownloadsCompleteWith10PercentLoss() = runTest(timeout = 5.minutes) { lossy(Direction.DOWNLOAD, 0.1) }

    private companion object {
        const val SEEDS = 10

        /** The ways a run may fail cleanly under loss. */
        val CLEAN_FAILURES = setOf(
            CoapException.Reason.RESPONSE_TIMEOUT,
            CoapException.Reason.BLOCKWISE_FAILED,
            CoapException.Reason.TRANSMISSION_TIMEOUT,
        )
    }
}
