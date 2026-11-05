package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions

/**
 * Why a request's Q-Block options are refused: answer with [code] and [diagnostic] (RFC 7252 §5.5.2), plus
 * Size1 = [size1] when the body is too large (RFC 7959 §2.9.3), in the request's message type.
 *
 * [resetIfNonConfirmable] marks the violations that make the option unrecognized (RFC 7252 §5.4.3, §5.4.5): a
 * Non-confirmable request with one is rejected with a Reset instead (§5.4.1). Q-Block mixed with Block1 or
 * Block2 is not one of them: RFC 9177 §4.1 rejects it with 4.02 whatever the type, so a Non-confirmable
 * request gets a Non-confirmable 4.02.
 */
internal class QBlockViolation(
    val code: CoapCode,
    val diagnostic: String,
    val size1: Long? = null,
    val resetIfNonConfirmable: Boolean = false,
) {
    override fun toString(): String = "$code $diagnostic"
}

/**
 * The structural rules of RFC 9177 for the Q-Block options of one message, checked before any body state is
 * touched. The rules that need the body (the same Size1 and SZX on every block, block numbers within it) are
 * the receivers' ([QBlock1Receiver], [QBlock2Receiver]).
 */
internal object QBlockCheck {
    private val Q_BLOCK1 = CoapOptions.Q_BLOCK1.number
    private val Q_BLOCK2 = CoapOptions.Q_BLOCK2.number

    /**
     * Checks a request carrying Q-Block1 or Q-Block2; null when it may be processed.
     *
     * - 4.02, or a Reset for a Non-confirmable request (RFC 7252 §5.4.1, §5.4.3, §5.4.5): an option longer
     *   than 3 bytes; Q-Block1 more than once, since only Q-Block2 may repeat.
     * - 4.02 (§4.1): Q-Block mixed with Block1 or Block2 in one request.
     * - 4.00: SZX 7 where BERT is not in use (§4.2, RFC 7959 §2.2: reserved on UDP; RFC 8323 §6: only on a
     *   connection whose CSMs both indicated BERT); Q-Block1 without Request-Tag or Size1 (§4.3); a Size1 that
     *   needs more than 2^20 blocks of the block size, which 20-bit block numbers cannot reach (RFC 7959
     *   §2.2); a payload that does not fill the block although M is set (RFC 7959 §2.3), or one larger than
     *   the block (RFC 7959 §2.2: only the last block may differ, by being smaller); a block outside Size1 or
     *   a last block that does not end at Size1 (§4.6); Q-Block2 options that are not strictly ascending or
     *   mix sizes, or repeat next to Q-Block1 (§4.4, §4.7).
     * - 4.13 with Size1: Size1 above [maxBodySize] (RFC 7959 §2.9.3, §7.1).
     */
    fun request(request: CoapMessage, allowsBert: Boolean, maxBodySize: Int): QBlockViolation? {
        val options = request.options
        val qBlock1Options = options.all(Q_BLOCK1)
        val qBlock2Options = options.all(Q_BLOCK2)
        if (qBlock1Options.isEmpty() && qBlock2Options.isEmpty()) return null
        if ((qBlock1Options + qBlock2Options).any { it.length > MAX_LENGTH }) {
            return unrecognized("A Q-Block option longer than $MAX_LENGTH bytes")
        }
        if (qBlock1Options.size > 1) return unrecognized("Q-Block1 repeated")
        if (CoapOptions.BLOCK1.number in options || CoapOptions.BLOCK2.number in options) {
            return QBlockViolation(CoapCode.BAD_OPTION, "Q-Block mixed with Block1 or Block2")
        }
        val qBlock1 = options.qBlock1
        val qBlock2List = options.qBlock2List
        if ((listOfNotNull(qBlock1) + qBlock2List).any { it.isBert && !allowsBert }) {
            return badRequest("SZX 7 is reserved without BERT")
        }
        if (qBlock1 != null) checkQBlock1(request, qBlock1, maxBodySize)?.let { return it }
        if (qBlock2List.size > 1) {
            if (qBlock1 != null) return badRequest("Missing blocks are asked for without Q-Block1")
            if (qBlock2List.any { it.sizeExponent != qBlock2List[0].sizeExponent }) return badRequest("Q-Block2 sizes differ")
            if (qBlock2List.zipWithNext().any { (a, b) -> b.number <= a.number }) {
                return badRequest("Q-Block2 block numbers are not strictly ascending")
            }
        }
        return null
    }

