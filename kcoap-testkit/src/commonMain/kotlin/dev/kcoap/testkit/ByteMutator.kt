package dev.kcoap.testkit

import kotlin.random.Random

/**
 * Structure-unaware mutations of byte strings, for fuzz tests of decoders: bit flips, replaced, inserted
 * and deleted bytes, duplicated prefixes and truncation. Start from valid encodings (RFC test vectors are
 * ideal seeds) so mutations reach deep into the parser, and use a seeded [Random] so a failure
 * reproduces.
 *
 * ```
 * val random = Random(1)
 * repeat(5_000) {
 *     val input = ByteMutator.mutate(validMessage, random)
 *     try { decode(input) } catch (_: MyFormatException) { }   // anything else is a bug
 * }
 * ```
 */
public object ByteMutator {
    /** One to three mutations of [seed]; the seed itself is not changed. */
    public fun mutate(seed: ByteArray, random: Random): ByteArray {
        var bytes = seed.copyOf()
        repeat(random.nextInt(1, 4)) {
            bytes = when (random.nextInt(6)) {
                0 -> bytes.also { if (it.isNotEmpty()) it.flipBit(random) }
                1 -> bytes.also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                2 -> bytes.insertAt(random.nextInt(bytes.size + 1), random.nextBytes(random.nextInt(1, 4)))
                3 -> if (bytes.isEmpty()) bytes else bytes.removeAt(random.nextInt(bytes.size), random.nextInt(1, 4))
                4 -> bytes + bytes.copyOfRange(0, random.nextInt(bytes.size + 1))
                else -> bytes.copyOf(random.nextInt(bytes.size + 1))
            }
        }
        return bytes
    }

    private fun ByteArray.flipBit(random: Random) {
        val index = random.nextInt(size)
        this[index] = (this[index].toInt() xor (1 shl random.nextInt(8))).toByte()
    }

    private fun ByteArray.insertAt(index: Int, extra: ByteArray): ByteArray =
        copyOfRange(0, index) + extra + copyOfRange(index, size)

    private fun ByteArray.removeAt(index: Int, count: Int): ByteArray =
        copyOfRange(0, index) + copyOfRange(minOf(size, index + count), size)
}
