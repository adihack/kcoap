package dev.kcoap.coap.stack

/**
 * The MAX_PAYLOADS_SETs of RFC 9177 §7.2: set `s` holds the block numbers `s × MAX_PAYLOADS` to
 * `(s + 1) × MAX_PAYLOADS − 1`, cut short by the end of the body. A sender pauses for NON_TIMEOUT_RANDOM
 * after each set; a receiver acknowledges a complete set with 2.31 (Q-Block1) or a Continue (Q-Block2).
 */
internal class QBlockSets(val maxPayloads: Int) {
    init {
        require(maxPayloads >= 1) { "MAX_PAYLOADS must be at least 1: $maxPayloads" }
    }

    /** The set that block [num] belongs to. */
    fun of(num: Long): Long = num / maxPayloads

    /** The first block number of set [set]. */
    fun first(set: Long): Long = set * maxPayloads

    /** The last block number of set [set] in a body of [count] blocks. */
    fun last(set: Long, count: Long): Long = minOf(first(set + 1), count) - 1

    /** The block numbers of set [set] in a body of [count] blocks; empty beyond the end. */
    fun numbers(set: Long, count: Long): LongRange = first(set)..last(set, count)

    /** Whether set [set] is the last one of a body of [count] blocks. */
    fun isLast(set: Long, count: Long): Boolean = first(set + 1) >= count

    /**
     * Whether a Q-Block2 option with NUM [num] and the M bit set is a Continue: a non-zero multiple of
     * MAX_PAYLOADS (RFC 9177 §4.4).
     */
    fun isContinue(num: Long): Boolean = num > 0 && num % maxPayloads == 0L

    override fun toString(): String = "QBlockSets(MAX_PAYLOADS=$maxPayloads)"
}
