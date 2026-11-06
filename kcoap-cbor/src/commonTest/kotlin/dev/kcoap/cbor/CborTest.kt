package dev.kcoap.cbor

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CborTest {
    /** RFC 8949 Appendix A, every example without floating point or integers beyond the Long range. */
    private val appendixA: List<Pair<CborValue, String>> = listOf(
        CborInt(0) to "00",
        CborInt(1) to "01",
        CborInt(10) to "0a",
        CborInt(23) to "17",
        CborInt(24) to "18 18",
        CborInt(25) to "18 19",
        CborInt(100) to "18 64",
        CborInt(1000) to "19 03e8",
        CborInt(1_000_000) to "1a 000f4240",
        CborInt(1_000_000_000_000) to "1b 000000e8d4a51000",
        CborInt(-1) to "20",
        CborInt(-10) to "29",
        CborInt(-100) to "38 63",
        CborInt(-1000) to "39 03e7",
        CborBool(false) to "f4",
        CborBool(true) to "f5",
        CborNull to "f6",
        CborUndefined to "f7",
        CborSimple(16) to "f0",
        CborSimple(255) to "f8 ff",
        CborTag(0, CborText("2013-03-21T20:04:00Z")) to "c0 74 323031332d30332d32315432303a30343a30305a",
        CborTag(1, CborInt(1_363_896_240)) to "c1 1a 514b67b0",
        CborTag(23, CborBytes("01020304".hexToBytes())) to "d7 44 01020304",
        CborTag(24, CborBytes("6449455446".hexToBytes())) to "d8 18 45 6449455446",
        CborTag(32, CborText("http://www.example.com")) to "d8 20 76 687474703a2f2f7777772e6578616d706c652e636f6d",
        CborBytes(ByteArray(0)) to "40",
        CborBytes("01020304".hexToBytes()) to "44 01020304",
        CborText("") to "60",
        CborText("a") to "61 61",
        CborText("IETF") to "64 49455446",
        CborText("\"\\") to "62 225c",
        CborText("ü") to "62 c3bc",
        CborText("水") to "63 e6b0b4",
        CborText("𐅑") to "64 f0908591",
        CborArray() to "80",
        CborArray(1.toCbor(), 2.toCbor(), 3.toCbor()) to "83 010203",
        CborArray(1.toCbor(), CborArray(2.toCbor(), 3.toCbor()), CborArray(4.toCbor(), 5.toCbor())) to "83 01 820203 820405",
        CborArray((1..25).map { it.toCbor() }) to "98 19 0102030405060708090a0b0c0d0e0f101112131415161718181819",
        CborMap() to "a0",
        CborMap(1.toCbor() to 2.toCbor(), 3.toCbor() to 4.toCbor()) to "a2 0102 0304",
        CborMap("a".toCbor() to 1.toCbor(), "b".toCbor() to CborArray(2.toCbor(), 3.toCbor())) to "a2 6161 01 6162 820203",
        CborArray("a".toCbor(), CborMap("b".toCbor() to "c".toCbor())) to "82 6161 a1 6162 6163",
        CborMap(("a".."e").map { it.toCbor() to it.uppercase().toCbor() }) to
            "a5 6161 6141 6162 6142 6163 6143 6164 6144 6165 6145",
    )

    private operator fun String.rangeTo(last: String): List<String> = (this[0]..last[0]).map { it.toString() }

    @Test
    fun encodesAppendixAExamples() {
        for ((value, hex) in appendixA) assertEquals(hex.replace(" ", ""), value.encode().toHex(), value.toString())
    }

    @Test
    fun decodesAppendixAExamples() {
        for ((value, hex) in appendixA) assertEquals(value, Cbor.decode(hex.hexToBytes()), hex)
    }

    @Test
    fun longRangeBoundaries() {
        assertEquals("1b 7fffffffffffffff".replace(" ", ""), CborInt(Long.MAX_VALUE).encode().toHex())
        assertEquals("3b 7fffffffffffffff".replace(" ", ""), CborInt(Long.MIN_VALUE).encode().toHex())
        assertEquals(CborInt(Long.MIN_VALUE), Cbor.decode("3b7fffffffffffffff".hexToBytes()))
        // RFC 8949 Appendix A: 18446744073709551615 and -18446744073709551616 exceed Long.
        assertFailsWith<CborException> { Cbor.decode("1bffffffffffffffff".hexToBytes()) }
        assertFailsWith<CborException> { Cbor.decode("3bffffffffffffffff".hexToBytes()) }
    }

    @Test
    fun readerParsesSequencesAndKeepsExactBytes() {
        // An EDHOC message_1-like sequence: method, suites array, bstr, int.
        val sequence = "03 820602 41 ff 37".hexToBytes()
        val reader = CborReader(sequence)
        assertEquals(CborType.UNSIGNED, reader.peekType())
        assertEquals(3, reader.readInt())
        assertEquals("820602", reader.readEncodedItem().toHex())
        assertContentEquals(byteArrayOf(-1), reader.readBytes())
        assertEquals(-24, reader.readInt())
        assertTrue(reader.isAtEnd)
        assertEquals(4, Cbor.decodeSequence(sequence).size)
        assertContentEquals(sequence, Cbor.encodeSequence(Cbor.decodeSequence(sequence)))
    }

    @Test
    fun streamingReadersAndWriters() {
        val bytes = CborWriter()
            .writeArrayHeader(7)
            .writeText("Encrypt0").writeBytes(ByteArray(0)).writeTag(24).writeBool(true)
            .writeNull().writeUndefined().writeMapHeader(1).writeInt(-1).writeSimple(19)
            .writeEncoded("01".hexToBytes())
            .toByteArray()
        val reader = CborReader(bytes)
        assertEquals(7, reader.readArrayHeader())
        assertEquals("Encrypt0", reader.readText())
        assertEquals(0, reader.readBytes().size)
        assertEquals(24, reader.readTag())
        assertTrue(reader.readBool())
        assertTrue(reader.peekIsNull())
        reader.readNull()
        assertFalse(reader.peekIsNull())
        reader.skip()
        assertEquals(1, reader.readMapHeader())
        assertEquals(-1, reader.readInt())
        assertEquals(CborSimple(19), reader.readValue())
        assertEquals(1, reader.readInt())
        assertTrue(reader.isAtEnd)
    }

    @Test
    fun rejectsMalformedAndUnsupportedInput() {
        val bad = mapOf(
            "" to "empty input",
            "18" to "truncated argument",
            "44 0102" to "truncated byte string",
            "5f 41 00 ff" to "indefinite byte string",
            "9f ff" to "indefinite array",
            "1c" to "reserved additional info",
            "f9 3c00" to "half float",
            "fb 3ff0000000000000" to "double",
            "f8 13" to "simple value below 32 in two bytes",
            "62 c328" to "invalid UTF-8",
            "9b 7fffffffffffffff" to "array count larger than the input",
            "00 00" to "trailing bytes",
        )
        for ((hex, reason) in bad) {
            assertFailsWith<CborException>(reason) { Cbor.decode(hex.hexToBytes()) }
        }
        val deep = ByteArray(40) { 0x81.toByte() } + byteArrayOf(0)
        assertFailsWith<CborException>("deep nesting") { Cbor.decode(deep) }
    }

    @Test
    fun typedReadsReportWhatTheyFound() {
        assertFailsWith<CborException> { CborReader("40".hexToBytes()).readInt() }
        assertFailsWith<CborException> { CborReader("01".hexToBytes()).readBytes() }
        assertFailsWith<CborException> { CborReader("01".hexToBytes()).readArrayHeader() }
        assertFailsWith<CborException> { CborReader("01".hexToBytes()).readTag() }
        assertFailsWith<CborException> { CborReader("01".hexToBytes()).readBool() }
        assertFailsWith<CborException> { CborReader("01".hexToBytes()).readNull() }
        val error = assertFailsWith<CborException> { CborReader("0102".hexToBytes(), start = 1).readText() }
        assertEquals(1, error.offset)
        assertFailsWith<IllegalArgumentException> { CborReader(ByteArray(2), start = 3) }
    }

    @Test
    fun valueAccessors() {
        val map = CborMap(1.toCbor() to "x".toCbor(), "k".toCbor() to byteArrayOf(9).toCbor())
        assertEquals("x", map[1]?.asText())
        assertEquals("x", map[1L]?.asText())
        assertContentEquals(byteArrayOf(9), map["k"]?.asBytes())
        assertNull(map[2])
        assertEquals(7, 7L.toCbor().asInt())
        assertFailsWith<CborException> { CborInt(Long.MAX_VALUE).asInt() }
        assertFailsWith<CborException> { CborNull.asLong() }
        assertFailsWith<CborException> { CborNull.asBytes() }
        assertFailsWith<CborException> { CborNull.asText() }
        assertFailsWith<CborException> { CborNull.asArray() }
        assertFailsWith<CborException> { CborNull.asMap() }
        assertEquals(2, CborArray(1.toCbor(), true.toCbor()).asArray().size)
        assertEquals(CborBool(true), CborArray(1.toCbor(), true.toCbor())[1])
        assertEquals(1, map.asMap().entries.count { it.first is CborInt })
        assertFailsWith<IllegalArgumentException> { CborSimple(20) }
    }

    @Test
    fun deterministicMapOrder() {
        val map = CborMap("b".toCbor() to 1.toCbor(), 10.toCbor() to 2.toCbor(), (-1).toCbor() to 3.toCbor(), 1.toCbor() to 4.toCbor())
        // Bytewise order of encoded keys: 01, 0a, 20, 6162.
        assertEquals("a4 0104 0a02 2003 616201".replace(" ", ""), map.sortedDeterministically().encode().toHex())
    }

    @Test
    fun readableToString() {
        val value = CborArray(1.toCbor(), "a".toCbor(), byteArrayOf(0x0f).toCbor(), CborMap(true.toCbor() to CborNull), CborTag(24, CborUndefined))
        assertEquals("[1, \"a\", h'0f', {true: null}, 24(undefined)]", value.toString())
        assertEquals("simple(32)", CborSimple(32).toString())
        assertEquals(CborBytes(byteArrayOf(1)).hashCode(), CborBytes(byteArrayOf(1)).hashCode())
        assertEquals(1, CborBytes(byteArrayOf(1)).size)
    }
}
