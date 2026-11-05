package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.stack.QBlockMessages.block
import dev.kcoap.coap.stack.QBlockMessages.followUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The server side of Q-Block2 (RFC 9177 §4.4, §7) on its own: which blocks go on which request, and when. */
class QBlock2SenderTest {
    private val pause = 2.5.seconds

    private fun sender(
        size: Int = 25 * 64 + 9,
        mode: BodyMode = BodyMode.NON,
        parameters: TransmissionParameters = TransmissionParameters(),
        piggybacked: Boolean = mode == BodyMode.CON,
    ) = QBlock2Sender<String>(size, 64, mode, QBlockTiming(parameters), pause, piggybacked)

    private fun List<QBlockAction<String>>.sent(): List<String> =
        filterIsInstance<QBlockAction.SendBlock<String>>().map { "${it.num}/${if (it.more) 1 else 0}@${it.ref}" }

    @Test
    fun rfc9177Section7_2SetsGoOutOnTheOriginalRequestNonTimeoutRandomApart() {
        val sender = sender()
        assertEquals(26, sender.count)
        assertEquals(2, sender.sizeExponent)
        assertEquals((0..9).map { "$it/1@get" }, sender.start(Duration.ZERO, "get").sent())
        assertTrue(sender.isSending)
        assertEquals(pause, sender.nextDeadline())
        assertEquals(emptyList(), sender.onTimer(2.seconds))
        assertEquals((10..19).map { "$it/1@get" }, sender.onTimer(pause).sent())
        assertEquals((20..25).map { "$it/${if (it < 25) 1 else 0}@get" }, sender.onTimer(pause * 2).sent())
        assertNull(sender.nextDeadline())
        assertFalse(sender.isSending)
        assertFailsWith<IllegalStateException> { sender.start(Duration.ZERO, "again") }
    }

