package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.client.QBlockSupport
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.QBlockClientEvent
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.common.toHex
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.InMemoryDatagram
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The Q-Block client (RFC 9177) against a scripted Q-Block server ([QBlockTestServer]) on virtual time:
 * uploads and downloads, Non-confirmable and Confirmable, what goes on the wire, recovery, and the tokens,
 * exchanges and body leases left behind.
 */
class QBlockClientTest {
    internal class Setup(val fixture: StackFixture, val server: QBlockTestServer, val client: CoapClient) {
        val clientAddress: Endpoint.Udp get() = fixture.address(client.endpoint)

        /** Requests the client sent, in order. */
        fun requests(): List<CoapMessage> = fixture.sentBy(client.endpoint).filter { it.isRequest }

        /** Responses the server sent, in order. */
        fun responses(): List<CoapMessage> =
            fixture.traffic.filter { it.from == server.address }.mapNotNull { it.message }.filter { it.isResponse }

        suspend fun assertNothingLeft() {
            assertEquals(0, client.endpoint.exchangeCount(), "no exchange is left")
            assertEquals(0, client.endpoint.leaseCount(), "no body lease is left")
        }
    }

    private suspend fun TestScope.setup(
        qBlock: QBlockClientConfig = QBlockClientConfig(QBlockMode.PROBE),
        blockSize: Int? = 64,
        parameters: TransmissionParameters = TransmissionParameters(),
        seed: Int = 1,
    ): Setup {
        val fixture = StackFixture(this, seed)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
        val client = fixture.client(
            CoapClientConfig(blockSize = blockSize, qBlock = qBlock),
            fixture.endpointConfig(parameters = parameters),
        )
        return Setup(fixture, server, client)
    }

    private fun body(size: Int): ByteArray = ByteArray(size) { (it * 7 + it / 251).toByte() }

    private fun InMemoryDatagram.message(): CoapMessage? = runCatching { UdpMessageCodec.decode(bytes) }.getOrNull()

    private val CoapMessage.qBlock1: String? get() = options.qBlock1?.let { "${it.number}/${if (it.more) 1 else 0}/${it.size}" }

    private val CoapMessage.qBlock2: List<String> get() = options.qBlock2List.map { "${it.number}/${if (it.more) 1 else 0}/${it.size}" }

    // ---------------------------------------------------------------- probe

    @Test
    fun rfc9177Section4_1AProbeFindsSupportOnceAndLaterRequestsUseQBlock() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(300)

        val first = setup.client.get(setup.server.uri("/big"))
        val second = setup.client.get(setup.server.uri("/big"))

