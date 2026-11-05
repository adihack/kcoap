package dev.kcoap.coap.codec

import dev.kcoap.common.hexToBytes
import dev.kcoap.testkit.ByteMutator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Hostile datagrams and streams: every codec either decodes or throws [CoapFormatException], and what it
 * decodes encodes back to a message that decodes to the same thing.
 */
class CodecFuzzTest {
    /** RFC 8613 Appendix C messages, plain and OSCORE-protected. */
    private val udpSeeds = listOf(
        "44015d1f00003974396c6f63616c686f737483747631",
        "64455d1f00003974ff48656c6c6f20576f726c6421",
        "44025d1f00003974396c6f63616c686f7374620914ff612f1092f1776f1c1668b3825e",
        "44022f8eef9bbf7a396c6f63616c686f73746b19140837cbf3210017a2d3ff72cd7273fd331ac45cffbe55c3",
        "60001234",
    ).map { it.hexToBytes() }

    private val reliableSeeds = udpSeeds.map { UdpMessageCodec.decode(it) }.map { ReliableMessageCodec.Tcp.encode(it) }

    @Test
    fun udpDecoderRejectsCleanly() = fuzz(udpSeeds, 0xC0A) { input ->
        val message = UdpMessageCodec.decode(input)
        assertEquals(message, UdpMessageCodec.decode(UdpMessageCodec.encode(message)))
    }

    @Test
    fun tcpAndWebSocketDecodersRejectCleanly() = fuzz(reliableSeeds, 0xC0B) { input ->
        val message = ReliableMessageCodec.Tcp.decode(input)
        assertEquals(message, ReliableMessageCodec.Tcp.decode(ReliableMessageCodec.Tcp.encode(message)))
        ReliableMessageCodec.WebSocket.decode(input)
    }

    @Test
    fun streamDecoderRejectsCleanly() {
        val random = Random(0xC0C)
        repeat(ITERATIONS) { round ->
            val decoder = ReliableStreamDecoder(maxMessageSize = 4096)
            val input = ByteMutator.mutate(reliableSeeds[round % reliableSeeds.size] + reliableSeeds[(round + 1) % reliableSeeds.size], random)
            try {
                input.toList().chunked(random.nextInt(1, 9)).forEach { decoder.feed(it.toByteArray()) }
            } catch (_: CoapFormatException) {
                // expected for malformed streams
            } catch (e: Throwable) {
                fail("Unexpected ${e::class.simpleName}: $e")
            }
        }
    }

    private fun fuzz(seeds: List<ByteArray>, seed: Int, decode: (ByteArray) -> Unit) {
        val random = Random(seed)
        repeat(ITERATIONS) { round ->
            val input = if (round % 4 == 3) random.nextBytes(random.nextInt(0, 40)) else ByteMutator.mutate(seeds[round % seeds.size], random)
            try {
                decode(input)
            } catch (_: CoapFormatException) {
                // expected for malformed input
            } catch (e: Throwable) {
                fail("Unexpected ${e::class.simpleName} for ${input.size} bytes: $e")
            }
        }
    }

    private companion object {
        const val ITERATIONS = 3_000
    }
}
