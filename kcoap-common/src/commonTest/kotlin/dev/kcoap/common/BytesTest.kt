package dev.kcoap.common

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BytesTest {
    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x0a, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000a7f80ff", bytes.toHex())
        assertContentEquals(bytes, "00 0A 7f\n80 ff".hexToBytes())
        assertContentEquals(ByteArray(0), "".hexToBytes())
    }

    @Test
    fun hexRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "zz".hexToBytes() }
    }

    @Test
    fun constantTimeEqualsComparesContent() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }

    @Test
    fun wipeZeroes() {
        val key = byteArrayOf(1, 2, 3)
        key.wipe()
        assertContentEquals(ByteArray(3), key)
    }

    @Test
    fun minimalUnsignedEncoding() {
        assertContentEquals(ByteArray(0), minimalUnsignedBytes(0))
        assertContentEquals(byteArrayOf(0x14), minimalUnsignedBytes(20))
        assertContentEquals(byteArrayOf(0x01, 0x00), minimalUnsignedBytes(256))
        assertContentEquals("ffffffffffffff".hexToBytes(), minimalUnsignedBytes(0xFFFFFFFFFFFFFFL))
        assertFailsWith<IllegalArgumentException> { minimalUnsignedBytes(-1) }
    }

    @Test
    fun readUnsigned() {
        assertEquals(0, ByteArray(0).readUnsigned())
        assertEquals(0x0102, byteArrayOf(9, 1, 2).readUnsigned(offset = 1))
        assertEquals(0xFFFFFFFFFFFFFFL, "ffffffffffffff".hexToBytes().readUnsigned())
        assertFailsWith<IllegalArgumentException> { ByteArray(8).readUnsigned() }
        assertFailsWith<IllegalArgumentException> { ByteArray(2).readUnsigned(offset = 1, length = 2) }
    }
}
