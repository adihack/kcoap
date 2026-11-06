package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes

/**
 * Compressed SEC1 points (`02 || x` or `03 || x`) for P-256 and P-384, expanded to the uncompressed
 * `04 || x || y` that [KeyType.P256] and [KeyType.P384] document (SEC 1 v2 §2.3.4).
 *
 * P-256 and P-384 public keys cross the [CryptoBackend] boundary uncompressed, so protocol code expands
 * every compressed point before a backend sees it: EDHOC sends only the x-coordinate of an ephemeral
 * key (RFC 9528 §3.7, Appendix B), certificates may carry compressed keys (RFC 5480 §2.2), and a COSE_Key
 * may give y as a sign bit (RFC 9053 §7.1.1). Platforms disagree about compressed points: JCA, OpenSSL and
 * Node.js accept them, but Apple's SecKey and browsers' WebCrypto do not reliably, and a secure element
 * may not at all. Expanding them here makes the same input behave the same way on every platform and
 * with every backend.
 *
 * Both curves are `y^2 = x^3 - 3x + b` over a prime `p ≡ 3 (mod 4)`, so the square root is
 * `(x^3 - 3x + b)^((p + 1) / 4)`. A candidate that does not square back to the right-hand side means x
 * is not on the curve, and the point is rejected. The point is public, so the arithmetic need not be
 * constant time.
 */
public object Sec1 {
    private class Curve(prime: String, b: String) {
        val field = PrimeField(prime.hexToBytes())
        val b = field.fromBytes(b.hexToBytes())
        val three = field.add(field.add(field.one, field.one), field.one)
        val squareRootExponent = field.squareRootExponent()
    }

    // SEC 2 v2 §2.4.2 and §2.5.1 (NIST P-256 and P-384).
    private val p256 by lazy {
        Curve(
            prime = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            b = "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
        )
    }
    private val p384 by lazy {
        Curve(
            prime = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff" +
                "fffffffeffffffff0000000000000000ffffffff",
            b = "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875a" +
                "c656398d8a2ed19d2a85c8edd3ec2aef",
        )
    }

    /** Whether [point] starts like a compressed SEC1 point: with `02` or `03`. Its length is not checked. */
    public fun isCompressed(point: ByteArray): Boolean =
        point.isNotEmpty() && (point[0].toInt() == 2 || point[0].toInt() == 3)

    /**
     * The uncompressed `04 || x || y` of the compressed [point] on the curve of [type]: y is the square root
     * whose parity the prefix gives, `02` for an even y and `03` for an odd one.
     *
     * @throws IllegalArgumentException when [type] is not P-256 or P-384, when [point] is not `02` or `03`
     *   followed by one coordinate, or when x is not below the field prime or not on the curve.
     */
    public fun decompress(type: KeyType, point: ByteArray): ByteArray {
        val curve = when (type) {
            KeyType.P256 -> p256
            KeyType.P384 -> p384
            else -> throw IllegalArgumentException("$type has no compressed points")
        }
        val field = curve.field
        require(isCompressed(point) && point.size == 1 + field.byteLength) {
            "A compressed $type point is 02 or 03 followed by ${field.byteLength} bytes"
        }
        val x = field.fromBytes(point.copyOfRange(1, point.size))
        val xCubed = field.multiply(field.multiply(x, x), x)
        val rightHandSide = field.add(field.subtract(xCubed, field.multiply(curve.three, x)), curve.b)
        var y = field.power(rightHandSide, curve.squareRootExponent)
        require(field.isEqual(field.multiply(y, y), rightHandSide)) { "The x-coordinate is not on $type" }

        val wantsOddY = point[0].toInt() == 3
        if (field.isOdd(y) != wantsOddY) y = field.negate(y)
        return byteArrayOf(0x04) + field.toBytes(x) + field.toBytes(y)
    }
}
