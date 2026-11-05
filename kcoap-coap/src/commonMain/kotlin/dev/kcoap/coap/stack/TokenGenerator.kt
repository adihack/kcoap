package dev.kcoap.coap.stack

import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Chooses tokens for new requests (RFC 7252 §5.3.1). The endpoint asks again when a token is already in
 * use towards the same peer, so a generator only needs to make collisions unlikely.
 *
 * **Thread safety.** An endpoint calls its generator under its own lock, so a generator need not be thread
 * safe, but one that keeps state, such as [sessionSequence], must not be shared between endpoints. Leave
 * [CoapEndpointConfig.tokenGenerator] null and every endpoint creates its own.
 */
public fun interface TokenGenerator {
    /** A token of 0 to 8 bytes for a new request to [peer]. */
    public fun next(peer: Endpoint): ByteArray

    /** [peer] will never be used again (its DTLS session ended); per-peer state can go. */
    public fun forget(peer: Endpoint) {}

    public companion object {
        /**
         * Random tokens of [length] bytes. RFC 7252 §5.3.1: a client on the Internet SHOULD use at least
         * 32 bits of randomness against response spoofing; [random] should be a secure generator.
         */
        public fun random(length: Int = 8, random: Random = SecureRandom): TokenGenerator {
            require(length in 0..8) { "Tokens are 0 to 8 bytes: $length" }
            return TokenGenerator { random.nextBytes(length) }
        }

        /**
         * [random] tokens towards plain peers and, towards [Endpoint.Secure] peers, a sequence number
         * starting at zero for each DTLS session (RFC 9175 §4.2, which makes this a SHOULD because a
         * security protocol without request-response binding gives tokens cryptographic importance).
         */
        public fun sessionSequence(length: Int = 8, random: Random = SecureRandom): TokenGenerator {
            require(length in 0..8) { "Tokens are 0 to 8 bytes: $length" }
            return DefaultTokenGenerator(length, random)
        }
    }
}

private class DefaultTokenGenerator(private val length: Int, private val random: Random) : TokenGenerator {
    private val sequences = HashMap<Endpoint, Long>()

    override fun next(peer: Endpoint): ByteArray {
        if (peer !is Endpoint.Secure) return random.nextBytes(length)
        val sequence = sequences[peer] ?: 0L
        sequences[peer] = sequence + 1
        return minimalUnsignedBytes(sequence)
    }

    override fun forget(peer: Endpoint) {
        sequences.remove(peer)
    }
}

/**
 * The platform's cryptographically secure random generator, reached through [Uuid.random], which the
 * standard library backs by `SecureRandom` on the JVM and the system CSPRNG on Apple platforms. Only the
 * 122 random bits of each UUID are used.
 */
@OptIn(ExperimentalUuidApi::class)
internal object SecureRandom : Random() {
    override fun nextBits(bitCount: Int): Int {
        if (bitCount == 0) return 0
        // Bytes 0..5 of a version 4 UUID are fully random.
        val bytes = Uuid.random().toByteArray()
        val value = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return value ushr (32 - bitCount)
    }
}
