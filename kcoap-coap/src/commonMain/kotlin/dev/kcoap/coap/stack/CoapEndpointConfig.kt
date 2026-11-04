package dev.kcoap.coap.stack

import dev.kcoap.log.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a [CoapEndpoint] runs its messaging layer.
 *
 * @property parameters RFC 7252 §4.8 transmission parameters.
 * @property separateResponseDelay how long a Confirmable request may wait for its response before the
 *   endpoint sends an Empty ACK and the response follows separately (RFC 7252 §5.2.2). It should stay
 *   below ACK_TIMEOUT so the client does not retransmit; null disables it, so a slow handler makes the
 *   client retransmit.
 * @property tokenGenerator chooses request tokens; one generator per endpoint.
 * @property timeSource the clock every timer uses (architecture rule 5: one shared clock). Under
 *   `runTest`, pass `testScheduler.timeSource` so retransmissions and lifetimes run on virtual time.
 * @property random randomness for retransmission jitter and the first Message ID. Tokens come from
 *   [tokenGenerator], which uses a secure generator by default.
 * @property interceptors the message interceptor chain, in order.
 * @property eventListener receives [EndpointEvent]s.
 * @property loggerFactory where the endpoint's loggers come from.
 */
public class CoapEndpointConfig(
    public val parameters: TransmissionParameters = TransmissionParameters.Default,
    public val separateResponseDelay: Duration? = 1.seconds,
    public val tokenGenerator: TokenGenerator = TokenGenerator.sessionSequence(),
    public val timeSource: TimeSource = TimeSource.Monotonic,
    public val random: Random = Random.Default,
    public val interceptors: List<MessageInterceptor> = emptyList(),
    public val eventListener: EndpointEventListener = EndpointEventListener.None,
    public val loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    init {
        require(separateResponseDelay == null || !separateResponseDelay.isNegative()) {
            "Separate response delay must not be negative: $separateResponseDelay"
        }
    }

    override fun toString(): String =
        "CoapEndpointConfig($parameters, separateResponseDelay=$separateResponseDelay, interceptors=${interceptors.size})"
}
