package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The client side of Q-Block2 bodies (RFC 9177 §4.4, §4.5, §7) as a sans-IO state machine: response blocks
 * and time go in, [QBlockAction]s come out, and [nextDeadline] says when [onTimer] must run. One machine
 * follows one request; for an observation it assembles one body after the other, each with its own ETag, and
 * delivers each once ([QBlockAction.Deliver]: the code and options of block 0 without Q-Block2 and Size2, the
 * ETag kept, and the whole payload).
 *
 * A block is accepted only if it carries exactly one Q-Block2 and no Block option ([QBlockCheck.response]), at
 * most one ETag, Size2 no larger than [maxBodySize], the size, Size2 and Content-Format of the body, a NUM
 * inside the body, a payload that fills the block when M is set (RFC 7959 §2.3) and one that ends at Size2
 * when M is clear (§4.6). Other blocks and duplicates are dropped (§4.4).
 *
 * A block with another ETag than the body in progress means the representation changed (§4.4; a missing
 * ETag counts as another, which is how libcoap answers after it ran the handler again). If [restartable]
 * (GET and FETCH), the old body is dropped and the new one assembled, and late blocks of the old one are
 * ignored; a block that cannot start a body has the whole body asked for again. Otherwise the transfer fails.
 *
 * [BodyMode.NON] (§7.2):
 * - When every block up to the end of a set arrived and none of a later set has, a Continue asks for the
 *   next set at once (Figure 8). A Continue is never repeated by itself: if it is lost, the timer below asks
 *   for the next set.
 * - A block of a later set while earlier sets have gaps asks for those gaps at once, once per set (Figure 9).
 * - Otherwise the gaps up to the end of the highest set that arrived, or the next set if there are none, are
 *   asked for NON_RECEIVE_TIMEOUT × 2^n after the later of the last new block and the last request for
 *   blocks; after NON_MAX_RETRANSMIT requests the body is discarded (Figures 9, 10).
 * - Requests list one Q-Block2 per block, M clear, at most MAX_PAYLOADS. With [missingBlockMBit], the
 *   missing tail of a set is one option with M set (Figure 10), except where its NUM would read as a
 *   Continue. If block 0 is missing and the request is [restartable], the request is `0/M=1` alone: libcoap
 *   runs the handler again for NUM 0, so kcoap never asks for `0/M=0`.
 *
 * [BodyMode.CON]: the server retransmits each block, so the same recovery runs with MAX_TRANSMIT_WAIT, after
 * which the server gives a block up, in place of NON_RECEIVE_TIMEOUT; no Continue and no requests at once.
 * [BodyMode.RELIABLE]: nothing can be missing; only the lifetime below applies.
 *
 * A partial body is kept for min(NON_PARTIAL_TIMEOUT, Max-Age) (§4.4), but at least as long as the retry
 * ladder (124 s by default), since a Max-Age of 60 s or 0 would end the recovery before it could work.
 * Not thread safe: the driver serializes all calls.
 */
