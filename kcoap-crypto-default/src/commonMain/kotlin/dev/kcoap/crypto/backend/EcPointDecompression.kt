package dev.kcoap.crypto.backend

import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.KeyType

/**
 * Compressed SEC1 points (`02 || x` or `03 || x`) for P-256 and P-384, expanded to `04 || x || y` in
 * common code (SEC 1 v2 §2.3.4).
 *
 * EDHOC sends only the x-coordinate of P-256 and P-384 keys (RFC 9528 §3.7), and kcoap passes such a
 * key on as `02 || x`. JCA, CommonCrypto, OpenSSL and Node.js accept compressed points, but browsers'
 * WebCrypto does not reliably, so the backend expands the point itself and hands every platform the
 * uncompressed form. The point is public, so the arithmetic need not be constant time.
 *
 * Both curves are `y^2 = x^3 - 3x + b` over a prime `p ≡ 3 (mod 4)`, so the square root is
 * `(x^3 - 3x + b)^((p + 1) / 4)`. A candidate that does not square back to the right-hand side means
 * x is not on the curve, and the key is rejected.
 */
internal object EcPointDecompression {
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

    /** Whether [point] is a compressed SEC1 encoding (`02` or `03` prefix). */
    fun isCompressed(point: ByteArray): Boolean = point.isNotEmpty() && (point[0].toInt() == 2 || point[0].toInt() == 3)

    /**
     * The uncompressed `04 || x || y` for the compressed [point] on the curve of [type].
     *
     * @throws IllegalArgumentException when the encoding is malformed or x is not on the curve.
     */
    fun decompress(type: KeyType, point: ByteArray): ByteArray {
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
