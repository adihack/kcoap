package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.stack.QBlockMessages.block
import dev.kcoap.coap.stack.QBlockMessages.bytes
import dev.kcoap.coap.stack.QBlockMessages.upload
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server side of Q-Block1 (RFC 9177 §4.3, §7) on its own: the disposition of every block, and the timers. */
class QBlock1ReceiverTest {
    private val timing = QBlockTiming(TransmissionParameters())
    private val total = 25 * 64 + 7

    private fun receiver(mode: BodyMode = BodyMode.NON, first: CoapMessage = upload(0, total)) =
        QBlock1Receiver<Long>(first, mode, timing, Duration.ZERO)

    /** Feeds block [num] as request [num] and returns the disposition. */
    private fun QBlock1Receiver<Long>.block(num: Long, at: Duration = Duration.ZERO, size: Int = total) =
        onBlock(at, upload(num, size), num).single()

    @Test
    fun rfc9177Section4_3AWholeBodyIsDeliveredOnTheCompletingRequest() {
        val receiver = receiver()
        assertEquals(26, receiver.count)
        assertEquals(total.toLong(), receiver.size1)
        assertEquals(2, receiver.sizeExponent)
        for (num in (0L..25L).shuffled(kotlin.random.Random(3)).filter { it != 7L }) receiver.block(num)
        val deliver = assertIs<QBlockAction.Deliver<Long>>(receiver.block(7))
        assertEquals(7, deliver.ref)
        assertContentEquals(bytes(total), deliver.message.payload)
        assertNull(deliver.message.options.qBlock1, "the handler sees no Q-Block1")
        assertNull(deliver.message.options.size1, "nor Size1")
        assertTrue(deliver.message.options.requestTags.isNotEmpty())
        assertTrue(receiver.isComplete)
        assertNull(receiver.nextDeadline())
        assertIs<QBlockAction.Drop>(receiver.block(7), "the driver answers duplicates from its record of completed bodies")
    }

    @Test
    fun rfc9177Section4_3A231AcknowledgesEveryCompleteSetButTheLast() {
        val receiver = receiver()
        val dispositions = (0L..25L).map { receiver.block(it) }
        val continues = dispositions.filterIsInstance<QBlockAction.Send231<Long>>()
        assertEquals(listOf(9L to 9L, 19L to 19L), continues.map { it.ref to it.num }, "Q-Block1 = the set's last NUM")
        assertIs<QBlockAction.Deliver<Long>>(dispositions.last())
        assertTrue(dispositions.dropLast(1).filter { it !is QBlockAction.Send231<*> }.all { it is QBlockAction.NoResponse<*> })
    }

