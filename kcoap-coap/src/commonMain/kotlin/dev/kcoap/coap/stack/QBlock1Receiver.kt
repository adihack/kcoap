package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import kotlin.time.Duration

/**
 * The server side of one Q-Block1 upload (RFC 9177 §4.3, §7) as a sans-IO state machine: the blocks of one
 * body, identified by the driver (by peer, method, options and Request-Tag), go in with the driver's handle
 * [R] of their request; [QBlockAction]s come out, and [nextDeadline] says when [onTimer] must run. Every block
 * gets exactly one disposition: [QBlockAction.NoResponse], [QBlockAction.Send231], [QBlockAction.Send408],
 * [QBlockAction.Reject] or, for the block that completes the body, [QBlockAction.Deliver] of the assembled
 * request (without Q-Block1 and Size1), to be answered on that block's request.
 *
 * Blocks are checked by [QBlockCheck] first; this machine checks what needs the body: the same Size1 and SZX
 * on every block (§4.6). Duplicates are ignored (§4.3) and answered like the first copy: with nothing.
 *
 * [BodyMode.NON] (§7.2):
 * - A block of a later MAX_PAYLOADS_SET while earlier sets have gaps gets a 4.08 at once, listing those gaps,
 *   once per set (Figure 5).
 * - A block that completes every set up to its own, when that set is not the last and no later block has
 *   arrived, gets 2.31 with Q-Block1 = the set's last NUM, M set (Figure 3). libcoap's client truncates the
 *   upload after a 2.31 without Q-Block1, so it is always there.
 * - Otherwise the body waits NON_RECEIVE_TIMEOUT × 2^n after the later of the last new block and the last
 *   timer 4.08, then sends a 4.08 listing the missing blocks up to the end of the highest set that arrived,
 *   or, if that set is complete (a 2.31 went out and nothing since), the numbers of the next set (§7.2).
 *   After NON_MAX_RETRANSMIT such 4.08s the body is discarded (Figure 6: at 4, 12, 28 and 60 s, discarded
 *   at 124 s). A new block starts the ladder again.
 * - A 4.08 goes on a request the client still holds. The client releases the tokens a 2.31 confirmed, except
 *   that of the latest 2.31, and keeps those of every later block. So a 4.08 goes on the request of the
 *   highest block that arrived (Figures 5, 6 and 14 put it there too), and from a 2.31 on, on the 2.31's
 *   request until a higher block arrives: a 2.31 can go on a resent block below the highest one, whose
 *   token it releases. A Reset to it discards the body ([onReset], §4.3).
 *
 * [BodyMode.CON] and [BodyMode.RELIABLE] (§7.1, Appendices A and B): every block but the last gets
 * [QBlockAction.NoResponse] (an Empty ACK, or answered on a connection); no 2.31, since §4.3 says it SHOULD
 * NOT be used for Confirmable blocks, and no 4.08 lists. A body that stops is discarded NON_PARTIAL_TIMEOUT
 * after its last block.
 *
 * The final response goes on the request that completed the body. §4.3 requires one of the body's tokens and
 * prefers the last one received; RFC 9177 Figure 5 does this, Figure 14 uses another token of the body. Not
 * thread safe: the driver serializes all calls.
 *
 * @param first the first block that arrived, which fixes Size1 and the block size of the body.
 */
