package dev.kcoap.coap.client

import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.transport.ChannelSecurityKind
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.IpLiteral
import dev.kcoap.transport.TransportKind
import kotlin.time.Duration

/**
 * How a [CoapClient] sends requests.
 *
 * Build one with the DSL, `CoapClientConfig { requestTimeout = 5.seconds }`, or with a [Builder]
 * (`CoapClientConfig.Builder()`, set its properties, `build()`), which reads the same from Swift; [Default] has
 * every default, and [toBuilder] starts from an existing configuration. The configuration itself is immutable,
 * and every value is checked when it is built. Options added later go into the builder, so code that builds a
 * configuration keeps compiling, and compiled code keeps linking.
 */
public class CoapClientConfig private constructor(builder: Builder) {

    /**
     * Message security (OSCORE) for outgoing requests, called below block-wise and Observe and above the
     * messaging layer (RFC 8613 Figure 1); null sends requests unprotected.
     */
    public val security: ClientMessageSecurity? = builder.security

    /**
     * How long to wait for the response to one request (one block), measured from sending; null waits
     * EXCHANGE_LIFETIME. A Confirmable request without ACK fails earlier, after MAX_TRANSMIT_WAIT.
     */
    public val requestTimeout: Duration? = builder.requestTimeout

    /**
     * The block size the client prefers, 16..1024: Block1 uploads use it, and setting it turns on early
     * negotiation, so every request without a body proposes it as the Block2 size of the response (RFC 7959
     * §2.4); a server may still choose smaller blocks. Null, the default, uses the largest size the datagram (or
     * the server's Max-Message-Size, with BERT where both sides indicated it, RFC 8323 §6) fits and lets the
     * server choose the response block size. Set, it also turns BERT off. The server's counterpart is its upper
     * bound `CoapServerConfig.maxBlockSize`.
     */
    public val preferredBlockSize: Int? = builder.preferredBlockSize

    /** The largest body assembled from Block2 transfers. */
    public val maxResponseBodySize: Int = builder.maxResponseBodySize

    /**
     * Turns the host of a URI into a transport [Endpoint]; [EndpointResolver.Default] takes IP literals for
     * every scheme and passes host names to the connectors of RFC 8323 schemes.
     */
    public val resolver: EndpointResolver = builder.resolver

    /**
     * What a cancelled observation does (RFC 7641 §3.6). Over reliable transports the client always
     * deregisters (RFC 8323 §7.4).
     */
    public val observeCancellation: ObserveCancellation = builder.observeCancellation

    /**
     * Whether a 4.01 with Echo is answered by repeating the request once with that Echo value (RFC 9175 §2.3:
     * SHOULD).
     */
    public val echoRetry: Boolean = builder.echoRetry

    /** Bytes kept free in each message for [security]'s overhead when choosing block sizes. */
    public val securityReserve: Int = builder.securityReserve

    /**
     * Whether and how requests use Q-Block (RFC 9177) instead of RFC 7959; off by default.
     * [preferredBlockSize] and [maxResponseBodySize] apply to Q-Block bodies too.
     */
    public val qBlock: QBlockClientConfig = builder.qBlock

    init {
        require(requestTimeout == null || requestTimeout.isPositive()) { "Request timeout must be positive: $requestTimeout" }
        val blockSize = preferredBlockSize
        require(blockSize == null || blockSize in 16..1024 && blockSize and (blockSize - 1) == 0) {
            "Block size must be a power of two in 16..1024: $blockSize"
        }
        require(maxResponseBodySize > 0) { "Maximum response body size must be positive" }
        require(securityReserve >= 0) { "Security reserve must not be negative" }
    }

