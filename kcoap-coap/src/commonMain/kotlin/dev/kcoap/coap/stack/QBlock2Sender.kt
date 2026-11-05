package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import kotlin.time.Duration

/**
 * The server side of one Q-Block2 body (RFC 9177 §4.4, §7) as a sans-IO state machine: the request that
 * started it, follow-up requests carrying Q-Block2 and time go in, [QBlockAction]s come out, and
 * [nextDeadline] says when [onTimer] must run. The driver builds each block from the cached body and adds
 * ETag, Size2 and, on blocks for the original request, Observe (§4.5); [R] is its handle of a request.
 *
 * - [BodyMode.NON] (§7.2): the blocks of set 0 go on the original request at once, every later set
 *   NON_TIMEOUT_RANDOM ([nonTimeoutRandom], drawn once per body) after the previous one, or as soon as a
 *   Continue asks for it (Figure 8).
 * - [BodyMode.CON] (§7.1, Appendix A, Figure 18): block 0 first, piggybacked if [firstBlockPiggybacked], then
 *   at most NSTART Confirmable blocks unacknowledged; each acknowledgement sends the next. A block the
 *   messaging layer gives up on stops the body; the cache stays for requests of missing blocks.
 * - [BodyMode.RELIABLE] (Appendix B, Figure 20): every block back to back.
 *
 * Follow-up requests ([onRequest]) are classified by their Q-Block2 options (§4.4):
 * - one option `NUM/M=1` with NUM a non-zero multiple of MAX_PAYLOADS is a Continue: that set goes out on the
 *   original request at once, even if it went out before (Figure 10), and the Continue itself gets no answer
 *   of its own. A second Continue for the same set within NON_TIMEOUT_RANDOM is a duplicate and is ignored.
 *   A body that never started, because its first request asked only for some blocks, takes the Continue as
 *   its original request.
 * - one option `0/M=1` asks for the whole body again; the driver decides whether the handler runs again
 *   (GET, FETCH) or this machine serves the cache ([restart]).
 * - anything else asks for missing blocks: `NUM/M=0` for that block, any other `NUM/M=1` for the rest of its
 *   set. Each block is sent once however the options overlap, ascending, on the requesting request (without
 *   Observe, §4.5), at most MAX_PAYLOADS at once and NON_TIMEOUT_RANDOM between such sets; the next set of
 *   the body then waits another NON_TIMEOUT_RANDOM (§7.2).
 *
 * Without a Continue, at most one MAX_PAYLOADS_SET goes out per NON_TIMEOUT_RANDOM, missing blocks and sets
 * of the body alike (§7.2: "a NON_TIMEOUT_RANDOM delay is still observed between each MAX_PAYLOADS_SET").
 *
 * A Reset to any block stops the body ([onReset]). Not thread safe: the driver serializes all calls.
 */
