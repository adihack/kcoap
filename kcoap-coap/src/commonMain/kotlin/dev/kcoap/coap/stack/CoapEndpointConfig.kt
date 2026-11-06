package dev.kcoap.coap.stack

import dev.kcoap.log.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * How a [CoapEndpoint] runs its messaging layers.
 *
 * Build one with the DSL, `CoapEndpointConfig { separateResponseDelay = 500.milliseconds }`, or with a
 * [Builder] (`CoapEndpointConfig.Builder()`, set its properties, `build()`), which reads the same from Swift;
 * [Default] has every default, and [toBuilder] starts from an existing configuration. The configuration
 * itself is immutable, and every value is checked when it is built. Options added later go into the
 * builder, so code that builds a configuration keeps compiling, and compiled code keeps linking.
 *
 * **Resource bounds.** Everything a peer can make the endpoint keep or do is bounded, with defaults sized
 * for a server. Over UDP the source address of a datagram proves nothing (RFC 7252 §11.4), so a flood of
 * spoofed messages must cost bounded memory and CPU, and RFC 7252 §11.3 warns that answering it can turn
 * the endpoint into an amplifier against the address it names. At a bound the endpoint therefore drops or
 * refuses only the extra work, answers with at most a few bytes more than it received (a refusal's
 * Max-Age), keeps serving what it already has, and reports [EndpointEvent.LimitReached] so the condition
 * can be counted. The server's observer bounds are in `CoapServerConfig`, the bounds on connections in
 * [ReliableTransportConfig].
 */
public class CoapEndpointConfig private constructor(builder: Builder) {

    /** RFC 7252 §4.8 transmission parameters, for datagram transports. */
    public val parameters: TransmissionParameters = builder.parameters

    /**
     * How long a Confirmable request may wait for its response before the endpoint sends an Empty ACK and the
     * response follows separately (RFC 7252 §5.2.2). It should stay below ACK_TIMEOUT so the client does not
     * retransmit; null disables it, so a slow handler makes the client retransmit.
     */
    public val separateResponseDelay: Duration? = builder.separateResponseDelay

    /**
     * Chooses request tokens. Null, the default, gives every endpoint a [TokenGenerator.sessionSequence] of
     * its own, so one configuration can serve several endpoints, such as a server's and a client's. A
     * generator given here that keeps state must not be shared between endpoints.
     */
    public val tokenGenerator: TokenGenerator? = builder.tokenGenerator

    /**
     * The clock every timer reads (architecture rule 5: one shared clock). Timers wait with the delays of the
     * endpoint scope's dispatcher, so this time source must advance with that dispatcher's clock: the default
     * `TimeSource.Monotonic` with a real dispatcher, and under `runTest` `testScheduler.timeSource`, so
     * retransmissions and lifetimes run on virtual time. The endpoint logs a warning when it sees the two
     * disagree.
     */
    public val timeSource: TimeSource = builder.timeSource

    /**
     * Randomness for retransmission jitter and the first Message ID. Tokens come from [tokenGenerator], which
     * uses a secure generator by default.
     */
    public val random: Random = builder.random

    /** The message interceptor chain, in order; a copy of the list given. */
    public val interceptors: List<MessageInterceptor> = builder.interceptors.toList()

    /** Receives [EndpointEvent]s. */
    public val eventListener: EndpointEventListener = builder.eventListener

    /** Where the endpoint's loggers come from. */
    public val loggerFactory: LoggerFactory = builder.loggerFactory

    /** CSM settings and connection management for CoAP over reliable transports (RFC 8323). */
    public val reliable: ReliableTransportConfig = builder.reliable

    /**
     * The most received messages remembered for deduplication (RFC 7252 §4.5), each for EXCHANGE_LIFETIME or
     * NON_LIFETIME together with the reply it got. Any new message from any source address adds one, so this
     * bounds what a flood of spoofed datagrams makes the endpoint remember. When it is full, new Confirmable
     * and Non-confirmable messages are dropped silently, without state and without an answer that would go to
     * a possibly spoofed source (§11.3); duplicates of remembered messages are still answered. The default
     * takes about 200 new messages a second.
     */
    public val maxDeduplicationEntries: Int = builder.maxDeduplicationEntries

