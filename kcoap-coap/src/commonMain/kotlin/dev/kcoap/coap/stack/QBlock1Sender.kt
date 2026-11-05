package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
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
 * A response whose Q-Block options break [QBlockCheck.response] is dropped (RFC 7252 §5.4.1).
 *
 * Policies where RFC 9177 is silent:
 * - The 2.31 Q-Block1 NUM, or without one the block sent on that token, confirms every block up to it.
 * - Silence after the last block of a Non-confirmable body: if [resendsLastBlock] (PUT, FETCH and iPATCH,
 *   which may be repeated), the last block is sent again NON_RECEIVE_TIMEOUT × 2^k after the latest send or
 *   response, at most NON_MAX_RETRANSMIT times; a server that completed the body answers the copy with its
 *   final response again (§4.3), one that lost the body starts asking for blocks. Then, or without
 *   [resendsLastBlock] (POST, PATCH) once the whole ladder (124 s by default) passed without an answer, the
 *   body fails with [CoapException.Reason.RESPONSE_TIMEOUT]. A 4.08 shows that the server holds the body
 *   and asks for blocks on its own ladder, which runs on the same times; a copy of the last block would only
 *   be a duplicate to it. So after a 4.08 the first copy waits until that ladder could have run out, the
 *   whole ladder after the latest 4.08.
 * - A block asked for in more than NON_MAX_RETRANSMIT + 1 4.08s fails the body: the server is not making
 *   progress.
 * - A 4.01 with Echo (RFC 9175 §2.3) to a block sent before the first such challenge has that block sent
 *   again, once per challenge: a server that checks freshness challenges every block of the burst that was
 *   already on its way. The driver adds the Echo value to the copy and every later block. A 4.01 with Echo to
 *   a block that carried Echo ends the body and is delivered, so a server that keeps asking cannot loop.
 * - A 4.13 whose Q-Block1 or Block1 names a smaller block size restarts the upload once, with that size and a
 *   new Request-Tag ([QBlockAction.Restart], RFC 7959 §2.9.3).
 * - Tokens: once a 2.31 confirms blocks, their requests are released, except the one that carried the latest
 *   2.31, on which the server may still send a 4.08 (§7.2); a Confirmable block acknowledged while later
 *   blocks are still unsent is released too, since it cannot complete the body; when the body ends every
 *   request but the one with the final response is released.
 * - A Non-confirmable body holds at most [maxTokens] requests before it hears from the server: a set that
 *   would hold more waits until a 2.31 or a 4.08 releases some. A server that sends no 2.31 while an
 *   earlier set has a gap (libcoap) answers with its 4.08 NON_RECEIVE_TIMEOUT after the last block it got,
 *   and that 4.08 releases every block it shows arrived. Without this, a long upload over a lossy path
 *   would hold a token per block and run into the endpoint's limit of exchanges per peer. A body that
 *   waits so and hears nothing for the whole retry ladder (124 s by default) fails with
 *   [CoapException.Reason.RESPONSE_TIMEOUT], as after its last block.
 * - On a reliable transport only the last block is answered, or block 0 with an early error such as a
 *   4.13 (Appendix B), so every other block's request is released as soon as the next block is sent.
 *
 * Not thread safe: the driver serializes all calls.
 *
 * @param size1 the body size, sent as Size1 on every block (§4.6).
 * @param blockSize the block size, 16 to 1024 bytes, fixed for the body. Never BERT: kcoap does not send SZX 7.
 * @param allowsBert whether a response may carry SZX 7: only on a connection whose CSMs both indicated BERT
 *   (RFC 8323 §6).
 * @param maxTokens how many requests a Non-confirmable body holds at most before a set waits for the server;
 *   at least two sets' worth, so a set always fits next to the one a 2.31 or a 4.08 answers.
 */
