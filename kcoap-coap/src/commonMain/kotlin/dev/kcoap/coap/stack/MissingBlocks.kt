package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption

/**
 * The `application/missing-blocks+cbor-seq` payload of RFC 9177 §5 (Content-Format 272): the block numbers
 * a 4.08 (Request Entity Incomplete) asks the client to send again, as a CBOR Sequence (RFC 8742) of
 * unsigned integers.
 *
 * Hand-written for CBOR major type 0 only, so that kcoap-coap needs no CBOR library; the tests cross-check it
 * against kcoap-cbor.
 */
internal object MissingBlocks {
    /**
     * Encodes [numbers], which must be strictly ascending block numbers (§5: "in numerical order", without
     * duplicates), each in the shortest form (RFC 8949 §4.2.1).
     */
    fun encode(numbers: List<Long>): ByteArray {
        val out = ArrayList<Byte>(numbers.size * 3)
        var previous = -1L
        for (number in numbers) {
            require(number in 0..BlockOption.MAX_NUMBER) { "Not a block number: $number" }
            require(number > previous) { "Block numbers must be strictly ascending: $numbers" }
            previous = number
            when {
                number < 24 -> out += number.toByte()
                number <= 0xFF -> {
                    out += ONE_BYTE.toByte()
                    out += number.toByte()
                }
                number <= 0xFFFF -> {
                    out += TWO_BYTES.toByte()
                    out += (number ushr 8).toByte()
                    out += number.toByte()
                }
                else -> {
                    out += FOUR_BYTES.toByte()
                    for (shift in 24 downTo 0 step 8) out += (number ushr shift).toByte()
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Decodes [payload] strictly, or returns null when the list must be dropped: it is empty, an item is not
     * an unsigned integer, the sequence is truncated, a number exceeds the 20 bits a block number has (RFC
     * 9177 §4.2), or the numbers go down. §5 makes a list out of order something a client SHOULD drop.
     * Repeated numbers next to each other are kept once, since §5 tells the client to ignore duplicates.
     */
    fun decode(payload: ByteArray): List<Long>? {
        if (payload.isEmpty()) return null
        val numbers = ArrayList<Long>()
        var position = 0
        while (position < payload.size) {
            val initial = payload[position++].toInt() and 0xFF
            if (initial ushr 5 != 0) return null
            val info = initial and 0x1F
            val length = when (info) {
                in 0..23 -> 0
                ONE_BYTE -> 1
                TWO_BYTES -> 2
                FOUR_BYTES -> 4
                EIGHT_BYTES -> 8
                else -> return null
            }
            if (position + length > payload.size) return null
            var value = if (length == 0) info.toLong() else 0L
            repeat(length) {
                if (value > BlockOption.MAX_NUMBER) return null
                value = (value shl 8) or (payload[position++].toLong() and 0xFF)
            }
            if (value > BlockOption.MAX_NUMBER) return null
            val last = numbers.lastOrNull()
            when {
                last == null || value > last -> numbers += value
                value == last -> Unit
                else -> return null
            }
        }
        return numbers
    }

    private const val ONE_BYTE = 24
    private const val TWO_BYTES = 25
    private const val FOUR_BYTES = 26
    private const val EIGHT_BYTES = 27
}
