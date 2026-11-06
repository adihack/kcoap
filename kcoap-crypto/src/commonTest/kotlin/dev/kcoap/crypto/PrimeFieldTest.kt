package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The modular arithmetic under [Sec1], on a small prime where every result can be checked by hand. */
class PrimeFieldTest {
    // 2^16 + 3 = 65539 is prime and ≡ 3 (mod 4); with three bytes it has two 16-bit limbs.
    private val field = PrimeField("010003".hexToBytes())

    private fun element(value: Int): IntArray = field.fromBytes(byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()))

    private fun valueOf(element: IntArray): Int = field.toBytes(element).toHex().toInt(16)

    @Test
    fun arithmeticModuloTheField() {
        assertEquals(3, field.byteLength)
        assertEquals(1, valueOf(field.one))
        assertEquals(2, valueOf(field.add(element(65_538), element(3))))
        assertEquals(65_538, valueOf(field.subtract(element(1), element(2))))
        assertEquals(0, valueOf(field.negate(element(0))))
        assertEquals(65_536, valueOf(field.negate(element(3))))
        assertEquals((60_000L * 50_000L % 65_539L).toInt(), valueOf(field.multiply(element(60_000), element(50_000))))
        assertEquals(1, valueOf(field.power(element(12_345), "010002".hexToBytes())), "Fermat: a^(p-1) = 1")
        assertTrue(field.isOdd(element(65_537)))
        assertTrue(field.isEqual(element(7), element(7)))
        // (p + 1) / 4 = 16385.
        assertEquals("004001", field.squareRootExponent().toHex())
    }

    @Test
    fun rejectsWhatIsNotAnElementOrAUsableModulus() {
        assertFailsWith<IllegalArgumentException> { field.fromBytes("010003".hexToBytes()) }
        assertFailsWith<IllegalArgumentException> { field.fromBytes("0001".hexToBytes()) }
        assertFailsWith<IllegalArgumentException> { PrimeField("0100".hexToBytes()) }
        // 13 ≡ 1 (mod 4): the square root needs another algorithm.
        assertFailsWith<IllegalArgumentException> { PrimeField("0d".hexToBytes()).squareRootExponent() }
    }
}
