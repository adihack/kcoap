package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEC 1 v2 §2.3.4 point decompression for P-256 and P-384, in common code on every target. */
class Sec1Test {
    /** The SEC 2 base points, their multiples and the RFC 9529 trace 2 points, with y of both parities. */
    @Test
    fun sec1Section2_3_4KnownPointsDecompress() {
        for ((type, compressed, uncompressed) in EcPoints.ALL) {
            assertEquals(uncompressed, Sec1.decompress(type, compressed.hexToBytes()).toHex(), compressed)
        }
    }

    /** The other prefix gives the other square root: the same x with y replaced by p - y. */
    @Test
    fun thePrefixChoosesTheParityOfY() {
        for ((type, compressed, uncompressed) in EcPoints.ALL) {
            val flipped = (if (compressed.startsWith("02")) "03" else "02") + compressed.substring(2)
            val other = Sec1.decompress(type, flipped.hexToBytes())
            val coordinateLength = (type.publicKeyLength - 1) / 2
            assertEquals(uncompressed.substring(0, 2 + 2 * coordinateLength), other.toHex().substring(0, 2 + 2 * coordinateLength))
            val y = other.copyOfRange(1 + coordinateLength, other.size)
            assertEquals(flipped.startsWith("03"), y.last().toInt() and 1 == 1, flipped)
            assertFalse(uncompressed == other.toHex(), "the two roots differ")
        }
    }

    @Test
    fun rejectsWhatIsNotACompressedPointOnTheCurve() {
        val x = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        // An x equal to p (as in the RFC 9529 invalid traces), and x = 1, whose x^3 - 3x + b has no square root.
        val p256 = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        val p384 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff" + "fffffffeffffffff0000000000000000ffffffff"
        val rejected = listOf(
            KeyType.P256 to "02$p256",
            KeyType.P256 to "03${"ff".repeat(32)}",
            KeyType.P256 to "02${"00".repeat(31)}01",
            KeyType.P384 to "02$p384",
            KeyType.P384 to "02${"00".repeat(47)}01",
            KeyType.P256 to "04$x",
            KeyType.P256 to "04$x$x",
            KeyType.P256 to "02${x}00",
            KeyType.P256 to "02${x.substring(2)}",
            KeyType.P256 to "",
            KeyType.P384 to "02$x",
            KeyType.X25519 to "02$x",
            KeyType.ED25519 to "02$x",
        )
        for ((type, point) in rejected) {
            assertFailsWith<IllegalArgumentException>("$type $point") { Sec1.decompress(type, point.hexToBytes()) }
        }
        assertEquals(
            "The x-coordinate is not on P256",
            assertFailsWith<IllegalArgumentException> { Sec1.decompress(KeyType.P256, "02${"00".repeat(31)}01".hexToBytes()) }.message,
        )
    }

    @Test
    fun isCompressedLooksAtThePrefixOnly() {
        assertTrue(Sec1.isCompressed("02".hexToBytes()))
        assertTrue(Sec1.isCompressed(EcPoints.P256_G_COMPRESSED.hexToBytes()))
        assertTrue(Sec1.isCompressed(EcPoints.P384_2G_COMPRESSED.hexToBytes()))
        assertFalse(Sec1.isCompressed(EcPoints.P256_G_UNCOMPRESSED.hexToBytes()))
        assertFalse(Sec1.isCompressed("01".hexToBytes()))
        assertFalse(Sec1.isCompressed(ByteArray(0)))
    }

    /** x = 0 is on P-256 (b is a square there), the smallest coordinate the arithmetic meets. */
    @Test
    fun theSmallestCoordinateDecompressesToAPointOnTheCurve() {
        val zero = "00".repeat(32)
        val even = Sec1.decompress(KeyType.P256, "02$zero".hexToBytes())
        val odd = Sec1.decompress(KeyType.P256, "03$zero".hexToBytes())
        assertEquals("04$zero", even.toHex().substring(0, 66))
        assertEquals(0, even.last().toInt() and 1)
        assertEquals(1, odd.last().toInt() and 1)
    }
}
