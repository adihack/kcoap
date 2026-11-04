package dev.kcoap.oscore

/**
 * The anti-replay sliding window of RFC 6347 §4.1.2.6, which RFC 8613 §3.2.2 makes the OSCORE default.
 *
 * It accepts a sequence number once, and rejects numbers older than the window. Not thread safe:
 * [SecurityContext] serializes access, because checking and updating must be atomic (RFC 8613 §7.4).
 */
internal class ReplayWindow(private val size: Int) {
    init {
        require(size in 1..64) { "Window size must be 1..64" }
    }

    /** The highest accepted sequence number, or -1 before the first one. */
    var highest: Long = -1
        private set

    /** Bit i set: `highest - i` was accepted. */
    private var accepted: Long = 0

    fun isReplay(sequence: Long): Boolean {
        if (sequence > highest) return false
        val age = highest - sequence
        if (age >= size) return true
        return accepted and (1L shl age.toInt()) != 0L
    }

    /**
     * Makes [sequence] the lower limit: it and every older number count as received (RFC 8613 Appendix
     * B.1.2, after the client proved freshness with Echo).
     */
    fun initializeAt(sequence: Long) {
        highest = sequence
        accepted = -1L
    }

    /** Records [sequence]; returns false, changing nothing, if it is a replay. */
    fun accept(sequence: Long): Boolean {
        if (isReplay(sequence)) return false
        if (sequence > highest) {
            val shift = sequence - highest
            accepted = if (highest < 0 || shift >= 64) 0 else accepted shl shift.toInt()
            accepted = accepted or 1L
            highest = sequence
        } else {
            accepted = accepted or (1L shl (highest - sequence).toInt())
        }
        return true
    }
}
