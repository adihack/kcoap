package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.backend.EcPointDecompression
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SEC 1 §2.3.4 point decompression for P-256 and P-384, which the backend does in common code. */
class EcPointDecompressionTest {
    private val backend = DefaultCryptoBackend()

    /** The base points of SEC 2 §2.4.2 and §2.5.1; both have an odd y. */
    @Test
    fun sec2BasePointsDecompress() {
        val p256x = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        val p256y = "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
        assertEquals("04$p256x$p256y", EcPointDecompression.decompress(KeyType.P256, "03$p256x".hex()).hex())

        val p384x = "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7"
        val p384y = "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"
        assertEquals("04$p384x$p384y", EcPointDecompression.decompress(KeyType.P384, "03$p384x".hex()).hex())
    }

    /** Fresh keys from the platform, compressed by hand, must come back unchanged with either parity of y. */
    @Test
    fun platformKeysRoundTripThroughCompression() = runTest {
        for (type in listOf(KeyType.P256, KeyType.P384)) {
            val parities = mutableSetOf<Boolean>()
            repeat(16) {
                val point = backend.generateKeyPair(type).publicKey
                val coordinateLength = (point.size - 1) / 2
                val isOdd = point.last().toInt() and 1 == 1
                parities += isOdd
                val compressed = byteArrayOf(if (isOdd) 3 else 2) + point.copyOfRange(1, 1 + coordinateLength)
                assertEquals(point.hex(), EcPointDecompression.decompress(type, compressed).hex(), type.name)
            }
            assertEquals(setOf(true, false), parities, "$type: 16 random keys should show both parities")
        }
    }

    @Test
    fun rejectsWhatIsNotACompressedPointOnTheCurve() {
        val x = "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
        // An x equal to p (as in the RFC 9529 invalid traces), and x = 1, whose x^3 - 3x + b has no square root.
        val p = "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.P256, "02$p".hex()) }
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.P256, "02${"00".repeat(31)}01".hex()) }
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.P256, "04$x".hex()) }
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.P256, "02${x}00".hex()) }
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.P384, "02$x".hex()) }
        assertFailsWith<IllegalArgumentException> { EcPointDecompression.decompress(KeyType.X25519, "02$x".hex()) }
        assertTrue(EcPointDecompression.isCompressed("02$x".hex()))
        assertFalse(EcPointDecompression.isCompressed("04$x".hex()))
        assertFalse(EcPointDecompression.isCompressed(ByteArray(0)))
    }
}