    /**
     * The most request handlers running at once, over every transport. Beyond it a Confirmable request is
     * answered with a piggybacked 5.03 Service Unavailable with a Max-Age of 2 seconds, when to try again
     * (RFC 7252 §5.9.3.4). That answer is at most 3 bytes larger than the request, an amplification factor
     * below 2 (§11.3), and duplicates of the request get the same answer from the deduplication cache; a
     * Non-confirmable request is dropped; a request on a connection gets the 5.03. Requests never queue, and
     * the receive loop never waits for a free handler.
     */
    public val maxConcurrentRequests: Int = builder.maxConcurrentRequests

    /**
     * The most exchanges in progress with one peer: requests of this endpoint awaiting their response and its
     * observations, and over datagrams also every Confirmable message awaiting its ACK or queued behind NSTART
     * (RFC 7252 §4.7), separate responses and notifications included, and every Non-confirmable Q-Block body
     * holding or awaiting its NSTART slot. A request beyond it fails at once with
     * [CoapException.Reason.IDENTIFIERS_EXHAUSTED]; a separate response or notification beyond it is not
     * sent. It bounds the queue that a peer which never acknowledges, such as a spoofed one, can build up
     * (§11.3).
     */
    public val maxExchangesPerPeer: Int = builder.maxExchangesPerPeer

    init {
        require(separateResponseDelay == null || !separateResponseDelay.isNegative()) {
            "Separate response delay must not be negative: $separateResponseDelay"
        }
        require(maxDeduplicationEntries > 0) { "At least one deduplication entry must be allowed: $maxDeduplicationEntries" }
        require(maxConcurrentRequests > 0) { "At least one request handler must be allowed: $maxConcurrentRequests" }
        require(maxExchangesPerPeer > 0) { "At least one exchange per peer must be allowed: $maxExchangesPerPeer" }
    }

    /**
     * The configuration with these values. Deprecated: build it with `CoapEndpointConfig { ... }` or a
     * [Builder], which take the same named values and, unlike this constructor, can gain options without
     * breaking compiled code.
     *
     * @throws IllegalArgumentException when a value is out of range, as [Builder.build] does.
     */
    @Deprecated(
        "Build the configuration with CoapEndpointConfig { ... } or CoapEndpointConfig.Builder; this constructor " +
            "is removed before the first release.",
    )
    public constructor(
        parameters: TransmissionParameters = TransmissionParameters.Default,
        separateResponseDelay: Duration? = 1.seconds,
        tokenGenerator: TokenGenerator? = null,
        timeSource: TimeSource = TimeSource.Monotonic,
        random: Random = Random.Default,
        interceptors: List<MessageInterceptor> = emptyList(),
        eventListener: EndpointEventListener = EndpointEventListener.None,
        loggerFactory: LoggerFactory = LoggerFactory.Global,
        reliable: ReliableTransportConfig = ReliableTransportConfig(),
        maxDeduplicationEntries: Int = DEFAULT_MAX_DEDUPLICATION_ENTRIES,
        maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
        maxExchangesPerPeer: Int = DEFAULT_MAX_EXCHANGES_PER_PEER,
    ) : this(
        Builder().also {
            it.parameters = parameters
            it.separateResponseDelay = separateResponseDelay
            it.tokenGenerator = tokenGenerator
            it.timeSource = timeSource
            it.random = random
            it.interceptors = interceptors
            it.eventListener = eventListener
            it.loggerFactory = loggerFactory
            it.reliable = reliable
            it.maxDeduplicationEntries = maxDeduplicationEntries
            it.maxConcurrentRequests = maxConcurrentRequests
            it.maxExchangesPerPeer = maxExchangesPerPeer
        },
    )

    /** A [Builder] holding this configuration's values, to build a changed copy. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Collects the values of a [CoapEndpointConfig]; every property starts at its default, documented on the
     * property of the same name. Not thread safe: build on one thread, then share the configuration.
     */
    public class Builder public constructor() {
        /** See [CoapEndpointConfig.parameters]. Default: [TransmissionParameters.Default]. */
        public var parameters: TransmissionParameters = TransmissionParameters.Default

        /** See [CoapEndpointConfig.separateResponseDelay]. Default: 1 second. */
        public var separateResponseDelay: Duration? = 1.seconds