    /**
     * The configuration with these values. Deprecated: build it with `CoapClientConfig { ... }` or a [Builder],
     * which take the same named values and, unlike this constructor, can gain options without breaking compiled
     * code.
     *
     * @throws IllegalArgumentException when a value is out of range, as [Builder.build] does.
     */
    @Deprecated(
        "Build the configuration with CoapClientConfig { ... } or CoapClientConfig.Builder; this constructor is " +
            "removed before the first release.",
    )
    public constructor(
        security: ClientMessageSecurity? = null,
        requestTimeout: Duration? = null,
        preferredBlockSize: Int? = null,
        maxResponseBodySize: Int = DEFAULT_MAX_RESPONSE_BODY_SIZE,
        resolver: EndpointResolver = EndpointResolver.Default,
        observeCancellation: ObserveCancellation = ObserveCancellation.DEREGISTER,
        echoRetry: Boolean = true,
        securityReserve: Int = DEFAULT_SECURITY_RESERVE,
        qBlock: QBlockClientConfig = QBlockClientConfig(),
    ) : this(
        Builder().also {
            it.security = security
            it.requestTimeout = requestTimeout
            it.preferredBlockSize = preferredBlockSize
            it.maxResponseBodySize = maxResponseBodySize
            it.resolver = resolver
            it.observeCancellation = observeCancellation
            it.echoRetry = echoRetry
            it.securityReserve = securityReserve
            it.qBlock = qBlock
        },
    )

    /** A [Builder] holding this configuration's values, to build a changed copy. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Collects the values of a [CoapClientConfig]; every property starts at its default, documented on the
     * property of the same name. Not thread safe: build on one thread, then share the configuration.
     */
    public class Builder public constructor() {
        /** See [CoapClientConfig.security]. Default: null, requests go unprotected. */
        public var security: ClientMessageSecurity? = null

        /** See [CoapClientConfig.requestTimeout]. Default: null, EXCHANGE_LIFETIME. */
        public var requestTimeout: Duration? = null

        /** See [CoapClientConfig.preferredBlockSize]. Default: null, the largest size that fits. */
        public var preferredBlockSize: Int? = null

        /** See [CoapClientConfig.maxResponseBodySize]. Default: 1 MiB. */
        public var maxResponseBodySize: Int = DEFAULT_MAX_RESPONSE_BODY_SIZE

        /** See [CoapClientConfig.resolver]. Default: [EndpointResolver.Default]. */
        public var resolver: EndpointResolver = EndpointResolver.Default

        /** See [CoapClientConfig.observeCancellation]. Default: [ObserveCancellation.DEREGISTER]. */
        public var observeCancellation: ObserveCancellation = ObserveCancellation.DEREGISTER

        /** See [CoapClientConfig.echoRetry]. Default: true. */
        public var echoRetry: Boolean = true

        /** See [CoapClientConfig.securityReserve]. Default: 64 bytes. */
        public var securityReserve: Int = DEFAULT_SECURITY_RESERVE

        /** See [CoapClientConfig.qBlock]. Default: `QBlockClientConfig()`, Q-Block off. */
        public var qBlock: QBlockClientConfig = QBlockClientConfig()

        internal constructor(config: CoapClientConfig) : this() {
            security = config.security
            requestTimeout = config.requestTimeout
            preferredBlockSize = config.preferredBlockSize
            maxResponseBodySize = config.maxResponseBodySize
            resolver = config.resolver
            observeCancellation = config.observeCancellation
            echoRetry = config.echoRetry
            securityReserve = config.securityReserve
            qBlock = config.qBlock
        }

        /**
         * The configuration with the values set so far. The builder stays usable: changing it afterwards does
         * not change configurations already built.
         *
         * @throws IllegalArgumentException when a value is out of range: a [requestTimeout] that is not
         *   positive, a [preferredBlockSize] that is not a power of two in 16..1024, a [maxResponseBodySize]
         *   below 1 or a negative [securityReserve].
         */
        public fun build(): CoapClientConfig = CoapClientConfig(this)
    }

    public companion object {
        private const val DEFAULT_MAX_RESPONSE_BODY_SIZE: Int = 1 shl 20
        private const val DEFAULT_SECURITY_RESERVE: Int = 64

        /** Every value at its default: no message security, RFC 7959 block-wise, Q-Block off. */
        public val Default: CoapClientConfig = Builder().build()
    }

    override fun toString(): String =
        "CoapClientConfig(security=${security != null}, requestTimeout=$requestTimeout, " +
            "preferredBlockSize=$preferredBlockSize, qBlock=${qBlock.mode})"
}

