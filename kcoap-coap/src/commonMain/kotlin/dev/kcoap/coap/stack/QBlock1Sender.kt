package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.ContentFormat
import kotlin.time.Duration

/**
 * The client side of a Q-Block1 upload (RFC 9177 §4.3, §7) as a sans-IO state machine: responses and time go
 * in, [QBlockAction]s come out, and [nextDeadline] says when [onTimer] must run. Every block is sent as a new
 * request with a sequence number of its own (the driver gives each a new token, §4.3); responses are fed back
 * with the sequence number of the request whose token they carry.
 *
 * - [BodyMode.NON]: sets of MAX_PAYLOADS blocks, NON_TIMEOUT_RANDOM ([nonTimeoutRandom], drawn once per body)
 *   between them (§7.2). A 2.31 that completes the current set sends the next one at once (Figure 3). A 4.08
 *   with Content-Format 272 has the listed blocks sent again, in ascending order and at most MAX_PAYLOADS of
 *   them, followed by the rest of the current set, then the wait starts again (§7.2, Figures 4 and 5).
 * - [BodyMode.CON]: at most NSTART blocks unacknowledged; each acknowledgement sends the next (§7.1,
 *   Figures 16 and 17). The messaging layer retransmits; a block it gives up on fails the body.
 * - [BodyMode.RELIABLE]: every block back to back (Appendix B, Figure 19).
 *
 * Any other response ends the body and is delivered: the final 2.01, 2.02, 2.04 or 2.05 on whichever token of
 * the body it comes (§4.3 only requires one of them), and errors such as 4.00, 4.02, 4.13 or 5.xx as they are.
 *
 * Policies where RFC 9177 is silent:
 * - The 2.31 Q-Block1 NUM, or without one the block sent on that token, confirms every block up to it.
 * - Silence after the last block of a Non-confirmable body: if [resendsLastBlock] (PUT, FETCH and iPATCH,
 *   which may be repeated), the last block is sent again NON_RECEIVE_TIMEOUT × 2^k after the latest send or
 *   response, at most NON_MAX_RETRANSMIT times; a server that completed the body answers the copy with its
 *   final response again (§4.3), one that lost the body starts asking for blocks. Then, or without
 *   [resendsLastBlock] (POST, PATCH) once the whole ladder (124 s by default) passed without an answer, the
 *   body fails with [CoapException.Reason.RESPONSE_TIMEOUT].
 * - A block asked for in more than NON_MAX_RETRANSMIT + 1 4.08s fails the body: the server is not making
 *   progress.
 * - A 4.01 with Echo (RFC 9175 §2.3) has its block sent again once per body; the driver adds the Echo value to
 *   that and every later block.
 * - A 4.13 whose Q-Block1 or Block1 names a smaller block size restarts the upload once, with that size and a
 *   new Request-Tag ([QBlockAction.Restart], RFC 7959 §2.9.3).
 * - Tokens: once a 2.31 confirms blocks, their requests are released, except the one that carried the latest
 *   2.31, on which the server may still send a 4.08 (§7.2); when the body ends every request but the one with
 *   the final response is released.
 *
 * Not thread safe: the driver serializes all calls.
 *
 * @param size1 the body size, sent as Size1 on every block (§4.6).
 * @param blockSize the block size, 16 to 1024 bytes, fixed for the body. Never BERT: kcoap does not send SZX 7.
 */