        /** See [CoapEndpointConfig.tokenGenerator]. Default: null, a generator per endpoint. */
        public var tokenGenerator: TokenGenerator? = null

        /** See [CoapEndpointConfig.timeSource]. Default: `TimeSource.Monotonic`. */
        public var timeSource: TimeSource = TimeSource.Monotonic

        /** See [CoapEndpointConfig.random]. Default: `Random.Default`. */
        public var random: Random = Random.Default

        /** See [CoapEndpointConfig.interceptors]; the configuration copies the list. Default: none. */
        public var interceptors: List<MessageInterceptor> = emptyList()

        /** See [CoapEndpointConfig.eventListener]. Default: [EndpointEventListener.None]. */
        public var eventListener: EndpointEventListener = EndpointEventListener.None

        /** See [CoapEndpointConfig.loggerFactory]. Default: [LoggerFactory.Global]. */
        public var loggerFactory: LoggerFactory = LoggerFactory.Global

        /** See [CoapEndpointConfig.reliable]. Default: `ReliableTransportConfig()`. */
        public var reliable: ReliableTransportConfig = ReliableTransportConfig()

        /** See [CoapEndpointConfig.maxDeduplicationEntries]. Default: [DEFAULT_MAX_DEDUPLICATION_ENTRIES]. */
        public var maxDeduplicationEntries: Int = DEFAULT_MAX_DEDUPLICATION_ENTRIES

        /** See [CoapEndpointConfig.maxConcurrentRequests]. Default: [DEFAULT_MAX_CONCURRENT_REQUESTS]. */
        public var maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS

        /** See [CoapEndpointConfig.maxExchangesPerPeer]. Default: [DEFAULT_MAX_EXCHANGES_PER_PEER]. */
        public var maxExchangesPerPeer: Int = DEFAULT_MAX_EXCHANGES_PER_PEER

        internal constructor(config: CoapEndpointConfig) : this() {
            parameters = config.parameters
            separateResponseDelay = config.separateResponseDelay
            tokenGenerator = config.tokenGenerator
            timeSource = config.timeSource
            random = config.random
            interceptors = config.interceptors
            eventListener = config.eventListener
            loggerFactory = config.loggerFactory
            reliable = config.reliable
            maxDeduplicationEntries = config.maxDeduplicationEntries
            maxConcurrentRequests = config.maxConcurrentRequests
            maxExchangesPerPeer = config.maxExchangesPerPeer
        }

        /**
         * The configuration with the values set so far. The builder stays usable: changing it afterwards
         * does not change configurations already built.
         *
         * @throws IllegalArgumentException when a value is out of range: a negative [separateResponseDelay],
         *   or a bound below 1.
         */
        public fun build(): CoapEndpointConfig = CoapEndpointConfig(this)
    }

    public companion object {
        /** About 200 new messages a second over EXCHANGE_LIFETIME; see [maxDeduplicationEntries]. */
        public const val DEFAULT_MAX_DEDUPLICATION_ENTRIES: Int = 50_000

        /** See [maxConcurrentRequests]. */
        public const val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 256

        /** See [maxExchangesPerPeer]. */
        public const val DEFAULT_MAX_EXCHANGES_PER_PEER: Int = 256

        /**
         * Every value at its default. It can serve several endpoints, since each one then makes its own token
         * generator.
         */
        public val Default: CoapEndpointConfig = Builder().build()
    }

    override fun toString(): String =
        "CoapEndpointConfig($parameters, separateResponseDelay=$separateResponseDelay, interceptors=${interceptors.size}, $reliable)"
}

/**
 * The [CoapEndpointConfig] that [block] sets up on a [CoapEndpointConfig.Builder]; what is not set keeps its
 * default:
 *
 * ```
 * val config = CoapEndpointConfig {
 *     parameters = TransmissionParameters(ackTimeout = 3.seconds)
 *     eventListener = EndpointEventListener { event -> println(event) }
 * }
 * ```
 *
 * @throws IllegalArgumentException when a value is out of range, as [CoapEndpointConfig.Builder.build] does.
 */
public fun CoapEndpointConfig(block: CoapEndpointConfig.Builder.() -> Unit): CoapEndpointConfig =
    CoapEndpointConfig.Builder().apply(block).build()
