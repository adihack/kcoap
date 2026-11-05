package dev.kcoap.coap

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.RequestHandlerFunction
import dev.kcoap.coap.server.ResourceBuilder
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.Endpoint
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The Q-Block server (RFC 9177) against a client scripted message by message ([QBlockPeer]), on virtual time:
 * every rule of the server side, including requests a correct client never sends.
 */
class QBlockServerTest {
    private val qBlockOn = QBlockServerConfig(isEnabled = true)
    private val on = CoapServerConfig(amplification = AmplificationPolicy.OFF, qBlock = qBlockOn)
    private val big = ByteArray(3000) { (it * 31 + it / 256).toByte() }
    private val tag = byteArrayOf(1, 2, 3, 4)

    private class Fixture(test: TestScope, val config: CoapServerConfig) {
        val stack = StackFixture(test)
        val stored = mutableListOf<ByteArray>()
        var bigRuns = 0
        lateinit var server: CoapServer
        lateinit var peer: QBlockPeer

        val events: List<EndpointEvent> get() = stack.events

        suspend fun start(body: ByteArray, maxDatagramSize: Int, routes: ResourceBuilder.() -> Unit): Fixture {
            server = stack.server(config, maxDatagramSize = maxDatagramSize) {
                resource("big") {
                    get {
                        bigRuns++
                        CoapResponse.content(body, ContentFormat.OCTET_STREAM)
                    }
                }
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
                    fetch { CoapResponse.content(payload.reversedArray(), ContentFormat.OCTET_STREAM) }
                }
                // Every unsafe method answering with a body, so a second request could be mistaken for a follow-up.
                resource("echo") {
                    val turn: RequestHandlerFunction = {
                        stored += payload
                        CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray())
                    }
                    put(turn)
                    post(turn)
                    patch(turn)
                    ipatch(turn)
                }
                routes()
            }
            peer = QBlockPeer.of(stack, server)
            return this
        }
    }

    private suspend fun TestScope.fixture(
        config: CoapServerConfig = on,
        body: ByteArray = big,
        maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE,
        routes: ResourceBuilder.() -> Unit = {},
    ) = Fixture(this, config).start(body, maxDatagramSize, routes)

    private fun block(num: Long, more: Boolean, size: Int = 64) = BlockOption(num, more, BlockOption.sizeExponentFor(size))

    private fun get(
        peer: QBlockPeer,
        vararg qBlock2: BlockOption,
        path: String = "big",
        type: CoapMessageType = CoapMessageType.NON,
        code: CoapCode = CoapCode.GET,
        options: OptionSet.Builder.() -> Unit = {},
    ) = CoapMessage.request(code) {
        this.type = type
        token = peer.token()
        options {
            uriPath(path)
            qBlock2.forEach { addQBlock2(it) }
            options()
        }
    }

    /** A request of one message with [payload] that offers Q-Block2 for its response (RFC 9177 §4.1). */
    private fun single(
        peer: QBlockPeer,
        code: CoapCode,
        payload: String,
        vararg qBlock2: BlockOption = arrayOf(block(0, true)),
        path: String = "echo",
        options: OptionSet.Builder.() -> Unit = {},
    ) = CoapMessage.request(code) {
        type = CoapMessageType.NON
        token = peer.token()
        this.payload = payload.encodeToByteArray()
        options {
            uriPath(path)
            qBlock2.forEach { addQBlock2(it) }
            options()
        }
    }

    /** Block [num] of [body] as a Q-Block1 request on a new token (RFC 9177 §4.3). */
    private fun upload(
        peer: QBlockPeer,
        num: Long,
        body: ByteArray = big,
        size: Int = 64,
        type: CoapMessageType = CoapMessageType.NON,
        code: CoapCode = CoapCode.PUT,
        requestTag: ByteArray = tag,
        path: String = "store",
        options: OptionSet.Builder.() -> Unit = {},
    ): CoapMessage {
        val offset = (num * size).toInt()
        val end = minOf(body.size, offset + size)
        return CoapMessage.request(code) {
            this.type = type
            token = peer.token()
            payload = body.copyOfRange(offset, end)
            options {
                uriPath(path)
                requestTag(requestTag)
                size1(body.size.toLong())
                qBlock1(block(num, end < body.size, size))
                options()
            }
        }
    }

    private fun slice(body: ByteArray, num: Long, size: Int = 64): ByteArray =
        body.copyOfRange((num * size).toInt(), minOf(body.size, ((num + 1) * size).toInt()))

    private fun List<CoapMessage>.numbers(): List<Long> = map { checkNotNull(it.options.qBlock2).number }

    private fun Fixture.serverSent(): List<StackFixture.Wire> = stack.traffic.filter { it.from == stack.address(server.endpoint) }

    // ---------------------------------------------------------------- admission

    @Test
    fun rfc9177Section4_1TheProbeGetsBlock0WithETagAndSize2Piggybacked() = runTest {
        val f = fixture { repeat(4) { resource("sensor-with-a-long-name-$it") { get { CoapResponse.content("$it") } } } }
        val probe = f.peer.send(get(f.peer, block(0, false, 16), path = ".well-known/core", type = CoapMessageType.CON))

        val answer = assertNotNull(f.peer.next())
        assertEquals(CoapMessageType.ACK, answer.type)
        assertEquals(probe.messageId, answer.messageId)
        assertEquals(CoapCode.CONTENT, answer.code)
        assertEquals(block(0, true, 16), answer.options.qBlock2, "RFC 9177 §4.4: M set, more blocks follow")
        assertEquals(4, answer.options.etags.single().size)
        val document = answer.options.size2
        assertNotNull(document)
        assertTrue(document > 16)
        assertEquals(16, answer.payloadSize)
        assertEquals(emptyList(), f.peer.drain(5.seconds), "only the block asked for")
    }

    @Test
    fun rfc9177Section4_1QBlockStaysOffByDefault() = runTest {
        val f = fixture(CoapServerConfig())
        f.peer.send(get(f.peer, block(0, true), type = CoapMessageType.CON))
        assertEquals(CoapCode.BAD_OPTION, f.peer.next()?.code)
    }

    @Test
    fun rfc9177Section4_1QBlockMixedWithBlockGets402EvenNonConfirmable() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)) { block2(block(0, false)) })

        val answer = assertNotNull(f.peer.next())
        assertEquals(CoapMessageType.NON, answer.type)
        assertEquals(CoapCode.BAD_OPTION, answer.code)
        assertEquals("Q-Block mixed with Block1 or Block2", answer.payloadAsText())
    }

    @Test
    fun rfc9177Section4_4ADescendingListMixedSizesOrSzx7Get400() = runTest {
        val f = fixture()
        val requests = listOf(
            get(f.peer, block(7, false), block(2, false)),
            get(f.peer, block(2, false, 64), block(7, false, 128)),
            get(f.peer, BlockOption(0, true, 7)),
        )
        for (request in requests) {
            f.peer.send(request)
            val answer = assertNotNull(f.peer.next())
            assertEquals(CoapCode.BAD_REQUEST, answer.code, answer.payloadAsText())
        }
        assertEquals(0, f.bigRuns)
    }

    @Test
    fun rfc9177Section4_3AnUploadWithoutRequestTagOrSize1Gets400AndOneTooLarge413WithSize1() = runTest {
        val f = fixture(CoapServerConfig(amplification = AmplificationPolicy.OFF, maxBodySize = 2048, qBlock = qBlockOn))
        val withoutTag = upload(f.peer, 0).toBuilder().options { remove(CoapOptions.REQUEST_TAG.number) }.build()
        val withoutSize1 = upload(f.peer, 0).toBuilder().options { remove(CoapOptions.SIZE1.number) }.build()

        f.peer.send(withoutTag)
        assertEquals("Q-Block1 without Request-Tag", f.peer.next()?.payloadAsText())
        f.peer.send(withoutSize1)
        assertEquals("Q-Block1 without Size1", f.peer.next()?.payloadAsText())
        f.peer.send(upload(f.peer, 0))
        val tooLarge = assertNotNull(f.peer.next())
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, tooLarge.code)
        assertEquals(2048L, tooLarge.options.size1)
        assertTrue(f.server.qBlock.state().isEmpty)
    }

    @Test
    fun rfc7959Section2_9_3ARequestOfOneMessageBeyondMaxBodySizeGets413AlsoWithQBlock2() = runTest {
        val f = fixture(CoapServerConfig(amplification = AmplificationPolicy.OFF, maxBodySize = 16, qBlock = qBlockOn))
        f.peer.send(single(f.peer, CoapCode.POST, "x".repeat(64)))

        val answer = f.peer.drain().single()
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, answer.code)
        assertEquals(16L, answer.options.size1)
        assertTrue(f.stored.isEmpty(), "the handler did not run")
        f.peer.send(single(f.peer, CoapCode.POST, "x".repeat(16)))
        assertEquals(CoapCode.CHANGED, f.peer.drain().single().code, "up to the bound it does")
    }

    @Test
    fun rfc9177Section4_1AProtectedRequestGets402UnlessQBlockIsOnWithMessageSecurity() = runTest {
        val protected = CoapServerConfig(security = ToySecurity, amplification = AmplificationPolicy.OFF, qBlock = qBlockOn)
        val f = fixture(protected)
        f.peer.send(get(f.peer, block(0, true), type = CoapMessageType.CON) { oscore(ByteArray(0)) })
        assertEquals(CoapCode.BAD_OPTION, f.peer.next()?.code, "RFC 8613 has one response per request")
        f.peer.send(get(f.peer, block(0, true), type = CoapMessageType.CON))
        assertEquals(block(0, true), f.peer.next()?.options?.qBlock2, "an unprotected request on the same server may")

        val withSecurity = QBlockServerConfig(isEnabled = true, withMessageSecurity = true)
        val allowed = fixture(CoapServerConfig(security = ToySecurity, amplification = AmplificationPolicy.OFF, qBlock = withSecurity))
        allowed.peer.send(get(allowed.peer, block(0, true)) { oscore(ByteArray(0)) })
        assertEquals((0L..9L).toList(), allowed.peer.drain().numbers())
    }

    // ---------------------------------------------------------------- Q-Block2

    @Test
    fun rfc9177Section7_2ABodyGoesInSetsOfMaxPayloadsNonTimeoutRandomApart() = runTest {
        val f = fixture()
        val request = f.peer.send(get(f.peer, block(0, true)))

        val blocks = mutableListOf<CoapMessage>()
        while (blocks.size < 47) blocks += assertNotNull(f.peer.next(4.seconds), "${blocks.size} blocks so far")

        assertEquals((0L..46L).toList(), blocks.numbers())
        assertTrue(blocks.all { it.hasToken(request.token) && it.type == CoapMessageType.NON }, "all on the request's token")
        val etag = blocks.first().options.etags.single()
        assertTrue(blocks.all { it.options.etags.single().contentEquals(etag) }, "RFC 9177 §4.4: one ETag per body")
        assertTrue(blocks.all { it.options.size2 == 3000L }, "RFC 9177 §4.6: Size2 on every block")
        assertTrue(blocks.all { it.options.contentFormat == ContentFormat.OCTET_STREAM })
        assertTrue(blocks.none { CoapOptions.OBSERVE.number in it.options })
        assertContentEquals(big, blocks.fold(ByteArray(0)) { body, block -> body + block.payload })
        val times = f.serverSent().filter { it.message?.isResponse == true }.map { it.at }.distinct()
        assertEquals(5, times.size, "five sets: $times")
        val pauses = times.zipWithNext { a, b -> b - a }
        assertTrue(pauses.all { it in 2.seconds..3.seconds } && pauses.distinct().size == 1, "one NON_TIMEOUT_RANDOM per body: $pauses")
    }

    @Test
    fun rfc9177Figure8AContinueSendsTheNextSetAtOnceOnTheOriginalToken() = runTest {
        val f = fixture()
        val request = f.peer.send(get(f.peer, block(0, true)))
        assertEquals((0L..9L).toList(), f.peer.drain().numbers())

        val proceed = f.peer.send(get(f.peer, block(10, true)))
        val set = f.peer.drain()

        assertEquals((10L..19L).toList(), set.numbers())
        assertTrue(set.all { it.hasToken(request.token) }, "on the original token")
        assertTrue(f.serverSent().mapNotNull { it.message }.none { it.hasToken(proceed.token) }, "nothing on the Continue's")
        assertEquals(1, f.bigRuns)
    }

    @Test
    fun rfc9177Section4_4MissingBlocksGoOnTheirRequestOnceAndAscending() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()

        val missing = f.peer.send(get(f.peer, block(2, false), block(7, false)))
        val resent = f.peer.drain()
        assertEquals(listOf(2L, 7L), resent.numbers())
        assertTrue(resent.all { it.hasToken(missing.token) && it.options.etags.size == 1 && it.options.size2 == 3000L })
        assertContentEquals(slice(big, 7), resent[1].payload)

        // With the M bit, the rest of the set (libcoap's -L 15); 3/M and 5/M=0 overlap, each block goes once.
        val rest = f.peer.send(get(f.peer, block(3, true), block(5, false)))
        val again = f.peer.drain()
        assertEquals((3L..9L).toList(), again.numbers())
        assertTrue(again.all { it.hasToken(rest.token) })
        val recoveries = f.events.filterIsInstance<EndpointEvent.QBlockRecovery>()
        assertEquals(listOf(listOf(2L, 7L), (3L..9L).toList()), recoveries.map { it.blocks })
        assertTrue(recoveries.all { it.isRequestedByPeer })
    }

    @Test
    fun rfc9177Section4_4AFollowUpInAnotherSizeOrBeyondTheBodyGets400() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()

        f.peer.send(get(f.peer, block(2, false, 128)))
        assertEquals("Q-Block2 size differs from 64 of the body", f.peer.drain().single().payloadAsText())
        f.peer.send(get(f.peer, block(50, true)))
        assertEquals("Continue beyond the body", f.peer.drain().single().payloadAsText())
        assertEquals(1, f.bigRuns)
    }

    @Test
    fun rfc9177Section4_4AResetToABlockStopsTheBody() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)))
        val set = f.peer.drain()
        f.peer.reset(set[3])

        assertEquals(emptyList(), f.peer.drain(10.seconds), "no later set")
    }

    @Test
    fun rfc9177Section7_1AConfirmableBodyGoesBlockByBlockAsEachIsAcknowledged() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true), type = CoapMessageType.CON))

        val first = f.peer.drain()
        assertEquals(listOf(CoapMessageType.ACK, CoapMessageType.CON), first.map { it.type }, "block 0 piggybacked, block 1 separate")
        assertEquals(listOf(0L, 1L), first.numbers())
        val blocks = first.toMutableList()
        while (blocks.last().options.qBlock2?.more == true) {
            f.peer.acknowledge(blocks.last())
            val next = f.peer.drain()
            assertEquals(1, next.size, "NSTART 1: one Confirmable block at a time")
            blocks += next
        }
        assertEquals((0L..46L).toList(), blocks.numbers())
        assertContentEquals(big, blocks.fold(ByteArray(0)) { body, block -> body + block.payload })
    }

    @Test
    fun rfc9177Section4_4AGetAfterTheCacheExpiredRunsAgainWithANewETag() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)))
        val etag = f.peer.drain().first().options.etags.single()
        delay(300.seconds)
        f.peer.drain()

        val missing = f.peer.send(get(f.peer, block(2, false)))
        val answer = f.peer.drain().single()
        assertTrue(answer.hasToken(missing.token))
        assertEquals(2L, answer.options.qBlock2?.number)
        assertFalse(answer.options.etags.single().contentEquals(etag), "RFC 9177 §4.4: the body that answers has a new ETag")
        assertEquals(3000L, answer.options.size2)
        assertEquals(2, f.bigRuns)
    }

    @Test
    fun rfc9177Section4_4AWholeBodyRequestOfAGetRunsTheHandlerAgain() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true)))
        val first = f.peer.drain().first().options.etags.single()
        f.peer.send(get(f.peer, block(0, true)))
        val second = f.peer.drain().first().options.etags.single()

        assertEquals(2, f.bigRuns)
        assertFalse(first.contentEquals(second), "one ETag per transmitted body")
    }

    @Test
    fun rfc9177Section4_4EveryBodyHasAnETagOfItsOwnInPlaceOfTheResources() = runTest {
        val f = fixture { resource("tagged") { get { CoapResponse.content(big).withOptions { etag(byteArrayOf(9, 9)) } } } }
        val etags = List(2) {
            f.peer.send(get(f.peer, block(0, true), path = "tagged"))
            val body = f.peer.collect().values.single()
            assertEquals(47, body.size)
            assertTrue(body.all { block -> block.options.etags.size == 1 }, "RFC 9177 §4.4: one ETag per block")
            val etag = body.first().options.etags.single()
            assertTrue(body.all { block -> block.options.etags.single().contentEquals(etag) })
            assertEquals(4, etag.size)
            assertFalse(etag.contentEquals(byteArrayOf(9, 9)), "not the resource's")
            etag
        }
        assertFalse(etags[0].contentEquals(etags[1]), "each transmitted body has its own")
    }

    // ---------------------------------------------------------------- Observe (RFC 7641, RFC 9177 §4.5)

    /** An observable resource answering [reply] of its [version], which [change] increases. */
    private class Counter(
        name: String = "counter",
        var reply: (Int) -> CoapResponse = { version -> CoapResponse.content(ByteArray(3000) { (it + version).toByte() }) },
    ) : ObservableResource(name) {
        var version = 0
        override val notificationType: CoapMessageType get() = CoapMessageType.NON

        override suspend fun handleGet(context: RequestContext): CoapResponse = reply(version)

        fun change() {
            version++
            notifyObservers()
        }
    }

    private fun register(peer: QBlockPeer, path: String = "counter", options: OptionSet.Builder.() -> Unit = {}) =
        get(peer, block(0, true), path = path) {
            observe(0)
            options()
        }

    @Test
    fun rfc9177Section4_5ARegistrationAddsAnObserverAndEveryBlockOfItsBodyCarriesObserve() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        val registration = f.peer.send(register(f.peer))

        val set0 = f.peer.drain()
        assertEquals((0L..9L).toList(), set0.numbers())
        assertTrue(set0.all { it.hasToken(registration.token) && it.options.observe == 0L }, "§4.5: Observe on every block")
        assertEquals(1, counter.observerCount)

        f.peer.send(get(f.peer, block(10, true), path = "counter"))
        val set1 = f.peer.drain()
        assertEquals((10L..19L).toList(), set1.numbers())
        assertTrue(set1.all { it.hasToken(registration.token) && it.options.observe == 0L }, "§7.2: a Continue's set goes on with Observe")

        val missing = f.peer.send(get(f.peer, block(3, false), path = "counter"))
        val resent = f.peer.drain().single()
        assertTrue(resent.hasToken(missing.token))
        assertNull(resent.options.observe, "§4.5: no Observe on a missing block")

        f.peer.drain(5.seconds)
        counter.change()
        val notification = f.peer.drain()
        assertEquals((0L..9L).toList(), notification.numbers())
        assertTrue(notification.all { it.hasToken(registration.token) && it.options.observe == 1L })
        assertFalse(notification.first().options.etags.single().contentEquals(set0.first().options.etags.single()), "a new ETag")
    }

    @Test
    fun rfc7641Section4_1AResourceThatCannotBeObservedIsServedWithoutObserve() = runTest {
        val f = fixture()
        f.peer.send(register(f.peer, path = "big"))

        val set = f.peer.drain()
        assertEquals(10, set.size)
        assertTrue(set.none { CoapOptions.OBSERVE.number in it.options })
        assertTrue(f.events.none { it is EndpointEvent.ObserverAdded })
    }

    @Test
    fun rfc7641Section3_6AGetWithObserve1AndQBlock2Deregisters() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        val registration = f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)
        assertEquals(1, counter.observerCount)

        val deregistration = get(f.peer, block(0, true), path = "counter") { observe(1) }
        f.peer.send(deregistration.toBuilder().apply { token = registration.token }.build())

        val answer = f.peer.drain()
        assertEquals(0, counter.observerCount)
        assertTrue(answer.isNotEmpty() && answer.none { CoapOptions.OBSERVE.number in it.options }, "served as a plain request")
    }

    @Test
    fun rfc7641Section4_1AnObserverBeyondTheBoundsIsServedWithoutObserve() = runTest {
        val counter = Counter()
        val config = CoapServerConfig(amplification = AmplificationPolicy.OFF, maxObserversPerResource = 1, qBlock = qBlockOn)
        val f = fixture(config) { add(counter) }
        f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)
        f.peer.send(register(f.peer))

        val second = f.peer.drain()
        assertTrue(second.isNotEmpty() && second.none { CoapOptions.OBSERVE.number in it.options })
        assertEquals(1, counter.observerCount)
        assertEquals(1, f.events.count { it is EndpointEvent.LimitReached && it.limit == Limit.OBSERVERS_PER_RESOURCE })
    }

    @Test
    fun rfc7641Section7AQBlock2RegistrationIsChallengedWhenEveryResponseIsChecked() = runTest {
        val counter = Counter()
        val f = fixture(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, qBlock = qBlockOn)) { add(counter) }
        f.peer.send(register(f.peer))

        val challenge = f.peer.drain().single()
        assertEquals(CoapCode.UNAUTHORIZED, challenge.code)
        assertEquals(0, counter.observerCount)
        f.peer.send(register(f.peer) { echo(assertNotNull(challenge.options.echo)) })
        assertTrue(f.peer.drain().all { it.options.observe == 0L })
        assertEquals(1, counter.observerCount)
    }

    @Test
    fun rfc9175Section2_4ARegistrationWhoseFirstBurstIsChallengedAddsNoObserver() = runTest {
        val counter = Counter()
        val f = fixture(CoapServerConfig(qBlock = qBlockOn)) { add(counter) }
        f.peer.send(register(f.peer))

        assertEquals(CoapCode.UNAUTHORIZED, f.peer.drain().single().code)
        assertEquals(0, counter.observerCount, "RFC 7641 §4.1: a registration whose response is not sent registers nothing")
        assertEquals(0, f.server.qBlock.state().servedBodies)
    }

    @Test
    fun rfc7641Section3_2ANotificationThatIsNotABodyGoesAsOneMessageAndAnErrorEndsTheObservation() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        val registration = f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)

        counter.reply = { CoapResponse(CoapCode.CONTENT) }
        counter.change()
        val empty = f.peer.drain(5.seconds).single()
        assertEquals(1L, empty.options.observe)
        assertNull(empty.options.qBlock2, "no body, so no Q-Block2")

        counter.reply = { CoapResponse(CoapCode.NOT_FOUND) }
        counter.change()
        val error = f.peer.drain(5.seconds).single()
        assertEquals(CoapCode.NOT_FOUND, error.code)
        assertTrue(error.hasToken(registration.token))
        assertNull(error.options.observe)
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun rfc9177Section4_4ANotificationWithoutRoomForA16ByteBlockGets413AndEndsTheObservation() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)

        counter.reply = { CoapResponse.content(big).withOptions { repeat(5) { locationQuery("q".repeat(250)) } } }
        counter.change()

        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, f.peer.drain(5.seconds).single().code)
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun rfc9177Section4_4ARegistrationWithoutRoomForA16ByteBlockGets413AndRegistersNothing() = runTest {
        val counter = Counter(reply = { CoapResponse.content(big).withOptions { repeat(5) { locationQuery("q".repeat(250)) } } })
        val f = fixture { add(counter) }
        f.peer.send(register(f.peer))

        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, f.peer.drain(5.seconds).single().code)
        assertEquals(0, counter.observerCount)
    }

    @Test
    fun rfc9177Section4_5AFetchUploadedWithQBlock1RegistersOnTheTokenThatCompletedIt() = runTest {
        val resource = object : ObservableResource("query") {
            var version = 0
            override val notificationType: CoapMessageType get() = CoapMessageType.NON
            override suspend fun handleFetch(context: RequestContext) =
                CoapResponse.content(context.payload.reversedArray() + byteArrayOf(version.toByte()))
        }
        val f = fixture { add(resource) }
        val query = big.copyOf(200)
        val blocks = (0L..3L).map { upload(f.peer, it, query, code = CoapCode.FETCH, path = "query") { observe(0) } }
        // Block 3 comes before block 2, which completes the body: the 2.05 goes on block 2's token (Figure 14).
        f.peer.send(blocks[0])
        f.peer.send(blocks[1])
        f.peer.send(blocks[3])
        f.peer.send(blocks[2])

        val body = f.peer.drain()
        assertTrue(body.isNotEmpty() && body.all { it.hasToken(blocks[2].token) && it.options.observe == 0L }, "$body")
        assertEquals(1, resource.observerCount)
        f.peer.drain(5.seconds)
        resource.version++
        resource.notifyObservers()
        val notification = f.peer.drain(5.seconds)
        assertTrue(notification.isNotEmpty() && notification.all { it.hasToken(blocks[2].token) && it.options.observe == 1L })
        assertEquals(1, notification.last().payload.last().toInt(), "the handler ran on the uploaded query")
    }

    @Test
    fun rfc7641Section4_5_1TheNonConfirmableBodiesOfTwoObservationsTakeOneNstartSlotEach() = runTest {
        val first = Counter("first")
        val second = Counter("second")
        val f = fixture {
            add(first)
            add(second)
        }
        f.peer.send(register(f.peer, path = "first"))
        f.peer.send(register(f.peer, path = "second"))
        f.peer.drain(5.seconds)

        val start = f.stack.now
        first.change()
        second.change()
        f.peer.drain(10.seconds)

        val bodies = f.serverSent().filter { it.at >= start }.mapNotNull { wire -> wire.message?.let { wire.at to it } }
            .groupBy { it.second.tokenHex }.values.map { blocks -> blocks.first().first }
        assertEquals(2, bodies.size, "two bodies")
        val gap = bodies.max() - bodies.min()
        assertTrue(gap >= f.config.nonConfirmableNotificationInterval, "the second waits for the first's slot: $bodies")
        delay(5.seconds)
        assertEquals(0, f.server.endpoint.leaseCount())
    }

    @Test
    fun rfc7641Section3_6AnObserverRemovedWhileItsBodyWaitsForItsSlotGetsNoBody() = runTest {
        val first = Counter("first")
        val second = Counter("second")
        val f = fixture {
            add(first)
            add(second)
        }
        f.peer.send(register(f.peer, path = "first"))
        val registration = f.peer.send(register(f.peer, path = "second"))
        f.peer.drain(5.seconds)

        val start = f.stack.now
        first.change()
        delay(100.milliseconds)
        // Its handler ran; its body waits for the NSTART slot that the first body holds for 3 s.
        second.change()
        delay(1.seconds)
        f.peer.send(
            CoapMessage.request(CoapCode.GET) {
                type = CoapMessageType.NON
                token = registration.token
                options {
                    uriPath("second")
                    observe(1)
                }
            },
        )
        f.peer.drain(10.seconds)

        assertEquals(0, second.observerCount)
        val notifications = f.serverSent().filter { it.at >= start }.mapNotNull { it.message }
            .filter { it.hasToken(registration.token) && it.options.observe != null }
        assertEquals(emptyList(), notifications, "RFC 7641 §3.6: nothing of a body for the observer that is gone")
        delay(5.seconds)
        assertEquals(0, f.server.endpoint.leaseCount(), "the slot it waited for goes back")
    }

    @Test
    fun rfc7641Section4_1AReRegistrationOnTheSameTokenEndsTheBodyOfTheObserverItReplaces() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        val registration = f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)
        counter.change()
        val set0 = f.peer.drain()
        assertEquals((0L..9L).toList(), set0.numbers(), "the first of five sets")
        val replaced = set0.first().options.etags.single()

        // As libcoap does: the same token again.
        f.peer.send(register(f.peer).toBuilder().apply { token = registration.token }.build())
        val after = f.peer.drain(10.seconds)

        assertTrue(after.isNotEmpty() && after.all { it.options.observe != null }, "the new registration's body")
        assertTrue(after.none { it.options.etags.single().contentEquals(replaced) }, "no later set of the old body")
        assertEquals(1, counter.observerCount)
    }

    @Test
    fun rfc7959Section7_1AnObservationKeepsNoBodyOnceItLeftTheCache() = runTest {
        val counter = Counter()
        val f = fixture { add(counter) }
        val registration = f.peer.send(register(f.peer))
        f.peer.drain(5.seconds)
        counter.change()
        f.peer.drain(5.seconds)

        delay(TransmissionParameters.Default.nonPartialTimeout + 1.seconds)
        val state = f.server.qBlock.state()
        assertTrue(state.isEmpty, "nothing of either body is held for the observation: $state")
        assertEquals(1, counter.observerCount, "which goes on")
        counter.change()
        val next = f.peer.drain(5.seconds)
        assertEquals(47, next.size)
        assertTrue(next.all { it.hasToken(registration.token) && it.options.observe == 2L })
    }

    @Test
    fun rfc9177Section4_4AResponseWithoutRoomForA16ByteBlockGets413() = runTest {
        val f = fixture {
            resource("crowded") {
                get { CoapResponse.content(big).withOptions { repeat(5) { locationQuery("q".repeat(250)) } } }
            }
        }
        f.peer.send(get(f.peer, block(0, true), path = "crowded"))

        val answer = assertNotNull(f.peer.next())
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, answer.code)
        assertNull(answer.options.size1)
    }

    @Test
    fun rfc9177Section4_4ASmallBodyIsOneBlockWithQBlock2AndAnErrorIsPlain() = runTest {
        val f = fixture(body = "tiny".encodeToByteArray())
        f.peer.send(get(f.peer, block(0, true)))
        val single = f.peer.drain().single()
        assertEquals(block(0, false), single.options.qBlock2, "Q-Block2 is echoed even for one block")
        assertEquals("tiny", single.payloadAsText())

        f.peer.send(get(f.peer, block(0, true), path = "store", code = CoapCode.DELETE))
        val error = f.peer.drain().single()
        assertEquals(CoapCode.METHOD_NOT_ALLOWED, error.code)
        assertNull(error.options.qBlock2)
    }

    // ---------------------------------------------------------------- Q-Block1

    @Test
    fun rfc9177Section4_3AnUploadGets231PerSetAndTheFinalResponseOnTheLastToken() = runTest {
        val f = fixture()
        val requests = (0L..46L).map { f.peer.send(upload(f.peer, it)) }

        val answers = f.peer.drain()
        assertEquals(List(4) { CoapCode.CONTINUE } + CoapCode.CHANGED, answers.map { it.code })
        assertEquals(listOf(9L, 19L, 29L, 39L), answers.dropLast(1).map { checkNotNull(it.options.qBlock1).number })
        assertTrue(answers.dropLast(1).all { it.options.qBlock1 == block(it.options.qBlock1!!.number, true) }, "RFC 9177 §4.3: M set")
        assertTrue(answers[0].hasToken(requests[9].token) && answers[3].hasToken(requests[39].token))
        assertTrue(answers.last().hasToken(requests[46].token), "on the request that completed the body")
        assertNull(answers.last().options.qBlock1)
        assertTrue(answers.all { it.type == CoapMessageType.NON })
        assertContentEquals(big, f.stored.single())
        assertEquals(0, f.server.qBlock.state().partialBodies)
    }

    @Test
    fun rfc9177Section7_2GapsBeforeALaterSetGetA408AtOnceOnItsToken() = runTest {
        val f = fixture()
        val requests = (0L..10L).filter { it != 2L && it != 7L }.map { f.peer.send(upload(f.peer, it)) }

        val answer = f.peer.drain().single()
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, answer.code)
        assertEquals(CoapMessageType.NON, answer.type, "libcoap ignores a Confirmable 4.08")
        assertEquals(ContentFormat.MISSING_BLOCKS_CBOR_SEQ, answer.options.contentFormat)
        assertContentEquals(byteArrayOf(0x02, 0x07), answer.payload)
        assertTrue(answer.hasToken(requests.last().token), "the token of block 10")

        f.peer.send(upload(f.peer, 2))
        f.peer.send(upload(f.peer, 7))
        (11L..46L).forEach { f.peer.send(upload(f.peer, it)) }
        assertEquals(listOf(CoapCode.CONTINUE, CoapCode.CONTINUE, CoapCode.CONTINUE, CoapCode.CHANGED), f.peer.drain().map { it.code })
        assertContentEquals(big, f.stored.single())
        val recovery = f.events.filterIsInstance<EndpointEvent.QBlockRecovery>().single()
        assertEquals(listOf(2L, 7L), recovery.blocks)
        assertFalse(recovery.isRequestedByPeer)
    }

    @Test
    fun rfc9177Section7_2ALostLastBlockIsAskedForAfterNonReceiveTimeout() = runTest {
        val f = fixture()
        val requests = (0L..45L).map { f.peer.send(upload(f.peer, it)) }
        val start = f.stack.now
        assertEquals(4, f.peer.drain().size)

        val answer = assertNotNull(f.peer.next(10.seconds))
        assertEquals(4.seconds, f.stack.now - start, "NON_RECEIVE_TIMEOUT")
        assertContentEquals(byteArrayOf(0x18, 0x2e), answer.payload, "46 in Content-Format 272")
        assertTrue(answer.hasToken(requests[45].token), "on the highest block's token, which the client keeps")

        f.peer.send(upload(f.peer, 46))
        assertEquals(CoapCode.CHANGED, f.peer.next()?.code)
    }

    @Test
    fun rfc9177Figure6TheServerGivesUpAfterNonMaxRetransmit408s() = runTest {
        val f = fixture()
        f.peer.send(upload(f.peer, 0))
        val start = f.stack.now
        val times = mutableListOf<Duration>()
        while (true) {
            val answer = f.peer.next(100.seconds) ?: break
            assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09), answer.payload, "the rest of set 0")
            times += f.stack.now - start
        }

        assertEquals(listOf(4.seconds, 12.seconds, 28.seconds, 60.seconds), times)
        assertEquals("Missing blocks asked for NON_MAX_RETRANSMIT times", f.events.filterIsInstance<EndpointEvent.PartialBodyDropped>().single().reason)
        assertEquals(0, f.server.qBlock.state().partialBodies)
    }

    @Test
    fun rfc9177Section4_3AResetToA408DiscardsTheBody() = runTest {
        val f = fixture()
        f.peer.send(upload(f.peer, 0))
        f.peer.send(upload(f.peer, 10))
        val incomplete = assertNotNull(f.peer.next())
        assertEquals((1L..9L).toList(), MissingBlocks.decode(incomplete.payload))

        f.peer.reset(incomplete)
        delay(100.milliseconds)

        assertEquals(1, f.events.count { it is EndpointEvent.PartialBodyDropped })
        assertTrue(f.server.qBlock.state().partialBodies == 0)
        assertEquals(emptyList(), f.peer.drain(30.seconds), "no more 4.08s for it")
    }

    @Test
    fun rfc9177Section4_3ACopyOfTheLastBlockGetsTheFinalResponseAgainAndOthersNothing() = runTest {
        val f = fixture()
        (0L..46L).forEach { f.peer.send(upload(f.peer, it)) }
        assertEquals(CoapCode.CHANGED, f.peer.drain().last().code)

        val last = f.peer.send(upload(f.peer, 46))
        val again = f.peer.drain().single()
        assertEquals(CoapCode.CHANGED, again.code)
        assertTrue(again.hasToken(last.token))
        f.peer.send(upload(f.peer, 5))
        assertEquals(emptyList(), f.peer.drain(), "a copy of a block in the middle gets nothing, as the first did")
        assertEquals(1, f.stored.size, "the handler ran once")
    }

    @Test
    fun rfc9177Section4_3AHandlerFailingOnACompletedUploadGets500AndSoDoesACopyOfItsLastBlock() = runTest {
        val f = fixture { resource("fail") { put { error("the handler failed") } } }
        val body = ByteArray(200) { it.toByte() }
        val requests = (0L..3L).map { f.peer.send(upload(f.peer, it, body = body, path = "fail")) }
        val answer = f.peer.drain().single()
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, answer.code)
        assertTrue(answer.hasToken(requests.last().token))
        assertEquals(0, f.server.qBlock.state().partialBodies)

        val copy = f.peer.send(upload(f.peer, 3, body = body, path = "fail"))
        val again = f.peer.drain().single()
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, again.code, "answered as the first copy was, not with nothing")
        assertTrue(again.hasToken(copy.token))
    }

    @Test
    fun rfc9177Section7_1AConfirmableUploadGetsEmptyAcksAndAPiggybackedFinalResponse() = runTest {
        val f = fixture()
        val answers = (0L..46L).map { num ->
            val request = f.peer.send(upload(f.peer, num, type = CoapMessageType.CON))
            val answer = assertNotNull(f.peer.next())
            assertEquals(request.messageId, answer.messageId)
            answer
        }

        assertTrue(answers.dropLast(1).all { it.type == CoapMessageType.ACK && it.isEmpty }, "RFC 9177 §4.3: no 2.31 for Confirmable blocks")
        assertEquals(CoapMessageType.ACK, answers.last().type)
        assertEquals(CoapCode.CHANGED, answers.last().code)
        assertContentEquals(big, f.stored.single())
    }

    @Test
    fun rfc9177Section4_7AnUploadWithABodyInReturnGetsItOnTheCompletingTokenAndContinues() = runTest {
        val f = fixture()
        val requests = (0L..46L).map { f.peer.send(upload(f.peer, it, code = CoapCode.POST)) }
        val answers = f.peer.drain()

        val response = answers.drop(4)
        assertEquals((0L..9L).toList(), response.numbers())
        assertTrue(response.all { it.code == CoapCode.CHANGED && it.hasToken(requests[46].token) })
        val proceed = f.peer.send(
            CoapMessage.request(CoapCode.POST) {
                type = CoapMessageType.NON
                token = f.peer.token()
                options {
                    uriPath("store")
                    requestTag(tag)
                    qBlock2(block(10, true))
                }
            },
        )
        val next = f.peer.drain()
        assertEquals((10L..19L).toList(), next.numbers())
        assertTrue(next.all { it.hasToken(requests[46].token) })
        assertTrue(next.none { it.hasToken(proceed.token) })
        assertContentEquals(slice(big.reversedArray(), 12), next[2].payload)
        assertEquals(1, f.stored.size, "the Continue did not run the handler again")
    }

    @Test
    fun rfc9177Figure12AFollowUpWithoutRequestTagContinuesTheOneBodyItFitsAndIsRefusedForTwo() = runTest {
        val f = fixture()
        val query = ByteArray(700) { it.toByte() }
        val first = (0L..10L).map { f.peer.send(upload(f.peer, it, body = query, code = CoapCode.FETCH)) }.last()
        assertEquals((0L..9L).toList(), f.peer.drain().drop(1).numbers(), "2.31 for set 0, then the body")

        fun continueWithoutTag() = CoapMessage.request(CoapCode.FETCH) {
            type = CoapMessageType.NON
            token = f.peer.token()
            options {
                uriPath("store")
                qBlock2(block(10, true))
            }
        }
        f.peer.send(continueWithoutTag())
        val continued = f.peer.drain()
        assertEquals((10L..10L).toList(), continued.numbers())
        assertTrue(continued.single().hasToken(first.token), "the one body it fits")

        // A second upload with another Request-Tag: a follow-up without one can no longer tell them apart.
        (0L..10L).forEach { f.peer.send(upload(f.peer, it, body = query, code = CoapCode.FETCH, requestTag = byteArrayOf(9))) }
        f.peer.drain()
        f.peer.send(continueWithoutTag())
        val refused = f.peer.drain().single()
        assertEquals(CoapCode.BAD_REQUEST, refused.code)
        assertEquals("Q-Block2 request fits more than one body: add its Request-Tag", refused.payloadAsText())
    }

    @Test
    fun rfc9177Section4_4AFollowUpOfAnUnsafeMethodWithoutCachedBodyGets408() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(10, true), path = "store", code = CoapCode.POST) { requestTag(tag) })

        val answer = assertNotNull(f.peer.next())
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, answer.code)
        assertEquals("No response body to continue", answer.payloadAsText())
        assertTrue(f.stored.isEmpty())
    }

    @Test
    fun rfc9177Section4_1EveryUnsafeRequestWithAPayloadAndQBlock2RunsItsHandler() = runTest {
        // §4.1: a client offers Q-Block2 for the response to a PUT or POST with 0/M=1 on every such request.
        val f = fixture()
        for (code in listOf(CoapCode.POST, CoapCode.PUT, CoapCode.PATCH, CoapCode.IPATCH)) {
            val first = f.peer.send(single(f.peer, code, "ab"))
            val a = f.peer.drain().single()
            val second = f.peer.send(single(f.peer, code, "cd"))
            val b = f.peer.drain().single()

            assertEquals("ba", a.payloadAsText(), "$code")
            assertEquals("dc", b.payloadAsText(), "$code: the second request ran, it is not a follow-up of the first")
            assertTrue(a.hasToken(first.token) && b.hasToken(second.token))
            assertEquals(block(0, false), b.options.qBlock2)
            assertFalse(a.options.etags.single().contentEquals(b.options.etags.single()), "two bodies")
        }
        assertEquals(List(4) { listOf("ab", "cd") }.flatten(), f.stored.map { it.decodeToString() })
    }

    @Test
    fun rfc9177Figure12ASmallPostWithoutRequestTagAfterUploadsRunsItsHandler() = runTest {
        val f = fixture()
        for (requestTag in listOf(tag, byteArrayOf(9))) {
            (0L..46L).forEach { f.peer.send(upload(f.peer, it, code = CoapCode.POST, requestTag = requestTag)) }
            assertEquals((0L..9L).toList(), f.peer.drain().drop(4).numbers())

            f.peer.send(single(f.peer, CoapCode.POST, "xy", path = "store"))
            val answer = f.peer.drain().single()
            assertEquals(CoapCode.CHANGED, answer.code, answer.payloadAsText())
            assertEquals("yx", answer.payloadAsText(), "not the upload's body, nor 4.00 for two of them")
        }
        assertEquals(4, f.stored.size)
        assertEquals("xy", f.stored.last().decodeToString())
    }

    @Test
    fun rfc9177Section4_1APayloadNextToQBlock2Block0WithoutMRunsTheHandlerAndNextToALaterBlockGets400() = runTest {
        val f = fixture()
        f.peer.send(single(f.peer, CoapCode.POST, "ab", block(0, false)))
        assertEquals("ba", f.peer.drain().single().payloadAsText(), "nothing cached, and still no 4.08")

        f.peer.send(single(f.peer, CoapCode.POST, "cd", block(2, false)))
        f.peer.send(single(f.peer, CoapCode.PUT, "ef", block(0, false), block(1, false)))
        val refused = f.peer.drain()
        assertEquals(listOf(CoapCode.BAD_REQUEST, CoapCode.BAD_REQUEST), refused.map { it.code })
        assertEquals("A request with a payload asks for Q-Block2 block 0", refused.first().payloadAsText())
        assertEquals(listOf("ab"), f.stored.map { it.decodeToString() })
    }

    @Test
    fun rfc9177Section4_4AnUnsafeWholeBodyRequestIsServedFromTheCacheOnlyWithItsRequestTag() = runTest {
        val f = fixture()
        (0L..46L).forEach { f.peer.send(upload(f.peer, it, code = CoapCode.POST)) }
        val etag = f.peer.drain().drop(4).first().options.etags.single()

        val again = f.peer.send(get(f.peer, block(0, true), path = "store", code = CoapCode.POST) { requestTag(tag) })
        val cached = f.peer.drain()
        assertEquals((0L..9L).toList(), cached.numbers())
        assertTrue(cached.all { it.hasToken(again.token) && it.options.etags.single().contentEquals(etag) }, "the same body")
        assertEquals(1, f.stored.size)

        f.peer.send(get(f.peer, block(0, true), path = "store", code = CoapCode.POST))
        assertEquals(CoapCode.CHANGED, f.peer.drain().single().code, "without the tag: a new, empty POST")
        assertEquals(2, f.stored.size)
    }

    @Test
    fun rfc9177Section4_3AFollowUpCarryingQBlock1WithoutPayloadGets400() = runTest {
        // libcoap 4.3.5's client repeats Q-Block1 0/M, without payload, in its Q-Block2 follow-ups of an upload.
        val f = fixture()
        (0L..46L).forEach { f.peer.send(upload(f.peer, it, code = CoapCode.POST)) }
        f.peer.drain()
        f.peer.send(
            CoapMessage.request(CoapCode.POST) {
                type = CoapMessageType.NON
                token = f.peer.token()
                options {
                    uriPath("store")
                    requestTag(tag)
                    size1(3000)
                    qBlock1(block(0, true))
                    qBlock2(block(10, true))
                }
            },
        )

        val answer = f.peer.drain().single()
        assertEquals(CoapCode.BAD_REQUEST, answer.code)
        assertEquals("Q-Block1 payload of 0 bytes does not fill block 0", answer.payloadAsText())
    }

    // ---------------------------------------------------------------- bounds, amplification and lifetime

    @Test
    fun rfc7959Section7_1UploadsBeyondTheBoundsGet503WithMaxAge() = runTest {
        val config = CoapServerConfig(
            amplification = AmplificationPolicy.OFF,
            qBlock = QBlockServerConfig(isEnabled = true, maxPartialBodies = 2, maxBodiesPerPeer = 1),
        )
        val f = fixture(config)
        f.peer.send(upload(f.peer, 0))
        f.peer.send(upload(f.peer, 0, requestTag = byteArrayOf(2)))
        val perPeer = assertNotNull(f.peer.next())
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, perPeer.code)
        assertEquals(4L, perPeer.options.maxAge)

        val second = QBlockPeer.of(f.stack, f.server)
        second.send(upload(second, 0))
        assertEquals(emptyList(), second.drain())
        val third = QBlockPeer.of(f.stack, f.server)
        third.send(upload(third, 0))
        assertEquals(CoapCode.SERVICE_UNAVAILABLE, third.next()?.code)

        val limits = f.events.filterIsInstance<EndpointEvent.LimitReached>().map { it.limit }
        assertEquals(listOf(Limit.BODIES_PER_PEER, Limit.PARTIAL_BODIES), limits)
    }

    @Test
    fun rfc7959Section7_1ABlockStartingAtSize1Gets400AndLeavesNothingBehind() = runTest {
        // Q-Block1 2/0/64 with Size1 128 and no payload adds up to Size1 but lies outside the body (plan row 39).
        val f = fixture()
        f.peer.send(upload(f.peer, 2, body = ByteArray(128)))
        val answer = f.peer.drain().single()
        assertEquals(CoapCode.BAD_REQUEST, answer.code)
        assertEquals("Q-Block1 block 2 starts beyond Size1 128", answer.payloadAsText())

        // As many as the bounds take, from 16 addresses: none of them starts a body.
        val spoofed = List(16) { QBlockPeer.of(f.stack, f.server) }
        for (peer in spoofed) repeat(4) { peer.send(upload(peer, 2, body = ByteArray(128), requestTag = byteArrayOf(it.toByte()))) }
        spoofed.forEach { assertEquals(4, it.drain().count { answer -> answer.code == CoapCode.BAD_REQUEST }) }
        assertTrue(f.server.qBlock.state().isEmpty, "${f.server.qBlock.state()}")
        delay(TransmissionParameters.Default.nonPartialTimeout + 1.seconds)
        assertTrue(f.server.qBlock.state().isEmpty, "${f.server.qBlock.state()}")

        (0L..46L).forEach { f.peer.send(upload(f.peer, it)) }
        assertEquals(CoapCode.CHANGED, f.peer.drain().last().code, "no 5.03")
        assertTrue(f.events.none { it is EndpointEvent.LimitReached })
    }

    @Test
    fun rfc7959Section7_1AnUploadHoldsWhatArrivedNotWhatItsSize1Claims() = runTest {
        val f = fixture()
        val claim = ByteArray(1 shl 20)
        f.peer.send(upload(f.peer, 0, body = claim))
        f.peer.send(upload(f.peer, 16383, body = claim))
        f.peer.drain()

        val state = f.server.qBlock.state()
        assertEquals(1, state.partialBodies)
        assertEquals(128L, state.partialBytes, "two blocks of a body that claims 1 MiB")
    }

    @Test
    fun cachedBodiesBeyondTheBoundsGiveWayOldestFirst() = runTest {
        val config = CoapServerConfig(
            amplification = AmplificationPolicy.OFF,
            qBlock = QBlockServerConfig(isEnabled = true, maxCachedBodies = 2, maxBodiesPerPeer = 1),
        )
        val f = fixture(config)
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()
        f.peer.send(get(f.peer, block(0, true)) { uriQuery("v=2") })
        f.peer.drain()
        val other = QBlockPeer.of(f.stack, f.server)
        other.send(get(other, block(0, true)))
        other.drain()
        val third = QBlockPeer.of(f.stack, f.server)
        third.send(get(third, block(0, true)))
        third.drain()

        assertEquals(2, f.server.qBlock.state().servedBodies)
        val limits = f.events.filterIsInstance<EndpointEvent.LimitReached>().map { it.limit to it.peer }
        assertEquals(listOf(Limit.BODIES_PER_PEER to f.peer.address, Limit.CACHED_BODIES to f.peer.address), limits)
    }

    /** Every message until none has come for [quiet], by the token it answers. */
    private suspend fun QBlockPeer.collect(quiet: Duration = 4.seconds): Map<List<Byte>, List<CoapMessage>> {
        val messages = mutableListOf<CoapMessage>()
        while (true) messages += next(quiet) ?: break
        return messages.groupBy { it.token.toList() }
    }

    @Test
    fun rfc9177Section7_2BodiesDoneSendingGiveWayFirstSoADownloadKeepsItsSets() = runTest {
        val small = ByteArray(100) { it.toByte() }
        val f = fixture { repeat(4) { resource("small-$it") { get { CoapResponse.content(small) } } } }
        val download = f.peer.send(get(f.peer, block(0, true)))
        // Four bodies of two blocks each while /big still sends: with the default of four per peer, the
        // oldest of those that are done sending gives way, never the download.
        repeat(4) { f.peer.send(get(f.peer, block(0, true), path = "small-$it")) }

        val received = f.peer.collect()
        assertEquals((0L..46L).toList(), received.getValue(download.token.toList()).numbers())
        assertEquals(5, received.size)
        assertEquals(1, f.bigRuns)
        assertEquals(listOf(Limit.BODIES_PER_PEER), f.events.filterIsInstance<EndpointEvent.LimitReached>().map { it.limit })
        assertEquals(4, f.server.qBlock.state().servedBodies)
    }

    @Test
    fun rfc9177Section7_2ABodyReplacedWhileItSendsFinishesItsSets() = runTest {
        val f = fixture()
        val first = f.peer.send(get(f.peer, block(0, true)))
        val second = f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()
        val busy = f.server.qBlock.state()
        assertEquals(1, busy.servedBodies, "follow-ups find the newer body")
        assertEquals(1, busy.sendingBodies, "the older one still sends")

        val received = f.peer.collect()
        assertEquals((10L..46L).toList(), received.getValue(first.token.toList()).numbers())
        assertEquals((10L..46L).toList(), received.getValue(second.token.toList()).numbers())
        assertEquals(0, f.server.qBlock.state().sendingBodies, "gone once done")
    }

    @Test
    fun cachedBodiesThatStillSendAreBoundedToo() = runTest {
        val config = CoapServerConfig(
            amplification = AmplificationPolicy.OFF,
            qBlock = QBlockServerConfig(isEnabled = true, maxCachedBodies = 1, maxBodiesPerPeer = 1),
        )
        val f = fixture(config)
        val requests = List(3) { f.peer.send(get(f.peer, block(0, true))) }

        val received = f.peer.collect()
        assertEquals((0L..9L).toList(), received.getValue(requests[0].token.toList()).numbers(), "stopped for the third")
        assertEquals(47, received.getValue(requests[1].token.toList()).size)
        assertEquals(47, received.getValue(requests[2].token.toList()).size)
        assertEquals(listOf(Limit.CACHED_BODIES to f.peer.address), f.events.filterIsInstance<EndpointEvent.LimitReached>().map { it.limit to it.peer })
    }

    @Test
    fun rfc9177Section4_4AGetBodyOfOneBlockIsNotKeptAndRunsAgainForItsBlock() = runTest {
        val f = fixture(body = "tiny".encodeToByteArray())
        f.peer.send(get(f.peer, block(0, true)))
        assertEquals("tiny", f.peer.drain().single().payloadAsText())
        assertEquals(0, f.server.qBlock.state().servedBodies)

        val again = f.peer.send(get(f.peer, block(0, false)))
        val answer = f.peer.drain().single()
        assertTrue(answer.hasToken(again.token))
        assertEquals("tiny", answer.payloadAsText())
        assertEquals(2, f.bigRuns)
    }

    @Test
    fun recordsOfCompletedUploadsBeyondTheBoundGiveWayOldestFirst() = runTest {
        val config = CoapServerConfig(amplification = AmplificationPolicy.OFF, qBlock = QBlockServerConfig(isEnabled = true, maxCachedBodies = 1))
        val f = fixture(config)
        val body = ByteArray(200) { it.toByte() }
        for (requestTag in listOf(tag, byteArrayOf(9))) {
            (0L..3L).forEach { f.peer.send(upload(f.peer, it, body = body, requestTag = requestTag)) }
            assertEquals(CoapCode.CHANGED, f.peer.drain().single().code)
        }
        assertEquals(listOf(Limit.CACHED_BODIES to f.peer.address), f.events.filterIsInstance<EndpointEvent.LimitReached>().map { it.limit to it.peer })

        f.peer.send(upload(f.peer, 3, body = body))
        assertEquals(emptyList(), f.peer.drain(), "the first upload's final response is no longer remembered")
        val ask = assertNotNull(f.peer.next(5.seconds), "the copy starts a body of its own")
        assertEquals(listOf(0L, 1L, 2L), MissingBlocks.decode(ask.payload))
        assertEquals(2, f.stored.size)
    }

    @Test
    fun rfc9175Section2_4ABurstToAnUnverifiedAddressWaitsForEchoButTheProbeDoesNot() = runTest {
        val f = fixture(CoapServerConfig(qBlock = qBlockOn))
        f.peer.send(get(f.peer, block(0, false, 16), path = ".well-known/core", type = CoapMessageType.CON))
        assertEquals(CoapCode.CONTENT, f.peer.next()?.code, "a single 16-byte block stays within 3 times the request")

        f.peer.send(get(f.peer, block(0, true)))
        val challenge = f.peer.drain().single()
        assertEquals(CoapCode.UNAUTHORIZED, challenge.code)
        assertEquals(CoapMessageType.NON, challenge.type)
        val echo = assertNotNull(challenge.options.echo)
        assertEquals(1, f.server.qBlock.state().servedBodies, "only the probe's body is kept, not the challenged one")

        f.peer.send(get(f.peer, block(0, true)) { echo(echo) })
        assertEquals((0L..9L).toList(), f.peer.drain().numbers())
        assertEquals(1, f.events.count { it is EndpointEvent.LimitReached && it.limit == Limit.AMPLIFICATION_FACTOR })
    }

    @Test
    fun rfc9175Section2_4LaterSetsCountWithTheFirstAgainstTheRequestUntilAContinueAsks() = runTest {
        // 124 bytes padded with Uri-Query ask for 16-byte blocks of 35 bytes: set 0 (350) fits 3 × 124, set 1 not too.
        val f = fixture(CoapServerConfig(qBlock = qBlockOn))
        val pad: OptionSet.Builder.() -> Unit = { uriQuery("p=" + "x".repeat(107)) }
        val request = f.peer.send(get(f.peer, block(0, true, 16), options = pad))
        val requestSize = UdpMessageCodec.encode(request).size
        assertEquals(124, requestSize)

        assertEquals((0L..9L).toList(), f.peer.drain().numbers(), "set 0 stays within the factor")
        assertEquals(emptyList(), f.peer.drain(30.seconds), "set 1 would take the request beyond it: no set by itself")
        val sent = f.serverSent().sumOf { UdpMessageCodec.encode(checkNotNull(it.message)).size }
        assertTrue(sent <= 3 * requestSize, "$sent bytes for a request of $requestSize")

        val proceed = f.peer.send(get(f.peer, block(10, true, 16), options = pad))
        val set = f.peer.drain()
        assertEquals((10L..19L).toList(), set.numbers(), "a Continue is a request of its own, within the factor too")
        assertTrue(set.all { it.hasToken(request.token) } && UdpMessageCodec.encode(proceed).size == requestSize)
        assertEquals(emptyList(), f.peer.drain(30.seconds), "and the sets still wait for the client")
        assertTrue(f.events.none { it is EndpointEvent.LimitReached }, "nothing was challenged")
    }

    @Test
    fun rfc9175Section2_4ASeparateConfirmableBlockCountsWithItsRetransmissions() = runTest {
        // Block 0 piggybacked and block 1 Confirmable, 35 bytes each, fit 3 × 40; with block 1's four
        // retransmissions to an address that never acknowledges (RFC 7252 §4.2) they do not.
        val f = fixture(CoapServerConfig(qBlock = qBlockOn))
        val request = f.peer.send(get(f.peer, block(0, true, 16), type = CoapMessageType.CON) { uriQuery("p=" + "x".repeat(23)) })
        assertEquals(40, UdpMessageCodec.encode(request).size)

        val answer = f.peer.drain().single()
        assertEquals(CoapCode.UNAUTHORIZED, answer.code)
        assertEquals(CoapMessageType.ACK, answer.type)
        assertNotNull(answer.options.echo)
        assertEquals(emptyList(), f.peer.drain(60.seconds), "no block, and nothing retransmitted")
    }

    @Test
    fun rfc9175Section2_4AnUnsafeUploadChallengedForItsBodyKeepsItsResponseForTheRepeat() = runTest {
        val f = fixture(CoapServerConfig(qBlock = qBlockOn))
        val requests = (0L..46L).map { f.peer.send(upload(f.peer, it, code = CoapCode.POST)) }
        val challenge = f.peer.drain().last()
        assertEquals(CoapCode.UNAUTHORIZED, challenge.code)

        f.peer.send(requests[46].toBuilder().apply { messageId = 0 }.options { echo(checkNotNull(challenge.options.echo)) }.build())
        val body = f.peer.drain()
        assertEquals((0L..9L).toList(), body.numbers())
        assertEquals(1, f.stored.size, "the handler ran once")
    }

    @Test
    fun rfc9177Section7_2NothingIsLeftAfterNonPartialTimeout() = runTest {
        val f = fixture()
        (0L..46L).forEach { f.peer.send(upload(f.peer, it)) }
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.send(upload(f.peer, 0, requestTag = byteArrayOf(7), type = CoapMessageType.CON))
        f.peer.drain(5.seconds)
        val busy = f.server.qBlock.state()
        assertEquals(1, busy.partialBodies)
        assertEquals(1, busy.servedBodies)
        assertEquals(1, busy.completedBodies)

        delay(TransmissionParameters.Default.nonPartialTimeout + 1.seconds)
        f.peer.drain()

        val state = f.server.qBlock.state()
        assertTrue(state.isEmpty, "$state")
        assertEquals("No block for NON_PARTIAL_TIMEOUT", f.events.filterIsInstance<EndpointEvent.PartialBodyDropped>().single().reason)
    }

    @Test
    fun warnsOnceAtStartWhenQBlockRunsForRequestsWithoutMessageSecurity() = runTest {
        val records = mutableListOf<LogRecord>()
        val stack = StackFixture(this)
        val loggerFactory = LoggerFactory(
            LogConfig.build {
                level = LogLevel.WARN
                sink = LogSink { records += it }
            },
        )
        suspend fun start(config: CoapServerConfig): List<String> {
            records.clear()
            stack.server(config, CoapEndpointConfig(timeSource = testScheduler.timeSource, loggerFactory = loggerFactory)) {}
            return records.filter { it.level == LogLevel.WARN }.map { it.message }
        }

        val noSecurity = start(on).single()
        assertTrue("RFC 9177 §11" in noSecurity && "on without message security" in noSecurity, noSecurity)
        // With OSCORE, requests without it are still answered unprotected, and may use Q-Block.
        val unprotected = start(CoapServerConfig(security = ToySecurity, qBlock = qBlockOn)).single()
        assertTrue("RFC 9177 §11" in unprotected && "also for requests without message security" in unprotected, unprotected)
        assertEquals(emptyList(), start(CoapServerConfig()))
        assertEquals(emptyList(), start(CoapServerConfig(security = ToySecurity)))
    }

    @Test
    fun rfc9177Section4_3ACopyOfTheLastBlockGetsTheSameResponseBodyAgain() = runTest {
        val f = fixture()
        (0L..46L).forEach { f.peer.send(upload(f.peer, it, code = CoapCode.POST)) }
        val first = f.peer.drain().drop(4)
        val copy = f.peer.send(upload(f.peer, 46, code = CoapCode.POST))

        val again = f.peer.drain()
        assertEquals((0L..9L).toList(), again.numbers())
        assertTrue(again.all { it.hasToken(copy.token) })
        assertContentEquals(first.first().options.etags.single(), again.first().options.etags.single(), "the same body, from the cache")
        assertEquals(1, f.stored.size)
    }

    @Test
    fun rfc9177Section4_6ABlockWhoseSize1DiffersFromItsBodysGets400() = runTest {
        val f = fixture()
        f.peer.send(upload(f.peer, 0))
        f.peer.send(upload(f.peer, 1) { size1(2999) })

        val answer = assertNotNull(f.peer.next())
        assertEquals(CoapCode.BAD_REQUEST, answer.code)
        assertEquals("Size1 2999 differs from 3000 of the body", answer.payloadAsText())
    }

    @Test
    fun rfc9177Section7_1AConfirmableBodyStopsWhenABlockIsNeverAcknowledgedAndStaysCached() = runTest {
        val f = fixture()
        f.peer.send(get(f.peer, block(0, true), type = CoapMessageType.CON))

        val seen = f.peer.drain(60.seconds)
        assertEquals(listOf(0L) + List(5) { 1L }, seen.numbers(), "block 1 and its four retransmissions, then nothing")
        val missing = f.peer.send(get(f.peer, block(5, false)))
        val five = f.peer.drain().single()
        assertTrue(five.hasToken(missing.token))
        assertContentEquals(seen.first().options.etags.single(), five.options.etags.single(), "served from the cache")
        assertEquals(1, f.bigRuns)
    }

    @Test
    fun rfc9175Section2_4ALaterSetToAnAddressNoLongerVerifiedDoesNotGoOut() = runTest {
        val f = fixture(CoapServerConfig(qBlock = qBlockOn, verifiedAddressLifetime = 1.seconds))
        f.peer.send(get(f.peer, block(0, true)))
        val echo = assertNotNull(f.peer.drain().single().options.echo)
        f.peer.send(get(f.peer, block(0, true)) { echo(echo) })
        assertEquals((0L..9L).toList(), f.peer.drain().numbers())

        assertEquals(emptyList(), f.peer.drain(10.seconds), "set 1 would be a burst of its own to an address verified too long ago")
    }

    @Test
    fun rfc9177Section4_4ABlockSizeThatDoesNotFitIsAnsweredWithTheWholeBodyInSmallerBlocks() = runTest {
        val f = fixture(maxDatagramSize = 600)
        f.peer.send(get(f.peer, block(2, false, 1024)))

        val blocks = f.peer.drain()
        assertEquals((0L..5L).toList(), blocks.numbers())
        assertTrue(blocks.all { it.options.qBlock2!!.size == 512 })
        assertContentEquals(big, blocks.fold(ByteArray(0)) { body, block -> body + block.payload })
    }

    @Test
    fun rfc7252Section9_1_1APeerWhoseSessionEndedLosesEveryBodyAlsoOnesStillSending() = runTest {
        val f = fixture()
        f.peer.send(upload(f.peer, 0))
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()
        assertEquals(1, f.server.qBlock.state().sendingBodies)

        f.server.qBlock.forget(f.peer.address)

        assertTrue(f.server.qBlock.state().isEmpty, "${f.server.qBlock.state()}")
        assertEquals(emptyList(), f.peer.drain(10.seconds), "no later set of either body")
        assertEquals("the session ended", f.events.filterIsInstance<EndpointEvent.PartialBodyDropped>().single().reason)
    }

    @Test
    fun closingTheServerDropsEveryBodyAndTimer() = runTest {
        val f = fixture()
        f.peer.send(upload(f.peer, 0))
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.send(get(f.peer, block(0, true)))
        f.peer.drain()
        assertEquals(1, f.server.qBlock.state().sendingBodies)

        f.server.close()

        assertTrue(f.server.qBlock.state().isEmpty, "${f.server.qBlock.state()}")
    }

    @Test
    fun theConfigurationRefusesEmptyBoundsAndNamesItsValues() {
        assertFailsWith<IllegalArgumentException> { QBlockServerConfig(maxPartialBodies = 0) }
        assertFailsWith<IllegalArgumentException> { QBlockServerConfig(maxCachedBodies = 0) }
        assertFailsWith<IllegalArgumentException> { QBlockServerConfig(maxBodiesPerPeer = 0) }
        assertEquals(
            "QBlockServerConfig(isEnabled=false, withMessageSecurity=false, onReliableTransports=true, " +
                "maxPartialBodies=64, maxCachedBodies=64, maxBodiesPerPeer=4)",
            QBlockServerConfig().toString(),
        )
        assertTrue("qBlock=true" in on.toString())
        val peer = Endpoint.Udp("192.0.2.1", 5683)
        assertEquals(
            "QBlockRecovery(peer=$peer, requestedByPeer=true, blocks=[2, 7])",
            EndpointEvent.QBlockRecovery(peer, true, listOf(2, 7)).toString(),
        )
        assertEquals("QBlockFallback(peer=$peer, reason=4.02)", EndpointEvent.QBlockFallback(peer, "4.02").toString())
        assertEquals("PartialBodyDropped(peer=$peer, reason=reset)", EndpointEvent.PartialBodyDropped(peer, "reset").toString())
    }

    private object Recipient : SecurityIdentity {
        override val description: String get() = "toy recipient"
    }

    /** Message security that only marks requests with an empty OSCORE option, enough to authenticate them. */
    private object ToySecurity : ServerMessageSecurity {
        override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
            if (request.options.oscore == null) return null
            return object : UnprotectedRequest {
                override val message = request.toBuilder().options { remove(CoapOptions.OSCORE.number) }.build()
                override val identity: SecurityIdentity get() = Recipient

                override suspend fun protectResponse(response: CoapMessage): CoapMessage = response
            }
        }
    }
}
