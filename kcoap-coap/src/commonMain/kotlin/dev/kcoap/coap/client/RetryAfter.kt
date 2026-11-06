package dev.kcoap.coap.client

import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.common.KcoapException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Sends a request again when the server asks to be asked later, as RFC 8516 §4 has a client do: while the
 * answer is 4.29 Too Many Requests, or 5.03 Service Unavailable with the same meaning for an overload of all
 * clients (RFC 7252 §5.9.3.4), it waits as long as the response's Max-Age says ([CoapResponse.retryAfter], 60
 * seconds without one) and sends the request again, [maxAttempts] times at most in all.
 *
 * - It never sends earlier than the server asked (RFC 8516 §4: SHOULD NOT): a wait longer than [maxWait] is not
 *   waited out, and the refusal is returned at once for the caller to decide.
 * - The wait is a coroutine `delay`, so cancelling the caller ends it, and nothing more is sent.
 * - A 4.29 that no security protocol authenticated may be forged to make a client wait (RFC 8516 §5); [maxWait]
 *   bounds what that costs. With message security the client acts only on verified responses (ADR 0008).
 * - Each attempt is a new request, with a new token and Message ID, since the server answered the last one.
 *
 * ```
 * val response = RetryAfter(maxWait = 30.seconds).request(client, CoapRequest(CoapCode.GET, CoapUri.parse(uri)))
 * ```
 *
 * @property maxWait the longest wait it takes on; at least zero.
 * @property maxAttempts how many times the request is sent at most, the first included; at least 1.
 */
public class RetryAfter(
    public val maxWait: Duration = DEFAULT_MAX_WAIT,
    public val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(!maxWait.isNegative()) { "The longest wait must not be negative: $maxWait" }
        require(maxAttempts >= 1) { "At least one attempt is needed: $maxAttempts" }
    }

    /**
     * Sends [request] with [client], again after each 4.29 or 5.03 as the class describes, and returns the last
     * response: one of another code, a refusal whose wait is longer than [maxWait], or the answer to the last
     * attempt.
     *
     * @throws dev.kcoap.coap.stack.CoapException as [CoapClient.request] throws it, for any attempt.
     */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun request(client: CoapClient, request: CoapRequest): CoapResponse {
        var attempt = 1
        while (true) {
            val response = client.request(request)
            val wait = response.retryAfter ?: return response
            if (attempt >= maxAttempts || wait > maxWait) return response
            delay(wait)
            attempt++
        }
    }

    override fun toString(): String = "RetryAfter(maxWait=$maxWait, maxAttempts=$maxAttempts)"

    public companion object {
        /** The longest wait taken on by default: Max-Age's default of 60 seconds (RFC 7252 §5.10.5). */
        public val DEFAULT_MAX_WAIT: Duration = 60.seconds

        /** Three attempts in all by default. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3
    }
}
