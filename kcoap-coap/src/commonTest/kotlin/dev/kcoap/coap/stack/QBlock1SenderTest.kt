package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.stack.QBlockMessages.block
import dev.kcoap.coap.stack.QBlockMessages.missing
import dev.kcoap.coap.stack.QBlockMessages.response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The client side of Q-Block1 (RFC 9177 §4.3, §7) on its own: what goes out for each response and timer. */
class QBlock1SenderTest {
    private val timing = QBlockTiming(TransmissionParameters())
    private val pause = 2.5.seconds

    private fun sender(
        size: Int = 30 * 64 + 10,
        mode: BodyMode = BodyMode.NON,
        resendsLastBlock: Boolean = true,
        parameters: TransmissionParameters = TransmissionParameters(),
        mayRestart: Boolean = true,
        allowsBert: Boolean = false,
    ) = QBlock1Sender(size, 64, mode, QBlockTiming(parameters), pause, resendsLastBlock, mayRestart, allowsBert)

    private fun List<QBlockAction<Int>>.sent(): List<String> =
        filterIsInstance<QBlockAction.SendBlock<Int>>().map { "${it.num}/${if (it.more) 1 else 0}@${it.ref}" }

    private fun List<QBlockAction<Int>>.released(): List<Int> = filterIsInstance<QBlockAction.Release<Int>>().flatMap { it.refs }

    private val continue231 = response(CoapCode.CONTINUE)

    @Test
    fun rfc9177Section7_2SetsGoOutNonTimeoutRandomApart() {
        val sender = sender()
        assertEquals(31, sender.count)
        assertEquals(List(10) { "$it/1@$it" }, sender.start(Duration.ZERO).sent())
        assertEquals(pause, sender.nextDeadline())
        assertEquals(emptyList(), sender.onTimer(2.seconds), "nothing before the deadline")
        assertEquals(List(10) { "${it + 10}/1@${it + 10}" }, sender.onTimer(pause).sent())
        assertEquals(List(10) { "${it + 20}/1@${it + 20}" }, sender.onTimer(pause * 2).sent())
        assertEquals(listOf("30/0@30"), sender.onTimer(pause * 3).sent(), "the last set is the last block alone")
        assertEquals(pause * 3 + 4.seconds, sender.nextDeadline(), "then only the silence ladder")
        assertEquals(30L, sender.numberOf(30))
        assertNull(sender.numberOf(31))
    }

    @Test
    fun rfc9177Section4_3AnEmptyBodyIsOneEmptyBlock() {
        val sender = sender(size = 0)
        assertEquals(1, sender.count)
        assertEquals(listOf("0/0@0"), sender.start(Duration.ZERO).sent())
        assertFailsWith<IllegalStateException> { sender.start(Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { sender(size = -1) }
        assertFailsWith<IllegalArgumentException> { QBlock1Sender(16 * (1 shl 20) + 1, 16, BodyMode.NON, timing, pause, true) }
    }

    @Test
    fun rfc9177Section4_3A231ConfirmsTheSetAndSkipsThePause() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val actions = sender.onResponse(1.seconds, 9, continue231)
        assertEquals(List(10) { "${it + 10}/1@${it + 10}" }, actions.sent(), "the NUM of the token, 9, ends the set")
        assertEquals((0..8).toList(), actions.released(), "the 2.31's own token stays")
        assertEquals(1.seconds + pause, sender.nextDeadline())
        // A 2.31 naming its NUM; the earlier 2.31's token goes now.
        val next = sender.onResponse(2.seconds, 15, response(CoapCode.CONTINUE) { qBlock1(block(19, true)) })
        assertEquals(List(10) { "${it + 20}/1@${it + 20}" }, next.sent())
        assertEquals(((10..19) - 15 + 9).sorted(), next.released().sorted())
    }

    @Test
    fun rfc9177Section4_3A231ForAnEarlierBlockOnlyConfirms() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val actions = sender.onResponse(1.seconds, 4, continue231)
        assertEquals(emptyList(), actions.sent())
        assertEquals(listOf(0, 1, 2, 3), actions.released())
        assertEquals(pause, sender.nextDeadline())
        assertIs<QBlockAction.Drop>(
            sender.onResponse(1.seconds, 4, response(CoapCode.CONTINUE) { qBlock1(block(30, true)) }).single(),
            "a 2.31 cannot confirm the last block",
        )
    }

