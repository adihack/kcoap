package dev.kcoap.crypto.backend

/**
 * Arithmetic modulo an odd prime, for the elliptic-curve point decompression in [EcPointDecompression].
 *
 * Only public values pass through here (coordinates of public keys), so nothing is constant time.
 * Numbers are little-endian arrays of 16-bit limbs held in `Int`s: every product of two limbs plus two
 * more limbs stays below 2^32, so each step is exact in 32-bit arithmetic with `ushr`, and no `Long` is
 * needed (Kotlin/JS emulates `Long` in software). Multiplication is Montgomery's (HAC Algorithm 14.36),
 * which needs no division; values are kept in Montgomery form `aR mod p` with `R = 2^(16 * size)`
 * between [fromBytes] and [toBytes].
 */
internal class PrimeField(modulus: ByteArray) {
    private val p: IntArray = limbsOf(modulus)
    private val size = p.size

    /** `-p^-1 mod 2^16`, the factor that makes each Montgomery step divisible by the limb base. */
    private val negativeInverse: Int

    /** `R^2 mod p`, for converting into Montgomery form. */
    private val rSquared: IntArray

    /** The number of bytes of an element, as SEC1 encodes coordinates. */
    val byteLength: Int = modulus.size

    /** 1 in Montgomery form. */
    val one: IntArray

    init {
        require(p[0] and 1 == 1) { "The modulus must be odd" }
        // Newton's iteration doubles the correct low bits of the inverse each round: 3 bits (any odd p is its own
        // inverse mod 8), then 6, 12, 24.
        var inverse = p[0]
        repeat(3) { inverse *= 2 - p[0] * inverse }
        negativeInverse = -inverse and LIMB_MASK

        // 2^(2 * 16 * size) mod p by doubling, which needs only additions.
        var power = IntArray(size).also { it[0] = 1 }
        repeat(2 * LIMB_BITS * size) { power = add(power, power) }
        rSquared = power
        one = toMontgomery(IntArray(size).also { it[0] = 1 })
    }

    /** The [byteLength] big-endian [bytes] in Montgomery form; they must encode a number below the modulus. */
    fun fromBytes(bytes: ByteArray): IntArray {
        require(bytes.size == byteLength) { "A field element has $byteLength bytes, got ${bytes.size}" }
        val value = limbsOf(bytes)
        require(compare(value, p) < 0) { "Not a field element: not below the modulus" }
        return toMontgomery(value)
    }

    /** The big-endian encoding of [element], [byteLength] bytes long. */
    fun toBytes(element: IntArray): ByteArray {
        val value = montgomeryMultiply(element, IntArray(size).also { it[0] = 1 })
        return ByteArray(byteLength) { index ->
            val bitOffset = 8 * (byteLength - 1 - index)
            (value[bitOffset / LIMB_BITS] ushr (bitOffset % LIMB_BITS)).toByte()
        }
    }

    fun add(a: IntArray, b: IntArray): IntArray {
        val sum = IntArray(size + 1)
        var carry = 0
        for (i in 0 until size) {
            val s = a[i] + b[i] + carry
            sum[i] = s and LIMB_MASK
            carry = s ushr LIMB_BITS
        }
        sum[size] = carry
        return reduceOnce(sum)
    }

    fun subtract(a: IntArray, b: IntArray): IntArray = add(a, negate(b))

    fun negate(a: IntArray): IntArray = if (a.all { it == 0 }) a.copyOf() else subtractLimbs(p, a)

    fun multiply(a: IntArray, b: IntArray): IntArray = montgomeryMultiply(a, b)

    /** [base] to the power of the big-endian [exponent], by square-and-multiply. */
    fun power(base: IntArray, exponent: ByteArray): IntArray {
        var result = one
        for (byte in exponent) {
            for (bit in 7 downTo 0) {
                result = multiply(result, result)
                if ((byte.toInt() ushr bit) and 1 == 1) result = multiply(result, base)
            }
        }
        return result
    }

    /** The parity of [element] as an integer (not of its Montgomery form). */
    fun isOdd(element: IntArray): Boolean = toBytes(element).last().toInt() and 1 == 1

