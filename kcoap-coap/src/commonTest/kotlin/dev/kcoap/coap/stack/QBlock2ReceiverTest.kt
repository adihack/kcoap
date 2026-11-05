package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.stack.QBlockMessages.block
import dev.kcoap.coap.stack.QBlockMessages.bytes
import dev.kcoap.coap.stack.QBlockMessages.download
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The client side of Q-Block2 (RFC 9177 §4.4, §4.5, §7) on its own: acceptance, assembly and recovery. */
class QBlock2ReceiverTest {
    private val timing = QBlockTiming(TransmissionParameters())
    private val size2 = 25 * 64 + 9

    private fun receiver(
        mode: BodyMode = BodyMode.NON,
        restartable: Boolean = true,
        mBit: Boolean = false,
        maxBodySize: Int = 1 shl 20,
        allowsBert: Boolean = false,
    ) = QBlock2Receiver(mode, timing, maxBodySize, restartable, mBit, allowsBert)

    private fun QBlock2Receiver.feed(vararg nums: Long, at: Duration = Duration.ZERO, size: Int = size2, etag: Int = 1) =
        nums.flatMap { onBlock(at, download(it, size, etag = etag)) }

    private fun List<QBlockAction<Nothing>>.drops(): List<String> = filterIsInstance<QBlockAction.Drop>().map { it.reason }

    private fun List<QBlockAction<Nothing>>.requested(): List<String> =
        filterIsInstance<QBlockAction.RequestMissing>().map { request -> request.blocks.joinToString() }

    @Test
    fun rfc9177Section4_4ABodyIsDeliveredOnceWithTheOptionsOfBlock0() {
        val receiver = receiver()
        val actions = receiver.feed(*(25L downTo 0L).toList().toLongArray())
        val delivered = assertIs<QBlockAction.Deliver<Nothing>>(actions.last()).message
        assertContentEquals(bytes(size2), delivered.payload)
        assertNull(delivered.options.qBlock2)
        assertNull(delivered.options.size2)
        assertContentEquals(byteArrayOf(1), delivered.options.etags.single(), "the ETag stays")
        assertEquals(CoapCode.CONTENT, delivered.code)
        assertFalse(receiver.isAssembling)
        assertNull(receiver.nextDeadline())
        assertEquals(listOf("A block of a body already delivered"), receiver.feed(3).drops())
        assertEquals(emptyList(), receiver.onTimer(100.seconds))
    }

    @Test
    fun rfc9177Section4_4BlocksThatDoNotFitTheBodyAreDropped() {
        val receiver = receiver()
        receiver.feed(0)
        fun drop(message: CoapMessage) = receiver.onBlock(Duration.ZERO, message).drops().single()
        assertEquals("Duplicate block 0", drop(download(0, size2)))
        assertEquals("No Q-Block2", drop(download(1, size2).toBuilder().options { remove(CoapOptions.Q_BLOCK2.number) }.build()))
        assertEquals("More than one ETag", drop(download(1, size2) { etag(byteArrayOf(2)) }))
        assertEquals("No Size2", drop(download(1, size2).toBuilder().options { remove(CoapOptions.SIZE2.number) }.build()))
        assertEquals("Block size 32 differs from the body's", drop(download(1, size2, size = 32)))
        assertEquals("Size2 99 differs from the body's 1609", drop(download(1, size2).toBuilder().options { size2(99) }.build()))
        assertEquals("Content-Format differs from the body's", drop(download(1, size2) { contentFormat(ContentFormat.JSON) }))
        assertEquals("Block 30 beyond Size2 1609", drop(download(1, size2).toBuilder().options { qBlock2(block(30, true)) }.build()))
        assertEquals("Block 1 with M set does not fill its size", drop(download(1, size2).toBuilder().apply { payload = ByteArray(5) }.build()))
        assertEquals("Last block 3 does not end at Size2 1609", drop(download(3, size2).toBuilder().options { qBlock2(block(3, false)) }.build()))
        assertEquals("Q-Block mixed with Block1 or Block2", drop(download(1, size2) { block2(block(1, true)) }))
        assertTrue(receiver.isAssembling)
    }

