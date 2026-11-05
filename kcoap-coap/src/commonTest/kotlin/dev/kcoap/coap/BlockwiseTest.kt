package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.testkit.LinkConditions
import dev.kcoap.transport.DatagramEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

class BlockwiseTest {
    private val large = Random(9).nextBytes(3000)

    private fun StackFixture.requests(): List<CoapMessage> = traffic.mapNotNull { it.message }.filter { it.isRequest }
    private fun StackFixture.responses(): List<CoapMessage> = traffic.mapNotNull { it.message }.filter { it.isResponse }

    @Test
    fun rfc7959Section2_4ALargeResponseIsDownloadedInBlock2Blocks() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large, ContentFormat.OCTET_STREAM) } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/large"))

        assertContentEquals(large, response.payload)
        assertEquals(ContentFormat.OCTET_STREAM, response.contentFormat)
        assertEquals(null, response.options.block2, "the assembled response has no Block2")
        val blocks = fixture.responses().map { assertNotNull(it.options.block2) }
        assertEquals(listOf(0L, 1L, 2L), blocks.map { it.number })
        assertEquals(listOf(true, true, false), blocks.map { it.more })
        assertTrue(blocks.all { it.size == 1024 }, "the largest size the 1152-byte datagram fits")
        val first = fixture.responses().first()
        assertEquals(3000L, first.options.size2)
        assertEquals(8, first.options.etags.single().size, "an ETag identifies the representation (RFC 9175 §3.8)")
        assertTrue(fixture.responses().all { it.options.etags.single().contentEquals(first.options.etags.single()) })
        assertEquals(listOf(null, 1L, 2L), fixture.requests().map { it.options.block2?.number })
    }

    @Test
    fun blockSizesFollowTheDatagramSize() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(maxDatagramSize = 300) { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client(maxDatagramSize = 300)

        assertContentEquals(large, client.get(fixture.uri(server, "/large")).payload)
        assertTrue(fixture.responses().all { it.options.block2?.size == 256 })
    }

    @Test
    fun rfc7959Section2_4EarlyNegotiationUsesTheClientBlockSize() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client(CoapClientConfig(blockSize = 64))

        assertContentEquals(large, client.get(fixture.uri(server, "/large")).payload)
        assertEquals(BlockOption(0, false, 2), fixture.requests().first().options.block2)
        assertEquals(47, fixture.responses().size)
        assertTrue(fixture.responses().all { it.options.block2?.size == 64 })
    }

    @Test
    fun theServerCapsTheBlockSize() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(maxBlockSize = 128)) { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client()

        assertContentEquals(large, client.get(fixture.uri(server, "/large")).payload)
        assertTrue(fixture.responses().all { it.options.block2?.size == 128 })
    }

    @Test
    fun rfc7959Section2_4ABlockRequestForALargerSizeContinuesAtTheSameOffset() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(maxBlockSize = 256)) { resource("large") { get { CoapResponse.content(large) } } }
        val raw = fixture.transport()
        val request = CoapMessage.request(CoapCode.GET) {
            messageId = 1
            token = byteArrayOf(1)
            options {
                uriPath("large")
                block2(BlockOption(1, false, 6))
            }
        }
        raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(request))
        val response = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        assertEquals(BlockOption(4, true, 4), response.options.block2, "offset 1024 in 256-byte blocks")
        assertContentEquals(large.copyOfRange(1024, 1280), response.payload)
    }

    @Test
    fun rfc7959Section2_5ALargeRequestIsUploadedInBlock1Blocks() = runTest {
        val fixture = StackFixture(this)
        var received: ByteArray? = null
        var contentFormat: Int? = null
        val server = fixture.server {
            resource("upload") {
                put {
                    received = payload
                    contentFormat = options.contentFormat
                    assertEquals(null, options.block1, "the handler sees the assembled body")
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client()

        val response = client.put(fixture.uri(server, "/upload"), large, ContentFormat.OCTET_STREAM)

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(large, received)
        assertEquals(ContentFormat.OCTET_STREAM, contentFormat)
        val blocks = fixture.requests().map { assertNotNull(it.options.block1) }
        assertEquals(listOf(0L, 1L, 2L), blocks.map { it.number })
        assertEquals(listOf(true, true, false), blocks.map { it.more })
        assertEquals(3000L, fixture.requests().first().options.size1)
        assertEquals(listOf(CoapCode.CONTINUE, CoapCode.CONTINUE, CoapCode.CHANGED), fixture.responses().map { it.code })
        assertEquals(BlockOption(2, false, 6), fixture.responses().last().options.block1, "§2.3 control usage")
    }

    @Test
    fun rfc7959Figure9TheClientAdoptsTheServerBlockSize() = runTest {
        val fixture = StackFixture(this)
        var received: ByteArray? = null
        val server = fixture.server(CoapServerConfig(maxBlockSize = 256)) {
            resource("upload") {
                post {
                    received = payload
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client()

        client.post(fixture.uri(server, "/upload"), large)

        assertContentEquals(large, received)
        val blocks = fixture.requests().map { assertNotNull(it.options.block1) }
        assertEquals(1024, blocks[0].size)
        assertEquals(BlockOption(0, true, 4), fixture.responses().first().options.block1)
        assertEquals(4L, blocks[1].number, "rescaled: offset 1024 is block 4 of 256 bytes")
        assertTrue(blocks.drop(1).all { it.size == 256 })
    }

    @Test
    fun rfc7959Section2_7Block1AndBlock2Combine() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("echo") { post { CoapResponse(CoapCode.CHANGED, payload = payload) } } }
        val client = fixture.client(CoapClientConfig(blockSize = 512))

        val response = client.post(fixture.uri(server, "/echo"), large)

        assertEquals(CoapCode.CHANGED, response.code)
        assertContentEquals(large, response.payload)
        val followUps = fixture.requests().filter { it.options.block1 == null }
        assertTrue(followUps.isNotEmpty() && followUps.all { it.payloadSize == 0 && (it.options.block2?.number ?: 0) > 0 })
        assertEquals(BlockOption(0, false, 5), fixture.requests().first { it.options.block1?.more == false }.options.block2)
    }

    @Test
    fun rfc7959Section2_9_3TooLargeBodiesGet413WithSize1() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(maxBodySize = 2048)) { resource("upload") { put { CoapResponse.changed() } } }
        val client = fixture.client()

        val response = client.put(fixture.uri(server, "/upload"), large)

        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, response.code)
        assertEquals(2048L, response.options.size1)
        assertEquals(1, fixture.requests().size, "the announced Size1 is rejected on the first block")
    }

    @Test
    fun aBodyGrowingBeyondTheLimitWithoutSize1Gets413() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(CoapServerConfig(maxBodySize = 1500)) { resource("upload") { put { CoapResponse.changed() } } }
        val raw = fixture.transport()
        repeat(2) { number ->
            val block = CoapMessage.request(CoapCode.PUT) {
                messageId = number
                token = byteArrayOf(number.toByte())
                options {
                    uriPath("upload")
                    block1(BlockOption(number.toLong(), true, 6))
                }
                payload = ByteArray(1024)
            }
            raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(block))
        }
        val first = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        val second = UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        assertEquals(CoapCode.CONTINUE, first.code)
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, second.code)
    }

    @Test
    fun rfc7959Section2_9_3A413WithoutBlock1IsAHintToUseBlock1() = runTest {
        val fixture = StackFixture(this)
        var received = 0
        val server = fixture.server {
            resource("picky") {
                put {
                    if (options.block1 == null && payloadSize > 100 && received == 0) {
                        received = -1
                        CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE)
                    } else {
                        received = payloadSize
                        CoapResponse.changed()
                    }
                }
            }
        }
        val client = fixture.client()

        val response = client.put(fixture.uri(server, "/picky"), ByteArray(500))
        // The server assembles Block1 itself, so the handler sees no Block1 and refuses again.
        assertEquals(CoapCode.CHANGED, response.code)
        assertEquals(500, received)
        assertTrue(fixture.requests().last().options.block1 != null)
    }

    @Test
    fun rfc7959Section2_5BlocksOutOfSequenceOrWithoutStartGet408() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("upload") { put { CoapResponse.changed() } } }
        val raw = fixture.transport()
        suspend fun send(number: Long, more: Boolean, size: Int = 64, format: Int? = null, mid: Int): CoapMessage {
            val block = CoapMessage.request(CoapCode.PUT) {
                messageId = mid
                token = byteArrayOf(mid.toByte())
                options {
                    uriPath("upload")
                    block1(BlockOption(number, more, BlockOption.sizeExponentFor(64)))
                    format?.let { contentFormat(it) }
                }
                payload = ByteArray(size)
            }
            raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(block))
            return UdpMessageCodec.decode((raw.receive() as DatagramEvent.Received).datagram)
        }
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, send(3, more = false, size = 10, mid = 1).code, "no block 0")
        assertEquals(CoapCode.CONTINUE, send(0, more = true, mid = 2).code)
        assertEquals(CoapCode.CONTINUE, send(0, more = true, mid = 3).code, "a block sent again is accepted")
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, send(2, more = true, mid = 4).code, "block 1 is missing")
        assertEquals(CoapCode.CONTINUE, send(0, more = true, mid = 5).code)
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, send(1, more = true, format = 50, mid = 6).code, "§2.3 Content-Format changed")
        assertEquals(CoapCode.BAD_REQUEST, send(0, more = true, size = 10, mid = 7).code, "§2.3 payload must match SZX")
        assertEquals(CoapCode.CONTINUE, send(0, more = true, mid = 8).code)
        assertEquals(CoapCode.CHANGED, send(1, more = false, size = 5, mid = 9).code)
    }

    @Test
    fun rfc7959Section2_2Szx7IsABadRequestOverUdp() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("x") } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/r")) { options { block2(BlockOption(0, false, 7)) } }
        assertEquals(CoapCode.BAD_REQUEST, response.code)
    }

    @Test
    fun aBlockBeyondTheEndIsABadOption() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/large")) { options { block2(BlockOption(50, false, 6)) } }
        assertEquals(CoapCode.BAD_OPTION, response.code)
    }

    @Test
    fun followUpBlocksOfAnUnsafeMethodNeedTheCachedResponse() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { post { CoapResponse.content(large) } } }
        val client = fixture.client()

        val response = client.post(fixture.uri(server, "/r")) { options { block2(BlockOption(1, false, 6)) } }
        assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, response.code)
    }

    /** A server that answers each request piggybacked with [answer]'s response. */
    private fun StackFixture.scriptedServer(transport: InMemoryTransport, answer: (CoapMessage) -> CoapMessage) =
        test.backgroundScope.async {
            while (true) {
                val event = transport.receive() as DatagramEvent.Received
                val request = UdpMessageCodec.decode(event.datagram)
                val response = answer(request).toBuilder().apply {
                    type = CoapMessageType.ACK
                    messageId = request.messageId
                    token = request.token
                }.build()
                transport.send(event.from, UdpMessageCodec.encode(response))
            }
        }

    private fun block(number: Long, more: Boolean, etag: Byte, fill: Char, size: Int = 64) = CoapMessage.build {
        code = CoapCode.CONTENT
        options {
            etag(byteArrayOf(etag))
            block2(BlockOption(number, more, BlockOption.sizeExponentFor(64)))
        }
        payload = ByteArray(size) { fill.code.toByte() }
    }

    private fun InMemoryTransport.uri() = "coap://${localEndpoint.host}:${localEndpoint.port}/r"

    @Test
    fun rfc7959Section2_4AChangingRepresentationRestartsTheTransfer() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        var calls = 0
        fixture.scriptedServer(transport) { request ->
            when (calls++) {
                0 -> block(0, true, etag = 1, fill = 'a')
                1 -> block(1, true, etag = 2, fill = 'b').also { assertEquals(1L, request.options.block2?.number) }
                2 -> block(0, true, etag = 2, fill = 'b').also { assertEquals(0L, request.options.block2?.number) }
                else -> block(1, false, etag = 2, fill = 'b', size = 10)
            }
        }
        val client = fixture.client()

        val response = client.get(transport.uri())

        assertEquals(74, response.payloadSize)
        assertTrue(response.payload.all { it == 'b'.code.toByte() }, "only blocks of the newer representation")
    }

    @Test
    fun aRepresentationThatNeverSettlesFailsTheTransfer() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        var calls = 0
        fixture.scriptedServer(transport) { request ->
            block(request.options.block2?.number ?: 0, true, etag = (calls++).toByte(), fill = 'x')
        }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> { client.get(transport.uri()) }
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, error.reason)
    }

    @Test
    fun brokenFollowUpResponsesFailTheTransfer() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        var mode = 0
        fixture.scriptedServer(transport) { request ->
            if (request.options.block2 == null) {
                block(0, true, etag = 1, fill = 'a')
            } else {
                when (mode) {
                    0 -> CoapMessage.build { code = CoapCode.NOT_FOUND }
                    1 -> CoapMessage.build {
                        code = CoapCode.CONTENT
                        options { etag(byteArrayOf(1)) }
                    }
                    else -> block(5, true, etag = 1, fill = 'a')
                }
            }
        }
        for (broken in 0..2) {
            mode = broken
            val error = assertFailsWith<CoapException> { client.get(transport.uri()) }
            assertEquals(CoapException.Reason.BLOCKWISE_FAILED, error.reason, "mode $broken")
        }
    }

    @Test
    fun anAnnouncedSize2BeyondTheLimitFailsAtOnce() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        fixture.scriptedServer(transport) { block(0, true, etag = 1, fill = 'a').toBuilder().options { size2(10_000_000) }.build() }
        val client = fixture.client()

        val error = assertFailsWith<CoapException> { client.get(transport.uri()) }
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, error.reason)
        assertEquals(1, fixture.traffic.count { it.message?.isRequest == true })
    }

    @Test
    fun theClientRefusesTooLargeBodies() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("large") { get { CoapResponse.content(large) } } }
        val client = fixture.client(CoapClientConfig(maxResponseBodySize = 2000))

        val error = assertFailsWith<CoapException> { client.get(fixture.uri(server, "/large")) }
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, error.reason)
    }

    @Test
    fun rfc9175Section3_5_2ConcurrentUploadsToOneResourceGetDistinctRequestTags() = runTest {
        val fixture = StackFixture(this)
        val bodies = mutableListOf<ByteArray>()
        val server = fixture.server {
            resource("upload") {
                post {
                    bodies += payload
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client(
            CoapClientConfig(blockSize = 256),
            endpointConfig = fixture.endpointConfig(parameters = dev.kcoap.coap.stack.TransmissionParameters(nstart = 4)),
        )
        val first = ByteArray(1000) { 1 }
        val second = ByteArray(1000) { 2 }

        listOf(first, second).map { body -> async { client.post(fixture.uri(server, "/upload"), body) } }.awaitAll()

        assertEquals(2, bodies.size)
        assertTrue(bodies.any { it.contentEquals(first) } && bodies.any { it.contentEquals(second) }, "no mixed blocks")
        val tags = fixture.requests().map { request -> request.options.requestTags.map { it.toList() } }.toSet()
        assertEquals(setOf(emptyList(), listOf(listOf<Byte>(1))), tags, "absent for one, a short tag for the other")
    }

    @Test
    fun blockwiseSurvivesALossyNetwork() = runTest {
        val fixture = StackFixture(this, seed = 11)
        var received: ByteArray? = null
        val server = fixture.server {
            resource("echo") {
                put {
                    received = payload
                    CoapResponse.content(payload)
                }
            }
        }
        val client = fixture.client()
        fixture.network.conditions = LinkConditions(lossRate = 0.2, duplicationRate = 0.2, jitter = 30.milliseconds)

        val response = client.put(fixture.uri(server, "/echo"), large)

        assertContentEquals(large, received)
        assertContentEquals(large, response.payload)
    }

    @Test
    fun responsesThatCannotBeSplitProduce500() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server(maxDatagramSize = 64) {
            resource("r") { get { CoapResponse.content(ByteArray(100)).withOptions { repeat(5) { etag(ByteArray(8)) } } } }
        }
        val raw = fixture.transport()
        raw.send(fixture.address(server.endpoint), UdpMessageCodec.encode(CoapMessage.request(CoapCode.GET) { options { uriPath("r") } }))
        val response = UdpMessageCodec.decode(assertIs<DatagramEvent.Received>(raw.receive()).datagram)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, response.code)
    }
}
