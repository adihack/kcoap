package dev.kcoap.crypto

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeccakTest {
    /** FIPS 202 known answer: SHAKE256 of the empty message, 512 bits. */
    @Test
    fun shake256EmptyMessage() {
        assertEquals(
            "46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762f" +
                "d75dc4ddd8c0f200cb05019d67b592f6fc821c49479ab48640292eacb3b7c4be",
            Shake256.digest(ByteArray(0), 64).toHex(),
        )
    }

    /** A shorter output is a prefix of a longer one, across the 136-byte rate boundary. */
    @Test
    fun shake256OutputIsAPrefixStream() {
        val data = ByteArray(300) { it.toByte() }
        val long = Shake256.digest(data, 500)
        assertContentEquals(long.copyOf(17), Shake256.digest(data, 17))
        assertContentEquals(long.copyOf(136), Shake256.digest(data, 136))
        assertEquals(0, Shake256.digest(data, 0).size)
    }

    /** NIST SP 800-185 KMAC sample #4: KMAC256 with customization "My Tagged Application". */
    @Test
    fun kmac256NistSample4() {
        val key = "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f".hexToBytes()
        val output = Kmac256.compute(key, "00010203".hexToBytes(), 64, "My Tagged Application".encodeToByteArray())
        assertEquals(
            "20c570c31346f703c9ac36c61c03cb64c3970d0cfc787e9b79599d273a68d2f7" +
                "f69d4cc3de9d104a351689f27cf6f5951f0103f33f4f24871024d9c27773a8dd",
            output.toHex(),
        )
    }

    @Test
    fun encodingsFollowSp800185() {
        assertEquals("0100", Kmac256.leftEncode(0).toHex())
        assertEquals("0188", Kmac256.leftEncode(136).toHex())
        assertEquals("020100", Kmac256.leftEncode(256).toHex())
        assertEquals("0001", Kmac256.rightEncode(0).toHex())
        assertEquals("010002", Kmac256.rightEncode(256).toHex())
        assertFailsWith<IllegalArgumentException> { Kmac256.compute(ByteArray(0), ByteArray(0), -1) }
    }
}