        assertContentEquals(body(300), first.payload)
        assertContentEquals(body(300), second.payload)
        val probes = setup.requests().filter { it.options.path == "/.well-known/core" }
        val probe = probes.single()
        assertEquals(CoapMessageType.CON, probe.type, "RFC 9177 §4.1: a Confirmable probe")
        assertEquals(listOf("0/0/16"), probe.qBlock2)
        assertEquals(
            QBlockSupport.Capability.SUPPORTED,
            setup.client.qBlock!!.support.known(setup.server.address, isProtected = false),
        )
        val gets = setup.requests().filter { it.options.path == "/big" }
        assertEquals(listOf("0/1/64"), gets[0].qBlock2, "§4.1: every request signals Q-Block2 support")
        setup.assertNothingLeft()
    }

    // ---------------------------------------------------------------- Q-Block1

    @Test
    fun rfc9177Figure2NonQBlock1WithoutLoss() = runTest {
        val setup = setup()
        val upload = body(200)

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val blocks = setup.requests().filter { it.options.path == "/store" }
        assertEquals(listOf("0/1/64", "1/1/64", "2/1/64", "3/0/64"), blocks.map { it.qBlock1 })
        assertTrue(blocks.all { it.type == CoapMessageType.NON })
        assertTrue(blocks.all { it.options.size1 == 200L }, "§4.6: Size1 on every block")
        val tags = blocks.map { it.options.requestTags.single().toHex() }.toSet()
        assertEquals(1, tags.size, "§4.3: one Request-Tag for the body")
        assertEquals(4, blocks[0].options.requestTags.single().size)
        assertEquals(4, blocks.map { it.tokenHex }.toSet().size, "every block on a token of its own")
        assertTrue(setup.responses().none { it.code == CoapCode.CONTINUE }, "no 2.31 for the last set")
        assertEquals(blocks.last().tokenHex, setup.responses().last().tokenHex, "2.04 on the last block's token")
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Figure3A231SendsTheNextSetAtOnce() = runTest {
        val setup = setup()
        val upload = body(25 * 64)

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val continues = setup.responses().filter { it.code == CoapCode.CONTINUE }
        assertEquals(listOf(9L, 19L), continues.map { it.options.qBlock1?.number })
        val blockTimes = setup.fixture.traffic.filter { it.from == setup.clientAddress && it.message?.options?.qBlock1 != null }
        assertTrue(blockTimes.all { it.at == blockTimes.first().at }, "§7.2: no pause after a 2.31: ${blockTimes.map { it.at }}")
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Figure4And5LostBlocksAreSentAgainOnA408() = runTest {
        val setup = setup()
        val upload = body(15 * 64)
        val dropped = mutableSetOf<Long>()
        setup.fixture.network.filter = { datagram ->
            val num = datagram.message()?.options?.qBlock1?.number
            !(num == 2L && dropped.add(2L)) && !(num == 7L && dropped.add(7L))
        }

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val missing = setup.responses().filter { it.code == CoapCode.REQUEST_ENTITY_INCOMPLETE }
        assertEquals(listOf(listOf(2L, 7L)), missing.map { MissingBlocks.decode(it.payload) })
        val recovery = setup.fixture.events.filterIsInstance<QBlockClientEvent.Recovery>().single()
        assertEquals(listOf(2L, 7L), recovery.blocks)
        assertTrue(!recovery.sent)
        assertEquals("QBlockClientEvent.Recovery(peer=${setup.server.address}, sent=false, blocks=[2, 7])", recovery.toString())
        setup.assertNothingLeft()
    }

    // ---------------------------------------------------------------- Q-Block2

    @Test
    fun rfc9177Figure8ANonGetGetsItsBodyWithContinues() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(3000)

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertEquals(CoapCode.CONTENT, response.code)
        assertContentEquals(body(3000), response.payload)
        assertEquals(null, response.options.qBlock2, "delivered without Q-Block2")
        assertEquals(null, response.options.size2)
        val gets = setup.requests().filter { it.options.path == "/big" }
        assertEquals(listOf("0/1/64", "10/1/64", "20/1/64", "30/1/64", "40/1/64"), gets.map { it.qBlock2.single() })
        assertTrue(gets.all { it.type == CoapMessageType.NON })
        assertTrue(gets.drop(1).all { it.options.etags.isEmpty() && it.options.observe == null })
        assertTrue(setup.fixture.now < 1.seconds, "Continues skip every pause: ${setup.fixture.now}")
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Figure9MissingBlocksAreAskedForWhenALaterSetArrives() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(1500)
        val dropped = mutableSetOf<Long>()
        setup.fixture.network.filter = { datagram ->
            val num = datagram.message()?.options?.qBlock2?.number
            datagram.from != setup.server.address || !((num == 2L || num == 7L) && dropped.add(num))
        }

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertContentEquals(body(1500), response.payload)
        val asked = setup.requests().filter { it.options.path == "/big" && it.options.qBlock2List.size > 1 }
        assertEquals(listOf(listOf("2/0/64", "7/0/64")), asked.map { it.qBlock2 })
        val recovery = setup.fixture.events.filterIsInstance<QBlockClientEvent.Recovery>().single()
        assertEquals(listOf(2L, 7L), recovery.blocks)
        assertTrue(recovery.sent)
        setup.assertNothingLeft()
    }

    // ---------------------------------------------------------------- Confirmable bodies

    @Test
    fun rfc9177Figure16ConfirmableQBlock1GoesInLockStepWithEmptyAcks() = runTest {
        val setup = setup()
        val upload = body(200)

        val response = setup.client.put(setup.server.uri("/store"), upload)

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val blocks = setup.requests().filter { it.options.path == "/store" }
        assertEquals(listOf("0/1/64", "1/1/64", "2/1/64", "3/0/64"), blocks.map { it.qBlock1 })
        assertTrue(blocks.all { it.type == CoapMessageType.CON })
        val answers = setup.fixture.traffic.filter { it.from == setup.server.address }.mapNotNull { it.message }.drop(1)
        assertEquals(listOf(true, true, true, false), answers.map { it.isEmpty }, "§7.1: no 2.31 for Confirmable blocks")
        assertEquals(CoapMessageType.ACK, answers.last().type, "the 2.04 piggybacked on the last block")
        assertEquals(0, setup.client.endpoint.leaseCount(), "a Confirmable body takes no lease")
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Figure18ConfirmableQBlock2HasBlock0PiggybackedAndTheRestConfirmable() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(300)

        val response = setup.client.get(setup.server.uri("/big"))

        assertContentEquals(body(300), response.payload)
        val blocks = setup.responses().filter { it.options.qBlock2 != null }.drop(1)
        val expected = listOf(CoapMessageType.ACK) + List(4) { CoapMessageType.CON }
        assertEquals(expected, blocks.map { it.type })
        val acks = setup.fixture.sentBy(setup.client.endpoint).filter { it.type == CoapMessageType.ACK }
        assertEquals(blocks.drop(1).map { it.messageId }, acks.map { it.messageId }, "every Confirmable block acknowledged")
        assertEquals(1, setup.requests().count { it.options.path == "/big" }, "no Continue for Confirmable blocks")
        setup.assertNothingLeft()
    }

    // ---------------------------------------------------------------- upload, then download

    @Test
    fun rfc9177Section4_7AFetchBodyGoesInQBlock1AndItsResponseBodyComesInQBlock2() = runTest {
        val setup = setup()
        setup.server.fetch = { it.reversedArray() }
        setup.server.maxBlockSize = 64
        val query = body(700)

        val response = setup.client.fetch(setup.server.uri("/search"), query) { nonConfirmable() }

        assertEquals(CoapCode.CONTENT, response.code)
        assertContentEquals(query.reversedArray(), response.payload)
        val fetches = setup.requests().filter { it.options.path == "/search" }
        val uploads = fetches.filter { it.options.qBlock1 != null }
        assertEquals((0..10).map { "$it/${if (it < 10) 1 else 0}/64" }, uploads.map { it.qBlock1 })
        assertEquals(listOf(9L), setup.responses().filter { it.code == CoapCode.CONTINUE }.map { it.options.qBlock1?.number })
        val followUps = fetches - uploads.toSet()
        assertEquals(listOf("10/1/64"), followUps.map { it.qBlock2.single() }, "one Continue for the second set")
        val tag = uploads.first().options.requestTags.single()
        assertTrue(followUps.all { it.payloadSize == 0 && it.options.requestTags.single().contentEquals(tag) }, "§4.7")
        assertTrue(followUps.all { it.options.qBlock1 == null && it.options.size1 == null })
        setup.assertNothingLeft()
    }

    // ---------------------------------------------------------------- answers that end a body

    @Test
    fun rfc9177Section4_3AnErrorResponseEndsTheUploadAndIsReturned() = runTest {
        val setup = setup()
        setup.server.script = { from, request ->
            val refuse = request.options.qBlock1?.number == 1L
            if (refuse) send(from, nonResponse(request, CoapCode.FORBIDDEN))
            refuse
        }

        val response = setup.client.put(setup.server.uri("/store"), body(500)) { nonConfirmable() }

        assertEquals(CoapCode.FORBIDDEN, response.code)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc7959Section2_9_3A413NamingASmallerSizeRestartsTheUploadOnceWithANewRequestTag() = runTest {
        val setup = setup()
        setup.server.script = { from, request ->
            val block = request.options.qBlock1
            val large = block != null && block.size == 64
            if (large && block?.number == 0L) {
                send(from, nonResponse(request, CoapCode.REQUEST_ENTITY_TOO_LARGE) { qBlock1(BlockOption(0, false, 1)) })
            }
            large
        }
        val upload = body(150)

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val (large, small) = setup.requests().filter { it.options.qBlock1 != null }.partition { it.options.qBlock1!!.size == 64 }
        assertEquals(3, large.size)
        assertEquals((0..4).map { "$it/${if (it < 4) 1 else 0}/32" }, small.map { it.qBlock1 })
        assertFalse(large[0].options.requestTags.single().contentEquals(small[0].options.requestTags.single()), "a new Request-Tag")
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9175Section2_3A401WithEchoHasTheBlocksSentAgainWithIt() = runTest {
        val setup = setup()
        val echo = byteArrayOf(0x45, 0x63, 0x68, 0x6f)
        setup.server.script = { from, request ->
            val challenge = request.options.qBlock1 != null && request.options.echo == null
            if (challenge) send(from, nonResponse(request, CoapCode.UNAUTHORIZED) { echo(echo) })
            challenge
        }
        val upload = body(200)

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        val blocks = setup.requests().filter { it.options.qBlock1 != null }
        assertEquals(8, blocks.size, "each block of the burst once without and once with Echo")
        assertTrue(blocks.drop(4).all { it.options.echo.contentEquals(echo) })
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Section4_3PutSendsItsLastBlockAgainOnTheLadderThenTimesOut() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        setup.fixture.network.filter = { it.from != setup.server.address }

        val failure = assertFailsWith<CoapException> {
            setup.client.put(setup.server.uri("/store"), body(150)) { nonConfirmable() }
        }

        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
        val blocks = setup.fixture.traffic.filter { it.from == setup.clientAddress && it.message?.options?.qBlock1 != null }
        assertEquals(listOf(0L, 1L, 2L, 2L, 2L, 2L, 2L), blocks.map { it.message!!.options.qBlock1!!.number })
        assertEquals(listOf(0, 0, 0, 4, 12, 28, 60).map { it.seconds }, blocks.map { it.at })
        assertEquals(124.seconds, setup.fixture.now)
        assertEquals(0, setup.client.endpoint.exchangeCount())
        assertEquals(1, setup.client.endpoint.leaseCount(), "§7.2: a body that got no response leaves its lease to expire")
        delay(27.seconds)
        assertEquals(0, setup.client.endpoint.leaseCount(), "after 150 bytes at PROBING_RATE")
    }

    @Test
    fun rfc9177Section4_3PostFailsAfterTheLadderWithoutSendingAnythingAgain() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        setup.fixture.network.filter = { it.from != setup.server.address }

        val failure = assertFailsWith<CoapException> {
            setup.client.post(setup.server.uri("/store"), body(150)) { nonConfirmable() }
        }

        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
        assertEquals(3, setup.requests().count { it.options.qBlock1 != null })
        assertEquals(124.seconds, setup.fixture.now)
    }

    // ---------------------------------------------------------------- downloads that go wrong

    @Test
    fun rfc9177Section4_4AChangedRepresentationRestartsAGetWithoutMixingTheBodies() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(1500)
        val newer = body(900).reversedArray()
        var changed = false
        setup.server.script = { _, request ->
            if (!changed && request.options.qBlock2List.singleOrNull()?.number == 10L) {
                // The resource changed, and the server runs the handler again for the Continue.
                changed = true
                resources["/big"] = newer
                forgetServed("/big")
            }
            false
        }

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertContentEquals(newer, response.payload)
        assertEquals(2, setup.server.servedBodies)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Section4_4ALostRequestForTheWholeBodyIsRepeatedAfterNonReceiveTimeout() = runTest {
        // libcoap K8: once its cache expired, it answers a request for missing blocks without ETag and Size2.
        val setup = setup()
        setup.server.resources["/big"] = body(1500)
        setup.server.script = { from, request ->
            val asked = request.options.qBlock2List.map { it.number } == listOf(2L)
            if (asked) {
                send(from, nonResponse(request, CoapCode.CONTENT, body(1500).copyOfRange(128, 192)) { qBlock2(BlockOption(2, true, 2)) })
            }
            asked
        }
        var blockLost = false
        var wholeBody = 0
        setup.fixture.network.filter = { datagram ->
            val message = datagram.message()
            val isBlock2 = datagram.from == setup.server.address && message?.options?.qBlock2?.number == 2L
            val asksForWholeBody = datagram.from == setup.clientAddress && message?.isRequest == true && message.qBlock2 == listOf("0/1/64")
            when {
                isBlock2 && !blockLost -> false.also { blockLost = true }
                asksForWholeBody -> wholeBody++ != 1
                else -> true
            }
        }

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertContentEquals(body(1500), response.payload)
        val restarts = setup.fixture.traffic.filter { it.from == setup.clientAddress && it.message?.qBlock2 == listOf("0/1/64") }
        assertEquals(3, restarts.size, "the GET, the lost request for the whole body, and its repeat")
        assertEquals(DatagramFate.FILTERED, restarts[1].fate)
        assertEquals(4.seconds, restarts[2].at - restarts[1].at)
        assertEquals(2, setup.server.servedBodies)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Section7_2ALongNonUploadToAServerThatAnswersOnlyOnItsLadderHoldsBoundedTokens() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        // libcoap sends no 2.31 while set 0 has a gap, and its first 4.08 NON_RECEIVE_TIMEOUT after the last block.
        setup.server.answersBlocksAtOnce = false
        var most = 0
        setup.server.script = { _, _ ->
            most = maxOf(most, setup.client.endpoint.exchangeCount())
            false
        }
        val upload = body(300 * 64)

        val response = setup.client.put(setup.server.uri("/store"), upload) { nonConfirmable() }

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(upload, setup.server.resources["/store"])
        assertTrue(setup.responses().none { it.code == CoapCode.CONTINUE })
        assertTrue(setup.responses().count { it.code == CoapCode.REQUEST_ENTITY_INCOMPLETE } > 5)
        assertTrue(most <= 50, "four sets unconfirmed, and one sent on a 4.08: $most")
        setup.assertNothingLeft()
    }

    @Test
    fun aBodyLargerThanMaxResponseBodySizeFails() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now })
        server.resources["/big"] = body(3000)
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)
        val client = fixture.client(CoapClientConfig(blockSize = 64, maxResponseBodySize = 1000, qBlock = qBlock))

        val failure = assertFailsWith<CoapException> { client.get(server.uri("/big")) { nonConfirmable() } }

        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertEquals(0, client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177Section4_4APartialBodyIsGivenUpAfterNonMaxRetransmitRequests() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(600)
        setup.fixture.network.filter = { it.from != setup.server.address || it.message()?.options?.qBlock2?.number != 5L }

        val failure = assertFailsWith<CoapException> { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } }

        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        val dropped = setup.fixture.events.filterIsInstance<QBlockClientEvent.PartialBodyDropped>().single()
        assertEquals(setup.server.address, dropped.peer)
        assertTrue(dropped.toString().contains("PartialBodyDropped"))
        val asked = setup.requests().filter { it.options.qBlock2List.singleOrNull()?.number == 5L }
        assertEquals(4, asked.size, "NON_MAX_RETRANSMIT requests for the missing block")
        setup.assertNothingLeft()
    }

    @Test
    fun aNonGetWithoutAnyAnswerIsSentAgainOnTheLadderThenTimesOut() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        setup.server.resources["/big"] = body(600)
        setup.fixture.network.filter = { it.from != setup.server.address }

        val failure = assertFailsWith<CoapException> { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } }

        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
        val gets = setup.fixture.traffic.filter { it.from == setup.clientAddress && it.message?.isRequest == true }
        assertEquals(listOf(0, 4, 12, 28, 60).map { it.seconds }, gets.map { it.at })
        assertEquals(124.seconds, setup.fixture.now)
        assertEquals(0, setup.client.endpoint.exchangeCount())
    }

    @Test
    fun aBodyOfOneBlockAndAPlainResponseAreDeliveredAsTheyAre() = runTest {
        val setup = setup()
        setup.server.resources["/small"] = body(40)

        val small = setup.client.get(setup.server.uri("/small")) { nonConfirmable() }
        val missing = setup.client.get(setup.server.uri("/missing")) { nonConfirmable() }

        assertContentEquals(body(40), small.payload)
        assertEquals(null, small.options.qBlock2)
        assertEquals(CoapCode.NOT_FOUND, missing.code)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Section4_1AResponseMixingBlock2AndQBlock2IsRejected() = runTest {
        val setup = setup()
        setup.server.script = { from, request ->
            val mixed = request.options.path == "/mixed"
            if (mixed) {
                send(from, nonResponse(request, CoapCode.CONTENT, ByteArray(64)) {
                    etag(byteArrayOf(1))
                    size2(300)
                    block2(BlockOption(0, true, 2))
                    qBlock2(BlockOption(0, true, 2))
                })
            }
            mixed
        }

        val failure = assertFailsWith<CoapException> { setup.client.get(setup.server.uri("/mixed")) { nonConfirmable() } }

        assertEquals(CoapException.Reason.BAD_RESPONSE, failure.reason)
        setup.assertNothingLeft()
    }

    @Test
    fun aServerThatAnswersWithBlock2IsFollowedWithRfc7959Only() = runTest {
        val setup = setup()
        val whole = body(200)
        setup.server.script = { from, request ->
            val legacy = request.options.path == "/legacy"
            if (legacy) {
                val num = request.options.block2?.number ?: 0
                val end = minOf(whole.size, ((num + 1) * 64).toInt())
                send(from, CoapMessage.response(request, CoapCode.CONTENT) {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    payload = whole.copyOfRange((num * 64).toInt(), end)
                    options { block2(BlockOption(num, end < whole.size, 2)) }
                })
            }
            legacy
        }

        val response = setup.client.get(setup.server.uri("/legacy"))

        assertContentEquals(whole, response.payload)
        val requests = setup.requests().filter { it.options.path == "/legacy" }
        assertEquals(listOf("0/1/64"), requests.first().qBlock2)
        assertTrue(requests.drop(1).all { it.options.qBlock2 == null && it.options.block2 != null }, "never both")
        setup.assertNothingLeft()
    }

    @Test
    fun anUploadAnsweredWithABlock2BodyGetsTheRestWithRfc7959AndTheRequestTag() = runTest {
        val setup = setup()
        val whole = body(150)
        setup.server.script = { from, request ->
            val final = request.options.qBlock1?.more == false || request.options.block2 != null
            if (final) {
                val num = request.options.block2?.number ?: 0
                val end = minOf(whole.size, ((num + 1) * 64).toInt())
                send(from, nonResponse(request, CoapCode.CHANGED, whole.copyOfRange((num * 64).toInt(), end)) {
                    block2(BlockOption(num, end < whole.size, 2))
                })
            }
            final
        }

        val response = setup.client.post(setup.server.uri("/echo"), body(100)) { nonConfirmable() }

        assertContentEquals(whole, response.payload)
        val follow = setup.requests().filter { it.options.block2 != null }
        assertEquals(2, follow.size)
        val tag = setup.requests().first { it.options.qBlock1 != null }.options.requestTags.single()
        assertTrue(follow.all { it.options.requestTags.single().contentEquals(tag) && it.options.qBlock1 == null })
        setup.assertNothingLeft()
    }

    @Test
    fun aRequestForBlocksAnsweredWithAnErrorFailsTheDownload() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(1500)
        setup.server.script = { from, request ->
            val continued = request.options.qBlock2List.singleOrNull()?.number == 10L
            if (continued) {
                send(from, nonResponse(request, CoapCode.VALID))
                send(from, CoapMessage.response(request, CoapCode.SERVICE_UNAVAILABLE) {
                    type = CoapMessageType.NON
                    messageId = 0x6100
                })
            }
            continued
        }

        val failure = assertFailsWith<CoapException> { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } }

        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertTrue(failure.message!!.contains("5.03"), failure.message)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9175Section2_3ADownloadChallengedWithEchoIsRepeatedWithItOnce() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(300)
        val echo = byteArrayOf(1, 2, 3, 4)
        setup.server.script = { from, request ->
            val challenge = request.options.path == "/big" && request.options.echo == null
            if (challenge) send(from, nonResponse(request, CoapCode.UNAUTHORIZED) { echo(echo) })
            challenge
        }

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertContentEquals(body(300), response.payload)
        val gets = setup.requests().filter { it.options.path == "/big" }
        assertEquals(2, gets.size)
        assertContentEquals(echo, gets.last().options.echo)
        setup.assertNothingLeft()
    }

    @Test
    fun withoutEchoRetryAnUploadChallengedWithEchoReturnsThe401() = runTest {
        val fixture = StackFixture(this)
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now })
        server.script = { from, request ->
            send(from, nonResponse(request, CoapCode.UNAUTHORIZED) { echo(byteArrayOf(5)) })
            true
        }
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)
        val client = fixture.client(CoapClientConfig(blockSize = 64, echoRetry = false, qBlock = qBlock))

        val response = client.put(server.uri("/store"), body(200)) { nonConfirmable() }

        assertEquals(CoapCode.UNAUTHORIZED, response.code)
        assertEquals(0, client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177Section4_3AResetToABlockAfterTheBodyStartedFailsItAndForgetsTheProbe() = runTest {
        val setup = setup()
        setup.server.script = { from, request ->
            val reset = request.options.qBlock1?.number == 10L
            if (reset) send(from, CoapMessage.empty(CoapMessageType.RST, request.messageId))
            reset
        }

        val failure = assertFailsWith<CoapException> {
            setup.client.put(setup.server.uri("/store"), body(15 * 64)) { nonConfirmable() }
        }

        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertEquals(null, setup.client.qBlock!!.support.known(setup.server.address, false))
        setup.assertNothingLeft()
    }

    @Test
    fun rfc9177Section4_1A402AfterTheBodyStartedIsReturnedAndForgetsTheProbe() = runTest {
        val setup = setup()
        setup.server.script = { from, request ->
            val reject = request.options.qBlock1?.number == 10L
            if (reject) send(from, nonResponse(request, CoapCode.BAD_OPTION))
            reject
        }

        val response = setup.client.put(setup.server.uri("/store"), body(15 * 64)) { nonConfirmable() }

        assertEquals(CoapCode.BAD_OPTION, response.code)
        assertEquals(null, setup.client.qBlock!!.support.known(setup.server.address, false))
        setup.assertNothingLeft()
    }

    @Test
    fun aResetToARequestForBlocksIsIgnoredAndTheTimersRecover() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(1500)
        var resets = 0
        setup.server.script = { from, request ->
            val reset = request.options.qBlock2List.singleOrNull()?.number == 10L && resets++ == 0
            if (reset) send(from, CoapMessage.empty(CoapMessageType.RST, request.messageId))
            reset
        }

        val response = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }

        assertContentEquals(body(1500), response.payload)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc7252Section4_2AConfirmableBlockThatIsNeverAcknowledgedFailsTheUpload() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        setup.fixture.network.filter = { it.message()?.options?.qBlock1?.number != 1L }

        val failure = assertFailsWith<CoapException> { setup.client.put(setup.server.uri("/store"), body(200)) }

        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, failure.reason)
        setup.assertNothingLeft()
    }

    @Test
    fun rfc7252Section4_2AConfirmableDownloadThatIsNeverAcknowledgedFails() = runTest {
        val setup = setup(qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))
        setup.fixture.network.filter = { it.from != setup.server.address }

        val failure = assertFailsWith<CoapException> { setup.client.get(setup.server.uri("/big")) }

        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, failure.reason)
        setup.assertNothingLeft()
    }

    @Test
    fun anEndpointClosedInTheMiddleOfABodyFailsIt() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(3000)
        setup.fixture.network.filter = { it.message()?.options?.qBlock2?.number != 5L }

        val download = async { runCatching { setup.client.get(setup.server.uri("/big")) { nonConfirmable() } } }
        delay(1.seconds)
        setup.client.endpoint.close()

        assertEquals(CoapException.Reason.CLOSED, (download.await().exceptionOrNull() as CoapException).reason)
    }

    @Test
    fun duplicateAndUselessAnswersAreDropped() = runTest {
        val setup = setup()
        setup.server.resources["/big"] = body(300)
        setup.server.duplicates = true
        setup.server.script = { from, request ->
            if (request.options.qBlock1?.number == 0L) {
                send(from, nonResponse(request, CoapCode.REQUEST_ENTITY_INCOMPLETE, MissingBlocks.encode(listOf(100))) {
                    contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ)
                })
            }
            false
        }

        val got = setup.client.get(setup.server.uri("/big")) { nonConfirmable() }
        val put = setup.client.put(setup.server.uri("/store"), body(200)) { nonConfirmable() }

        assertContentEquals(body(300), got.payload)
        assertEquals(CoapCode.CHANGED, put.code)
        assertTrue(setup.fixture.events.none { it is QBlockClientEvent.Recovery }, "a list of blocks outside the body asks for nothing")
        setup.assertNothingLeft()
    }
}

/** A Non-confirmable response to [request] with a Message ID of its own. */
internal fun nonResponse(
    request: CoapMessage,
    code: CoapCode,
    payload: ByteArray = ByteArray(0),
    extra: OptionSet.Builder.() -> Unit = {},
): CoapMessage = CoapMessage.response(request, code) {
    type = CoapMessageType.NON
    messageId = 0x6000 + (request.messageId and 0xFFF)
    this.payload = payload
    options(extra)
}
