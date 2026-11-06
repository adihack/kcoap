package dev.kcoap.crypto

/**
 * SHAKE256 (FIPS 202) in common code. No platform API offers it everywhere, and the post-quantum EDHOC
 * suites (draft-ietf-lake-pqsuites, libedhoc suite -24) use it as their hash.
 */
public object Shake256 {
    public fun digest(data: ByteArray, outputLength: Int): ByteArray {
        val sponge = KeccakSponge(RATE)
        sponge.absorb(data)
        return sponge.squeeze(SHAKE_SUFFIX, outputLength)
    }

    internal const val RATE = 136
    internal const val SHAKE_SUFFIX = 0x1F
}

/**
 * KMAC256 (NIST SP 800-185 §4). EDHOC uses it for EDHOC_Extract and EDHOC_Expand when the suite's
 * hash is SHAKE256 (RFC 9528 §4.1).
 */
public object Kmac256 {
    /**
     * @param key the KMAC key K.
     * @param data the input X.
     * @param outputLength L in bytes.
     * @param customization S, empty for EDHOC.
     */
    public fun compute(key: ByteArray, data: ByteArray, outputLength: Int, customization: ByteArray = ByteArray(0)): ByteArray {
        require(outputLength >= 0) { "Negative output length" }
        val sponge = KeccakSponge(Shake256.RATE)
        // cSHAKE256 prefix: bytepad(encode_string("KMAC") || encode_string(S), rate)
        sponge.absorb(bytePad(encodeString(FUNCTION_NAME) + encodeString(customization), Shake256.RATE))
        // KMAC input: bytepad(encode_string(K), rate) || X || right_encode(L)
        sponge.absorb(bytePad(encodeString(key), Shake256.RATE))
        sponge.absorb(data)
        sponge.absorb(rightEncode(8L * outputLength))
        return sponge.squeeze(CSHAKE_SUFFIX, outputLength)
    }

    private val FUNCTION_NAME = "KMAC".encodeToByteArray()
    private const val CSHAKE_SUFFIX = 0x04

    /** SP 800-185 §2.3.1: the byte count, then the value big-endian in that many bytes. */
    internal fun leftEncode(value: Long): ByteArray {
        val digits = bigEndianDigits(value)
        return byteArrayOf(digits.size.toByte()) + digits
    }

    internal fun rightEncode(value: Long): ByteArray {
        val digits = bigEndianDigits(value)
        return digits + byteArrayOf(digits.size.toByte())
    }

    private fun bigEndianDigits(value: Long): ByteArray {
        require(value >= 0) { "Negative length" }
        var count = 1
        while (count < 8 && (value ushr (8 * count)) != 0L) count++
        return ByteArray(count) { i -> (value ushr (8 * (count - 1 - i))).toByte() }
    }

    private fun encodeString(bytes: ByteArray): ByteArray = leftEncode(8L * bytes.size) + bytes

    private fun bytePad(input: ByteArray, width: Int): ByteArray {
        val prefixed = leftEncode(width.toLong()) + input
        val padding = (width - prefixed.size % width) % width
        return prefixed + ByteArray(padding)
    }
}

/** The Keccak[1600] sponge with a byte-oriented interface. One instance computes one output. */
internal class KeccakSponge(private val rate: Int) {
    private val state = LongArray(25)
    private var position = 0

    fun absorb(data: ByteArray) {
        for (byte in data) {
            xorByte(position, byte.toInt() and 0xFF)
            position++
            if (position == rate) {
                keccakF(state)
                position = 0
            }
        }
    }

    /** Applies the domain [suffix] and padding, then squeezes [length] bytes. */
    fun squeeze(suffix: Int, length: Int): ByteArray {
        xorByte(position, suffix)
        xorByte(rate - 1, 0x80)
        keccakF(state)

        val output = ByteArray(length)
        var offset = 0
        while (true) {
            val chunk = minOf(rate, length - offset)
            for (i in 0 until chunk) output[offset + i] = readByte(i).toByte()
            offset += chunk
            if (offset == length) return output
            keccakF(state)
        }
    }

    private fun xorByte(index: Int, value: Int) {
        val lane = index / 8
        state[lane] = state[lane] xor (value.toLong() shl (8 * (index % 8)))
    }

    private fun readByte(index: Int): Int = ((state[index / 8] ushr (8 * (index % 8))) and 0xFF).toInt()

    private companion object {
        val ROUND_CONSTANTS = longArrayOf(
            0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(), 0x800000000000808AuL.toLong(),
            0x8000000080008000uL.toLong(), 0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(),
            0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(), 0x000000000000008AuL.toLong(),
            0x0000000000000088uL.toLong(), 0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
            0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(), 0x8000000000008089uL.toLong(),
            0x8000000000008003uL.toLong(), 0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
            0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(), 0x8000000080008081uL.toLong(),
            0x8000000000008080uL.toLong(), 0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong(),
        )

        /** Keccak-f[1600], FIPS 202 §3.3, with ρ and π fused into one walk over the lanes. */
        fun keccakF(a: LongArray) {
            val c = LongArray(5)
            val row = LongArray(5)
            for (round in 0 until 24) {
                // θ: XOR each column's parity into its neighbours.
                for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
                for (x in 0 until 5) {
                    val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
                    for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
                }
                // ρ and π: rotate each lane and move it to its new position.
                var x = 1
                var y = 0
                var current = a[1]
                for (t in 0 until 24) {
                    val nextX = y
                    val nextY = (2 * x + 3 * y) % 5
                    val index = nextX + 5 * nextY
                    val displaced = a[index]
                    a[index] = current.rotateLeft(((t + 1) * (t + 2) / 2) % 64)
                    current = displaced
                    x = nextX
                    y = nextY
                }
                // χ: the only non-linear step, row by row.
                for (base in 0 until 25 step 5) {
                    for (i in 0 until 5) row[i] = a[base + i]
                    for (i in 0 until 5) a[base + i] = row[i] xor (row[(i + 1) % 5].inv() and row[(i + 2) % 5])
                }
                // ι: break the symmetry between rounds.
                a[0] = a[0] xor ROUND_CONSTANTS[round]
            }
        }
    }
}