internal class QBlock2Sender<R>(
    val bodySize: Int,
    val blockSize: Int,
    val mode: BodyMode,
    private val timing: QBlockTiming,
    private val nonTimeoutRandom: Duration,
    private val firstBlockPiggybacked: Boolean = mode == BodyMode.CON,
) {
    init {
        require(bodySize >= 0) { "Negative body size: $bodySize" }
    }

    val sizeExponent: Int = BlockOption.sizeExponentFor(blockSize)

    /** How many blocks the body has; an empty body is one empty block. */
    val count: Long = maxOf(1L, (bodySize.toLong() + blockSize - 1) / blockSize).also {
        require(it <= ReceivedBlocks.MAX_COUNT) { "A body of $bodySize bytes needs more than 2^20 blocks of $blockSize" }
    }

    private val sets = timing.sets
    private val last = count - 1
    private var original: R? = null
    private var currentSet = 0L
    private var nextSetAt: Duration? = null
    private val continuedAt = HashMap<Long, Duration>()
    private val pending = ArrayDeque<Pair<Long, R>>()
    private var pendingAt: Duration? = null
    private var nextUnsent = 0L
    private var unacknowledged = 0

    /** Whether a Reset or a failed Confirmable block stopped the body. */
    var isStopped: Boolean = false
        private set

    /**
     * Whether blocks are still to go out by themselves: a later set, queued missing blocks, or the rest of a
     * started Confirmable body. A body that never started, whose first request asked only for some blocks,
     * sends nothing by itself.
     */
    val isSending: Boolean
        get() = !isStopped && (nextSetAt != null || pending.isNotEmpty() || (mode == BodyMode.CON && original != null && nextUnsent < count))

    /**
     * Whether every block of the body went out at least once, and for [BodyMode.CON] was acknowledged too:
     * a notification body is the outstanding notification until then (RFC 7641 §4.5.1, RFC 9177 §4.5).
     */
    val hasSentAll: Boolean
        get() = nextUnsent >= count && (mode != BodyMode.CON || unacknowledged == 0)

    /** Starts sending the body as the response to [ref]. */
    fun start(now: Duration, ref: R): List<QBlockAction<R>> {
        check(original == null) { "Already started" }
        return restart(now, ref)
    }

    /**
     * Sends the whole body again, from the cache, as the response to [ref], which asked for `0/M=1`: an
     * unsafe method whose handler must not run twice.
     */
    fun restart(now: Duration, ref: R): List<QBlockAction<R>> {
        original = ref
        isStopped = false
        currentSet = 0
        continuedAt.clear()
        pending.clear()
        pendingAt = null
        unacknowledged = 0
        nextUnsent = 0
        val actions = ArrayList<QBlockAction<R>>()
        when (mode) {
            BodyMode.NON -> sendSet(now, 0, actions)
            BodyMode.CON -> {
                if (firstBlockPiggybacked) actions += block(nextUnsent++, ref)
                while (nextUnsent < count && unacknowledged < timing.parameters.nstart) {
                    unacknowledged++
                    actions += block(nextUnsent++, ref)
                }
            }
            BodyMode.RELIABLE -> while (nextUnsent < count) actions += block(nextUnsent++, ref)
        }
        return actions
    }

    /** A follow-up [request] with Q-Block2 options, already checked by [QBlockCheck], arrived as [ref]. */
    fun onRequest(now: Duration, request: CoapMessage, ref: R): List<QBlockAction<R>> {
        val blocks = request.options.qBlock2List
        if (blocks.isEmpty()) return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "No Q-Block2"))
        if (blocks.any { it.sizeExponent != sizeExponent }) {
            return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "Q-Block2 size differs from $blockSize of the body"))
        }
        val single = blocks.singleOrNull()
        return when {
            single != null && single.more && single.number == 0L -> restart(now, ref)
            single != null && single.more && sets.isContinue(single.number) -> onContinue(now, sets.of(single.number), ref)
            else -> onMissing(now, blocks, ref)
        }
    }

    /** The Confirmable block [num] was acknowledged: the next one may go (CON). */
    fun onAcknowledged(now: Duration, num: Long): List<QBlockAction<R>> {
        if (mode != BodyMode.CON || isStopped || unacknowledged == 0) return emptyList()
        unacknowledged--
        val ref = original ?: return emptyList()
        if (nextUnsent >= count) return emptyList()
        unacknowledged++
        return listOf(block(nextUnsent++, ref))
    }

    /** The messaging layer gave up on the Confirmable block [num]: the transfer ends, the cache stays. */
    fun onTimeout(now: Duration, num: Long): List<QBlockAction<R>> {
        stop()
        return emptyList()
    }

    /** The client answered a block with a Reset: it does not want this body (any more). */
    fun onReset(now: Duration): List<QBlockAction<R>> {
        stop()
        return emptyList()
    }

    /** When [onTimer] must run next: the next set or the next chunk of missing blocks. */
    fun nextDeadline(): Duration? = if (isStopped) null else listOfNotNull(nextSetAt, pendingAt).minOrNull()

    fun onTimer(now: Duration): List<QBlockAction<R>> {
        if (isStopped) return emptyList()
        val actions = ArrayList<QBlockAction<R>>()
        val pendingDue = pendingAt?.let { now >= it } ?: false
        if (pendingDue) {
            sendPending(now, actions)
            // §7.2: that was a MAX_PAYLOADS_SET, so the next set of the body waits for the pause after it.
            putOffNextSet(now)
        } else {
            nextSetAt?.let { if (now >= it) sendSet(now, currentSet + 1, actions) }
        }
        return actions
    }

    // ---------------------------------------------------------------- internals

    private fun onContinue(now: Duration, set: Long, ref: R): List<QBlockAction<R>> {
        if (sets.first(set) >= count) return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "Continue beyond the body"))
        val actions = ArrayList<QBlockAction<R>>()
        // A body whose first request asked only for some blocks never started: the Continue becomes its
        // original request, which the rest of the body goes on.
        if (original == null) original = ref else actions += QBlockAction.NoResponse(ref)
        val previous = continuedAt[set]
        if (previous != null && now - previous < nonTimeoutRandom) return actions
        continuedAt[set] = now
        sendSet(now, set, actions)
        return actions
    }

    private fun onMissing(now: Duration, blocks: List<BlockOption>, ref: R): List<QBlockAction<R>> {
        // §4.4: each block once, however the options overlap, in ascending order.
        val requested = HashSet<Long>()
        for (option in blocks) {
            if (option.more) {
                requested.addAll(sets.numbers(sets.of(option.number), count).filter { it >= option.number })
            } else {
                requested += option.number
            }
        }
        val inBody = requested.filter { it < count }.sorted()
        if (inBody.isEmpty()) return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "No block of the body asked for"))
        val actions = ArrayList<QBlockAction<R>>()
        pending.clear()
        inBody.forEach { pending.addLast(it to ref) }
        sendPending(now, actions)
        // §7.2: after the missing blocks, another NON_TIMEOUT_RANDOM before the next set.
        putOffNextSet(now)
        return actions
    }

    /** The next set of the body waits NON_TIMEOUT_RANDOM after the last chunk of missing blocks (§7.2). */
    private fun putOffNextSet(now: Duration) {
        if (nextSetAt != null) nextSetAt = (pendingAt ?: now) + nonTimeoutRandom
    }

    private fun sendPending(now: Duration, actions: MutableList<QBlockAction<R>>) {
        val chunk = if (mode == BodyMode.NON) timing.maxPayloads else pending.size
        repeat(minOf(chunk, pending.size)) {
            val (num, ref) = pending.removeFirst()
            actions += block(num, ref)
        }
        pendingAt = if (pending.isEmpty()) null else now + nonTimeoutRandom
    }

    /** Sends every block of [set] on the original request; the next set follows NON_TIMEOUT_RANDOM later. */
    private fun sendSet(now: Duration, set: Long, actions: MutableList<QBlockAction<R>>) {
        val ref = original ?: return
        currentSet = maxOf(currentSet, set)
        for (num in sets.numbers(set, count)) actions += block(num, ref)
        nextUnsent = maxOf(nextUnsent, sets.last(set, count) + 1)
        nextSetAt = if (mode != BodyMode.NON || sets.isLast(currentSet, count)) null else now + nonTimeoutRandom
    }

    private fun block(num: Long, ref: R): QBlockAction<R> = QBlockAction.SendBlock(num, num < last, ref)

    private fun stop() {
        isStopped = true
        nextSetAt = null
        pending.clear()
        pendingAt = null
    }

    override fun toString(): String = "QBlock2Sender($bodySize bytes in $count blocks of $blockSize, $mode, set $currentSet)"
}
