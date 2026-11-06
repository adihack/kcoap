package dev.kcoap.coap.stack

import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * The platform's cryptographically secure random generator, for the CoAP stack's own secrets: the default
 * tokens ([TokenGenerator]; RFC 7252 §5.3.1 asks for at least 32 random bits against spoofed responses) and the
 * Echo values of address verification (RFC 9175 §2.4). It is reached through [Uuid.random], which the standard
 * library backs by the platform's CSPRNG on every target: `java.security.SecureRandom` on the JVM and Android,
 * `crypto.getRandomValues` in browsers and Node.js (js and wasmJs), and the system CSPRNG on Apple platforms and
 * Linux. So plain CoAP needs no crypto module; the security layers draw from `CryptoBackend.randomBytes`
 * instead, which known-answer tests and trace replay can inject.
 *
 * A version 4 UUID has 122 random bits: [nextBytes] uses 120 of them, 15 bytes per UUID, and [nextBits] the
 * 32 of its first four bytes.
 */
internal object PlatformCsprng : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        // Bytes 0..5 of a version 4 UUID are fully random.
        val bytes = Uuid.random().toByteArray()
        val value = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return value ushr (32 - bitCount)
    }

    override fun nextBytes(array: ByteArray, fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex in 0..array.size && toIndex in fromIndex..array.size) {
            "fromIndex ($fromIndex) and toIndex ($toIndex) must be within 0..${array.size}, in order"
        }
        var position = fromIndex
        while (position < toIndex) {
            val random = randomBytesOf(Uuid.random())
            val count = minOf(random.size, toIndex - position)
            random.copyInto(array, position, 0, count)
            position += count
        }
        return array
    }

    /**
     * The 15 bytes of [uuid] made of its random bits (RFC 9562 §5.4): bytes 0–5, 7 and 9–15 whole, and one byte
     * of the low four bits of byte 6 (the high four are the version) and of byte 8 (the high two are the variant).
     */
    private fun randomBytesOf(uuid: Uuid): ByteArray {
        val bytes = uuid.toByteArray()
        val out = ByteArray(RANDOM_BYTES)
        bytes.copyInto(out, 0, 0, 6)
        out[6] = bytes[7]
        bytes.copyInto(out, 7, 9, 16)
        out[14] = (((bytes[6].toInt() and 0x0F) shl 4) or (bytes[8].toInt() and 0x0F)).toByte()
        return out
    }

    private const val RANDOM_BYTES = 15
}