    @Test
    fun rfc9177Section4_4AContinueSendsItsSetAtOnce() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        val actions = sender.onRequest(1.seconds, followUp(block(10, true)), "continue")
        assertEquals("continue", assertIs<QBlockAction.NoResponse<String>>(actions.first()).ref, "nothing on the Continue's token")
        assertEquals((10..19).map { "$it/1@get" }, actions.sent())
        assertEquals(1.seconds + pause, sender.nextDeadline())
        assertEquals(emptyList(), sender.onRequest(2.seconds, followUp(block(10, true)), "again").sent(), "a repeat within the pause")
        assertEquals(10, sender.onRequest(1.seconds + pause, followUp(block(10, true)), "later").sent().size, "after it, served again")
    }

    @Test
    fun rfc9177Section4_4AContinueOnABodyThatNeverStartedBecomesItsOriginalRequest() {
        val sender = sender()
        // The first request asked for block 0 alone, as libcoap's probe does with Q-Block2 0/0.
        assertEquals(listOf("0/1@probe"), sender.onRequest(Duration.ZERO, followUp(block(0, false)), "probe").sent())
        val actions = sender.onRequest(1.seconds, followUp(block(10, true)), "continue")
        assertTrue(actions.none { it is QBlockAction.NoResponse<*> }, "the Continue gets the set itself")
        assertEquals((10..19).map { "$it/1@continue" }, actions.sent())
        assertEquals((20..25).map { "$it/${if (it < 25) 1 else 0}@continue" }, sender.onTimer(1.seconds + pause).sent())
    }

    @Test
    fun rfc9177Section4_4AContinueBeyondTheBodyIsRefused() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        assertEquals(CoapCode.BAD_REQUEST, assertIs<QBlockAction.Reject<String>>(sender.onRequest(1.seconds, followUp(block(30, true)), "x").single()).code)
    }

    @Test
    fun rfc9177Section4_4WholeBodyAgainFromTheCache() {
        val sender = sender()
        sender.start(Duration.ZERO, "post")
        sender.onTimer(pause)
        assertEquals((0..9).map { "$it/1@again" }, sender.onRequest(3.seconds, followUp(block(0, true)), "again").sent())
        assertEquals(3.seconds + pause, sender.nextDeadline())
        assertEquals((10..19).map { "$it/1@again" }, sender.onTimer(3.seconds + pause).sent())
    }

    @Test
    fun rfc9177Section4_4MissingBlocksGoOnTheRequestThatAskedOnce() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        val actions = sender.onRequest(1.seconds, followUp(block(2, false), block(4, true), block(7, false)), "missing")
        assertEquals((listOf(2) + (4..9)).map { "$it/1@missing" }, actions.sent(), "4/M=1 is the rest of set 0; 7 is sent once")
        assertEquals(1.seconds + pause, sender.nextDeadline(), "§7.2: the next set waits another NON_TIMEOUT_RANDOM")
        val last = sender.onRequest(2.seconds, followUp(block(25, false), block(30, false)), "end")
        assertEquals(listOf("25/0@end"), last.sent(), "numbers beyond the body are left out")
    }

    @Test
    fun rfc9177Section4_4MoreThanMaxPayloadsMissingBlocksGoInSets() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        val actions = sender.onRequest(1.seconds, followUp(block(1, true), block(11, true), block(21, false)), "missing")
        assertEquals((1..9).map { "$it/1@missing" } + "11/1@missing", actions.sent())
        assertTrue(sender.isSending)
        assertEquals(1.seconds + pause, sender.nextDeadline())
        val second = sender.onTimer(1.seconds + pause).sent()
        assertEquals((12..19).map { "$it/1@missing" } + "21/1@missing", second, "§7.2: one set per pause")
        assertEquals(1.seconds + pause * 2, sender.nextDeadline())
        assertEquals((10..19).map { "$it/1@get" }, sender.onTimer(1.seconds + pause * 2).sent(), "then the next set of the body")
    }

    @Test
    fun rfc9177Section7_2ThreeChunksOfMissingBlocksAndTheNextSetEachWaitAPause() {
        val sender = sender(size = 40 * 64)
        sender.start(Duration.ZERO, "get")
        val missing = followUp(*(1L..9L).map { block(it, false) }.toTypedArray(), block(11, true), block(21, true))
        val times = mutableListOf(1.seconds to sender.onRequest(1.seconds, missing, "missing").sent().size)
        while (true) {
            val at = sender.nextDeadline() ?: break
            times += at to sender.onTimer(at).sent().size
        }
        assertEquals(
            listOf(1.seconds to 10, 1.seconds + pause to 10, 1.seconds + pause * 2 to 7, 1.seconds + pause * 3 to 10) +
                listOf(4, 5).map { 1.seconds + pause * it to 10 },
            times,
            "at most MAX_PAYLOADS blocks at any instant",
        )
    }

    @Test
    fun rfc9177Section4_4RequestsThatDoNotFitTheBodyAreRefused() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        assertEquals("No Q-Block2", assertIs<QBlockAction.Reject<String>>(sender.onRequest(1.seconds, followUp(), "x").single()).diagnostic)
        assertIs<QBlockAction.Reject<String>>(sender.onRequest(1.seconds, followUp(block(1, false, 32)), "x").single())
        assertIs<QBlockAction.Reject<String>>(sender.onRequest(1.seconds, followUp(block(40, false), block(41, false)), "x").single())
    }

    @Test
    fun rfc9177Section4_4AResetStopsTheBody() {
        val sender = sender()
        sender.start(Duration.ZERO, "get")
        assertEquals(emptyList(), sender.onReset(1.seconds))
        assertTrue(sender.isStopped)
        assertFalse(sender.isSending)
        assertNull(sender.nextDeadline())
        assertEquals(emptyList(), sender.onTimer(pause))
        assertEquals(listOf("3/1@missing"), sender.onRequest(2.seconds, followUp(block(3, false)), "missing").sent(), "the cache still serves")
    }

    @Test
    fun rfc9177AppendixAConfirmableBlocksFollowAcknowledgementsUnderNstart() {
        val sender = sender(size = 6 * 64, mode = BodyMode.CON, parameters = TransmissionParameters(nstart = 3))
        assertEquals(listOf("0/1@get", "1/1@get", "2/1@get", "3/1@get"), sender.start(Duration.ZERO, "get").sent(), "block 0 piggybacked")
        assertNull(sender.nextDeadline())
        assertEquals(listOf("4/1@get"), sender.onAcknowledged(1.seconds, 1).sent())
        assertEquals(listOf("5/0@get"), sender.onAcknowledged(1.seconds, 2).sent())
        assertEquals(emptyList(), sender.onAcknowledged(1.seconds, 3).sent())
        assertEquals(listOf("2/1@missing"), sender.onRequest(2.seconds, followUp(block(2, false)), "missing").sent())
        assertEquals(emptyList(), sender.onTimeout(60.seconds, 4))
        assertTrue(sender.isStopped)
        assertEquals(emptyList(), sender.onAcknowledged(61.seconds, 5))
    }

    @Test
    fun rfc9177AppendixANotificationBodiesStartWithoutPiggybacking() {
        val sender = sender(size = 6 * 64, mode = BodyMode.CON, piggybacked = false)
        assertEquals(listOf("0/1@obs"), sender.start(Duration.ZERO, "obs").sent(), "NSTART 1")
        assertEquals(listOf("1/1@obs"), sender.onAcknowledged(1.seconds, 0).sent())
        assertEquals(emptyList(), sender(mode = BodyMode.NON).onAcknowledged(1.seconds, 0), "only Confirmable bodies count ACKs")
    }

    @Test
    fun rfc9177Section4_4ABodyThatNeverStartedSendsNothingByItself() {
        for (mode in listOf(BodyMode.NON, BodyMode.CON)) {
            val sender = sender(mode = mode)
            assertEquals(listOf("0/1@probe"), sender.onRequest(Duration.ZERO, followUp(block(0, false)), "probe").sent(), "$mode")
            assertFalse(sender.isSending, "$mode: only the block asked for")
            assertNull(sender.nextDeadline())
        }
    }

    @Test
    fun rfc9177AppendixBReliableBlocksGoBackToBack() {
        val sender = sender(mode = BodyMode.RELIABLE)
        assertEquals(26, sender.start(Duration.ZERO, "get").sent().size)
        assertNull(sender.nextDeadline())
        assertFalse(sender.isSending)
        assertEquals(10, sender.onRequest(1.seconds, followUp(block(10, true)), "continue").sent().size)
        assertNull(sender.nextDeadline(), "no sets on a reliable transport")
    }

    @Test
    fun rfc9177Section4_4AnEmptyBodyIsOneEmptyBlock() {
        val sender = sender(size = 0)
        assertEquals(listOf("0/0@get"), sender.start(Duration.ZERO, "get").sent())
        assertTrue(sender.toString().startsWith("QBlock2Sender(0 bytes in 1 blocks of 64"))
        assertFailsWith<IllegalArgumentException> { sender(size = -1) }
        assertFailsWith<IllegalArgumentException> {
            QBlock2Sender<String>(16 * (1 shl 20) + 1, 16, BodyMode.NON, QBlockTiming(TransmissionParameters()), pause)
        }
    }
}