/**
 * The [CoapClientConfig] that [block] sets up on a [CoapClientConfig.Builder]; what is not set keeps its
 * default:
 *
 * ```
 * val client = CoapClient(transport, scope, CoapClientConfig {
 *     security = OscoreClient(contexts)
 *     requestTimeout = 5.seconds
 * })
 * ```
 *
 * @throws IllegalArgumentException when a value is out of range, as [CoapClientConfig.Builder.build] does.
 */
public fun CoapClientConfig(block: CoapClientConfig.Builder.() -> Unit): CoapClientConfig =
    CoapClientConfig.Builder().apply(block).build()

/** How a [CoapClient] ends an observation whose collector went away (RFC 7641 §3.6). */
public enum class ObserveCancellation {
    /** Send a GET with Observe 1 and the same token, so the server forgets the observer at once. */
    DEREGISTER,

    /** Forget the token; the next notification is answered with a Reset, which ends it on the server. */
    FORGET,
}

/**
 * Finds the transport endpoint for a URI's host and port. The kind of [Endpoint] it returns picks the
 * endpoint's transport: an [Endpoint.Udp] or [Endpoint.Secure] its datagram transport, an [Endpoint.Tcp] or
 * [Endpoint.WebSocket] a connection.
 *
 * **Errors.** A lookup that fails at runtime, as a DNS lookup does without network or for a name without an
 * address, throws [CoapException] with [CoapException.Reason.UNRESOLVED_HOST] and the underlying failure as cause:
 * a condition that may pass. `IllegalArgumentException` is kept for a host this resolver cannot resolve by design,
 * a mistake of configuration that no retry fixes, such as a host name given to [Default], which knows no DNS.
 * [CoapClient] passes both on, and reports any other exception of [resolve] as
 * [CoapException.Reason.UNRESOLVED_HOST] with it as cause, so a resolver built on a platform's DNS may let that
 * platform's exceptions through. That includes the cancellation of a lookup that times out in its own
 * `withTimeout` while the caller is still active; only the caller's own cancellation passes as one.
 *
 * A [CoapClient] does not resolve `coaps` URIs: without DTLS (RFC 7252 §9.1) it fails such a call with
 * [CoapException.Reason.TRANSPORT_UNAVAILABLE] before the resolver runs.
 */
public fun interface EndpointResolver {
    /**
     * The endpoint for [uri]'s host and port.
     *
     * @throws CoapException with [CoapException.Reason.UNRESOLVED_HOST] when the lookup fails at runtime.
     * @throws IllegalArgumentException when this resolver cannot resolve such a host at all.
     */
    public suspend fun resolve(uri: CoapUri): Endpoint

    public companion object {
        /**
         * IP literals for every scheme, in their canonical form ([IpLiteral], so that responses match by
         * endpoint equality), and `localhost` as 127.0.0.1 for datagram schemes.
         *
         * For the reliable schemes of RFC 8323 §8, responses arrive on the connection that carried the
         * request, so host names stay names, which the connector resolves and TLS checks the certificate
         * against: `coap+tcp` and `coaps+tcp` become an [Endpoint.Tcp], `coap+ws` and `coaps+ws` the
         * [Endpoint.WebSocket] at `/.well-known/coap` of the URI's authority. Datagram host names other than
         * `localhost` need a resolver the application supplies, one that knows DNS, since a response over
         * datagrams matches its request by the address it comes from.
         */
        public val Default: EndpointResolver = EndpointResolver { uri ->
            val usesTls = uri.scheme.security == ChannelSecurityKind.TLS
            when (uri.scheme.kind) {
                TransportKind.DATAGRAM -> {
                    val host = when {
                        uri.host == "localhost" -> "127.0.0.1"
                        else -> IpLiteral.canonical(uri.host)
                            ?: throw IllegalArgumentException("Cannot resolve '${uri.host}': configure an EndpointResolver")
                    }
                    Endpoint.Udp(host, uri.port)
                }
                TransportKind.BYTE_STREAM -> Endpoint.Tcp(IpLiteral.canonical(uri.host) ?: uri.host, uri.port, usesTls)
                TransportKind.MESSAGE_STREAM -> Endpoint.WebSocket.of(IpLiteral.canonical(uri.host) ?: uri.host, uri.port, usesTls)
            }
        }
    }
}
