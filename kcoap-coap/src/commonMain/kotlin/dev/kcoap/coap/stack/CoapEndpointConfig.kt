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
 * **Resource bounds.** Everything a peer can make the endpoint keep or do is bounded, in total and per peer,
 * with defaults sized for a server. Over UDP the source address of a datagram proves nothing (RFC 7252
 * §11.4), so a flood of spoofed messages must cost bounded memory and CPU, and RFC 7252 §11.3 warns that
 * answering it can turn the endpoint into an amplifier against the address it names. At a bound the endpoint
 * first forgets what the RFCs let it forget, then drops or refuses only the extra work, answers with at most a
 * few bytes more than it received (a refusal's Max-Age), keeps serving what it already has, and reports what
 * it refused as [EndpointEvent.LimitReached] so the condition can be counted:
 *
 * | Bound | Default | At the bound |
 * |---|---|---|
 * | [maxDeduplicationEntries], [maxDeduplicationEntriesPerPeer] | 50 000, 10 000 | forgets the oldest message that may be processed again (RFC 7252 §4.5), else drops the new one |
 * | [maxReservedMessageIds], [maxReservedMessageIdsPerPeer] | 262 144, 65 536 | refuses the message that needs a new Message ID (§4.4 forbids reuse), a response to a Non-confirmable request already at three quarters |
 * | [maxExchanges], [maxExchangesPerPeer] | 16 384, 256 | forgets the oldest Non-confirmable message kept for a Reset, else gives up the oldest Confirmable one unacknowledged after a retransmission, else refuses |
 * | [maxConcurrentRequests] | 256 | 5.03 with Max-Age to a Confirmable request, drops a Non-confirmable one |
 *
 * The server's observer bounds are in `CoapServerConfig`, the bounds on connections in [ReliableTransportConfig].
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
     * bounds what a flood of spoofed datagrams makes the endpoint remember. When it is full, a new message takes
     * the place of the oldest *repeatable* one, a message §4.5 lets the endpoint process again because that
     * ends the same way: a request with an idempotent method (GET, PUT, DELETE, FETCH, iPATCH) once answered or
     * acknowledged, a message answered with a Reset, or a request answered or dropped before anything acted on
     * it (a message-security failure, an access refusal, an unknown path, an unrecognized critical option, an
     * overload refusal). A request message security verified is never repeatable: OSCORE records its Partial
     * IV in the replay window (RFC 8613 §7.4), so processing it again would end in a replay error. A duplicate
     * of a forgotten message is processed as a new one (§4.5 "relaxed" deduplication). When nothing repeatable
     * is left, a new message is dropped silently, without state and without an answer that would go to a
     * possibly spoofed source (§11.3), and reported as [EndpointEvent.LimitReached] with
     * `DEDUPLICATION_ENTRIES`; its sender retransmits a Confirmable one, and duplicates of remembered messages
     * are still answered. A quarter of the bound is kept for repeatable messages: a new non-idempotent request
     * is dropped once three quarters hold messages that must be remembered, so a flood of POSTs never keeps a
     * GET out, nor the responses to the endpoint's own requests, which do not count against that share (a
     * response that matches no exchange, all a forged source can send, is reset at once).
     */
    public val maxDeduplicationEntries: Int = builder.maxDeduplicationEntries

    /**
     * The most received messages remembered from one peer, out of [maxDeduplicationEntries], so one source,
     * such as one flooding address, cannot take the whole cache. When a peer has this many, its own oldest
     * repeatable message is forgotten for a new one, else the new one is dropped and reported with
     * `DEDUPLICATION_ENTRIES_PER_PEER`. A peer that sends more than about 40 non-idempotent requests a second for
     * EXCHANGE_LIFETIME reaches it with the default.
     */
    public val maxDeduplicationEntriesPerPeer: Int = builder.maxDeduplicationEntriesPerPeer

    /**
     * The most Message IDs reserved at once, towards all peers together. Every message the endpoint sends with
     * a new Message ID (a request, a separate response, a notification, every message of a Q-Block body)
     * reserves it for EXCHANGE_LIFETIME, since RFC 7252 §4.4 forbids reusing it towards that peer before, and a
     * flood of spoofed Non-confirmable requests makes the endpoint send one response, and so reserve one ID,
     * each. Beyond the bound such a message is refused instead: a request fails with
     * [CoapException.Reason.IDENTIFIERS_EXHAUSTED], a separate response is not sent, a notification is sent
     * later with the latest state while the observer stays (RFC 7641 §4.5), and each is reported with
     * `MESSAGE_IDS`. A quarter of the bound, and of [maxReservedMessageIdsPerPeer], is kept from Non-confirmable
     * responses to Non-confirmable requests, which a client may lose anyway (§5.2.3) and a flood from forged
     * addresses makes the endpoint send: they are refused at three quarters, so the rest is left for the
     * endpoint's own requests, notifications and separate responses to Confirmable requests. Piggybacked
     * responses reuse the request's ID and are never refused. The default allows four peers at the most
     * RFC 7252 §4.4 allows one (65 536 per EXCHANGE_LIFETIME), about 1 000 messages a second in all, and takes
     * about 26 MB when full.
     */
    public val maxReservedMessageIds: Int = builder.maxReservedMessageIds

    /**
     * The most Message IDs reserved at once towards one peer, 1..65 536: by default the whole ID space, so the
     * bound is the one RFC 7252 §4.4 sets, about 265 messages a second to one peer. Beyond it a message to that
     * peer that needs a new ID is refused as for [maxReservedMessageIds], reported with `MESSAGE_IDS_PER_PEER`.
     */
    public val maxReservedMessageIdsPerPeer: Int = builder.maxReservedMessageIdsPerPeer

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

    /**
     * The most exchanges in progress with all peers together, counted as for [maxExchangesPerPeer]: requests
     * of this endpoint awaiting their response and its observations, and over datagrams every message the
     * messaging layer keeps, also each Non-confirmable one kept for NON_LIFETIME so that a Reset can match it.
     * At the bound a new datagram message first takes the place of the oldest Non-confirmable message kept only
     * for a Reset (RFC 7252 §4.3 lets a sender ignore a Reset), then of the oldest Confirmable message that is
     * still unacknowledged after a retransmission, which is given up early as if it had timed out and reported
     * with `EXCHANGES`: a peer that answers acknowledges within ACK_TIMEOUT, while a spoofed source only draws
     * retransmissions (§11.3). Otherwise a request fails with [CoapException.Reason.IDENTIFIERS_EXHAUSTED], a
     * separate response is not sent, and a notification is sent later with the latest state while the observer
     * stays (RFC 7641 §4.5), each reported with `EXCHANGES`.
     */
    public val maxExchanges: Int = builder.maxExchanges

    init {
        require(separateResponseDelay == null || !separateResponseDelay.isNegative()) {
            "Separate response delay must not be negative: $separateResponseDelay"
        }
        require(maxDeduplicationEntries > 0) { "At least one deduplication entry must be allowed: $maxDeduplicationEntries" }
        require(maxConcurrentRequests > 0) { "At least one request handler must be allowed: $maxConcurrentRequests" }
        require(maxExchangesPerPeer > 0) { "At least one exchange per peer must be allowed: $maxExchangesPerPeer" }
        require(maxDeduplicationEntriesPerPeer > 0) {
            "At least one deduplication entry per peer must be allowed: $maxDeduplicationEntriesPerPeer"
        }
        require(maxExchanges > 0) { "At least one exchange must be allowed: $maxExchanges" }
        require(maxReservedMessageIds > 0) { "At least one Message ID must be allowed: $maxReservedMessageIds" }
        require(maxReservedMessageIdsPerPeer in 1..MESSAGE_ID_SPACE) {
            "Message IDs per peer must be in 1..$MESSAGE_ID_SPACE: $maxReservedMessageIdsPerPeer"
        }
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

        /**
         * See [CoapEndpointConfig.maxDeduplicationEntriesPerPeer]. Default:
         * [DEFAULT_MAX_DEDUPLICATION_ENTRIES_PER_PEER].
         */
        public var maxDeduplicationEntriesPerPeer: Int = DEFAULT_MAX_DEDUPLICATION_ENTRIES_PER_PEER

        /** See [CoapEndpointConfig.maxExchanges]. Default: [DEFAULT_MAX_EXCHANGES]. */
        public var maxExchanges: Int = DEFAULT_MAX_EXCHANGES

        /** See [CoapEndpointConfig.maxReservedMessageIds]. Default: [DEFAULT_MAX_RESERVED_MESSAGE_IDS]. */
        public var maxReservedMessageIds: Int = DEFAULT_MAX_RESERVED_MESSAGE_IDS

        /**
         * See [CoapEndpointConfig.maxReservedMessageIdsPerPeer]. Default:
         * [DEFAULT_MAX_RESERVED_MESSAGE_IDS_PER_PEER].
         */
        public var maxReservedMessageIdsPerPeer: Int = DEFAULT_MAX_RESERVED_MESSAGE_IDS_PER_PEER

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
            maxDeduplicationEntriesPerPeer = config.maxDeduplicationEntriesPerPeer
            maxExchanges = config.maxExchanges
            maxReservedMessageIds = config.maxReservedMessageIds
            maxReservedMessageIdsPerPeer = config.maxReservedMessageIdsPerPeer
        }

        /**
         * The configuration with the values set so far. The builder stays usable: changing it afterwards
         * does not change configurations already built.
         *
         * @throws IllegalArgumentException when a value is out of range: a negative [separateResponseDelay],
         *   a bound below 1, or [maxReservedMessageIdsPerPeer] above 65 536.
         */
        public fun build(): CoapEndpointConfig = CoapEndpointConfig(this)
    }

    public companion object {
        /**
         * About 200 new messages a second that must be remembered over EXCHANGE_LIFETIME; see
         * [maxDeduplicationEntries].
         */
        public const val DEFAULT_MAX_DEDUPLICATION_ENTRIES: Int = 50_000

        /** A fifth of [DEFAULT_MAX_DEDUPLICATION_ENTRIES]; see [maxDeduplicationEntriesPerPeer]. */
        public const val DEFAULT_MAX_DEDUPLICATION_ENTRIES_PER_PEER: Int = 10_000

        /** Four times the Message ID space; see [maxReservedMessageIds]. */
        public const val DEFAULT_MAX_RESERVED_MESSAGE_IDS: Int = 262_144

        /** The whole Message ID space (RFC 7252 §3); see [maxReservedMessageIdsPerPeer]. */
        public const val DEFAULT_MAX_RESERVED_MESSAGE_IDS_PER_PEER: Int = 65_536

        /** See [maxExchanges]. */
        public const val DEFAULT_MAX_EXCHANGES: Int = 16_384

        /** RFC 7252 §3: a Message ID has 16 bits. */
        private const val MESSAGE_ID_SPACE: Int = 0x10000

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