    @Test
    fun rfc9177Section4_3A408HasTheListedBlocksSentAgainThenWaits() {
        val sender = sender()
        sender.start(Duration.ZERO)
        sender.onTimer(pause)
        val actions = sender.onResponse(3.seconds, 12, missing(1, 9))
        assertEquals(listOf("1/1@20", "9/1@21"), actions.sent())
        assertEquals(3.seconds + pause, sender.nextDeadline(), "§7.2: another NON_TIMEOUT_RANDOM before the next set")
    }

    @Test
    fun rfc9177Section5ABadOrEmptyMissingBlockListIsIgnored() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val broken = response(CoapCode.REQUEST_ENTITY_INCOMPLETE, byteArrayOf(9, 1)) { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
        assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 0, broken).single())
        assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 0, missing(31, 40)).single(), "beyond the body")
        assertTrue(!sender.isFinished)
    }

    @Test
    fun rfc9177Section4_3AtMostMaxPayloadsBlocksAreSentAgainAtOnce() {
        val sender = sender()
        sender.start(Duration.ZERO)
        sender.onTimer(pause)
        val actions = sender.onResponse(3.seconds, 0, missing(*LongArray(12) { it.toLong() }))
        assertEquals((0..9).map { "$it/1@${it + 20}" }, actions.sent())
    }

    @Test
    fun aBlockAskedForTooOftenFailsTheBody() {
        val sender = sender()
        sender.start(Duration.ZERO)
        repeat(5) { assertEquals(1, sender.onResponse(it.seconds, 0, missing(1)).sent().size) }
        val actions = sender.onResponse(6.seconds, 0, missing(1))
        val failure = assertIs<QBlockAction.Fail>(actions.first())
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, failure.reason)
        assertTrue(sender.isFinished)
        assertNull(sender.nextDeadline())
        assertTrue(actions.released().isNotEmpty(), "every token goes")
    }

    @Test
    fun rfc9175Section2_3EveryBlockOfTheBurstChallengedWithEchoIsSentAgain() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val challenge = response(CoapCode.UNAUTHORIZED) { echo(byteArrayOf(1, 2)) }
        assertEquals(listOf("0/1@10"), sender.onResponse(1.seconds, 0, challenge).sent())
        assertEquals(listOf("1/1@11"), sender.onResponse(1.seconds, 1, challenge).sent(), "sent before the Echo was known")
        assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 0, challenge).single(), "once per challenge")
        assertFalse(sender.isFinished)
        val again = assertIs<QBlockAction.Deliver<Int>>(sender.onResponse(2.seconds, 10, challenge).first())
        assertEquals(CoapCode.UNAUTHORIZED, again.message.code, "the copy carried the Echo")
        assertTrue(sender.isFinished)
    }

    @Test
    fun rfc7252Section5_4_1AResponseWithABadQBlockOptionIsDropped() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val tooLong = response(CoapCode.CONTINUE) { add(CoapOptions.Q_BLOCK1.number, ByteArray(4)) }
        val dropped = assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 9, tooLong).single())
        assertEquals("A Q-Block option longer than 3 bytes", dropped.reason)
        val mixed = response(CoapCode.CONTINUE) {
            qBlock1(block(9, true))
            block1(block(9, true))
        }
        assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 9, mixed).single())
        assertEquals(pause, sender.nextDeadline(), "nothing changed")
        assertFalse(sender.isFinished)
    }

    @Test
    fun rfc7959Section2_9_3A413NamingASmallerSizeRestartsOnce() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val smaller = response(CoapCode.REQUEST_ENTITY_TOO_LARGE) { qBlock1(block(0, true, 32)) }
        val actions = sender.onResponse(1.seconds, 0, smaller)
        assertEquals(32, assertIs<QBlockAction.Restart>(actions.last()).blockSize)
        assertEquals((0..9).toList(), actions.released())
        assertTrue(sender.isFinished)
        val again = sender(mayRestart = false)
        again.start(Duration.ZERO)
        assertIs<QBlockAction.Deliver<Int>>(again.onResponse(1.seconds, 0, smaller).first(), "the restarted body takes the 4.13")
    }

    @Test
    fun rfc7959Section2_9_3A413WithoutASmallerSizeIsDelivered() {
        val bert = response(CoapCode.REQUEST_ENTITY_TOO_LARGE) { qBlock1(BlockOption(0, true, 7)) }
        for ((tooLarge, allowsBert) in listOf(
            response(CoapCode.REQUEST_ENTITY_TOO_LARGE) { size1(100) } to false,
            response(CoapCode.REQUEST_ENTITY_TOO_LARGE) { block1(block(0, true, 128)) } to false,
            bert to true,
        )) {
            val sender = sender(allowsBert = allowsBert)
            sender.start(Duration.ZERO)
            assertEquals(tooLarge, assertIs<QBlockAction.Deliver<Int>>(sender.onResponse(1.seconds, 2, tooLarge).first()).message)
        }
        val udp = sender()
        udp.start(Duration.ZERO)
        val reserved = assertIs<QBlockAction.Drop>(udp.onResponse(1.seconds, 2, bert).single())
        assertEquals("SZX 7 is reserved without BERT", reserved.reason)
        val sender = sender()
        sender.start(Duration.ZERO)
        val smallerBlock1 = response(CoapCode.REQUEST_ENTITY_TOO_LARGE) { block1(block(0, true, 16)) }
        assertEquals(16, assertIs<QBlockAction.Restart>(sender.onResponse(1.seconds, 2, smallerBlock1).last()).blockSize)
    }

    @Test
    fun rfc9177Section4_3TheFinalResponseMayComeOnAnyTokenOfTheBody() {
        // Figure 14 answers on the token of the body's last block, not of the request that completed it.
        val sender = sender(size = 4 * 64 - 10)
        sender.start(Duration.ZERO)
        assertEquals(listOf(0), sender.onResponse(4.seconds, 3, missing(1, 2)).released(), "block 0 arrived")
        val actions = sender.onResponse(4.seconds, 3, response(CoapCode.CONTENT) { qBlock2(block(0, true)) })
        assertEquals(3, assertIs<QBlockAction.Deliver<Int>>(actions.first()).ref)
        assertEquals(listOf(1, 2, 4, 5), actions.released(), "every other token but the one of the response")
        assertIs<QBlockAction.Drop>(sender.onResponse(5.seconds, 3, continue231).single(), "after the end")
        assertEquals(emptyList(), sender.onTimer(10.seconds))
        assertEquals(emptyList(), sender.abandon())
    }

    @Test
    fun rfc9177Section4_3ErrorsEndTheBodyAndAreDelivered() {
        for (code in listOf(CoapCode.BAD_REQUEST, CoapCode.BAD_OPTION, CoapCode.REQUEST_ENTITY_INCOMPLETE, CoapCode.SERVICE_UNAVAILABLE)) {
            val sender = sender()
            sender.start(Duration.ZERO)
            val actions = sender.onResponse(1.seconds, 5, response(code))
            assertEquals(code, assertIs<QBlockAction.Deliver<Int>>(actions.first()).message.code, "a 4.08 without 272 too")
            assertEquals((0..9) - 5, actions.released())
        }
    }

    @Test
    fun aResponseOnATokenOfAnotherBodyIsIgnored() {
        val sender = sender()
        sender.start(Duration.ZERO)
        assertIs<QBlockAction.Drop>(sender.onResponse(1.seconds, 99, continue231).single())
        assertEquals(emptyList(), sender.onAcknowledged(1.seconds, 99))
        assertEquals(emptyList(), sender.onTimeout(1.seconds, 99))
        assertEquals(emptyList(), sender.onReset(1.seconds, 99))
    }

    @Test
    fun anIdempotentBodySendsItsLastBlockAgainAfterSilence() {
        val sender = sender(size = 3 * 64)
        sender.start(Duration.ZERO)
        val resends = mutableListOf<Duration>()
        var failure: QBlockAction.Fail? = null
        while (failure == null) {
            val now = checkNotNull(sender.nextDeadline())
            val actions = sender.onTimer(now)
            if (actions.sent().isNotEmpty()) {
                assertEquals(listOf("2/0@${resends.size + 3}"), actions.sent(), "the last block on a new token")
                resends += now
            }
            failure = actions.filterIsInstance<QBlockAction.Fail>().firstOrNull()?.also { assertEquals(124.seconds, now) }
        }
        assertEquals(listOf(4, 12, 28, 60).map { it.seconds }, resends, "NON_RECEIVE_TIMEOUT × 2^k from the previous send")
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
    }

    @Test
    fun aBodyThatMustNotBeRepeatedFailsAfterTheLadderWithoutResending() {
        val sender = sender(size = 3 * 64, resendsLastBlock = false)
        sender.start(Duration.ZERO)
        assertEquals(124.seconds, sender.nextDeadline())
        val actions = sender.onTimer(124.seconds)
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, assertIs<QBlockAction.Fail>(actions.first()).reason)
        assertEquals(emptyList(), actions.sent())
    }

    @Test
    fun aResponseRestartsTheSilenceLadder() {
        val sender = sender(size = 3 * 64)
        sender.start(Duration.ZERO)
        sender.onTimer(4.seconds)
        assertEquals(12.seconds, sender.nextDeadline())
        sender.onResponse(10.seconds, 0, continue231)
        assertEquals(14.seconds, sender.nextDeadline())
    }

    /** The server's 4.08 ladder and the client's silence ladder run on the same times (Figure 6). */
    @Test
    fun afterA408TheLastBlockWaitsUntilTheServersLadderCouldHaveRunOut() {
        val sender = sender(size = 3 * 64)
        sender.start(Duration.ZERO)
        assertEquals(listOf("1/1@3"), sender.onResponse(4.seconds, 2, missing(1)).sent())
        assertEquals(128.seconds, sender.nextDeadline(), "not at 8 s: the server holds the body")
        assertEquals(listOf("1/1@4"), sender.onResponse(12.seconds, 2, missing(1)).sent())
        assertEquals(136.seconds, sender.nextDeadline())
        assertEquals(listOf("2/0@5"), sender.onTimer(136.seconds).sent(), "real silence: the last block again")
        assertEquals(144.seconds, sender.nextDeadline(), "then the ladder as usual")
        val post = sender(size = 3 * 64, resendsLastBlock = false)
        post.start(Duration.ZERO)
        post.onResponse(4.seconds, 2, missing(1))
        assertEquals(128.seconds, post.nextDeadline())
    }

    @Test
    fun rfc9177Section7_1ConfirmableBlocksGoInLockStepUnderNstart() {
        val sender = sender(mode = BodyMode.CON, parameters = TransmissionParameters(nstart = 2))
        assertEquals(listOf("0/1@0", "1/1@1"), sender.start(Duration.ZERO).sent())
        assertNull(sender.nextDeadline(), "the messaging layer times Confirmable blocks")
        assertEquals(listOf("2/1@2"), sender.onAcknowledged(1.seconds, 0).sent())
        assertEquals(listOf("3/1@3"), sender.onResponse(1.seconds, 1, continue231).sent(), "a 2.31 acknowledges like an ACK")
        assertEquals(listOf("1/1@4"), sender.onResponse(1.seconds, 1, missing(1)).sent(), "a stray 4.08 list is honoured")
        val timedOut = sender.onTimeout(2.seconds, 2)
        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, assertIs<QBlockAction.Fail>(timedOut.first()).reason)
        assertEquals(emptyList(), sender.onAcknowledged(3.seconds, 3))
    }

    @Test
    fun rfc9177Section5A408ReleasesTheTokensOfTheBlocksItShowsArrived() {
        val sender = sender()
        sender.start(Duration.ZERO)
        sender.onTimer(pause)

        // On block 15's request, the server misses 2 and 7: every other block up to 15 has arrived.
        val short = sender.onResponse(3.seconds, 15, missing(2, 7))
        assertEquals((0..14) - listOf(2, 7), short.released())
        assertEquals(listOf("2/1@20", "7/1@21"), short.sent())

        // A full list may have been cut short: only the blocks below its last entry count as arrived.
        val cut = sender()
        cut.start(Duration.ZERO)
        cut.onTimer(pause)
        val full = cut.onResponse(3.seconds, 19, missing(1, 3, 5, 7, 9, 11, 13, 15, 17, 18))
        assertEquals(listOf(0, 2, 4, 6, 8, 10, 12, 14, 16), full.released())
    }

    @Test
    fun rfc9177Section4_3AnAcknowledgedConfirmableBlockReleasesItsTokenWhileLaterBlocksAreUnsent() {
        val sender = sender(size = 4 * 64, mode = BodyMode.CON)
        sender.start(Duration.ZERO)

        val first = sender.onAcknowledged(1.seconds, 0)
        val second = sender.onAcknowledged(2.seconds, 1)
        val third = sender.onAcknowledged(3.seconds, 2)

        assertEquals(listOf(0), first.released(), "block 0 cannot complete the body while 1 to 3 are unsent")
        assertEquals(listOf("1/1@1"), first.sent())
        assertEquals(listOf(1), second.released())
        assertEquals(listOf(2), third.released())
        assertEquals(listOf("3/0@3"), third.sent())
        assertEquals(emptyList(), sender.onAcknowledged(4.seconds, 3).released(), "the last block may carry the final response")
    }

    @Test
    fun rfc9177Section4_3WithNstart2ABlockAcknowledgedOnceEveryBlockWentOutKeepsItsToken() {
        val sender = sender(size = 3 * 64, mode = BodyMode.CON, parameters = TransmissionParameters(nstart = 2))
        sender.start(Duration.ZERO)

        assertEquals(listOf(0), sender.onAcknowledged(1.seconds, 0).released())
        assertEquals(emptyList(), sender.onAcknowledged(2.seconds, 1).released(), "block 1 may arrive after block 2")
    }

    @Test
    fun rfc9177Section4_3AResetFailsTheBody() {
        val sender = sender()
        sender.start(Duration.ZERO)
        val actions = sender.onReset(1.seconds, 3)
        assertEquals(CoapException.Reason.BLOCKWISE_FAILED, assertIs<QBlockAction.Fail>(actions.first()).reason)
        assertEquals((0..9).toList(), actions.released())
    }

    @Test
    fun rfc9177AppendixBReliableBlocksGoBackToBack() {
        val sender = sender(mode = BodyMode.RELIABLE)
        assertEquals(31, sender.start(Duration.ZERO).sent().size)
        assertNull(sender.nextDeadline())
        assertEquals(emptyList(), sender.onResponse(1.seconds, 5, continue231), "no 2.31 on a reliable transport")
        assertEquals(emptyList(), sender.onAcknowledged(1.seconds, 5))
    }

    @Test
    fun rfc9177Section4_3AnAbandonedBodyReleasesEveryToken() {
        val sender = sender()
        sender.start(Duration.ZERO)
        assertEquals((0..9).toList(), sender.abandon().released())
        assertTrue(sender.isFinished)
        assertTrue(sender.toString().startsWith("QBlock1Sender(1930 bytes in 31 blocks of 64"))
    }
}
