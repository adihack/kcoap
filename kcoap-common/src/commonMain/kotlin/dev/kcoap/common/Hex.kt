package dev.kcoap.common

/**
 * Lower-case hexadecimal encoding, used by logs, test vectors and `toString()` of byte-carrying types.
 */
public object Hex {
    private const val DIGITS = "0123456789abcdef"

    public fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            out[2 * i] = DIGITS[value ushr 4]
            out[2 * i + 1] = DIGITS[value and 0x0F]
        }
        return out.concatToString()
    }

    /**
     * Decodes [text], ignoring whitespace so RFC vectors can be pasted as they are printed.
     *
     * @throws IllegalArgumentException on an odd number of digits or a non-hex character.
     */
    public fun decode(text: String): ByteArray {
        val digits = text.filterNot { it.isWhitespace() }
        require(digits.length % 2 == 0) { "Hex string has an odd number of digits" }
        return ByteArray(digits.length / 2) { i ->
            ((digitValue(digits[2 * i]) shl 4) or digitValue(digits[2 * i + 1])).toByte()
        }
    }

    private fun digitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("Not a hex digit: '$c'")
    }
}

/** `byteArrayOf(0x0a, 0xff).toHex() == "0aff"`. */
public fun ByteArray.toHex(): String = Hex.encode(this)

/** `"0a ff".hexToBytes()` contentEquals `byteArrayOf(0x0a, 0xff)`. */
public fun String.hexToBytes(): ByteArray = Hex.decode(this)