    private fun checkQBlock1(request: CoapMessage, block: BlockOption, maxBodySize: Int): QBlockViolation? {
        val options = request.options
        if (options.requestTags.isEmpty()) return badRequest("Q-Block1 without Request-Tag")
        val size1 = options.size1 ?: return badRequest("Q-Block1 without Size1")
        if (size1 > maxBodySize) {
            return QBlockViolation(CoapCode.REQUEST_ENTITY_TOO_LARGE, "Body of $size1 bytes exceeds $maxBodySize", maxBodySize.toLong())
        }
        if ((size1 + block.size - 1) / block.size > ReceivedBlocks.MAX_COUNT) {
            return badRequest("Body of $size1 bytes needs more than 2^20 blocks of ${block.size}")
        }
        val payload = request.payloadSize
        val offset = block.offset
        val fills = if (block.isBert) payload > 0 && payload % block.size == 0 else payload == block.size
        return when {
            !block.isBert && payload > block.size ->
                badRequest("Q-Block1 block ${block.number} carries more than ${block.size} bytes")
            block.more && !fills -> badRequest("Q-Block1 payload of $payload bytes does not fill block ${block.number}")
            block.more && offset + payload >= size1 -> badRequest("Q-Block1 block ${block.number} reaches beyond Size1 $size1")
            !block.more && offset + payload != size1 -> badRequest("Q-Block1 last block ${block.number} does not end at Size1 $size1")
            else -> null
        }
    }

    /**
     * Checks the Q-Block options of a response; null when it may be processed, otherwise why it must be
     * rejected (RFC 7252 §5.4.1): an option longer than 3 bytes, Q-Block1 or Q-Block2 more than once (only a
     * request for missing blocks repeats Q-Block2, §4.1), Q-Block mixed with Block1 or Block2 (§4.1), or SZX 7
     * where BERT is not in use.
     */
    fun response(response: CoapMessage, allowsBert: Boolean): String? {
        val options = response.options
        val qBlockOptions = options.filter { it.number == Q_BLOCK1 || it.number == Q_BLOCK2 }
        return when {
            qBlockOptions.isEmpty() -> null
            qBlockOptions.any { it.length > MAX_LENGTH } -> "A Q-Block option longer than $MAX_LENGTH bytes"
            options.all(Q_BLOCK1).size > 1 || options.all(Q_BLOCK2).size > 1 -> "A Q-Block option repeated in a response"
            CoapOptions.BLOCK1.number in options || CoapOptions.BLOCK2.number in options -> "Q-Block mixed with Block1 or Block2"
            listOfNotNull(options.qBlock1, options.qBlock2).any { it.isBert && !allowsBert } -> "SZX 7 is reserved without BERT"
            else -> null
        }
    }

    /** RFC 7252 §5.4.3, §5.4.5: the option counts as unrecognized, and Q-Block1 and Q-Block2 are critical. */
    private fun unrecognized(diagnostic: String) =
        QBlockViolation(CoapCode.BAD_OPTION, diagnostic, resetIfNonConfirmable = true)

    private fun badRequest(diagnostic: String) = QBlockViolation(CoapCode.BAD_REQUEST, diagnostic)

    /** RFC 9177 §4.2: the options are unsigned integers of 0 to 3 bytes. */
    private const val MAX_LENGTH = 3
}
