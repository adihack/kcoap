package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.coap.stack.ExpiringCache
import dev.kcoap.coap.stack.SecureRandom
import dev.kcoap.common.constantTimeEquals
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The client addresses a server has verified with Echo, against amplification (RFC 9175 §2.4 item 3,
 * §2.6): a response larger than [CoapServerConfig.amplificationFactor] times its request goes to an
 * unauthenticated client only once the client has shown that it receives at its address, by returning
 * the Echo value of a 4.01 Unauthorized sent there.
 *
 * Echo values follow RFC 9175 Appendix A's list of cached random values: 8 bytes, the 64 unpredictable
 * bits §5 asks for, from the platform's CSPRNG (never the endpoint's injectable `Random`, which tests
 * seed), one per challenged address and valid for [challengeLifetime]. A value verifies only the address
 * it was sent to, so one an attacker fetched at its own address verifies nothing else. While an address
 * has an outstanding value, every challenge to it repeats that value, so concurrent requests of one
 * client all succeed with the first answer.
 *
 * Everything is bounded: [CoapServerConfig.maxVerifiedAddresses] verified addresses, which expire
 * [CoapServerConfig.verifiedAddressLifetime] after their verification, and as many outstanding challenges;
 * [CoapServerConfig.maxConcurrentTransfers] responses held back for the repeat of a request that must not
 * run twice ([HeldBackResponses], one per request, for as long as a challenge). Every eviction for room is
 * reported as [EndpointEvent.LimitReached].
 *
 * Safe for concurrent use: one [Mutex], never held across a suspension; [report] runs after it.
 */
internal class AddressVerification(
    private val config: CoapServerConfig,
    private val challengeLifetime: Duration,
    private val report: (EndpointEvent) -> Unit,
    private val randomBytes: (Int) -> ByteArray = { SecureRandom.nextBytes(it) },
) {
    private val lock = Mutex()
    private val verified = ExpiringCache<Endpoint, Unit>(config.maxVerifiedAddresses, config.verifiedAddressLifetime)
    private val challenges = ExpiringCache<Endpoint, ByteArray>(config.maxVerifiedAddresses, challengeLifetime)
    private val withheld = HeldBackResponses(config.maxConcurrentTransfers, challengeLifetime)

    /** [response], held back from [request], which must not run twice, for that request's repeat with Echo. */
    class Withheld(val request: CoapMessage, val response: CoapResponse)

    /** Whether [CoapServerConfig.amplification] covers a response, or with [isBurst] a Q-Block2 burst. */
    fun appliesTo(isBurst: Boolean): Boolean = when (config.amplification) {
        AmplificationPolicy.Q_BLOCK_BURSTS -> isBurst
        AmplificationPolicy.ALL_RESPONSES -> true
        AmplificationPolicy.OFF -> false
    }

    /**
     * Whether a response of [responseSize] bytes to a request of [requestSize] bytes needs a verified address
     * under [CoapServerConfig.amplification]: [isBurst] says whether it is a Q-Block2 burst, [responseSize]
     * then the size of all the blocks sent in answer to that request so far, this burst included (§2.4
     * item 3: "the total size of the response(s)"). Up to [CoapServerConfig.amplificationFactor] times the
     * request is always allowed.
     */
    fun exceedsLimit(requestSize: Int, responseSize: Long, isBurst: Boolean): Boolean =
        appliesTo(isBurst) && responseSize > config.amplificationFactor.toLong() * requestSize

    /**
     * Whether the address of [peer] is verified: still within its lifetime, or by now, because [echo], the
     * Echo value its request carries, is the one outstanding for it (RFC 9175 §2.3: the server verifies that
     * it generated the value, for this address). A value that does not match changes nothing.
     */
    suspend fun verify(peer: Endpoint, echo: ByteArray?, now: Duration): Boolean {
        var evicted: Endpoint? = null
        val result = lock.withLock {
            if (verified.peek(peer, now) != null) return@withLock true
            val expected = challenges.peek(peer, now)
            if (expected == null || echo == null || !constantTimeEquals(echo, expected)) return@withLock false
            challenges.remove(peer)
            evicted = verified.put(peer, Unit, now)
            true
        }
        evicted?.let { report(EndpointEvent.LimitReached(it, Limit.VERIFIED_ADDRESSES)) }
        return result
    }

    /**
     * The Echo value for a 4.01 to [peer], whose address is not verified: the one outstanding for it, or a new
     * one. [response], when set, is kept for the repeat of a request that must not run twice. Reports the
     * held-back response, and a challenge or held-back response evicted for room.
     */
    suspend fun challenge(peer: Endpoint, now: Duration, response: Withheld? = null): ByteArray {
        var evictedChallenge: Endpoint? = null
        var evictedResponse: Endpoint? = null
        val echo = lock.withLock {
            response?.let { evictedResponse = withheld.put(HeldBackResponses.Key(peer, it.request), it.response, now) }
            challenges.peek(peer, now) ?: randomBytes(ECHO_LENGTH).also { evictedChallenge = challenges.put(peer, it, now) }
        }
        report(EndpointEvent.LimitReached(peer, Limit.AMPLIFICATION_FACTOR))
        evictedChallenge?.let { report(EndpointEvent.LimitReached(it, Limit.ADDRESS_CHALLENGES)) }
        evictedResponse?.let { report(EndpointEvent.LimitReached(it, Limit.HELD_BACK_RESPONSES)) }
        return echo.copyOf()
    }

    /** The response held back for [request] from [peer], if this is its repeat; it is handed out once. */
    suspend fun takeWithheld(peer: Endpoint, request: CoapMessage, now: Duration): CoapResponse? =
        lock.withLock { withheld.take(HeldBackResponses.Key(peer, request), now) }

    /** How many addresses are verified and challenged, for tests. */
    suspend fun sizes(): Pair<Int, Int> = lock.withLock { verified.size to challenges.size }

    companion object {
        /** RFC 9175 §5: 64 bits that no other party can predict. */
        const val ECHO_LENGTH = 8

        /**
         * Whether running a request of [code] twice may differ from running it once: POST (RFC 7252 §5.1) and
         * PATCH (RFC 8132 §3) are neither safe nor idempotent, so their response is kept for the repeat.
         */
        fun isRepeatSensitive(code: CoapCode): Boolean = code == CoapCode.POST || code == CoapCode.PATCH
    }
}
