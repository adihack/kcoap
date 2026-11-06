package dev.kcoap.cbor

import dev.kcoap.common.hexToBytes
import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail

/**
 * Hostile input: mutations of real encodings and random bytes must either decode or fail with
 * [CborException], never with another exception (an index error would mean a missing bounds check).
 * Deterministic: the seed is fixed, so a failure reproduces.
 */
class CborFuzzTest {
    private val seeds = listOf(
        "a2026b6578616d706c652e65647508a101a501020241322001215820bbc34960526ea4d32e940cad2a234148ddc21791a12afbcbac93622046dd44f02258204519e257236b2a0ce2023f0931f1f386ca7afda64fcde0108c224c51eabf6072",
        "8368456e63727970743040488501810a40411440",
        "83 01 820203 820405",
        "a5 6161 6141 6162 6142 6163 6143 6164 6144 6165 6145",
        "d8 20 76 687474703a2f2f7777772e6578616d706c652e636f6d",
        "1b 000000e8d4a51000",
    ).map { it.replace(" ", "").hexToBytes() }

    @Test
    fun mutatedEncodingsFailCleanly() {
        val random = Random(0xCB0)
        repeat(ITERATIONS) { round ->
            val input = ByteMutator.mutate(seeds[round % seeds.size], random)
            decodeOrReject(input)
        }
    }

    @Test
    fun randomBytesFailCleanly() {
        val random = Random(0xCB1)
        repeat(ITERATIONS) { decodeOrReject(random.nextBytes(random.nextInt(0, 64))) }
    }

    /** Whatever decodes re-encodes to a value that decodes to the same thing. */
    @Test
    fun decodedValuesRoundTrip() {
        val random = Random(0xCB2)
        repeat(ITERATIONS) { round ->
            val input = ByteMutator.mutate(seeds[round % seeds.size], random)
            val value = try {
                Cbor.decodeSequence(input)
            } catch (_: CborException) {
                return@repeat
            }
            assertContentEquals(Cbor.encodeSequence(value), Cbor.encodeSequence(Cbor.decodeSequence(Cbor.encodeSequence(value))))
        }
    }

    private fun decodeOrReject(input: ByteArray) {
        try {
            Cbor.decodeSequence(input)
        } catch (_: CborException) {
            // expected for malformed input
        } catch (e: Throwable) {
            fail("Unexpected ${e::class.simpleName} for ${input.size} bytes: $e")
        }
    }

    private companion object {
        const val ITERATIONS = 3_000
    }
}