internal class QBlock2Receiver(
    private val mode: BodyMode,
    private val timing: QBlockTiming,
    private val maxBodySize: Int,
    private val restartable: Boolean,
    private val missingBlockMBit: Boolean = false,
    private val allowsBert: Boolean = false,
) {
    private val sets = timing.sets

    private class Body(
        val etag: ByteArray?,
        val size2: Long,
        val sizeExponent: Int,
        val unit: Int,
        val contentFormat: Int?,
        val expiresAt: Duration,
        now: Duration,
    ) {
        val count: Long = maxOf(1L, (size2 + unit - 1) / unit)
        val buffer = ByteArray(size2.toInt())
        val received = ReceivedBlocks(count)
        var block0: CoapMessage? = null
        var observe: Long? = null
        var lastActivity = now
        var lastRequestAt = Duration.ZERO
        var retries = 0
        var reportedSet = -1L
        var continuedSet = -1L
        var isDelivered = false
    }

    private var body: Body? = null

    /** ETags of bodies that were superseded, so their late blocks are not taken for a new body. */
    private val superseded = ArrayDeque<ByteArray?>()

    /** Whether the transfer failed for good. */
    var isFailed: Boolean = false
        private set

    /** Whether a body is being assembled. */
    val isAssembling: Boolean get() = body?.let { !it.isDelivered } ?: false

    /** Block [response] arrived, on any token of the request. */
    fun onBlock(now: Duration, response: CoapMessage): List<QBlockAction<Nothing>> {
        if (isFailed) return drop("The transfer failed")
        QBlockCheck.response(response, allowsBert)?.let { return drop(it) }
        val options = response.options
        val block = options.qBlock2 ?: return drop("No Q-Block2")
        val etags = options.etags
        if (etags.size > 1) return drop("More than one ETag")
        val etag = etags.firstOrNull()
        val current = body
        val isOtherBody = current == null || !current.etag.contentEqualsNullable(etag)
        if (isOtherBody && superseded.any { it.contentEqualsNullable(etag) }) return drop("A block of a superseded body")
        if (current != null && isOtherBody) {
            if (current.isDelivered && !restartable) return drop("A block of another body after delivery")
            if (!restartable) return fail(CoapException.Reason.BLOCKWISE_FAILED, "The representation changed during the transfer")
            supersede(current)
            if (options.size2 == null) {
                // libcoap ran the handler again and answered without ETag and Size2: ask for the new body.
                return listOf(QBlockAction.RequestMissing(listOf(BlockOption(0, true, block.sizeExponent))))
            }
        } else if (current != null && current.isDelivered) {
            return drop("A block of a body already delivered")
        }
        val size2 = options.size2 ?: return drop("No Size2")
        if (size2 > maxBodySize) return fail(CoapException.Reason.BLOCKWISE_FAILED, "Response body of $size2 bytes exceeds $maxBodySize")
        val target = body ?: newBody(now, response, block, etag, size2).also { body = it }
        return accept(now, target, response, block, size2)
    }

    /** When [onTimer] must run next, or null while no body is being assembled. */
    fun nextDeadline(): Duration? {
        val body = body?.takeIf { !it.isDelivered } ?: return null
        val recovery = recoveryBase()?.let { maxOf(body.lastActivity, body.lastRequestAt) + it * (1 shl body.retries) }
        return listOfNotNull(recovery, body.expiresAt).min()
    }

    fun onTimer(now: Duration): List<QBlockAction<Nothing>> {
        val body = body?.takeIf { !it.isDelivered } ?: return emptyList()
        if (now >= body.expiresAt) return discard(body, "The partial body expired")
        val deadline = nextDeadline() ?: return emptyList()
        if (now < deadline) return emptyList()
        if (body.retries >= timing.nonMaxRetransmit) return discard(body, "Missing blocks asked for NON_MAX_RETRANSMIT times")
        val highestSet = sets.of(body.received.highest)
        val missing = body.received.missing(0..sets.last(highestSet, body.count), timing.maxPayloads)
            .ifEmpty { sets.numbers(highestSet + 1, body.count).toList() }
        body.retries++
        body.lastRequestAt = now
        return listOf(QBlockAction.RequestMissing(options(body, missing)))
    }

    // ---------------------------------------------------------------- internals

    private fun newBody(now: Duration, response: CoapMessage, block: BlockOption, etag: ByteArray?, size2: Long): Body {
        val maxAge = response.options.maxAge.seconds
        val lifetime = maxOf(minOf(timing.nonPartialTimeout, maxAge), timing.receiveLadder)
        return Body(etag, size2, block.sizeExponent, block.size, response.options.contentFormat, now + lifetime, now)
    }

    private fun accept(now: Duration, body: Body, response: CoapMessage, block: BlockOption, size2: Long): List<QBlockAction<Nothing>> {
        val options = response.options
        if (block.sizeExponent != body.sizeExponent) return drop("Block size ${block.size} differs from the body's")
        if (size2 != body.size2) return drop("Size2 $size2 differs from the body's ${body.size2}")
        if (options.contentFormat != body.contentFormat) return drop("Content-Format differs from the body's")
        if (block.number >= body.count) return drop("Block ${block.number} beyond Size2 $size2")
        val payload = response.payload
        val end = block.offset + payload.size
        val fills = if (block.isBert) payload.isNotEmpty() && payload.size % body.unit == 0 else payload.size == body.unit
        if (block.more && (!fills || end >= size2)) return drop("Block ${block.number} with M set does not fill its size")
        if (!block.more && end != size2) return drop("Last block ${block.number} does not end at Size2 $size2")
        val units = if (block.isBert) maxOf(1L, (payload.size.toLong() + body.unit - 1) / body.unit) else 1L
        if (!body.received.addRange(block.number, units)) return drop("Duplicate block ${block.number}")
        payload.copyInto(body.buffer, destinationOffset = block.offset.toInt())
        body.lastActivity = now
        body.retries = 0
        if (block.number == 0L) body.block0 = response
        if (body.observe == null) body.observe = options.observe
        if (body.received.isComplete) {
            body.isDelivered = true
            return listOf(QBlockAction.Deliver(null, assembled(body)))
        }
        if (mode != BodyMode.NON) return emptyList()
        val set = sets.of(block.number)
        val gaps = if (set > body.reportedSet) body.received.missing(0 until sets.first(set), timing.maxPayloads) else emptyList()
        if (gaps.isNotEmpty()) {
            // §7.2: a block of the next set while earlier ones are missing asks for them at once.
            body.reportedSet = set
            body.lastRequestAt = now
            return listOf(QBlockAction.RequestMissing(options(body, gaps)))
        }
        val setEnd = sets.last(set, body.count)
        if (set > body.continuedSet && !sets.isLast(set, body.count) && body.received.highest <= setEnd &&
            body.received.hasAllUpTo(setEnd)
        ) {
            body.continuedSet = set
            return listOf(QBlockAction.SendContinue(sets.first(set + 1)))
        }
        return emptyList()
    }

    /** The Q-Block2 options asking for [missing], which is ascending. */
    private fun options(body: Body, missing: List<Long>): List<BlockOption> {
        if (restartable && 0L in missing) return listOf(BlockOption(0, true, body.sizeExponent))
        if (!missingBlockMBit) return missing.map { BlockOption(it, false, body.sizeExponent) }
        val result = ArrayList<BlockOption>()
        var index = 0
        while (index < missing.size) {
            val num = missing[index]
            val setEnd = sets.last(sets.of(num), body.count)
            val tail = (num..setEnd).toList()
            val isTail = !sets.isContinue(num) && missing.size - index >= tail.size && missing.subList(index, index + tail.size) == tail
            if (isTail && tail.size > 1) {
                result += BlockOption(num, true, body.sizeExponent)
                index += tail.size
            } else {
                result += BlockOption(num, false, body.sizeExponent)
                index++
            }
        }
        return result
    }

    private fun assembled(body: Body): CoapMessage {
        val block0 = checkNotNull(body.block0)
        return block0.toBuilder().apply {
            payload = body.buffer
            options {
                remove(CoapOptions.Q_BLOCK2.number)
                remove(CoapOptions.SIZE2.number)
                val observe = body.observe
                if (observe != null && CoapOptions.OBSERVE.number !in block0.options) observe(observe)
            }
        }.build()
    }

    /** The first wait before asking for missing blocks, or null where nothing can go missing. */
    private fun recoveryBase(): Duration? = when (mode) {
        BodyMode.NON -> timing.nonReceiveTimeout
        BodyMode.CON -> timing.parameters.maxTransmitWait
        BodyMode.RELIABLE -> null
    }

    private fun supersede(body: Body) {
        this.body = null
        superseded.addLast(body.etag)
        while (superseded.size > MAX_SUPERSEDED) superseded.removeFirst()
    }

    private fun discard(body: Body, reason: String): List<QBlockAction<Nothing>> {
        supersede(body)
        return listOf(QBlockAction.Discard(reason))
    }

    private fun fail(reason: CoapException.Reason, diagnostic: String): List<QBlockAction<Nothing>> {
        isFailed = true
        body = null
        return listOf(QBlockAction.Fail(reason, diagnostic))
    }

    private fun drop(reason: String): List<QBlockAction<Nothing>> = listOf(QBlockAction.Drop(reason))

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null) other == null else other != null && contentEquals(other)

    override fun toString(): String = "QBlock2Receiver($mode, ${body?.received}, failed=$isFailed)"

    private companion object {
        /** How many superseded ETags are remembered: enough for blocks that are late by a few bodies. */
        const val MAX_SUPERSEDED = 8
    }
}
