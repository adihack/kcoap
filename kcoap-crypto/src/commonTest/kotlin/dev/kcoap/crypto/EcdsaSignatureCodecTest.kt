package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ECDSA signatures between raw `r || s` (RFC 9053 §2.1) and DER `ECDSA-Sig-Value` (RFC 3279 §2.2.3, RFC 8422
 * §5.4). The JVM test `EcdsaSignatureCodecJcaTest` checks the same against the JDK's own signatures.
 */
class EcdsaSignatureCodecTest {
    private val r = "11".repeat(32)
    private val s = "22".repeat(32)

    @Test
    fun rfc3279Section2_2_3PositiveIntegersInASequence() {
        val der = EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, (r + s).hexToBytes())
        assertEquals("3044" + "0220$r" + "0220$s", der.toHex())
        assertEquals(r + s, EcdsaSignatureCodec.fromDer(SignatureAlgorithm.ES256, der)!!.toHex())
    }

    /** X.690 §8.3.2: minimal two's complement, so a set top bit needs a zero byte and leading zeros go. */
    @Test
    fun integersAreMinimalAndPositive() {
        val highBit = "80" + "00".repeat(31)
        val short = "00".repeat(31) + "01"
        val leadingZeros = "0000" + "7f".repeat(30)
        for ((raw, expected) in listOf(
            highBit + short to "3026" + "022100$highBit" + "020101",
            short + leadingZeros to "3023" + "020101" + "021e${"7f".repeat(30)}",
            "ff".repeat(32) + "ff".repeat(32) to "3046" + "022100${"ff".repeat(32)}" + "022100${"ff".repeat(32)}",
        )) {
            val der = EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, raw.hexToBytes())
            assertEquals(expected, der.toHex(), raw)
            assertEquals(raw, EcdsaSignatureCodec.fromDer(SignatureAlgorithm.ES256, der)!!.toHex(), "round trip of $raw")
        }
    }

    @Test
    fun es384UsesFortyEightByteHalves() {
        val raw = ("80" + "01".repeat(47) + "02".repeat(48)).hexToBytes()
        val der = EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES384, raw)
        assertEquals(2 + 3 + 48 + 2 + 48, der.size)
        assertEquals(raw.toHex(), EcdsaSignatureCodec.fromDer(SignatureAlgorithm.ES384, der)!!.toHex())
        assertNull(EcdsaSignatureCodec.fromDer(SignatureAlgorithm.ES256, der), "48-byte halves do not fit ES256")
    }

    @Test
    fun toDerRejectsWhatNoBackendSigns() {
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES384, ByteArray(64) { 1 }) }
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.toDer(SignatureAlgorithm.EDDSA, ByteArray(64) { 1 }) }
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.toDer(SignatureAlgorithm.ML_DSA_44, ByteArray(2420)) }
        val zeroR = ("00".repeat(32) + s).hexToBytes()
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, zeroR) }
        assertFailsWith<IllegalArgumentException> { EcdsaSignatureCodec.fromDer(SignatureAlgorithm.EDDSA, ByteArray(8)) }
    }

    /** A peer's malformed signature is one that does not verify: null, never an exception. */
    @Test
    fun fromDerReturnsNullForAnythingButAStrictDerSignature() {
        val malformed = listOf(
            "", // nothing
            "3044" + "0220$r" + "0220$s" + "00", // a trailing byte
            "3045" + "0220$r" + "0220$s" + "00", // a stray byte after s
            "3046" + "0220$r" + "0220$s" + "0500", // a third element
            "3022" + "0220$r", // s missing
            "3144" + "0220$r" + "0220$s", // SET instead of SEQUENCE
            "3044" + "0420$r" + "0220$s", // OCTET STRING instead of INTEGER
            "3026" + "022100$r" + "020101", // a zero byte the top bit does not need
            "3025" + "022080${"00".repeat(31)}" + "020101", // negative r
            "3006" + "020100" + "020101", // r = 0
            "3005" + "0200" + "020101", // an empty INTEGER
            "3045" + "022101${"00".repeat(32)}" + "0220$s", // r longer than 32 bytes
            "308144" + "0220$r" + "0220$s", // a non-minimal SEQUENCE length
            "3044" + "0220$r", // a length beyond the input
        )
        for (hex in malformed) {
            assertNull(EcdsaSignatureCodec.fromDer(SignatureAlgorithm.ES256, hex.hexToBytes()), hex)
        }
    }

    @Test
    fun mutatedSignaturesNeverThrow() {
        val seeds = listOf(
            EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, (r + s).hexToBytes()),
            EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES256, ("80" + "00".repeat(31) + "00".repeat(31) + "05").hexToBytes()),
            EcdsaSignatureCodec.toDer(SignatureAlgorithm.ES384, ByteArray(96) { (it + 1).toByte() }),
        )
        val random = Random(3279)
        var decoded = 0
        repeat(4_000) {
            val algorithm = if (it % 3 == 2) SignatureAlgorithm.ES384 else SignatureAlgorithm.ES256
            val raw = EcdsaSignatureCodec.fromDer(algorithm, ByteMutator.mutate(seeds[it % seeds.size], random))
            if (raw != null) {
                assertEquals(algorithm.signatureLength, raw.size)
                decoded++
            }
        }
        assertTrue(decoded in 1 until 4_000, "some mutations still decode, most do not: $decoded")
    }
}