internal class QBlock1Sender(
    val size1: Int,
    val blockSize: Int,
    private val mode: BodyMode,
    private val timing: QBlockTiming,
    private val nonTimeoutRandom: Duration,
    private val resendsLastBlock: Boolean,
    private val mayRestart: Boolean = true,
) {
    init {
        require(size1 >= 0) { "Negative body size: $size1" }
    }

    val sizeExponent: Int = BlockOption.sizeExponentFor(blockSize)

    /** How many blocks the body has; an empty body is one empty block. */
    val count: Long = maxOf(1L, (size1.toLong() + blockSize - 1) / blockSize).also {
        require(it <= ReceivedBlocks.MAX_COUNT) { "A body of $size1 bytes needs more than 2^20 blocks of $blockSize" }
    }

    private val sets = timing.sets
    private val last = count - 1

    /** The block each sequence number carried. */
    private val numbers = ArrayList<Long>()

    /** Sequence numbers whose tokens are still held. */
    private val held = LinkedHashSet<Int>()
    private val sent = ReceivedBlocks(count)
    private val requests = HashMap<Long, Int>()

    private var started = false
    private var currentSet = 0L
    private var nextSetAt: Duration? = null
    private var nextUnsent = 0L
    private var lastActivity = Duration.ZERO
    private var lastBlockSent = false
    private var silenceResends = 0
    private var latest231: Int? = null
    private var echoed = false

    /** Whether the body ended: delivered, failed, restarted or abandoned. */
    var isFinished: Boolean = false
        private set

    /** The block that request [sequence] carried, or null for a sequence number this body never used. */
    fun numberOf(sequence: Int): Long? = numbers.getOrNull(sequence)

    /** Sends the first set (NON), the first NSTART blocks (CON) or every block (reliable). */
    fun start(now: Duration): List<QBlockAction<Int>> {
        check(!started) { "Already started" }
        started = true
        lastActivity = now
        val actions = ArrayList<QBlockAction<Int>>()
        when (mode) {
            BodyMode.NON -> sendSet(now, 0, actions)
            BodyMode.CON -> repeat(minOf(timing.parameters.nstart.toLong(), count).toInt()) { sendNext(now, actions) }
            BodyMode.RELIABLE -> while (nextUnsent < count) sendNext(now, actions)
        }
        return actions
    }

    /** When [onTimer] must run next: the next set, or the silence ladder after the last block (NON only). */
    fun nextDeadline(): Duration? = if (isFinished || mode != BodyMode.NON) null else nextSetAt ?: silenceDeadline()

    /** The silence ladder runs once every set went out and the last block with it. */
    private fun silenceDeadline(): Duration? = when {
        !lastBlockSent || nextSetAt != null -> null
        resendsLastBlock -> lastActivity + timing.receiveWait(silenceResends)
        else -> lastActivity + timing.receiveLadder
    }

    fun onTimer(now: Duration): List<QBlockAction<Int>> {
        if (isFinished) return emptyList()
        val actions = ArrayList<QBlockAction<Int>>()
        nextSetAt?.let { at ->
            if (now >= at) {
                nextSetAt = null
                sendSet(now, currentSet + 1, actions)
            }
        }
        val silence = silenceDeadline()
        if (silence != null && now >= silence) {
            if (!resendsLastBlock || silenceResends >= timing.nonMaxRetransmit) {
                return actions + fail(CoapException.Reason.RESPONSE_TIMEOUT, "No response to the last block of the body")
            }
            silenceResends++
            actions += send(now, last)
        }
        return actions
    }

    /** Request [sequence] got [response], piggybacked or separate. */
    fun onResponse(now: Duration, sequence: Int, response: CoapMessage): List<QBlockAction<Int>> {
        if (isFinished) return listOf(QBlockAction.Drop("The body has ended"))
        val num = numberOf(sequence) ?: return listOf(QBlockAction.Drop("Not a request of this body: $sequence"))
        lastActivity = now
        silenceResends = 0
        val options = response.options
        return when {
            response.code == CoapCode.CONTINUE -> onContinue(now, sequence, options.qBlock1?.number ?: num)
            response.code == CoapCode.REQUEST_ENTITY_INCOMPLETE && options.contentFormat == ContentFormat.MISSING_BLOCKS_CBOR_SEQ ->
                onMissing(now, response.payload)
            response.code == CoapCode.UNAUTHORIZED && options.echo != null && !echoed -> {
                // RFC 9175 §2.3: repeat the block with the Echo value, once per body.
                echoed = true
                listOf(send(now, num))
            }
            else -> {
                val smaller = if (response.code == CoapCode.REQUEST_ENTITY_TOO_LARGE && mayRestart) smallerSize(response) else null
                if (smaller != null) {
                    finish(sequence = null) + QBlockAction.Restart(smaller)
                } else {
                    listOf(QBlockAction.Deliver(sequence, response)) + finish(sequence)
                }
            }
        }
    }

    /** The Confirmable block [sequence] was acknowledged with an Empty ACK: the next block may go. */
    fun onAcknowledged(now: Duration, sequence: Int): List<QBlockAction<Int>> {
        if (isFinished || mode != BodyMode.CON || numberOf(sequence) == null) return emptyList()
        lastActivity = now
        val actions = ArrayList<QBlockAction<Int>>()
        sendNext(now, actions)
        return actions
    }

    /** The messaging layer gave up on the Confirmable block [sequence] after MAX_RETRANSMIT (RFC 7252 §4.2). */
    fun onTimeout(now: Duration, sequence: Int): List<QBlockAction<Int>> {
        if (isFinished || numberOf(sequence) == null) return emptyList()
        return fail(CoapException.Reason.TRANSMISSION_TIMEOUT, "Block ${numberOf(sequence)} was not acknowledged")
    }

    /** The server rejected request [sequence] with a Reset: it does not have, or no longer wants, this body. */
    fun onReset(now: Duration, sequence: Int): List<QBlockAction<Int>> {
        if (isFinished || numberOf(sequence) == null) return emptyList()
        return fail(CoapException.Reason.BLOCKWISE_FAILED, "Block ${numberOf(sequence)} was answered with a Reset")
    }

    /**
     * The application gave the body up: every token is released, so a late 4.08 gets a Reset and the server
     * deletes its partial body (RFC 9177 §4.3).
     */
    fun abandon(): List<QBlockAction<Int>> = if (isFinished) emptyList() else finish(sequence = null)

    // ---------------------------------------------------------------- internals

    private fun onContinue(now: Duration, sequence: Int, upTo: Long): List<QBlockAction<Int>> {
        if (mode != BodyMode.NON) {
            // A 2.31 to a Confirmable block (RFC 7959 style) acknowledges it like an Empty ACK.
            return onAcknowledged(now, sequence)
        }
        if (upTo >= last) return listOf(QBlockAction.Drop("2.31 for block $upTo, but the body ends at $last"))
        val actions = ArrayList<QBlockAction<Int>>()
        if (upTo == sets.last(currentSet, count) && nextSetAt != null) {
            // §7.2: the whole set arrived, so the rest of NON_TIMEOUT_RANDOM need not be waited.
            nextSetAt = null
            sendSet(now, currentSet + 1, actions)
        }
        val released = held.filter { it != sequence && numbers[it] <= upTo } + listOfNotNull(latest231?.takeIf { it != sequence })
        latest231 = sequence
        release(released.distinct(), actions)
        return actions
    }

    private fun onMissing(now: Duration, payload: ByteArray): List<QBlockAction<Int>> {
        val listed = MissingBlocks.decode(payload) ?: return listOf(QBlockAction.Drop("Malformed missing-block list"))
        val missing = listed.filter { it < count }.take(timing.maxPayloads)
        if (missing.isEmpty()) return listOf(QBlockAction.Drop("No block of this body is missing: $listed"))
        for (num in missing) {
            val times = (requests[num] ?: 0) + 1
            requests[num] = times
            if (times > timing.nonMaxRetransmit + 1) {
                return fail(CoapException.Reason.BLOCKWISE_FAILED, "Block $num was asked for $times times")
            }
        }
        val actions = ArrayList<QBlockAction<Int>>()
        for (num in missing) actions += send(now, num)
        if (mode == BodyMode.NON) {
            for (num in sets.numbers(currentSet, count)) if (num !in sent) actions += send(now, num)
            if (!sets.isLast(currentSet, count)) nextSetAt = now + nonTimeoutRandom
        }
        return actions
    }

    /** Sends the blocks of [set] that were never sent and waits NON_TIMEOUT_RANDOM unless it is the last set. */
    private fun sendSet(now: Duration, set: Long, actions: MutableList<QBlockAction<Int>>) {
        currentSet = set
        for (num in sets.numbers(set, count)) if (num !in sent) actions += send(now, num)
        nextUnsent = maxOf(nextUnsent, sets.last(set, count) + 1)
        nextSetAt = if (sets.isLast(set, count)) null else now + nonTimeoutRandom
    }

    private fun sendNext(now: Duration, actions: MutableList<QBlockAction<Int>>) {
        while (nextUnsent < count && nextUnsent in sent) nextUnsent++
        if (nextUnsent < count) actions += send(now, nextUnsent++)
    }

    private fun send(now: Duration, num: Long): QBlockAction<Int> {
        val sequence = numbers.size
        numbers += num
        held += sequence
        if (num !in sent) sent.add(num)
        if (num == last) lastBlockSent = true
        lastActivity = now
        return QBlockAction.SendBlock(num, num < last, sequence)
    }

    private fun smallerSize(response: CoapMessage): Int? {
        val proposed = response.options.qBlock1 ?: response.options.block1 ?: return null
        return proposed.size.takeIf { !proposed.isBert && it < blockSize }
    }

    private fun fail(reason: CoapException.Reason, diagnostic: String): List<QBlockAction<Int>> =
        listOf(QBlockAction.Fail(reason, diagnostic)) + finish(sequence = null)

    /** Ends the body and releases every token except that of [sequence], which carries the final response. */
    private fun finish(sequence: Int?): List<QBlockAction<Int>> {
        isFinished = true
        nextSetAt = null
        val actions = ArrayList<QBlockAction<Int>>()
        release(held.filter { it != sequence }, actions)
        return actions
    }

    private fun release(sequences: List<Int>, actions: MutableList<QBlockAction<Int>>) {
        if (sequences.isEmpty()) return
        held.removeAll(sequences.toSet())
        actions += QBlockAction.Release(sequences)
    }

    override fun toString(): String =
        "QBlock1Sender($size1 bytes in $count blocks of $blockSize, $mode, set $currentSet, finished=$isFinished)"
}
