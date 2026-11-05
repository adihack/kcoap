package dev.kcoap.coap.stack

/**
 * Which of the [count] blocks of a Q-Block body have arrived, as a bitset (RFC 9177 §4.3, §4.4: blocks may
 * arrive in any order, more than once, or not at all). A BERT block (RFC 8323 §6) covers several numbers
 * and is added as a range. Searching for gaps skips 64 received blocks at a time, so a body of 2^20 blocks
 * stays cheap.
 */
internal class ReceivedBlocks(val count: Long) {
    private val words: LongArray

    init {
        require(count in 0..MAX_COUNT) { "Block count out of range: $count" }
        words = LongArray(((count + 63) / 64).toInt())
    }

    /** How many distinct blocks have arrived. */
    var received: Long = 0
        private set

    /** The highest block number that has arrived, or -1 before any. */
    var highest: Long = -1
        private set

    /** The lowest block number that has not arrived; [count] once all have. */
    var firstMissing: Long = 0
        private set

    val isComplete: Boolean get() = received == count

    operator fun contains(num: Long): Boolean =
        num in 0 until count && words[(num ushr 6).toInt()] and (1L shl (num and 63).toInt()) != 0L

    /** Records block [num]; false when it had arrived before. */
    fun add(num: Long): Boolean {
        require(num in 0 until count) { "Block $num outside 0 until $count" }
        if (num in this) return false
        val index = (num ushr 6).toInt()
        words[index] = words[index] or (1L shl (num and 63).toInt())
        received++
        if (num > highest) highest = num
        while (firstMissing < count && firstMissing in this) firstMissing++
        return true
    }

    /** Records [units] blocks from [first] on; false when none of them is new. */
    fun addRange(first: Long, units: Long): Boolean {
        var added = false
        for (num in first until first + units) if (add(num)) added = true
        return added
    }

    /** Whether every block from 0 to [last] has arrived. */
    fun hasAllUpTo(last: Long): Boolean = firstMissing > last

    /** The block numbers in [range] that have not arrived, ascending, at most [limit] of them. */
    fun missing(range: LongRange, limit: Int = Int.MAX_VALUE): List<Long> {
        val missing = ArrayList<Long>()
        var num = maxOf(range.first, firstMissing)
        val end = minOf(range.last, count - 1)
        while (num <= end && missing.size < limit) {
            if (num and 63 == 0L && num + 63 <= end && words[(num ushr 6).toInt()] == -1L) {
                num += 64
                continue
            }
            if (num !in this) missing += num
            num++
        }
        return missing
    }

    override fun toString(): String = "ReceivedBlocks($received of $count, highest $highest)"

    companion object {
        /** A 20-bit block number (RFC 7959 §2.2) allows 2^20 blocks. */
        const val MAX_COUNT: Long = 1L shl 20
    }
}