    /** RFC 7959 §2.2: only the last block may differ from the block size, and only by being smaller. */
    @Test
    fun rfc7959Section2_2ALastBlockLargerThanItsSizeIsDropped() {
        val receiver = receiver()
        receiver.onBlock(Duration.ZERO, download(0, 100, size = 16))
        val tooLong = download(2, 100, size = 16).toBuilder().apply { payload = ByteArray(68) }
            .options { qBlock2(block(2, false, 16)) }.build()
        assertEquals(listOf("Block 2 carries more than 16 bytes"), receiver.onBlock(Duration.ZERO, tooLong).drops())
        val dropped = (0L..6L).filter { receiver.onBlock(Duration.ZERO, download(it, 100, size = 16)).drops().isNotEmpty() }
        assertEquals(listOf(0L), dropped, "only block 0 had arrived: block 2 did not mark or fill 3 to 6")
    }

    @Test
    fun rfc9177Section4_4EveryBlockNeedsExactlyOneETag() {
        fun withoutETag(num: Long, size: Int = size2) =
            download(num, size).toBuilder().options { remove(CoapOptions.ETAG.number) }.build()
        val receiver = receiver()
        assertEquals(listOf("No ETag"), receiver.onBlock(Duration.ZERO, withoutETag(0)).drops())
        assertFalse(receiver.isAssembling, "no body starts without ETag")
        val small = 64 + 3
        val noETags = receiver()
        val drops = listOf(0L, 1L).flatMap { noETags.onBlock(Duration.ZERO, withoutETag(it, small)).drops() }
        assertEquals(listOf("No ETag", "No ETag"), drops)
        assertNull(noETags.nextDeadline(), "two blocks without ETag never make a body")
        receiver.feed(0, 1, 2)
        val withSize2 = receiver.onBlock(Duration.ZERO, withoutETag(3)).drops()
        assertEquals(listOf("No ETag"), withSize2, "with Size2 it is not libcoap's rerun")
        assertTrue(receiver.isAssembling)
        val observation = receiver()
        assertIs<QBlockAction.Deliver<Nothing>>(observation.feed(0, 1, size = small).last())
        val notification = observation.onBlock(Duration.ZERO, withoutETag(0, small)).drops()
        assertEquals(listOf("No ETag"), notification, "a notification without ETag")
    }

    @Test
    fun rfc9177Section4_4ABodyOfMoreThan2To20BlocksFails() {
        val receiver = receiver(maxBodySize = 64 shl 20)
        val huge = download(0, 32, size = 16).toBuilder().options { size2(20L shl 20) }.build()
        val failure = assertIs<QBlockAction.Fail>(receiver.onBlock(Duration.ZERO, huge).single())
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertEquals("Response body of 20971520 bytes needs more than 2^20 blocks of 16", failure.diagnostic)
        val largest = receiver(maxBodySize = 64 shl 20)
        val fits = download(0, 32, size = 16).toBuilder().options { size2(16L shl 20) }.build()
        assertEquals(emptyList(), largest.onBlock(Duration.ZERO, fits), "exactly 2^20 blocks")
    }

    @Test
    fun rfc9177Section4_4ABodyAboveTheLimitFails() {
        val receiver = receiver(maxBodySize = 1000)
        val failure = assertIs<QBlockAction.Fail>(receiver.feed(0).single())
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertTrue(receiver.isFailed)
        assertEquals(listOf("The transfer failed"), receiver.feed(1).drops())
    }