    fun isEqual(a: IntArray, b: IntArray): Boolean = a.contentEquals(b)

    /** `(p + 1) / 4` as big-endian bytes: the exponent of a square root when `p ≡ 3 (mod 4)`. */
    fun squareRootExponent(): ByteArray {
        require(p[0] and 3 == 3) { "Square roots by exponentiation need p ≡ 3 (mod 4)" }
        val plusOne = IntArray(size + 1)
        var carry = 1
        for (i in 0 until size) {
            val s = p[i] + carry
            plusOne[i] = s and LIMB_MASK
            carry = s ushr LIMB_BITS
        }
        plusOne[size] = carry
        val quarter = IntArray(size + 1) { i ->
            ((plusOne[i] ushr 2) or (plusOne.getOrElse(i + 1) { 0 } shl (LIMB_BITS - 2))) and LIMB_MASK
        }
        return ByteArray(byteLength) { index ->
            val bitOffset = 8 * (byteLength - 1 - index)
            (quarter[bitOffset / LIMB_BITS] ushr (bitOffset % LIMB_BITS)).toByte()
        }
    }

    private fun toMontgomery(value: IntArray): IntArray = montgomeryMultiply(value, rSquared)

    /** `a * b * R^-1 mod p` for `a, b < p`. */
    private fun montgomeryMultiply(a: IntArray, b: IntArray): IntArray {
        // t has two extra limbs: it stays below 2p, and adding a limb product can carry one limb further.
        val t = IntArray(size + 2)
        for (i in 0 until size) {
            // t += a[i] * b
            var carry = 0
            for (j in 0 until size) {
                val s = a[i] * b[j] + t[j] + carry
                t[j] = s and LIMB_MASK
                carry = s ushr LIMB_BITS
            }
            var s = t[size] + carry
            t[size] = s and LIMB_MASK
            t[size + 1] += s ushr LIMB_BITS

            // t += m * p, with m chosen so that the lowest limb becomes zero, then t /= 2^16.
            val m = (t[0] * negativeInverse) and LIMB_MASK
            carry = (m * p[0] + t[0]) ushr LIMB_BITS
            for (j in 1 until size) {
                s = m * p[j] + t[j] + carry
                t[j - 1] = s and LIMB_MASK
                carry = s ushr LIMB_BITS
            }
            s = t[size] + carry
            t[size - 1] = s and LIMB_MASK
            s = t[size + 1] + (s ushr LIMB_BITS)
            t[size] = s and LIMB_MASK
            t[size + 1] = s ushr LIMB_BITS
        }
        return reduceOnce(t.copyOf(size + 1))
    }

    /** [value] (with one extra limb, below 2p) minus p if it is at least p. */
    private fun reduceOnce(value: IntArray): IntArray {
        val wide = p.copyOf(value.size)
        return if (compare(value, wide) >= 0) subtractLimbs(value, wide).copyOf(size) else value.copyOf(size)
    }

    private fun subtractLimbs(a: IntArray, b: IntArray): IntArray {
        val difference = IntArray(a.size)
        var borrow = 0
        for (i in a.indices) {
            val d = a[i] - b.getOrElse(i) { 0 } - borrow
            difference[i] = d and LIMB_MASK
            borrow = (d ushr 31) and 1
        }
        return difference
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        for (i in maxOf(a.size, b.size) - 1 downTo 0) {
            val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    private companion object {
        const val LIMB_BITS = 16
        const val LIMB_MASK = 0xFFFF

        /** Big-endian bytes as little-endian 16-bit limbs. */
        fun limbsOf(bytes: ByteArray): IntArray {
            val limbs = IntArray((bytes.size + 1) / 2)
            for (index in bytes.indices) {
                val bitOffset = 8 * (bytes.size - 1 - index)
                limbs[bitOffset / LIMB_BITS] =
                    limbs[bitOffset / LIMB_BITS] or ((bytes[index].toInt() and 0xFF) shl (bitOffset % LIMB_BITS))
            }
            return limbs
        }
    }
}