internal class QBlock1Sender(
    val size1: Int,
    val blockSize: Int,
    private val mode: BodyMode,
    private val timing: QBlockTiming,
    private val nonTimeoutRandom: Duration,
    private val resendsLastBlock: Boolean,
    private val mayRestart: Boolean = true,
    private val allowsBert: Boolean = false,
    private val maxTokens: Int = defaultMaxTokens(timing),
) {
    init {
        require(size1 >= 0) { "Negative body size: $size1" }
        require(maxTokens >= 2 * timing.maxPayloads) { "maxTokens must hold two sets of ${timing.maxPayloads}: $maxTokens" }
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
    private var last408At: Duration? = null
    private var latest231: Int? = null

    /** Whether the next set waits for a 2.31 or a 4.08 to release tokens, since [maxTokens] would be exceeded. */
    private var isWaiting = false

    /** The first sequence number sent after a 4.01 with Echo, which the driver gives the Echo value. */
    private var echoFrom: Int? = null

    /** Sequence numbers whose 4.01 with Echo has had its block sent again. */
    private val challenged = HashSet<Int>()

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
            BodyMode.RELIABLE -> while (nextUnsent < count) {
                sendNext(now, actions)
                // Appendix B: the block before this one gets no response, unless it is block 0.
                val previous = numbers.size - 2
                if (previous > 0) release(listOf(previous), actions)
            }
        }
        return actions
    }

    /**
     * When [onTimer] must run next: the next set, or the silence ladder after the last block or while a set
     * waits for tokens (NON only).
     */
    fun nextDeadline(): Duration? = if (isFinished || mode != BodyMode.NON) null else nextSetAt ?: silenceDeadline()

    /**
     * The silence ladder runs once every set went out and the last block with it, or while a set waits for
     * tokens, and not before the server's own ladder could have run out after its latest 4.08. A waiting body
     * sends nothing again: it gives up after the whole ladder.
     */
    private fun silenceDeadline(): Duration? {
        if (!(lastBlockSent || isWaiting) || nextSetAt != null) return null
        val resends = resendsLastBlock && !isWaiting
        val silence = lastActivity + if (resends) timing.receiveWait(silenceResends) else timing.receiveLadder
        return last408At?.let { maxOf(silence, it + timing.receiveLadder) } ?: silence
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
            if (isWaiting) {
                return actions + fail(CoapException.Reason.RESPONSE_TIMEOUT, "No response while ${held.size} blocks are unconfirmed")
            }
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
        QBlockCheck.response(response, allowsBert)?.let { return listOf(QBlockAction.Drop(it)) }
        lastActivity = now
        silenceResends = 0
        val options = response.options
        return when {
            response.code == CoapCode.CONTINUE -> onContinue(now, sequence, options.qBlock1?.number ?: num)
            response.code == CoapCode.REQUEST_ENTITY_INCOMPLETE &&
                options.contentFormat == ContentFormat.MISSING_BLOCKS_CBOR_SEQ -> {
                last408At = now
                onMissing(now, sequence, num, response.payload)
            }
            response.code == CoapCode.UNAUTHORIZED && options.echo != null && sequence < (echoFrom ?: numbers.size) ->
                onEchoChallenge(now, sequence, num)
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

    /**
     * The Confirmable block [sequence] was acknowledged with an Empty ACK: the next block may go. While blocks
     * remain unsent, the acknowledged one cannot be the block that completes the body, so its token cannot
     * carry the final response and is released (RFC 9177 §4.3), which keeps a long body from holding a token
     * per block.
     */
    fun onAcknowledged(now: Duration, sequence: Int): List<QBlockAction<Int>> {
        if (isFinished || mode != BodyMode.CON || numberOf(sequence) == null) return emptyList()
        lastActivity = now
        val actions = ArrayList<QBlockAction<Int>>()
        if (sequence in held && hasUnsent()) release(listOf(sequence), actions)
        sendNext(now, actions)
        return actions
    }

    /** Whether some block of the body has not been sent yet. */
    private fun hasUnsent(): Boolean = sent.received < count

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

    /** RFC 9175 §2.3: the block went out without (the latest) Echo; send it again, with the Echo value. */
    private fun onEchoChallenge(now: Duration, sequence: Int, num: Long): List<QBlockAction<Int>> {
        if (!challenged.add(sequence)) return listOf(QBlockAction.Drop("Request $sequence was challenged before"))
        if (echoFrom == null) echoFrom = numbers.size
        return listOf(send(now, num))
    }

    private fun onContinue(now: Duration, sequence: Int, upTo: Long): List<QBlockAction<Int>> {
        if (mode != BodyMode.NON) {
            // A 2.31 to a Confirmable block (RFC 7959 style) acknowledges it like an Empty ACK.
            return onAcknowledged(now, sequence)
        }
        if (upTo >= last) return listOf(QBlockAction.Drop("2.31 for block $upTo, but the body ends at $last"))
        val actions = ArrayList<QBlockAction<Int>>()
        val released = held.filter { it != sequence && numbers[it] <= upTo } + listOfNotNull(latest231?.takeIf { it != sequence })
        latest231 = sequence
        release(released.distinct(), actions)
        if ((upTo == sets.last(currentSet, count) && nextSetAt != null) || isWaiting) {
            // §7.2: the whole set arrived, so the rest of NON_TIMEOUT_RANDOM need not be waited; or the set
            // that waited for tokens may go now.
            nextSetAt = null
            sendSet(now, currentSet + 1, actions)
        }
        return actions
    }

    private fun onMissing(now: Duration, sequence: Int, num: Long, payload: ByteArray): List<QBlockAction<Int>> {
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
        releaseArrived(sequence, num, listed, actions)
        for (num in missing) actions += send(now, num)
        if (mode == BodyMode.NON) {
            for (num in sets.numbers(currentSet, count)) if (num !in sent) actions += send(now, num)
            if (!sets.isLast(currentSet, count)) {
                // A set that waited for tokens goes after the pause, if they fit then.
                isWaiting = false
                nextSetAt = now + nonTimeoutRandom
            }
        }
        return actions
    }

    /**
     * A 4.08 on request [sequence], which carried block [num], lists the blocks the server misses up to the end
     * of the highest set it has, ascending and at most MAX_PAYLOADS of them (§4.3, §5). So every block up to
     * [num] that it does not list has arrived, or up to the last listed one when the list is full and may have
     * been cut short. Their tokens are released, as a 2.31 would release them: once a later set has arrived,
     * the server sends no 2.31 for a set it completes late, so without this a long body over a lossy path
     * would hold a token per block. The 4.08's own request stays, since later 4.08s go there, and so does the
     * latest 2.31's. Neither can a released block complete the body and carry the final response: it is there
     * already.
     */
    private fun releaseArrived(sequence: Int, num: Long, listed: List<Long>, actions: MutableList<QBlockAction<Int>>) {
        val isFull = listed.size >= timing.maxPayloads
        val listedSet = listed.toHashSet()
        val arrived = held.filter { held ->
            val block = numbers[held]
            held != sequence && held != latest231 && block !in listedSet && (if (isFull) block < listed.last() else block <= num)
        }
        release(arrived, actions)
    }

    /**
     * Sends the blocks of [set] that were never sent and waits NON_TIMEOUT_RANDOM unless it is the last set; or,
     * if they would take the body beyond [maxTokens], waits for a 2.31 or a 4.08 instead.
     */
    private fun sendSet(now: Duration, set: Long, actions: MutableList<QBlockAction<Int>>) {
        val unsent = sets.numbers(set, count).filter { it !in sent }
        if (held.isNotEmpty() && held.size + unsent.size > maxTokens) {
            isWaiting = true
            nextSetAt = null
            return
        }
        isWaiting = false
        currentSet = set
        for (num in unsent) actions += send(now, num)
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

    companion object {
        /**
         * The default [maxTokens]: four sets of MAX_PAYLOADS, 40 by default, which leaves most of the default
         * 256 exchanges per peer to other requests.
         */
        fun defaultMaxTokens(timing: QBlockTiming): Int = 4 * timing.maxPayloads
    }
}