    @Test
    fun rfc9177Section4_4ANewETagStartsANewBodyForGet() {
        val receiver = receiver()
        receiver.feed(0, 1, 2)
        assertEquals(emptyList(), receiver.feed(5, etag = 2), "the old body is dropped and the new one assembled")
        assertEquals(listOf("A block of a superseded body"), receiver.feed(3, etag = 1).drops())
        val actions = receiver.feed(*((0L..25L) - 5L).toLongArray(), etag = 2)
        assertContentEquals(bytes(size2), assertIs<QBlockAction.Deliver<Nothing>>(actions.last()).message.payload)
        val noSize2 = receiver()
        noSize2.feed(0, 1)
        val changed = download(4, size2, etag = 3).toBuilder().options { remove(CoapOptions.SIZE2.number) }.build()
        assertEquals(listOf("0/1/64"), noSize2.onBlock(Duration.ZERO, changed).requested(), "it cannot start a body without Size2")
    }

    @Test
    fun rfc9177Section4_4ANewETagFailsAnUnsafeMethod() {
        val receiver = receiver(restartable = false)
        receiver.feed(0, 1)
        val failure = assertIs<QBlockAction.Fail>(receiver.feed(2, etag = 2).single())
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
    }

    @Test
    fun rfc9177Section4_4AfterDeliveryAnotherETagIsTheNextNotification() {
        val small = 64 + 3
        val receiver = receiver()
        receiver.feed(0, 1, size = small)
        assertIs<QBlockAction.Deliver<Nothing>>(receiver.feed(0, 1, size = small, etag = 2).last())
        val unsafe = receiver(restartable = false)
        unsafe.feed(0, 1, size = small)
        assertEquals(listOf("A block of another body after delivery"), unsafe.feed(0, size = small, etag = 2).drops())
    }

    @Test
    fun libcoapRunsTheHandlerAgainAndAnswersWithoutETagOrSize2() {
        val receiver = receiver()
        receiver.feed(0, 1, 2)
        val rerun = download(4, size2).toBuilder().options {
            remove(CoapOptions.ETAG.number)
            remove(CoapOptions.SIZE2.number)
        }.build()
        assertEquals(listOf("0/1/64"), receiver.onBlock(Duration.ZERO, rerun).requested(), "the whole body is asked for again")
        assertNull(receiver.nextDeadline(), "nothing is assembled meanwhile")
        val unsafe = receiver(restartable = false)
        unsafe.feed(0, 1, 2)
        val failure = assertIs<QBlockAction.Fail>(unsafe.onBlock(Duration.ZERO, rerun).single())
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
    }

    @Test
    fun rfc9177Section7_2AContinueGoesOutWhenASetIsComplete() {
        val receiver = receiver()
        val actions = receiver.feed(*(0L..9L).toList().toLongArray())
        assertEquals(listOf(QBlockAction.SendContinue(10).toString()), actions.map { it.toString() })
        assertEquals(emptyList(), receiver.feed(*(10L..19L).toList().toLongArray()).filter { it !is QBlockAction.SendContinue }.drops())
        val ahead = receiver()
        ahead.feed(0, 1, 2, 3, 4, 5, 6, 7, 8, 12)
        assertEquals(emptyList(), ahead.feed(9), "no Continue once the next set arrives")
    }

    @Test
    fun rfc9177Section7_2AMissingBlockThatCompletesTheHighestSetSendsTheContinue() {
        // libcoap K2: blocks 2 and 7 are lost, asked for when block 10 arrives, and come after block 19.
        val receiver = receiver()
        val first = receiver.feed(*((0L..19L) - listOf(2L, 7L)).toLongArray())
        assertEquals(listOf("2/0/64, 7/0/64"), first.requested())
        assertEquals(emptyList(), first.filterIsInstance<QBlockAction.SendContinue>())

        assertEquals(emptyList(), receiver.feed(2))
        assertEquals(listOf(QBlockAction.SendContinue(20).toString()), receiver.feed(7).map { it.toString() })
        assertEquals(emptyList(), receiver.feed(7).filterIsInstance<QBlockAction.SendContinue>(), "a duplicate asks for nothing")
    }

