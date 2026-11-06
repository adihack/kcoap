package dev.kcoap.coap.stack

import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.transport.Endpoint
import kotlin.random.Random

/**
 * Chooses tokens for new requests (RFC 7252 §5.3.1). The endpoint asks again when a token is already in
 * use towards the same peer, so a generator only needs to make collisions unlikely.
 *
 * **When the token's randomness matters.** On a path no security protocol protects (`coap`, `coap+tcp`,
 * `coap+ws`), the token is all an off-path attacker has to guess to have a forged response accepted, so RFC 7252
 * §5.3.1 asks for at least 32 random bits there. Inside a DTLS session or a TLS connection nobody but the peer
 * can send, and the token's job is to bind each response to the right request, which (D)TLS itself does not do
 * (RFC 9175 §4.1): a token reused on the session could match a late response, or a notification the client no
 * longer wants, to a new request. There a sequence number does it best, which [sessionSequence], the default,
 * uses. OSCORE binds a response to its request by itself (RFC 8613 §7.1), whatever the token. A deployment that
 * must draw all its randomness from one approved provider passes its own [Random] or generator.
 *
 * **Thread safety.** An endpoint calls its generator under its own lock, so a generator need not be thread
 * safe, but one that keeps state, such as [sessionSequence], must not be shared between endpoints. Leave
 * [CoapEndpointConfig.tokenGenerator] null and every endpoint creates its own.
 */
public fun interface TokenGenerator {
    /** A token of 0 to 8 bytes for a new request to [peer]. */
    public fun next(peer: Endpoint): ByteArray

    /**
     * [peer] will never be used again: its DTLS session ended or its connection closed, and a new session or
     * connection to the same peer has new keys. Per-peer state can go.
     */
    public fun forget(peer: Endpoint) {}

    public companion object {
        /**
         * Random tokens of [length] bytes. RFC 7252 §5.3.1: a client on the Internet SHOULD use at least
         * 32 bits of randomness against response spoofing; [random] should be a secure generator, and is the
         * platform's CSPRNG by default.
         */
        public fun random(length: Int = 8, random: Random = PlatformCsprng): TokenGenerator {
            require(length in 0..8) { "Tokens are 0 to 8 bytes: $length" }
            return TokenGenerator { random.nextBytes(length) }
        }

        /**
         * Sequence numbers on secure sessions and random tokens elsewhere, the default of every endpoint.
         *
         * Towards a peer on a DTLS session ([Endpoint.Secure]) or a TLS connection ([Endpoint.Tcp] or
         * [Endpoint.WebSocket] with `usesTls`, that is `coaps+tcp` and `coaps+ws`), the token is a sequence
         * number in its shortest form, starting at zero (the empty token) for each session or connection, as
         * RFC 9175 §4.2 says a client SHOULD do with a security protocol that does not bind responses to
         * requests, and towards every other peer [length] bytes of [random], the platform's CSPRNG by default.
         *
         * Why that is safe and better on a secure channel (RFC 9175 §4):
         * - The count never repeats while the session's keys last, so no response, however late, and no
         *   notification of an earlier observation can be taken for the answer to a newer request; random
         *   tokens only make that unlikely. The endpoint calls [forget] when the session ends or the connection
         *   closes, and opens a new connection to the same peer only after that, so a new count starts with
         *   new keys and never runs on an old one.
         * - That the token is predictable gives nothing away: an attacker who is not the peer cannot inject a
         *   message into a DTLS session or a TLS connection, and the peer can answer any token it was sent.
         * - On a connection tokens are scoped to that connection (RFC 8323 §3.3, §4.3), whose reliable,
         *   ordered delivery also means no stale copy of an old message arrives under a new token, and the
         *   short tokens save up to 8 bytes in every message.
         * - Plain UDP, TCP and WebSockets keep random tokens, since there the token is what stops off-path
         *   response spoofing (RFC 7252 §5.3.1); so does a server behind a reverse proxy that terminates TLS,
         *   which sees plain `coap+tcp` or `coap+ws`.
         */
        public fun sessionSequence(length: Int = 8, random: Random = PlatformCsprng): TokenGenerator {
            require(length in 0..8) { "Tokens are 0 to 8 bytes: $length" }
            return DefaultTokenGenerator(length, random)
        }
    }
}

private class DefaultTokenGenerator(private val length: Int, private val random: Random) : TokenGenerator {
    private val sequences = HashMap<Endpoint, Long>()

    override fun next(peer: Endpoint): ByteArray {
        if (!peer.isOnSecureChannel) return random.nextBytes(length)
        val sequence = sequences[peer] ?: 0L
        sequences[peer] = sequence + 1
        return minimalUnsignedBytes(sequence)
    }

    override fun forget(peer: Endpoint) {
        sequences.remove(peer)
    }

    /** Whether [this] peer is reached through DTLS or TLS, whose sessions bind messages to their keys. */
    private val Endpoint.isOnSecureChannel: Boolean
        get() = when (this) {
            is Endpoint.Secure -> true
            is Endpoint.Tcp -> usesTls
            is Endpoint.WebSocket -> usesTls
            is Endpoint.Udp, is Endpoint.Opaque -> false
        }
}
