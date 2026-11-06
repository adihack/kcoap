package dev.kcoap.coap.stack

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborInt
import dev.kcoap.common.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hand-written Content-Format 272 codec ([MissingBlocks], RFC 9177 §5) against kcoap-cbor's CBOR
 * Sequences (RFC 8742). On the JVM only: kcoap-cbor on the common test classpath breaks the Kotlin/Wasm
 * test build (see `kcoap-coap/build.gradle.kts`).
 */
class MissingBlocksCborTest {
    @Test
    fun rfc8742MissingBlocksAgreeWithKcoapCbor() {
        val random = Random(9177)
        repeat(50) {
            val numbers = List(random.nextInt(1, 12)) { random.nextLong(0, 0x100000) }.distinct().sorted()
            val encoded = MissingBlocks.encode(numbers)
            assertEquals(encoded.toHex(), Cbor.encodeSequence(numbers.map { CborInt(it) }).toHex())
            assertEquals(numbers, Cbor.decodeSequence(encoded).map { (it as CborInt).value })
            assertEquals(numbers, MissingBlocks.decode(encoded))
        }
    }
}