    @Test
    fun rfc9177Section4_4MissingBlock0IsAskedForWithTheWholeBodyForGet() {
        val receiver = receiver()
        receiver.feed(1, 2, 3)
        assertEquals(listOf("0/1/64"), receiver.onTimer(4.seconds).requested(), "never 0/M=0, which libcoap mishandles")
        val unsafe = receiver(restartable = false)
        unsafe.feed(1, 2, 3)
        assertEquals(listOf((listOf(0L) + (4L..9L)).joinToString { "$it/0/64" }), unsafe.onTimer(4.seconds).requested())
    }

    @Test
    fun rfc9177Section4_4TheMBitAsksForTheTailOfASet() {
        val receiver = receiver(mBit = true)
        receiver.feed(0, 1, 3)
        assertEquals(listOf("2/0/64, 4/1/64"), receiver.onTimer(4.seconds).requested())
        val wholeSet = receiver(mBit = true)
        wholeSet.feed(*(0L..9L).toList().toLongArray())
        assertEquals(listOf("10/0/64, 11/1/64"), wholeSet.onTimer(4.seconds).requested(), "10/M=1 would read as a Continue")
        val lastOne = receiver(mBit = true)
        lastOne.feed(*(0L..8L).toList().toLongArray())
        assertEquals(listOf("9/0/64"), lastOne.onTimer(4.seconds).requested())
        val unsafe = receiver(restartable = false, mBit = true)
        assertEquals(listOf("0/0/64, 1/1/64"), unsafe.feed(12).requested(), "0/M=1 would ask for the entire body")
    }

    @Test
    fun rfc9177Section7_2TheTimerLadderGivesUpAfterNonMaxRetransmit() {
        val receiver = receiver()
        receiver.feed(0)
        val times = mutableListOf<Duration>()
        while (true) {
            val at = receiver.nextDeadline() ?: break
            val actions = receiver.onTimer(at)
            if (actions.requested().isNotEmpty()) times += at
            if (actions.any { it is QBlockAction.Discard }) {
                assertEquals(124.seconds, at)
                break
            }
        }
        assertEquals(listOf(4, 12, 28, 60).map { it.seconds }, times)
        assertFalse(receiver.isAssembling)
        assertEquals(listOf("A block of a superseded body"), receiver.feed(1).drops(), "late blocks of the given-up body")
        assertEquals(emptyList(), receiver.onTimer(200.seconds))
    }

    @Test
    fun rfc9177Section4_4APartialBodyLivesForMaxAgeButAtLeastTheLadder() {
        val longLived = receiver(mode = BodyMode.RELIABLE)
        longLived.onBlock(Duration.ZERO, download(0, size2) { maxAge(1000) })
        assertEquals(247.seconds, longLived.nextDeadline(), "NON_PARTIAL_TIMEOUT")
        assertEquals(emptyList(), longLived.onTimer(246.seconds))
        assertEquals("The partial body expired", assertIs<QBlockAction.Discard>(longLived.onTimer(247.seconds).single()).reason)
        val shortLived = receiver(mode = BodyMode.RELIABLE)
        shortLived.onBlock(Duration.ZERO, download(0, size2) { maxAge(0) })
        assertEquals(124.seconds, shortLived.nextDeadline(), "a Max-Age of 0 would end recovery before it starts")
        val defaultAge = receiver(mode = BodyMode.RELIABLE)
        defaultAge.feed(0)
        assertEquals(124.seconds, defaultAge.nextDeadline(), "the default Max-Age of 60 s is shorter than the ladder")
    }

    @Test
    fun rfc9177Section4_4ConfirmableBodiesRecoverAfterMaxTransmitSpan() {
        val receiver = receiver(mode = BodyMode.CON)
        assertEquals(emptyList(), receiver.feed(0, 2), "no request at once and no Continue")
        assertEquals(45.seconds, receiver.nextDeadline(), "MAX_TRANSMIT_SPAN")
        assertEquals(listOf((listOf(1L) + (3L..9L)).joinToString { "$it/0/64" }), receiver.onTimer(45.seconds).requested())
    }