    @Test
    fun rfc9177Section7_2No231OnceALaterBlockArrived() {
        val receiver = receiver()
        for (num in 0L..8L) receiver.block(num)
        assertIs<QBlockAction.Send408<Long>>(receiver.block(12)).let {
            assertEquals(listOf(9L), it.missing, "a block of a later set asks for the gap at once")
            assertEquals(12, it.ref)
        }
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(15), "once per set")
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(9), "set 0 is complete, but the client is ahead")
    }

    @Test
    fun rfc9177Section4_3DuplicatesAreIgnoredAndDoNotPutTheTimerOff() {
        val receiver = receiver()
        receiver.block(0)
        assertEquals(4.seconds, receiver.nextDeadline())
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(0, at = 3.seconds))
        assertEquals(4.seconds, receiver.nextDeadline())
    }

    @Test
    fun rfc9177Section4_6BlocksThatDoNotFitTheBodyAreRejected() {
        val receiver = receiver()
        val otherSize = upload(1, total).toBuilder().options { size1(99) }.build()
        assertEquals(CoapCode.BAD_REQUEST, assertIs<QBlockAction.Reject<Long>>(receiver.onBlock(Duration.ZERO, otherSize, 1).single()).code)
        val otherSzx = upload(1, total, size = 32)
        assertIs<QBlockAction.Reject<Long>>(receiver.onBlock(Duration.ZERO, otherSzx, 1).single())
        val noBlock = upload(1, total).toBuilder().options { remove(CoapOptions.Q_BLOCK1.number) }.build()
        assertIs<QBlockAction.Reject<Long>>(receiver.onBlock(Duration.ZERO, noBlock, 1).single())
        assertFailsWith<IllegalArgumentException> { receiver(first = noBlock) }
        assertFailsWith<IllegalArgumentException> {
            receiver(first = upload(0, total).toBuilder().options { remove(CoapOptions.SIZE1.number) }.build())
        }
    }

    @Test
    fun rfc9177Section7_2TheTimerAsksForTheGapsUpToTheEndOfTheHighestSet() {
        val receiver = receiver()
        for (num in listOf(0L, 2L, 5L)) receiver.block(num)
        val ask = assertIs<QBlockAction.Send408<Long>>(receiver.onTimer(4.seconds).single())
        assertEquals(listOf(1L, 3L, 4L, 6L, 7L, 8L, 9L), ask.missing, "the trailing blocks of the set too")
        assertEquals(5, ask.ref, "on the request of the highest block")
        assertEquals(12.seconds, receiver.nextDeadline())
        assertEquals(emptyList(), receiver.onTimer(11.seconds), "not before its time")
    }

    @Test
    fun rfc9177Section7_2AfterA231AndSilenceTheTimerAsksForTheNextSet() {
        val receiver = receiver()
        for (num in 0L..9L) receiver.block(num)
        val ask = assertIs<QBlockAction.Send408<Long>>(receiver.onTimer(4.seconds).single())
        assertEquals((10L..19L).toList(), ask.missing)
        assertEquals(9, ask.ref, "the token of the 2.31, which the client keeps")
    }

    /**
     * Block 3 is lost and resent after a 4.08 on request 11; it completes set 0, so the 2.31 goes on 11 and
     * the client releases every token up to block 9, request 9 among them. Later 4.08s must use request 11.
     */
    @Test
    fun rfc9177Section4_3AfterA231OnAResentBlockThe408sGoOnThe231sRequest() {
        val receiver = receiver()
        for (num in (0L..9L) - 3L) receiver.block(num)
        assertEquals(9, assertIs<QBlockAction.Send408<Long>>(receiver.onTimer(4.seconds).single()).ref)
        val continue231 = assertIs<QBlockAction.Send231<Long>>(receiver.onBlock(4.seconds, upload(3, total), 11).single())
        assertEquals(11L to 9L, continue231.ref to continue231.num)
        val ask = assertIs<QBlockAction.Send408<Long>>(receiver.onTimer(8.seconds).single())
        assertEquals(11, ask.ref, "the token the client kept, not that of block 9")
        assertEquals((10L..19L).toList(), ask.missing)
        assertIs<QBlockAction.NoResponse<Long>>(receiver.onBlock(9.seconds, upload(12, total), 12).single())
        assertEquals(12, assertIs<QBlockAction.Send408<Long>>(receiver.onTimer(13.seconds).single()).ref, "a higher block moves it on")
    }

    @Test
    fun rfc9177Section4_3AResetToA408DiscardsTheBody() {
        val receiver = receiver()
        receiver.block(0)
        assertEquals("The client reset a 4.08", assertIs<QBlockAction.Discard>(receiver.onReset(5.seconds).single()).reason)
        assertTrue(receiver.isDiscarded)
        assertNull(receiver.nextDeadline())
        assertEquals(emptyList(), receiver.onReset(6.seconds))
        assertEquals(emptyList(), receiver.onTimer(10.seconds))
        assertIs<QBlockAction.Drop>(receiver.block(1))
    }

    @Test
    fun rfc9177Section7_1ConfirmableBlocksGetEmptyAcksAndNo231() {
        val first = upload(0, total, type = CoapMessageType.CON)
        val receiver = receiver(BodyMode.CON, first)
        val dispositions = (0L..24L).map { receiver.onBlock(Duration.ZERO, upload(it, total, type = CoapMessageType.CON), it).single() }
        assertTrue(dispositions.all { it is QBlockAction.NoResponse<*> }, "§4.3: no 2.31 for Confirmable blocks")
        assertEquals(timing.nonPartialTimeout, receiver.nextDeadline())
        assertEquals(emptyList(), receiver.onTimer(200.seconds))
        assertIs<QBlockAction.Discard>(receiver.onTimer(timing.nonPartialTimeout).single())
    }

    @Test
    fun rfc9177AppendixBReliableBlocksAreAnsweredWithoutResponse() {
        val receiver = receiver(BodyMode.RELIABLE)
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(0))
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(2), "never a 4.08 list")
        assertEquals(timing.nonPartialTimeout, receiver.nextDeadline())
    }

    @Test
    fun rfc8323Section6ABertBlockCoversSeveralNumbers() {
        fun bert(num: Long, payload: Int, more: Boolean) = CoapMessage.request(CoapCode.PUT) {
            this.payload = ByteArray(payload) { (num * 1024 + it).toByte() }
            options {
                requestTag(byteArrayOf(1))
                size1(3000)
                qBlock1(BlockOption(num, more, 7))
            }
        }
        val receiver = QBlock1Receiver<Long>(bert(0, 2048, true), BodyMode.RELIABLE, timing, Duration.ZERO)
        assertEquals(3, receiver.count)
        assertIs<QBlockAction.NoResponse<Long>>(receiver.onBlock(Duration.ZERO, bert(0, 2048, true), 0).single())
        val deliver = assertIs<QBlockAction.Deliver<Long>>(receiver.onBlock(Duration.ZERO, bert(2, 952, false), 2).single())
        assertContentEquals(ByteArray(3000) { it.toByte() }, deliver.message.payload)
        val beyond = QBlock1Receiver<Long>(bert(0, 2048, true), BodyMode.RELIABLE, timing, Duration.ZERO)
        assertIs<QBlockAction.Reject<Long>>(beyond.onBlock(Duration.ZERO, bert(2, 2048, true), 2).single())
    }

    @Test
    fun rfc9177Section7_2ABodyWhoseEveryBlockWasRejectedIsDiscardedByItsTimer() {
        // Block 2 of a 128-byte body, empty: QBlockCheck refuses it first; on its own the receiver rejects it.
        val receiver = receiver(first = upload(2, 128))
        assertIs<QBlockAction.Reject<Long>>(receiver.onBlock(Duration.ZERO, upload(2, 128), 2).single())

        val discard = assertIs<QBlockAction.Discard>(receiver.onTimer(4.seconds).single())
        assertEquals("No block of the body arrived", discard.reason, "no request to put a 4.08 on")
        assertTrue(receiver.isDiscarded)
        assertNull(receiver.nextDeadline())
    }

    @Test
    fun rfc9177Section4_3AnEmptyBodyIsCompleteWithItsOnlyBlock() {
        val receiver = receiver(first = upload(0, 0))
        assertEquals(1, receiver.count)
        assertEquals(0, assertIs<QBlockAction.Deliver<Long>>(receiver.onBlock(Duration.ZERO, upload(0, 0), 0).single()).message.payloadSize)
        assertTrue(receiver.toString().startsWith("QBlock1Receiver(0 bytes"))
    }

    @Test
    fun rfc9177Section4_3TheFirstBlockMayBeAnyBlock() {
        val receiver = receiver(first = upload(3, total))
        assertIs<QBlockAction.NoResponse<Long>>(receiver.block(3))
        assertEquals(block(3, true), upload(3, total).options.qBlock1)
    }
}
