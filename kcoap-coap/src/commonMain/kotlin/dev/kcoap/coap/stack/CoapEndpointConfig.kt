package dev.kcoap.coap.stack

import dev.kcoap.log.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a [CoapEndpoint] runs its messaging layers.
 *
 * **Resource bounds.** Everything a peer can make the endpoint keep or do is bounded, with defaults sized
 * for a server. Over UDP the source address of a datagram proves nothing (RFC 7252 §11.4), so a flood of
 * spoofed messages must cost bounded memory and CPU, and RFC 7252 §11.3 warns that answering it can turn
 * the endpoint into an amplifier against the address it names. At a bound the endpoint therefore drops or
 * refuses only the extra work, never answers with more than it received, keeps serving what it already
 * has, and reports [EndpointEvent.LimitReached] so the condition can be counted. The server's observer
 * bounds are in `CoapServerConfig`, the bound on connections that never finish their CSM exchange in
 * [ReliableTransportConfig].
 *
 * @property parameters RFC 7252 §4.8 transmission parameters, for datagram transports.
 * @property separateResponseDelay how long a Confirmable request may wait for its response before the
 *   endpoint sends an Empty ACK and the response follows separately (RFC 7252 §5.2.2). It should stay
 *   below ACK_TIMEOUT so the client does not retransmit; null disables it, so a slow handler makes the
 *   client retransmit.
 * @property tokenGenerator chooses request tokens. Null, the default, gives every endpoint a
 *   [TokenGenerator.sessionSequence] of its own, so one configuration can serve several endpoints, such as
 *   a server's and a client's. A generator given here that keeps state must not be shared between
 *   endpoints.
 * @property timeSource the clock every timer reads (architecture rule 5: one shared clock). Timers wait
 *   with the delays of the endpoint scope's dispatcher, so this time source must advance with that
 *   dispatcher's clock: the default `TimeSource.Monotonic` with a real dispatcher, and under `runTest`
 *   `testScheduler.timeSource`, so retransmissions and lifetimes run on virtual time. The endpoint logs a
 *   warning when it sees the two disagree.
 * @property random randomness for retransmission jitter and the first Message ID. Tokens come from
 *   [tokenGenerator], which uses a secure generator by default.
 * @property interceptors the message interceptor chain, in order.
 * @property eventListener receives [EndpointEvent]s.
 * @property loggerFactory where the endpoint's loggers come from.
 * @property reliable CSM settings and connection management for CoAP over reliable transports (RFC 8323).
 * @property maxDeduplicationEntries the most received messages remembered for deduplication (RFC 7252
 *   §4.5), each for EXCHANGE_LIFETIME or NON_LIFETIME together with the reply it got. Any new message from
 *   any source address adds one, so this bounds what a flood of spoofed datagrams makes the endpoint
 *   remember. When it is full, new Confirmable and Non-confirmable messages are dropped silently, without
 *   state and without an answer that would go to a possibly spoofed source (§11.3); duplicates of
 *   remembered messages are still answered. The default takes about 200 new messages a second.
 * @property maxConcurrentRequests the most request handlers running at once, over every transport.
 *   Beyond it a Confirmable request is answered with a piggybacked 5.03 Service Unavailable with Max-Age
 *   (RFC 7252 §5.9.3.4), no larger than the request, so it amplifies nothing (§11.3), and duplicates of it
 *   get the same answer from the deduplication cache; a Non-confirmable request is dropped; a request on a
 *   connection gets the 5.03. Requests never queue, and the receive loop never waits for a free handler.
 * @property maxExchangesPerPeer the most exchanges in progress with one peer: requests of this endpoint
 *   awaiting their response and its observations, and over datagrams also every Confirmable message
 *   awaiting its ACK or queued behind NSTART (RFC 7252 §4.7), separate responses and notifications
 *   included. A request beyond it fails at once with [dev.kcoap.coap.CoapException.Reason.IDENTIFIERS_EXHAUSTED];
 *   a separate response or notification beyond it is not sent. It bounds the queue that a peer which never
 *   acknowledges, such as a spoofed one, can build up (§11.3).
 */
public class CoapEndpointConfig(
    public val parameters: TransmissionParameters = TransmissionParameters.Default,
    public val separateResponseDelay: Duration? = 1.seconds,
    public val tokenGenerator: TokenGenerator? = null,
    public val timeSource: TimeSource = TimeSource.Monotonic,
    public val random: Random = Random.Default,
    public val interceptors: List<MessageInterceptor> = emptyList(),
    public val eventListener: EndpointEventListener = EndpointEventListener.None,
    public val loggerFactory: LoggerFactory = LoggerFactory.Global,
    public val reliable: ReliableTransportConfig = ReliableTransportConfig(),
    public val maxDeduplicationEntries: Int = DEFAULT_MAX_DEDUPLICATION_ENTRIES,
    public val maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    public val maxExchangesPerPeer: Int = DEFAULT_MAX_EXCHANGES_PER_PEER,
) {
    init {
        require(separateResponseDelay == null || !separateResponseDelay.isNegative()) {
            "Separate response delay must not be negative: $separateResponseDelay"
        }
        require(maxDeduplicationEntries > 0) { "At least one deduplication entry must be allowed: $maxDeduplicationEntries" }
        require(maxConcurrentRequests > 0) { "At least one request handler must be allowed: $maxConcurrentRequests" }
        require(maxExchangesPerPeer > 0) { "At least one exchange per peer must be allowed: $maxExchangesPerPeer" }
    }

    public companion object {
        /** About 200 new messages a second over EXCHANGE_LIFETIME; see [maxDeduplicationEntries]. */
        public const val DEFAULT_MAX_DEDUPLICATION_ENTRIES: Int = 50_000

        /** See [maxConcurrentRequests]. */
        public const val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 256

        /** See [maxExchangesPerPeer]. */
        public const val DEFAULT_MAX_EXCHANGES_PER_PEER: Int = 256
    }

    override fun toString(): String =
        "CoapEndpointConfig($parameters, separateResponseDelay=$separateResponseDelay, interceptors=${interceptors.size}, $reliable)"
}
