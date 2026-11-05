package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.ObserveCancellation
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.testkit.DatagramFate
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Observe with Q-Block (RFC 7641, RFC 9177 §4.5): kcoap's client observing kcoap's server, both with Q-Block on,
 * on the in-memory network and virtual time. The examples of RFC 9177 §10.2 and §10.3 (Figures 7 to 15) run
 * end to end with the figures' losses, and the wire must show their sequence: block numbers, M bits, tokens,
 * Observe values and ETags. Tokens are numbered in the order they appear (`T0`, `T1`, …), ETags likewise
 * (`E0`, `E1`, …); a lost message starts with `X`. Observe values count from 0, NON_TIMEOUT_RANDOM is the
 * server's draw and NON_RECEIVE_TIMEOUT 4 s.
 */
class QBlockObserveTest {
    private val parameters = TransmissionParameters.Default

    private fun body(size: Int, seed: Int): ByteArray = Random(seed).nextBytes(size)

    /** An observable resource whose representation is [bodies] of its current [version]; [change] notifies. */
    private class Resource(
        name: String,
        private val type: CoapMessageType,
        private val bodies: (Int) -> ByteArray,
    ) : ObservableResource(name) {
        var version = 0
            private set

        /** The FETCH payloads the handler saw. */
        val queries = mutableListOf<ByteArray>()

        override val notificationType: CoapMessageType get() = type

        override suspend fun handleGet(context: RequestContext): CoapResponse = CoapResponse.content(bodies(version), ContentFormat.OCTET_STREAM)

        override suspend fun handleFetch(context: RequestContext): CoapResponse {
            queries += context.payload
            return CoapResponse.content(bodies(version), ContentFormat.OCTET_STREAM)
        }

        fun change() {
            version++
            notifyObservers()
        }
    }

    private class Setup(val fixture: StackFixture, val server: CoapServer, val client: CoapClient, val resource: Resource) {
        val uri: String get() = fixture.uri(server, "/obs")

        /** Every notification the application got, in order. */
        val received = mutableListOf<CoapResponse>()

        private val tokens = HashMap<String, Int>()
        private val etags = HashMap<String, Int>()

        init {
            // ETags are numbered as they go on the wire, also those of messages a test does not look at.
            val tap = fixture.network.tap
            fixture.network.tap = { datagram, fate ->
                tap(datagram, fate)
                runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull()?.let { etagIndex(it) }
            }
        }

        /** The index of [message]'s ETag in the order ETags appear, or null without one. */
        fun etagIndex(message: CoapMessage): Int? =
            message.options.etags.singleOrNull()?.let { etag -> etags.getOrPut(etag.toList().toString()) { etags.size } }

        /** The wire as lines like the RFC's figures: `NON GET T0 Obs:0 QB2:0/1/1024`; `X` before a lost message. */
        fun lines(from: Int = 0, timed: Boolean = false): List<String> =
            fixture.traffic.drop(from).mapNotNull { wire -> wire.message?.let { line(wire, it, timed) } }

        private fun line(wire: StackFixture.Wire, message: CoapMessage, timed: Boolean): String = buildString {
            if (wire.fate != DatagramFate.DELIVERED) append("X ")
            if (timed) append(seconds(wire.at)).append(' ')
            append(if (wire.from == fixture.address(server.endpoint)) "S>C " else "C>S ")
            append(message.type).append(' ').append(if (message.isEmpty) "0.00" else codeName(message.code))
            if (message.token.isNotEmpty()) append(" T").append(tokens.getOrPut(message.tokenHex) { tokens.size })
            message.options.observe?.let { append(" Obs:").append(it) }
            message.options.qBlock1?.let { append(" QB1:").append(block(it)) }
            message.options.qBlock2List.forEach { append(" QB2:").append(block(it)) }
            etagIndex(message)?.let { append(" ETag:E").append(it) }
            if (message.options.contentFormat == ContentFormat.MISSING_BLOCKS_CBOR_SEQ) append(' ').append(MissingBlocks.decode(message.payload))
        }

        private fun block(option: BlockOption): String = "${option.number}/${if (option.more) 1 else 0}/${option.size}"

        private fun codeName(code: CoapCode): String =
            if (code.isRequest) listOf("GET", "POST", "PUT", "DELETE", "FETCH", "PATCH", "IPATCH")[code.detail - 1] else code.toString().take(4)

        private fun seconds(at: Duration): String {
            val millis = at.inWholeMilliseconds
            val fraction = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
            return if (fraction.isEmpty()) "${millis / 1000}s" else "${millis / 1000}.${fraction}s"
        }

        /**
         * Loses copy n of a block when [rule] says so: n counts the messages of one direction, ETag and block
         * number (Q-Block1 from the client, Q-Block2 from the server), from 1.
         */
        fun drop(rule: (message: CoapMessage, copy: Int) -> Boolean) {
            val copies = HashMap<String, Int>()
            fixture.network.filter = filter@{ datagram ->
                val message = runCatching { UdpMessageCodec.decode(datagram.bytes) }.getOrNull() ?: return@filter true
                val block = message.options.qBlock1 ?: message.options.qBlock2 ?: return@filter true
                if (message.isRequest && message.options.qBlock1 == null) return@filter true
                val key = "${message.isRequest} ${etagIndex(message)} ${block.number}"
                val copy = (copies[key] ?: 0) + 1
                copies[key] = copy
                !rule(message, copy)
            }
        }

        /** Starts observing; the job collects every notification into [received]. */
        fun observe(test: TestScope, request: CoapRequest = CoapRequest.Builder(CoapCode.GET, CoapUri.parse(uri)).nonConfirmable().build()): Job =
            test.backgroundScope.launch { client.observe(request).collect { received += it } }
    }

