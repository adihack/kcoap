package dev.kcoap.crypto

import dev.kcoap.common.toHex
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Point decompression against Bouncy Castle's, on random points of both curves and random x-coordinates. */
class Sec1DifferentialTest {
    private val random = Random(234)

    @Test
    fun randomPointsDecompressLikeBouncyCastle() {
        for ((type, name) in listOf(KeyType.P256 to "secp256r1", KeyType.P384 to "secp384r1")) {
            val curve = ECNamedCurveTable.getByName(name)
            repeat(64) {
                val k = BigInteger(1, random.nextBytes(curve.n.bitLength() / 8)).mod(curve.n).max(BigInteger.ONE)
                val point = curve.g.multiply(k).normalize()
                val compressed = point.getEncoded(true)
                assertEquals(point.getEncoded(false).toHex(), Sec1.decompress(type, compressed).toHex(), "$name k=$k")
            }
        }
    }

    /** About half of all x have a point; Bouncy Castle and [Sec1] must agree on which. */
    @Test
    fun randomCoordinatesAreAcceptedExactlyWhereBouncyCastleAcceptsThem() {
        for ((type, name) in listOf(KeyType.P256 to "secp256r1", KeyType.P384 to "secp384r1")) {
            val curve = ECNamedCurveTable.getByName(name).curve
            val length = (type.publicKeyLength - 1) / 2
            var accepted = 0
            repeat(200) {
                val compressed = byteArrayOf((2 + random.nextInt(2)).toByte()) + random.nextBytes(length)
                val expected = runCatching { curve.decodePoint(compressed).getEncoded(false) }.getOrNull()
                if (expected == null) {
                    assertFailsWith<IllegalArgumentException>(compressed.toHex()) { Sec1.decompress(type, compressed) }
                } else {
                    accepted++
                    assertEquals(expected.toHex(), Sec1.decompress(type, compressed).toHex(), compressed.toHex())
                }
            }
            check(accepted in 50..150) { "$name: $accepted of 200 random x on the curve" }
        }
    }
}