internal class QBlock1Receiver<R>(
    first: CoapMessage,
    private val mode: BodyMode,
    private val timing: QBlockTiming,
    now: Duration,
) {
    private val block0 = requireNotNull(first.options.qBlock1) { "Not a Q-Block1 request: $first" }

    /** The body size from Size1 (RFC 9177 §4.6). */
    val size1: Long = requireNotNull(first.options.size1) { "Q-Block1 request without Size1: $first" }

    val sizeExponent: Int = block0.sizeExponent
    private val unit: Int = block0.size

    /** How many block numbers the body has; an empty body is one empty block. */
    val count: Long = maxOf(1L, (size1 + unit - 1) / unit)

    private val sets = timing.sets
    private val buffer = ByteArray(size1.also { require(it <= Int.MAX_VALUE) { "Size1 $it too large" } }.toInt())
    private val received = ReceivedBlocks(count)
    private var highestRef: R? = null
    private var lastActivity = now
    private var last408At = Duration.ZERO
    private var retries = 0
    private var reportedSet = -1L
    private var continuedSet = -1L

    /** Whether the body was delivered. */
    var isComplete: Boolean = false
        private set

    /** Whether the body was given up. */
    var isDiscarded: Boolean = false
        private set

    /** Block [request] arrived on the request [ref]. */
    fun onBlock(now: Duration, request: CoapMessage, ref: R): List<QBlockAction<R>> {
        if (isComplete || isDiscarded) return listOf(QBlockAction.Drop("The body has ended"))
        val options = request.options
        val block = options.qBlock1 ?: return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "No Q-Block1"))
        if (options.size1 != size1) {
            return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "Size1 ${options.size1} differs from $size1 of the body"))
        }
        if (block.sizeExponent != sizeExponent) {
            return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "Block size ${block.size} differs from $unit of the body"))
        }
        val payload = request.payload
        // A BERT block (RFC 8323 §6) covers one number per 1024 bytes; any other block covers one.
        val units = if (block.isBert) maxOf(1L, (payload.size.toLong() + unit - 1) / unit) else 1L
        if (block.number + units > count) {
            return listOf(QBlockAction.Reject(ref, CoapCode.BAD_REQUEST, "Block ${block.number} is beyond Size1 $size1"))
        }
        val previousHighest = received.highest
        val isNew = received.addRange(block.number, units)
        if (received.highest > previousHighest) highestRef = ref
        if (isNew) {
            // Only progress counts: a duplicate must not put off asking for what is missing.
            payload.copyInto(buffer, destinationOffset = block.offset.toInt())
            lastActivity = now
            retries = 0
        }
        if (received.isComplete) {
            isComplete = true
            return listOf(QBlockAction.Deliver(ref, assembled(request)))
        }
        if (mode != BodyMode.NON || !isNew) return listOf(QBlockAction.NoResponse(ref))
        val set = sets.of(block.number)
        val gaps = if (set > reportedSet) received.missing(0 until sets.first(set), timing.maxPayloads) else emptyList()
        if (gaps.isNotEmpty()) {
            // §7.2: a block of the next set while earlier ones are missing asks for them at once.
            reportedSet = set
            last408At = now
            return listOf(QBlockAction.Send408(ref, gaps))
        }
        val setEnd = sets.last(set, count)
        if (set > continuedSet && !sets.isLast(set, count) && received.highest <= setEnd && received.hasAllUpTo(setEnd)) {
            continuedSet = set
            // The client releases every token up to setEnd but this one, so later 4.08s go here.
            highestRef = ref
            return listOf(QBlockAction.Send231(ref, setEnd))
        }
        return listOf(QBlockAction.NoResponse(ref))
    }

    /** When [onTimer] must run next, or null once the body ended. */
    fun nextDeadline(): Duration? = when {
        isComplete || isDiscarded -> null
        mode == BodyMode.NON -> maxOf(lastActivity, last408At) + timing.receiveWait(retries)
        else -> lastActivity + timing.nonPartialTimeout
    }

    fun onTimer(now: Duration): List<QBlockAction<R>> {
        val deadline = nextDeadline() ?: return emptyList()
        if (now < deadline) return emptyList()
        if (mode != BodyMode.NON) return discard("No block for NON_PARTIAL_TIMEOUT")
        if (retries >= timing.nonMaxRetransmit) return discard("Missing blocks asked for NON_MAX_RETRANSMIT times")
        // Every block was rejected: there is no request to put a 4.08 on, nor anything to keep.
        val ref = highestRef ?: return discard("No block of the body arrived")
        val highestSet = sets.of(received.highest)
        val missing = received.missing(0..sets.last(highestSet, count), timing.maxPayloads)
            .ifEmpty { sets.numbers(highestSet + 1, count).toList() }
        retries++
        last408At = now
        return listOf(QBlockAction.Send408(ref, missing))
    }

    /** The client answered a 4.08 with a Reset: it abandoned the body (RFC 9177 §4.3). */
    fun onReset(now: Duration): List<QBlockAction<R>> =
        if (isComplete || isDiscarded) emptyList() else discard("The client reset a 4.08")

    private fun discard(reason: String): List<QBlockAction<R>> {
        isDiscarded = true
        return listOf(QBlockAction.Discard(reason))
    }

    /** The completing request with the whole body, without Q-Block1 and Size1. */
    private fun assembled(request: CoapMessage): CoapMessage = request.toBuilder().apply {
        payload = buffer
        options {
            remove(CoapOptions.Q_BLOCK1.number)
            remove(CoapOptions.SIZE1.number)
        }
    }.build()

    override fun toString(): String = "QBlock1Receiver($size1 bytes, $received, $mode)"
}