    private suspend fun TestScope.setup(
        bodies: (Int) -> ByteArray,
        type: CoapMessageType = CoapMessageType.NON,
        blockSize: Int = 1024,
        missingBlockMBit: Boolean = false,
        observeCancellation: ObserveCancellation = ObserveCancellation.DEREGISTER,
        seed: Int = 1,
        amplification: AmplificationPolicy = AmplificationPolicy.OFF,
    ): Setup {
        val fixture = StackFixture(this, seed)
        val resource = Resource("obs", type, bodies)
        val server = fixture.server(
            CoapServerConfig(amplification = amplification, qBlock = QBlockServerConfig(isEnabled = true)),
            fixture.endpointConfig(parameters = parameters),
        ) { add(resource) }
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, missingBlockMBit = missingBlockMBit)
        val client = fixture.client(
            CoapClientConfig(blockSize = blockSize, observeCancellation = observeCancellation, qBlock = qBlock),
            fixture.endpointConfig(parameters = parameters),
        )
        return Setup(fixture, server, client, resource)
    }

    private fun blocks(range: IntRange, line: (Int) -> String): List<String> = range.map(line)

    /** M of block [num] of a body of [count] blocks, as the figures write it. */
    private fun m(num: Int, count: Int): Int = if (num < count - 1) 1 else 0

    private fun fetch(setup: Setup, query: ByteArray): CoapRequest = CoapRequest.Builder(CoapCode.FETCH, CoapUri.parse(setup.uri)).apply {
        payload = query
        contentFormat(ContentFormat.OCTET_STREAM)
        nonConfirmable()
    }.build()

    /** Nothing is left once the observation ended and NON_PARTIAL_TIMEOUT passed: no exchange, lease, observer or body. */
    private suspend fun assertNothingLeft(setup: Setup) {
        delay(parameters.nonPartialTimeout + 1.seconds)
        assertEquals(0, setup.resource.observerCount, "observers left")
        assertEquals(0, setup.client.endpoint.exchangeCount(), "client exchanges left")
        assertEquals(0, setup.client.endpoint.leaseCount(), "client leases left")
        assertEquals(0, setup.server.endpoint.leaseCount(), "server leases left")
        val state = setup.server.qBlock.state()
        assertTrue(state.isEmpty, "server Q-Block state left: $state")
    }

    // ---------------------------------------------------------------- §10.2: Q-Block2 notifications

    @Test
    fun rfc9177Figure7ObserveSameOnEveryBlock() = runTest {
        val bodies = listOf(body(3 * 1024 + 300, 1), body(3 * 1024 + 300, 2))
        val setup = setup({ bodies[it] })
        val observation = setup.observe(this)
        delay(10.seconds)
        setup.resource.change()
        delay(10.seconds)

        assertEquals(
            listOf("C>S NON GET T0 Obs:0 QB2:0/1/1024") +
                blocks(0..3) { "S>C NON 2.05 T0 Obs:0 QB2:$it/${m(it, 4)}/1024 ETag:E0" } +
                blocks(0..3) { "S>C NON 2.05 T0 Obs:1 QB2:$it/${m(it, 4)}/1024 ETag:E1" },
            setup.lines(),
        )
        assertEquals(2, setup.received.size, "one emission per body")
        assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() })
        assertEquals(listOf(0L, 1L), setup.received.map { it.options.observe })
        assertTrue(setup.received.all { it.options.qBlock2 == null && it.options.size2 == null && it.options.etags.size == 1 })

        observation.cancel()
        delay(1.seconds)
        val deregistration = setup.fixture.traffic.mapNotNull { it.message }.last { it.isRequest }
        assertEquals(1L, deregistration.options.observe, "RFC 7641 §3.6: GET with Observe 1")
        assertEquals(setup.fixture.traffic.first().message!!.tokenHex, deregistration.tokenHex, "on the observation's token")
        assertNothingLeft(setup)
    }

    @Test
    fun rfc9177Figure8ContinueOnOriginalToken() = runTest {
        val bodies = listOf(body(10 * 1024 + 10, 1), body(10 * 1024 + 10, 2))
        val setup = setup({ bodies[it] })
        setup.observe(this)
        delay(10.seconds)
        setup.resource.change()
        delay(10.seconds)

        assertEquals(
            listOf("0s C>S NON GET T0 Obs:0 QB2:0/1/1024") +
                blocks(0..9) { "0s S>C NON 2.05 T0 Obs:0 QB2:$it/1/1024 ETag:E0" } +
                listOf("0s C>S NON GET T1 QB2:10/1/1024", "0s S>C NON 2.05 T0 Obs:0 QB2:10/0/1024 ETag:E0") +
                blocks(0..9) { "10s S>C NON 2.05 T0 Obs:1 QB2:$it/1/1024 ETag:E1" } +
                listOf("10s C>S NON GET T2 QB2:10/1/1024", "10s S>C NON 2.05 T0 Obs:1 QB2:10/0/1024 ETag:E1"),
            setup.lines(timed = true),
            "§4.4, §7.2: the Continue carries no Observe and gets nothing on its own token; the next set follows at once",
        )
        assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() })
    }

    @Test
    fun rfc9177Figure9MissingAfterLaterSet() = runTest {
        val notification = body(10 * 1024 + 10, 2)
        val setup = setup({ if (it == 0) body(1024 + 10, 1) else notification })
        setup.observe(this)
        delay(10.seconds)
        val start = setup.fixture.traffic.size
        setup.drop { message, copy ->
            val num = message.options.qBlock2?.number
            !message.isRequest && ((num == 1L && copy <= 2) || (num == 9L && copy == 1))
        }

        setup.resource.change()
        delay(30.seconds)

        val lines = setup.lines(start, timed = true)
        val set1At = lines.single { "QB2:10/0" in it }.substringBefore('s').toDouble()
        assertTrue(set1At in 12.0..13.0, "§7.2: block 10 after NON_TIMEOUT_RANDOM: $set1At")
        val t = lines.single { "QB2:10/0" in it }.substringBefore(' ')
        assertEquals(
            listOf("10s S>C NON 2.05 T0 Obs:1 QB2:0/1/1024 ETag:E1", "X 10s S>C NON 2.05 T0 Obs:1 QB2:1/1/1024 ETag:E1") +
                blocks(2..8) { "10s S>C NON 2.05 T0 Obs:1 QB2:$it/1/1024 ETag:E1" } + listOf(
                    "X 10s S>C NON 2.05 T0 Obs:1 QB2:9/1/1024 ETag:E1",
                    "$t S>C NON 2.05 T0 Obs:1 QB2:10/0/1024 ETag:E1",
                    "$t C>S NON GET T1 QB2:1/0/1024 QB2:9/0/1024",
                    "X $t S>C NON 2.05 T1 QB2:1/1/1024 ETag:E1",
                    "$t S>C NON 2.05 T1 QB2:9/1/1024 ETag:E1",
                    "${seconds(set1At + 4)} C>S NON GET T2 QB2:1/0/1024",
                    "${seconds(set1At + 4)} S>C NON 2.05 T2 QB2:1/1/1024 ETag:E1",
                ),
            lines,
            "missing blocks asked for at once on a later set, the rest after NON_RECEIVE_TIMEOUT; no Observe on them",
        )
        assertContentEquals(notification, setup.received.last().payload)
        assertEquals(1L, setup.received.last().options.observe, "the Observe of the body, though block 1 came without")
        assertEquals(2, setup.received.size)
    }

    @Test
    fun rfc9177Figure10MBitRecoveryAndResentSet() = runTest {
        val notification = body(10 * 1024 + 10, 2)
        val setup = setup({ if (it == 0) body(1024 + 10, 1) else notification }, missingBlockMBit = true)
        setup.observe(this)
        delay(10.seconds)
        val start = setup.fixture.traffic.size
        setup.drop { message, copy -> !message.isRequest && message.options.qBlock2!!.number in 2L..10L && copy == 1 }

        setup.resource.change()
        delay(30.seconds)

        val lines = setup.lines(start, timed = true)
        val t = lines.first { "QB2:10/0" in it }.removePrefix("X ").substringBefore(' ')
        assertEquals(
            listOf("10s S>C NON 2.05 T0 Obs:1 QB2:0/1/1024 ETag:E1", "10s S>C NON 2.05 T0 Obs:1 QB2:1/1/1024 ETag:E1") +
                blocks(2..9) { "X 10s S>C NON 2.05 T0 Obs:1 QB2:$it/1/1024 ETag:E1" } +
                listOf("X $t S>C NON 2.05 T0 Obs:1 QB2:10/0/1024 ETag:E1", "14s C>S NON GET T1 QB2:2/1/1024") +
                blocks(2..9) { "14s S>C NON 2.05 T1 QB2:$it/1/1024 ETag:E1" } +
                listOf("14s C>S NON GET T2 QB2:10/1/1024", "14s S>C NON 2.05 T0 Obs:1 QB2:10/0/1024 ETag:E1"),
            lines,
            "§4.4: 2/M=1 asks for the rest of set 0, answered without Observe; the Continue sends block 10 again on T0",
        )
        assertContentEquals(notification, setup.received.last().payload)
    }

    // ---------------------------------------------------------------- §10.3: Q-Block1 and Q-Block2 together

    @Test
    fun rfc9177Figure11FetchObserveToken() = runTest {
        val query = body(2 * 1024 + 100, 1)
        val bodies = List(3) { body(3 * 1024 + 300, it + 2) }
        val setup = setup({ bodies[it] })
        val observation = setup.observe(this, fetch(setup, query))
        delay(10.seconds)
        assertEquals(1, setup.client.endpoint.exchangeCount(), "only the observation's token is kept")
        setup.resource.change()
        delay(10.seconds)

        assertEquals(
            blocks(0..2) { "C>S NON FETCH T$it Obs:0 QB1:$it/${m(it, 3)}/1024" } +
                blocks(0..3) { "S>C NON 2.05 T2 Obs:0 QB2:$it/${m(it, 4)}/1024 ETag:E0" } +
                blocks(0..3) { "S>C NON 2.05 T2 Obs:1 QB2:$it/${m(it, 4)}/1024 ETag:E1" },
            setup.lines(),
            "Figure 11: the 2.05 goes on the token of the block that completed the upload, the observation's token",
        )
        assertEquals(listOf(query.toList(), query.toList()), setup.resource.queries.map { it.toList() }, "every notification answers the query")
        assertEquals(bodies.take(2).map { it.toList() }, setup.received.map { it.payload.toList() })

        observation.cancel()
        delay(1.seconds)
        assertEquals(0, setup.client.endpoint.exchangeCount())
        assertEquals(3, setup.fixture.traffic.mapNotNull { it.message }.count { it.isRequest }, "no deregistration: the token is forgotten")
        setup.resource.change()
        delay(10.seconds)
        val resets = setup.fixture.sentBy(setup.client.endpoint).filter { it.type == CoapMessageType.RST }
        val blocks = setup.fixture.sentBy(setup.server.endpoint).filter { it.options.observe == 2L }
        assertEquals(blocks.map { it.messageId }, resets.map { it.messageId }, "RFC 7641 §3.6: the next notification gets Resets")
        assertEquals(0, setup.resource.observerCount)
        assertNothingLeft(setup)
    }

    @Test
    fun rfc9177Figure12ContinueWithoutRequestTag() = runTest {
        val query = body(10 * 1024 + 10, 1)
        val bodies = listOf(body(10 * 1024 + 20, 2), body(10 * 1024 + 20, 3))
        val setup = setup({ bodies[it] })
        setup.observe(this, fetch(setup, query))
        delay(10.seconds)
        setup.resource.change()
        delay(10.seconds)

        assertEquals(
            blocks(0..9) { "C>S NON FETCH T$it Obs:0 QB1:$it/1/1024" } + listOf(
                "S>C NON 2.31 T9 QB1:9/1/1024",
                "C>S NON FETCH T10 Obs:0 QB1:10/0/1024",
            ) + blocks(0..9) { "S>C NON 2.05 T10 Obs:0 QB2:$it/1/1024 ETag:E0" } + listOf(
                "C>S NON FETCH T11 QB2:10/1/1024",
                "S>C NON 2.05 T10 Obs:0 QB2:10/0/1024 ETag:E0",
            ) + blocks(0..9) { "S>C NON 2.05 T10 Obs:1 QB2:$it/1/1024 ETag:E1" } + listOf(
                "C>S NON FETCH T12 QB2:10/1/1024",
                "S>C NON 2.05 T10 Obs:1 QB2:10/0/1024 ETag:E1",
            ),
            setup.lines(),
            "Figure 12: the Continues go on new tokens, the sets on the observation's token T10",
        )
        val continues = setup.fixture.sentBy(setup.client.endpoint).filter { it.options.qBlock2 != null }
        assertTrue(continues.all { it.payloadSize == 0 && it.options.qBlock1 == null && it.options.observe == null })
        assertTrue(continues.all { it.options.requestTags.single().contentEquals(continues[0].options.requestTags.single()) }, "§4.7: with the upload's Request-Tag")
        assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() })
    }

    /**
     * Figure 12 as drawn, with a client that leaves the Request-Tag out of its Continue: the server continues the
     * one body the Continue fits. (The refusal when two bodies fit is `QBlockServerTest`'s.)
     */
    @Test
    fun rfc9177Figure12AContinueWithoutRequestTagContinuesTheOnlyBody() = runTest {
        val query = body(10 * 1024 + 10, 1)
        val bodies = listOf(body(10 * 1024 + 20, 2), body(10 * 1024 + 20, 3))
        val setup = setup({ bodies[it] })
        val peer = QBlockPeer.of(setup.fixture, setup.server)
        val tag = byteArrayOf(0x10)
        fun block(num: Long, token: ByteArray) = CoapMessage.request(CoapCode.FETCH) {
            type = CoapMessageType.NON
            this.token = token
            payload = query.copyOfRange((num * 1024).toInt(), minOf(query.size, (num * 1024 + 1024).toInt()))
            options {
                uriPath("obs")
                observe(0)
                requestTag(tag)
                size1(query.size.toLong())
                qBlock1(BlockOption(num, num < 10, 6))
            }
        }
        val tokens = (0L..10L).map { peer.token() }
        (0L..9L).forEach { peer.send(block(it, tokens[it.toInt()])) }
        assertEquals(CoapCode.CONTINUE, peer.next()!!.code)
        peer.send(block(10, tokens[10]))
        val set0 = List(10) { peer.next()!! }
        assertTrue(set0.all { it.hasToken(tokens[10]) && it.options.observe == 0L })

        peer.send(
            CoapMessage.request(CoapCode.FETCH) {
                type = CoapMessageType.NON
                token = peer.token()
                options {
                    uriPath("obs")
                    qBlock2(BlockOption(10, true, 6))
                }
            },
        )

        val continued = peer.next()!!
        assertEquals(10L, continued.options.qBlock2!!.number)
        assertTrue(continued.hasToken(tokens[10]), "on the observation's token")
        assertEquals(0L, continued.options.observe)
        assertEquals(1, setup.resource.observerCount)
    }

    /**
     * Figures 13, 14 and 15. Two differences from the figures, both allowed: kcoap's client sends the last block
     * of a FETCH again after NON_RECEIVE_TIMEOUT without an answer (ADR 0006), here at the moment the server's 4.08
     * goes out; and Figure 14 sends the response body on T:c3, the token of the block that ended the body, where
     * kcoap answers on the request that completed it, T6 here, as §4.3 equally allows. The client takes that token
     * for the observation.
     */
    @Test
    fun rfc9177Figure13To15LossBothWays() = runTest {
        val query = body(3 * 1024 + 50, 1)
        val bodies = listOf(body(3 * 1024 + 300, 2), body(2 * 1024 + 10, 3))
        val setup = setup({ bodies[it] })
        setup.drop { message, copy ->
            val num = (message.options.qBlock1 ?: message.options.qBlock2)!!.number
            when {
                message.isRequest -> num in setOf(1L, 2L) && copy == 1
                setup.etagIndex(message) == 0 -> (num == 1L && copy <= 2) || (num == 3L && copy == 1)
                else -> num == 1L && copy == 1
            }
        }
        setup.observe(this, fetch(setup, query))
        delay(20.seconds)
        setup.resource.change()
        delay(20.seconds)

        assertEquals(
            listOf(
                "0s C>S NON FETCH T0 Obs:0 QB1:0/1/1024",
                "X 0s C>S NON FETCH T1 Obs:0 QB1:1/1/1024",
                "X 0s C>S NON FETCH T2 Obs:0 QB1:2/1/1024",
                "0s C>S NON FETCH T3 Obs:0 QB1:3/0/1024",
                "4s S>C NON 4.08 T3 [1, 2]",
                "4s C>S NON FETCH T4 Obs:0 QB1:3/0/1024",
                "4s C>S NON FETCH T5 Obs:0 QB1:1/1/1024",
                "4s C>S NON FETCH T6 Obs:0 QB1:2/1/1024",
                "4s S>C NON 2.05 T6 Obs:0 QB2:0/1/1024 ETag:E0",
                "X 4s S>C NON 2.05 T6 Obs:0 QB2:1/1/1024 ETag:E0",
                "4s S>C NON 2.05 T6 Obs:0 QB2:2/1/1024 ETag:E0",
                "X 4s S>C NON 2.05 T6 Obs:0 QB2:3/0/1024 ETag:E0",
                "8s C>S NON FETCH T7 QB2:1/0/1024 QB2:3/0/1024",
                "X 8s S>C NON 2.05 T7 QB2:1/1/1024 ETag:E0",
                "8s S>C NON 2.05 T7 QB2:3/0/1024 ETag:E0",
                "12s C>S NON FETCH T8 QB2:1/0/1024",
                "12s S>C NON 2.05 T8 QB2:1/1/1024 ETag:E0",
                "20s S>C NON 2.05 T6 Obs:1 QB2:0/1/1024 ETag:E1",
                "X 20s S>C NON 2.05 T6 Obs:1 QB2:1/1/1024 ETag:E1",
                "20s S>C NON 2.05 T6 Obs:1 QB2:2/0/1024 ETag:E1",
                "24s C>S NON FETCH T9 QB2:1/0/1024",
                "24s S>C NON 2.05 T9 QB2:1/1/1024 ETag:E1",
            ),
            setup.lines(timed = true),
            "the lost last block 3 is known from Size2 and asked for with block 1",
        )
        assertContentEquals(query, setup.resource.queries.first())
        assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() })
        // §4.7, Figure 15: follow-ups repeat the method and Request-Tag, without Q-Block1, payload and Observe.
        val followUps = setup.fixture.sentBy(setup.client.endpoint).filter { it.options.qBlock2 != null }
        assertTrue(followUps.all { it.code == CoapCode.FETCH && it.options.requestTags.isNotEmpty() && it.payloadSize == 0 })
        assertTrue(followUps.all { it.options.qBlock1 == null && it.options.observe == null })
        // Figure 15 gives two messages Message ID 0xa7; every message here has its own.
        val serverIds = setup.fixture.sentBy(setup.server.endpoint).map { it.messageId }
        assertEquals(serverIds.size, serverIds.toSet().size)
    }

    /**
     * RFC 9177 §4.3: the 2.05 of a FETCH registration uploaded in Q-Block1 is lost, one block of it or the whole of
     * its one set. The client sends the last block again on a new token after NON_RECEIVE_TIMEOUT; the server sends
     * the 2.05 again on the token of the block that completed the upload, which its notifications use ("the same
     * token as used for the 2.05"), and nothing on the copy's token. The client keeps that token, and the
     * observation goes on.
     */
    @Test
    fun rfc9177Section4_3ALostRegistrationResponseComesAgainOnTheTokenOfTheNotifications() = runTest {
        for (blocks in listOf(1, 3)) {
            val name = "a 2.05 of $blocks blocks"
            val query = body(2 * 1024 + 100, 1)
            val bodies = listOf(body(blocks * 1024 - 10, 2), body(3 * 1024 + 300, 3))
            val setup = setup({ bodies[it] })
            setup.drop { message, copy -> !message.isRequest && setup.etagIndex(message) == 0 && copy == 1 }
            setup.observe(this, fetch(setup, query))
            delay(10.seconds)
            setup.resource.change()
            delay(10.seconds)

            assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() }, name)
            assertEquals(1, setup.resource.observerCount, name)
            val sent = setup.fixture.sentBy(setup.client.endpoint)
            assertTrue(sent.none { it.type == CoapMessageType.RST }, "$name: no Reset")
            val uploads = sent.filter { it.options.qBlock1 != null }
            assertEquals(listOf(0L, 1L, 2L, 2L), uploads.map { it.options.qBlock1!!.number }, "$name: block 2 again")
            val (completing, copy) = uploads[2] to uploads[3]
            val answers = setup.fixture.sentBy(setup.server.endpoint)
            assertTrue(answers.none { it.hasToken(copy.token) }, "$name: nothing on the copy's token")
            val observed = answers.filter { it.options.observe != null }
            assertTrue(
                observed.isNotEmpty() && observed.all { it.hasToken(completing.token) },
                "$name: §4.3, one token for the 2.05 and the notifications",
            )
            assertEquals(2, setup.resource.queries.size, "$name: the handler ran once for the registration")
            assertEquals(1, setup.client.endpoint.exchangeCount(), "$name: only the observation's token is kept")
        }
    }

    // ---------------------------------------------------------------- acceptance of QB-6

    /**
     * RFC 9177 §4.5 with RFC 7641 §4.5: a resource that changes every second while each of its bodies takes 24
     * blocks. A body is never cut short by the next change; changes fold into the next body, so the client gets
     * whole bodies all along, each one emission, and the latest state at the end.
     */
    @Test
    fun rfc9177Section4_5AResourceThatChangesEverySecondStillDeliversWholeBodies() = runTest {
        val setup = setup({ body(24 * 64 - 10, it) }, blockSize = 64)
        setup.observe(this)
        delay(5.seconds)
        repeat(60) {
            setup.resource.change()
            delay(1.seconds)
        }
        delay(20.seconds)

        val versions = (0..setup.resource.version).associateBy { body(24 * 64 - 10, it).toList() }
        val delivered = setup.received.mapNotNull { versions[it.payload.toList()] }
        assertEquals(setup.received.size, delivered.size, "only whole bodies of some version")
        assertEquals(delivered.sorted(), delivered, "in order")
        assertEquals(delivered.distinct(), delivered, "one emission per body")
        assertTrue(delivered.size >= 15, "a body at least every few seconds: ${delivered.size} of 61 versions")
        assertEquals(setup.resource.version, delivered.last(), "the latest state arrives (RFC 7641 §4.5)")
        val etags = setup.fixture.sentBy(setup.server.endpoint).filter { it.options.observe != null }.map { it.options.etags.single().toList() }.distinct()
        assertEquals(delivered.size, etags.size, "every body that went out arrived whole")
    }

    /** The same under 10 % loss each way: bodies with losses are recovered, and only whole ones are emitted. */
    @Test
    fun rfc9177Section4_5AResourceThatChangesEverySecondDeliversWholeBodiesUnderLoss() = runTest(timeout = 2.minutes) {
        val setup = setup({ body(24 * 64 - 10, it) }, blockSize = 64, seed = 3)
        setup.observe(this)
        delay(10.seconds)
        setup.fixture.network.conditions = LinkConditions(lossRate = 0.1)
        repeat(60) {
            setup.resource.change()
            delay(1.seconds)
        }
        setup.fixture.network.conditions = LinkConditions.Perfect
        setup.resource.change()
        delay(30.seconds)

        val versions = (0..setup.resource.version).associateBy { body(24 * 64 - 10, it).toList() }
        val delivered = setup.received.mapNotNull { versions[it.payload.toList()] }
        assertEquals(setup.received.size, delivered.size, "only whole bodies of some version")
        assertEquals(delivered.sorted(), delivered, "in order")
        // A request for the whole body after a lost block 0 runs the handler again, a body of its own (§4.4).
        val etags = setup.received.map { it.options.etags.single().toList() }
        assertEquals(etags.distinct(), etags, "one emission per body")
        assertTrue(delivered.size >= 5, "${delivered.size} whole bodies")
        assertEquals(setup.resource.version, delivered.last(), "the latest state arrives")
        assertTrue(setup.fixture.events.any { it is EndpointEvent.QBlockRecovery && !it.isRequestedByPeer }, "lost blocks were asked for")
    }

    /** RFC 7641 §3.6, RFC 9177 §4.5: every block is a notification, and a Reset to any of them removes the observer. */
    @Test
    fun rfc7641Section3_6AResetToAnyBlockRemovesTheObserver() = runTest {
        for (reset in listOf(0, 4, 12, 23)) {
            val setup = setup({ body(24 * 64 - 10, it) }, blockSize = 64)
            val peer = QBlockPeer.of(setup.fixture, setup.server)
            peer.send(
                CoapMessage.request(CoapCode.GET) {
                    type = CoapMessageType.NON
                    token = peer.token()
                    options {
                        uriPath("obs")
                        observe(0)
                        qBlock2(BlockOption(0, true, 2))
                    }
                },
            )
            val registration = peer.drain(5.seconds)
            assertEquals(24, registration.size, "the registration body in three sets")
            assertEquals(1, setup.resource.observerCount)

            setup.resource.change()
            val notification = mutableListOf<CoapMessage>()
            while (notification.none { it.options.qBlock2!!.number == reset.toLong() }) notification += peer.next()!!
            peer.reset(notification.last())
            delay(100.milliseconds)

            assertEquals(0, setup.resource.observerCount, "a Reset to block $reset removes the observer")
            val later = peer.drain(10.seconds)
            assertTrue(later.all { it.options.qBlock2!!.number <= (reset / 10) * 10 + 9 }, "no later set of the body follows: $later")
            setup.resource.change()
            assertNull(peer.next(10.seconds), "no more notifications")
            assertTrue(setup.fixture.events.any { it is EndpointEvent.ObserverRemoved && it.reason == "reset by client" })
            assertNothingLeft(setup)
        }
    }

    /**
     * The registration response is the first notification (RFC 7641 §3.2): a Reset to a block of it, of set 0 or
     * of a set sent for a Continue on the registration's token, removes the observer, and no later set follows.
     */
    @Test
    fun rfc7641Section3_6AResetToABlockOfTheRegistrationResponseRemovesTheObserver() = runTest {
        for (reset in listOf(4L, 12L)) {
            val setup = setup({ body(24 * 64 - 10, it) }, blockSize = 64)
            val peer = QBlockPeer.of(setup.fixture, setup.server)
            fun get(token: ByteArray, block: BlockOption, observe: Long?) = CoapMessage.request(CoapCode.GET) {
                type = CoapMessageType.NON
                this.token = token
                options {
                    uriPath("obs")
                    observe?.let { observe(it) }
                    qBlock2(block)
                }
            }
            val registration = peer.send(get(peer.token(), BlockOption(0, true, 2), observe = 0))
            val blocks = peer.drain().toMutableList()
            if (reset >= 10) {
                peer.send(get(peer.token(), BlockOption(10, true, 2), observe = null))
                blocks += peer.drain()
            }
            val target = blocks.single { it.options.qBlock2!!.number == reset }
            assertTrue(target.hasToken(registration.token) && target.options.observe == 0L, "of the registration")
            peer.reset(target)
            delay(100.milliseconds)

            assertEquals(0, setup.resource.observerCount, "a Reset to block $reset removes the observer")
            assertEquals(emptyList(), peer.drain(10.seconds), "no later set of the body follows")
            setup.resource.change()
            assertNull(peer.next(10.seconds), "no notification")
            val removed = setup.fixture.events.filterIsInstance<EndpointEvent.ObserverRemoved>()
            assertEquals(listOf("reset by client"), removed.map { it.reason })
            assertNothingLeft(setup)
        }
    }

    /** RFC 9177 §4.5, Figure 18: Confirmable notification bodies, every block acknowledged, Observe on each. */
    @Test
    fun rfc9177Figure18ConfirmableNotificationBodies() = runTest {
        val bodies = listOf(body(3 * 1024 + 300, 1), body(3 * 1024 + 300, 2))
        val setup = setup({ bodies[it] }, type = CoapMessageType.CON)
        setup.observe(this, CoapRequest.Builder(CoapCode.GET, CoapUri.parse(setup.uri)).build())
        delay(10.seconds)
        setup.resource.change()
        delay(10.seconds)

        val ack = "C>S ACK 0.00"
        assertEquals(
            listOf("C>S CON GET T0 Obs:0 QB2:0/1/1024", "S>C ACK 2.05 T0 Obs:0 QB2:0/1/1024 ETag:E0") +
                (1..3).flatMap { listOf("S>C CON 2.05 T0 Obs:0 QB2:$it/${m(it, 4)}/1024 ETag:E0", ack) } +
                (0..3).flatMap { listOf("S>C CON 2.05 T0 Obs:1 QB2:$it/${m(it, 4)}/1024 ETag:E1", ack) },
            setup.lines(),
            "block 0 piggybacked; NSTART 1: each Confirmable block waits for the previous ACK",
        )
        assertEquals(bodies.map { it.toList() }, setup.received.map { it.payload.toList() })
    }

    /**
     * RFC 7641 §4.5: a Confirmable notification block that is never acknowledged removes the observer. The client
     * still completes that body after MAX_TRANSMIT_SPAN, asking for the missing blocks (RFC 9177 §4.4).
     */
    @Test
    fun rfc7641Section4_5AnUnacknowledgedConfirmableBlockRemovesTheObserver() = runTest {
        val setup = setup({ body(3 * 1024 + 300, it) }, type = CoapMessageType.CON)
        setup.observe(this, CoapRequest.Builder(CoapCode.GET, CoapUri.parse(setup.uri)).build())
        delay(10.seconds)
        setup.drop { message, _ -> !message.isRequest && message.options.qBlock2!!.number == 2L && message.options.observe == 1L }
        setup.resource.change()
        delay(5.minutes)

        assertEquals(0, setup.resource.observerCount)
        assertTrue(setup.fixture.events.any { it is EndpointEvent.ObserverRemoved && it.reason == "notification not acknowledged" })
        val asked = setup.fixture.sentBy(setup.client.endpoint).first { it.options.qBlock2 != null && it.options.observe == null }
        assertEquals(listOf(2L, 3L), asked.options.qBlock2List.map { it.number }, "the blocks the server gave up on")
        assertTrue(setup.fixture.traffic.first { it.message == asked }.at >= 10.seconds + parameters.maxTransmitSpan)
        assertEquals(2, setup.received.size)
    }

    // ---------------------------------------------------------------- ending an observation

    @Test
    fun rfc7641Section3_6AForgottenObservationIsResetAndLeavesNothing() = runTest {
        val setup = setup({ body(3 * 1024 + 300, it) }, observeCancellation = ObserveCancellation.FORGET)
        val observation = setup.observe(this)
        delay(10.seconds)
        observation.cancel()
        delay(1.seconds)
        setup.resource.change()
        delay(10.seconds)

        assertEquals(1, setup.fixture.traffic.mapNotNull { it.message }.count { it.isRequest }, "no deregistration")
        assertEquals(0, setup.resource.observerCount, "the Reset to the next notification removed the observer")
        assertNothingLeft(setup)
    }

    /** RFC 7641 §3.2: the resource goes away; the 4.04 ends the observation, and the flow completes with it. */
    @Test
    fun rfc7641Section3_2ARemovedResourceEndsTheObservationWith404() = runTest {
        val setup = setup({ body(3 * 1024 + 300, it) })
        setup.observe(this)
        delay(10.seconds)
        setup.server.root.remove("obs")
        delay(10.seconds)

        assertEquals(listOf(CoapCode.CONTENT, CoapCode.NOT_FOUND), setup.received.map { it.code })
        assertEquals(0, setup.client.observationCount())
        assertNothingLeft(setup)
    }

    // ---------------------------------------------------------------- the address check (RFC 9175 §2.4)

    /**
     * RFC 9175 §2.4 item 3, RFC 7641 §7: under the default policy, and under ALL_RESPONSES, a registration with
     * Q-Block, a GET with Q-Block2 or a FETCH uploaded in Q-Block1, gets a 4.01 with Echo before anything else,
     * however small its first response: its notification bodies would go to the address for as long as it lasts.
     * The client repeats it with the Echo value, and a later body of 10 KiB goes to the verified address.
     */
    @Test
    fun rfc9175Section2_4AQBlockRegistrationVerifiesItsAddressBeforeAnyBody() = runTest {
        val large = body(10 * 1024, 2)
        for (policy in listOf(AmplificationPolicy.Q_BLOCK_BURSTS, AmplificationPolicy.ALL_RESPONSES)) {
            for (isFetch in listOf(false, true)) {
                val name = "$policy, ${if (isFetch) "FETCH in Q-Block1" else "GET"}"
                val setup = setup({ if (it == 0) "small".encodeToByteArray() else large }, amplification = policy)
                val get = CoapRequest.Builder(CoapCode.GET, CoapUri.parse(setup.uri)).nonConfirmable().build()
                setup.observe(this, if (isFetch) fetch(setup, body(2 * 1024 + 100, 1)) else get)
                delay(20.seconds)
                setup.resource.change()
                delay(10.seconds)

                val server = setup.fixture.address(setup.server.endpoint)
                val wire = setup.fixture.traffic.filter { it.message != null }
                val verified = wire.indexOfFirst { it.from != server && it.message!!.options.echo != null }
                assertTrue(verified > 0, "$name: the client returned the Echo value")
                val before = wire.take(verified).filter { it.from == server }.map { it.message!! }
                val challenges = before.isNotEmpty() && before.all { it.code == CoapCode.UNAUTHORIZED }
                assertTrue(challenges, "$name: nothing but 4.01s before the Echo came back: $before")
                val expected = listOf("small".encodeToByteArray(), large).map { it.toList() }
                assertEquals(expected, setup.received.map { it.payload.toList() }, name)
                assertEquals(1, setup.resource.observerCount, name)
                val notification = setup.fixture.sentBy(setup.server.endpoint).filter { it.options.observe == 1L }
                val isNon = notification.isNotEmpty() && notification.all { it.type == CoapMessageType.NON }
                assertTrue(isNon, "$name: Non-confirmable to a verified address")
            }
        }
    }

    // ---------------------------------------------------------------- the client against a scripted server

    /**
     * The client observing a [QBlockTestServer] that answers nothing by itself: the test sends every block. The
     * registration and every later request arrive in [requests].
     */
    private class Scripted(val fixture: StackFixture, val server: QBlockTestServer, val client: CoapClient) {
        val requests = Channel<Pair<Endpoint, CoapMessage>>(Channel.UNLIMITED)
        val received = mutableListOf<CoapResponse>()
        var failure: Throwable? = null
        var completed = false
        private var messageId = 0x7000

        /** Starts observing `/obs`, Non-confirmable; the job collects into [received] and records how it ended. */
        fun observe(test: TestScope): Job = test.backgroundScope.launch {
            try {
                client.observe(server.uri("/obs")) { nonConfirmable() }.collect { received += it }
                completed = true
            } catch (e: CoapException) {
                failure = e
            }
        }

        /** Block [num] of [body] in blocks of 64 bytes, as a Non-confirmable response on [token]. */
        fun block(
            token: ByteArray,
            num: Long,
            body: ByteArray,
            etag: Int?,
            observe: Long?,
            size2: Long? = body.size.toLong(),
            code: CoapCode = CoapCode.CONTENT,
        ): CoapMessage = CoapMessage.build {
            type = CoapMessageType.NON
            this.code = code
            messageId = this@Scripted.messageId++
            this.token = token
            val start = (num * 64).toInt()
            payload = body.copyOfRange(start, minOf(body.size, start + 64))
            options {
                observe?.let { observe(it) }
                etag?.let { etag(byteArrayOf(it.toByte())) }
                size2?.let { size2(it) }
                qBlock2(BlockOption(num, start + 64 < body.size, 2))
            }
        }

        /** A Non-confirmable response without Q-Block2 on [token]. */
        fun plain(token: ByteArray, observe: Long?, payload: String, code: CoapCode = CoapCode.CONTENT): CoapMessage = CoapMessage.build {
            type = CoapMessageType.NON
            this.code = code
            messageId = this@Scripted.messageId++
            this.token = token
            payload(payload)
            options { observe?.let { observe(it) } }
        }

        /** Sends every block of [body]. */
        suspend fun sendBody(peer: Endpoint, token: ByteArray, body: ByteArray, etag: Int, observe: Long?) {
            for (num in 0L until (body.size + 63L) / 64) server.send(peer, block(token, num, body, etag, observe))
        }
    }

    private suspend fun TestScope.scripted(maxResponseBodySize: Int = 1 shl 20): Scripted {
        val fixture = StackFixture(this)
        lateinit var scripted: Scripted
        val server = QBlockTestServer(fixture.transport(), backgroundScope, { fixture.now }, parameters)
        val client = fixture.client(
            CoapClientConfig(blockSize = 64, maxResponseBodySize = maxResponseBodySize, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)),
            fixture.endpointConfig(parameters = parameters),
        )
        scripted = Scripted(fixture, server, client)
        server.script = { from, request ->
            scripted.requests.send(from to request)
            true
        }
        return scripted
    }

    @Test
    fun rfc7641Section3_4AStaleBodyAndLateBlocksOfFinishedBodiesAreIgnored() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        val token = registration.token
        val bodies = List(6) { body(128, it) }
        suspend fun send(body: Int, num: Long, observe: Long?) = s.server.send(peer, s.block(token, num, bodies[body], body, observe))

        send(0, 0, 5)
        send(0, 1, 5)
        send(1, 0, 7)
        send(2, 0, 6)
        send(2, 1, 6)
        send(1, 1, 7)
        send(0, 1, 5)
        send(5, 0, null)
        send(3, 0, 8)
        send(4, 0, 9)
        send(4, 1, 9)
        send(3, 1, 8)
        delay(1.seconds)

        assertEquals(
            listOf(0, 1, 4).map { bodies[it].toList() },
            s.received.map { it.payload.toList() },
            "body 2 is older than body 1 (RFC 7641 §3.4); 0 and 3 are finished, 5 starts without Observe",
        )
        assertEquals(listOf(5L, 7L, 9L), s.received.map { it.options.observe })
    }

    @Test
    fun rfc7641Section3_2ANotificationOfOneMessageIsPassedOnAndOneWithoutObserveEndsTheObservation() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        val token = registration.token
        s.sendBody(peer, token, body(128, 1), 1, 1)

        // libcoap sends a body of one block without ETag and Size2.
        s.server.send(peer, s.block(token, 0, "small".encodeToByteArray(), etag = null, observe = 2, size2 = null))
        s.server.send(peer, s.plain(token, 3, "plain"))
        s.server.send(peer, s.plain(token, 2, "stale"))
        s.server.send(peer, s.plain(token, null, "last"))
        delay(1.seconds)

        assertEquals(listOf(null, "small", "plain", "last"), s.received.map { if (it.payloadSize == 128) null else it.payloadAsText() })
        assertTrue(s.completed, "RFC 7641 §3.2: a response without Observe ends the observation")
        assertEquals(0, s.client.endpoint.exchangeCount())
    }

    @Test
    fun rfc7641Section3_1ARegistrationAnsweredWithoutObserveEndsAtOnce() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        s.server.send(peer, s.block(registration.token, 0, "not observable".encodeToByteArray(), etag = 1, observe = null))
        delay(1.seconds)

        assertEquals(listOf("not observable"), s.received.map { it.payloadAsText() })
        assertTrue(s.completed)
    }

    @Test
    fun rfc9175Section2_3AContinueChallengedWithEchoIsSentAgainWithIt() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        val token = registration.token
        s.sendBody(peer, token, body(128, 1), 1, 1)
        val notification = body(11 * 64, 2)
        for (num in 0L..9L) s.server.send(peer, s.block(token, num, notification, 2, 2))

        val (_, first) = s.requests.receive()
        assertEquals(10L, first.options.qBlock2!!.number, "the Continue")
        s.server.send(peer, CoapMessage.response(first, CoapCode.UNAUTHORIZED) {
            type = CoapMessageType.NON
            messageId = 0x7100
            options { echo(byteArrayOf(1, 2, 3)) }
        })
        val (_, again) = s.requests.receive()
        assertContentEquals(byteArrayOf(1, 2, 3), again.options.echo, "RFC 9175 §2.3: repeated once with the Echo value")
        assertEquals(10L, again.options.qBlock2!!.number)
        s.server.send(peer, s.block(token, 10, notification, 2, 2))
        delay(1.seconds)

        assertContentEquals(notification, s.received.last().payload)
    }

    @Test
    fun rfc9177Section4_4ANotificationBodyThatCannotBeCompletedIsGivenUpAndTheNextOneComes() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        val token = registration.token
        s.sendBody(peer, token, body(128, 1), 1, 1)
        s.server.send(peer, s.block(token, 0, body(192, 2), 2, 2))
        // Every request for the missing blocks gets a 4.04 instead of them.
        backgroundScope.launch {
            for ((from, request) in s.requests) {
                s.server.send(from, CoapMessage.response(request, CoapCode.NOT_FOUND) {
                    type = CoapMessageType.NON
                    messageId = 0x7200 + request.messageId % 0x100
                })
            }
        }
        delay(5.minutes)
        s.sendBody(peer, token, body(128, 3), 3, 3)
        delay(1.seconds)

        assertEquals(listOf(body(128, 1), body(128, 3)).map { it.toList() }, s.received.map { it.payload.toList() })
        assertEquals(1, s.fixture.events.count { it is EndpointEvent.PartialBodyDropped })
        assertNull(s.failure, "the observation goes on")
    }

    @Test
    fun rfc9177Section4_4ANotificationBodyBeyondTheLimitFailsTheObservation() = runTest {
        val s = scripted(maxResponseBodySize = 1000)
        s.observe(this)
        val (peer, registration) = s.requests.receive()
        val token = registration.token
        s.sendBody(peer, token, body(128, 1), 1, 1)
        s.server.send(peer, s.block(token, 0, body(2000, 2), 2, 2))
        delay(1.seconds)

        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, (s.failure as? CoapException)?.reason)
        assertEquals(0, s.client.endpoint.exchangeCount())
    }

    @Test
    fun rfc9177Section4_1ARegistrationAnsweredWithBlock2IsMadeAgainWithRfc7959() = runTest {
        val s = scripted()
        s.observe(this)
        val (peer, first) = s.requests.receive()
        assertTrue(first.options.qBlock2 != null)
        s.server.send(peer, CoapMessage.response(first, CoapCode.CONTENT) {
            type = CoapMessageType.NON
            messageId = 0x7300
            payload = ByteArray(64)
            options {
                observe(1)
                block2(BlockOption(0, true, 2))
            }
        })
        val (_, second) = s.requests.receive()
        assertNull(second.options.qBlock2, "RFC 7959 for the second registration")
        assertEquals(0L, second.options.observe)
        s.server.send(peer, s.plain(second.token, 2, "small"))
        val late = s.plain(first.token, 3, "on the first token")
        s.server.send(peer, late)
        delay(1.seconds)

        assertEquals(listOf("small"), s.received.map { it.payloadAsText() })
        val reset = s.fixture.sentBy(s.client.endpoint).single { it.type == CoapMessageType.RST }
        assertEquals(late.messageId, reset.messageId, "RFC 7641 §3.6: the first registration's token is forgotten")
    }

    @Test
    fun rfc9177Section4_1AServerWithoutQBlockGetsTheRfc7959Registration() = runTest {
        val fixture = StackFixture(this)
        val resource = Resource("obs", CoapMessageType.CON) { body(3000, it) }
        val server = fixture.server(CoapServerConfig(amplification = AmplificationPolicy.OFF)) { add(resource) }
        val client = fixture.client(CoapClientConfig(blockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED)))
        val received = mutableListOf<CoapResponse>()
        backgroundScope.launch { client.observe(fixture.uri(server, "/obs")).collect { received += it } }
        delay(10.seconds)
        resource.change()
        delay(10.seconds)

        assertEquals(listOf(body(3000, 0), body(3000, 1)).map { it.toList() }, received.map { it.payload.toList() })
        assertEquals(1, fixture.events.count { it is EndpointEvent.QBlockFallback }, "the 4.02 to the Q-Block registration")
        assertEquals(1, resource.observerCount)
    }

    private fun seconds(value: Double): String {
        val millis = (value * 1000).toLong()
        val fraction = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) "${millis / 1000}s" else "${millis / 1000}.${fraction}s"
    }
}
