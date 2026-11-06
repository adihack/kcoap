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
 * The soak of RFC 9177 between kcoap's client and kcoap's server (docs/plan-qblock-and-platforms.md §1.5): 20
 * seeds, 10 % and 30 % loss in each direction, bodies of 3000 bytes and of 20 KiB in 64-byte blocks, as
 * Non-confirmable bursts (§7.2): uploads in Q-Block1 (PUT), downloads in Q-Block2 (GET), and uploads answered
 * with a body in Q-Block2 (POST, §4.7). Every run ends with the whole body or a clean [CoapException]; the handler only ever sees
 * whole bodies; no message carries both Block and Q-Block options (§4.1); and afterwards no exchange or lease
 * is left on either side and, NON_PARTIAL_TIMEOUT later, the server holds no Q-Block state.
 */
class QBlockTest {
    private val parameters = TransmissionParameters.Default
    private val blockOptions = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251 + seed).toByte() }

    private enum class Direction { UPLOAD, DOWNLOAD, BOTH }

    private class Run(val fixture: StackFixture, val server: CoapServer, val client: CoapClient, val stored: MutableList<ByteArray>)

    private suspend fun TestScope.run(seed: Int, big: ByteArray): Run {
        val fixture = StackFixture(this, seed)
        val stored = mutableListOf<ByteArray>()
        val server = fixture.server(
            CoapServerConfig(amplification = AmplificationPolicy.OFF, qBlock = QBlockServerConfig(isEnabled = true)),
            fixture.endpointConfig(parameters = parameters),
        ) {
            resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
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
        }
        val client = fixture.client(
            CoapClientConfig(preferredBlockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)),
            fixture.endpointConfig(parameters = parameters),
        )
        return Run(fixture, server, client, stored)
    }

    /** [completedPerFailed]: how many runs at least complete for every one that fails cleanly. */
    private suspend fun TestScope.soak(direction: Direction, lossRate: Double, completedPerFailed: Int = 4) {
        var completed = 0
        var failed = 0
        var recoveries = 0
        var lost = 0
        val failures = mutableListOf<String>()
        for (seed in 1..SEEDS) {
            for (size in SIZES) {
                val big = body(size, seed)
                val run = run(seed, big)
                run.fixture.network.conditions = LinkConditions(lossRate = lossRate)
                val name = "seed $seed, $size bytes, ${(lossRate * 100).toInt()} % loss, $direction"
                val result = runCatching {
                    val uri = run.fixture.uri(run.server, if (direction == Direction.DOWNLOAD) "/big" else "/store")
                    when (direction) {
                        Direction.UPLOAD -> run.client.put(uri, big) { nonConfirmable() }
                        Direction.DOWNLOAD -> run.client.get(uri) { nonConfirmable() }
                        Direction.BOTH -> run.client.post(uri, big) { nonConfirmable() }
                    }
                }
                val response = result.getOrNull()
                if (response != null) {
                    when (direction) {
                        Direction.UPLOAD -> assertContentEquals(big, run.stored.last(), name)
                        Direction.DOWNLOAD -> assertContentEquals(big, response.payload, name)
                        Direction.BOTH -> {
                            assertContentEquals(big, run.stored.last(), name)
                            assertContentEquals(big.reversedArray(), response.payload, name)
                        }
                    }
                    completed++
                } else {
                    val error = result.exceptionOrNull()
                    if (error !is CoapException || error.reason !in CLEAN_FAILURES) fail("$name ended with $error")
                    failures += "$name: ${error.reason} ${error.message}"
                    failed++
                }
                assertTrue(run.stored.all { it.contentEquals(big) }, "$name: only whole bodies reach the handler")
                val mixed = run.fixture.traffic.mapNotNull { it.message }.filter { message ->
                    message.options.any { it.number in blockOptions } && message.options.any { it.number in qBlockOptions }
                }
                assertEquals(emptyList(), mixed, "$name: messages with Block and Q-Block options")
                assertTrue(run.fixture.traffic.mapNotNull { it.message }.none { message -> message.options.any { it.number in blockOptions } })
                assertEquals(0, run.client.endpoint.exchangeCount(), "$name: client exchanges left")
                delay(parameters.nonPartialTimeout + 1.seconds)
                assertEquals(0, run.client.endpoint.leaseCount(), "$name: client leases left")
                assertEquals(0, run.server.endpoint.leaseCount(), "$name: server leases left")
                val state = run.server.qBlock.state()
                assertTrue(state.isEmpty, "$name: server Q-Block state left: $state")
                recoveries += run.fixture.events.count { it is EndpointEvent.QBlockRecovery }
                lost += run.fixture.traffic.count { it.fate == DatagramFate.LOST }
                run.client.endpoint.close()
                run.server.endpoint.close()
            }
        }
        val most = completed >= failed * completedPerFailed
        assertTrue(most, "most runs complete: $completed completed, $failed failed: $failures")
        assertTrue(lost > 0 && recoveries > 0, "datagrams were lost ($lost) and blocks recovered ($recoveries)")
    }

    @Test
    fun rfc9177Section7_2UploadsSoakWith10PercentLoss() = runTest(timeout = 10.minutes) { soak(Direction.UPLOAD, 0.1) }

    @Test
    fun rfc9177Section7_2UploadsSoakWith30PercentLoss() = runTest(timeout = 10.minutes) { soak(Direction.UPLOAD, 0.3) }

    @Test
    fun rfc9177Section7_2DownloadsSoakWith10PercentLoss() = runTest(timeout = 10.minutes) { soak(Direction.DOWNLOAD, 0.1) }

    @Test
    fun rfc9177Section7_2DownloadsSoakWith30PercentLoss() = runTest(timeout = 10.minutes) { soak(Direction.DOWNLOAD, 0.3) }

    @Test
    fun rfc9177Section4_7UploadsAnsweredWithABodySoakWith10PercentLoss() = runTest(timeout = 10.minutes) {
        soak(Direction.BOTH, 0.1)
    }

    /**
     * A POST may not be sent again, so its last block is not repeated on silence (ADR 0006) and a lost final
     * answer costs the whole run: at 30 % loss each way about a fifth of these fail, cleanly. Every run still ends
     * whole or with a clean error and leaves nothing behind; two in three complete at least.
     */
    @Test
    fun rfc9177Section4_7UploadsAnsweredWithABodySoakWith30PercentLoss() = runTest(timeout = 10.minutes) {
        soak(Direction.BOTH, 0.3, completedPerFailed = 2)
    }

    private companion object {
        const val SEEDS = 20
        val SIZES = listOf(3000, 20 * 1024)

        /** The ways a run may fail cleanly under heavy loss. */
        val CLEAN_FAILURES = setOf(
            CoapException.Reason.RESPONSE_TIMEOUT,
            CoapException.Reason.BLOCKWISE_FAILED,
            CoapException.Reason.TRANSMISSION_TIMEOUT,
        )
    }
}