    /** Feeds one block every 10 s, running every timer that falls due in between. */
    private fun QBlock2Receiver.trickle(blocks: Int, size: Int): List<QBlockAction<Nothing>> {
        val actions = mutableListOf<QBlockAction<Nothing>>()
        for (num in 0 until blocks) {
            val at = (num * 10).seconds
            while (true) {
                val due = nextDeadline()?.takeIf { it <= at } ?: break
                actions += onTimer(due)
            }
            actions += onBlock(at, download(num.toLong(), size))
        }
        return actions
    }

    @Test
    fun rfc9177Section4_4APartialBodyLivesWhileItsBlocksKeepComing() {
        val size = 30 * 64 + 9
        for (mode in listOf(BodyMode.NON, BodyMode.RELIABLE)) {
            val actions = receiver(mode = mode).trickle(31, size)
            assertTrue(actions.none { it is QBlockAction.Discard }, "$mode: 300 s, but never 124 s without a block")
            val delivered = assertIs<QBlockAction.Deliver<Nothing>>(actions.last(), "$mode")
            assertContentEquals(bytes(size), delivered.message.payload)
        }
    }

    @Test
    fun rfc9177Section4_4AStalledPartialBodyExpiresLifetimeAfterItsLatestBlock() {
        val receiver = receiver(mode = BodyMode.RELIABLE)
        receiver.feed(0)
        receiver.feed(1, at = 50.seconds)
        assertEquals(174.seconds, receiver.nextDeadline())
        assertEquals(emptyList(), receiver.onTimer(173.seconds))
        val discard = assertIs<QBlockAction.Discard>(receiver.onTimer(174.seconds).single())
        assertEquals("The partial body expired", discard.reason)
    }

    @Test
    fun rfc8323Section6BertBlocksOnAReliableTransport() {
        fun bert(num: Long, payload: Int, more: Boolean) = CoapMessage.build {
            code = CoapCode.CONTENT
            this.payload = ByteArray(payload) { (num * 1024 + it).toByte() }
            options {
                etag(byteArrayOf(1))
                size2(3000)
                qBlock2(BlockOption(num, more, 7))
            }
        }
        val receiver = receiver(mode = BodyMode.RELIABLE, allowsBert = true)
        assertEquals(emptyList(), receiver.onBlock(Duration.ZERO, bert(0, 2048, true)))
        val delivered = assertIs<QBlockAction.Deliver<Nothing>>(receiver.onBlock(Duration.ZERO, bert(2, 952, false)).single())
        assertContentEquals(ByteArray(3000) { it.toByte() }, delivered.message.payload)
        assertEquals(listOf("SZX 7 is reserved without BERT"), receiver().onBlock(Duration.ZERO, bert(0, 2048, true)).drops())
    }

    @Test
    fun rfc9177Section4_5TheBodyKeepsTheObserveOfAnyOfItsBlocks() {
        val receiver = receiver()
        val small = 2 * 64
        receiver.onBlock(Duration.ZERO, download(1, small) { observe(7) })
        val delivered = assertIs<QBlockAction.Deliver<Nothing>>(receiver.feed(0, size = small).single()).message
        assertEquals(7L, delivered.options.observe, "block 0 came in answer to a request for missing blocks, without Observe")
        assertTrue(receiver.toString().startsWith("QBlock2Receiver(NON"))
    }

    @Test
    fun rfc9177Section4_4AnEmptyBodyIsOneEmptyBlock() {
        val delivered = assertIs<QBlockAction.Deliver<Nothing>>(receiver().feed(0, size = 0).single()).message
        assertEquals(0, delivered.payloadSize)
    }
}
