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
 * @property security message security (OSCORE) for outgoing requests, called below block-wise and
 *   Observe and above the messaging layer (RFC 8613 Figure 1); null sends requests unprotected.
 * @property requestTimeout how long to wait for the response to one request (one block), measured
 *   from sending; null waits EXCHANGE_LIFETIME. A Confirmable request without ACK fails earlier, after
 *   MAX_TRANSMIT_WAIT.
 * @property blockSize the block size for Block1 uploads and the Block2 size proposed in requests
 *   (early negotiation, RFC 7959 §2.4); null uses the largest size the datagram (or the server's
 *   Max-Message-Size, with BERT where both sides indicated it, RFC 8323 §6) fits and lets the server
 *   choose the response block size. Set, it also turns BERT off.
 * @property maxResponseBodySize the largest body assembled from Block2 transfers.
 * @property resolver turns the host of a URI into a transport [Endpoint]; [EndpointResolver.Default] takes IP
 *   literals for every scheme and passes host names to the connectors of RFC 8323 schemes.
 * @property observeCancellation what a cancelled observation does (RFC 7641 §3.6). Over reliable
 *   transports the client always deregisters (RFC 8323 §7.4).
 * @property echoRetry whether a 4.01 with Echo is answered by repeating the request once with that Echo
 *   value (RFC 9175 §2.3: SHOULD).
 * @property securityReserve bytes kept free in each message for [security]'s overhead when choosing
 *   block sizes.
 * @property qBlock whether and how requests use Q-Block (RFC 9177) instead of RFC 7959; off by default.
 *   [blockSize] and [maxResponseBodySize] apply to Q-Block bodies too.
 */
public class CoapClientConfig(
    public val security: ClientMessageSecurity? = null,
    public val requestTimeout: Duration? = null,
    public val blockSize: Int? = null,
    public val maxResponseBodySize: Int = 1 shl 20,
    public val resolver: EndpointResolver = EndpointResolver.Default,
    public val observeCancellation: ObserveCancellation = ObserveCancellation.DEREGISTER,
    public val echoRetry: Boolean = true,
    public val securityReserve: Int = 64,
    public val qBlock: QBlockClientConfig = QBlockClientConfig(),
) {
    init {
        require(requestTimeout == null || requestTimeout.isPositive()) { "Request timeout must be positive: $requestTimeout" }
        require(blockSize == null || (blockSize in 16..1024 && blockSize and (blockSize - 1) == 0)) {
            "Block size must be a power of two in 16..1024: $blockSize"
        }
        require(maxResponseBodySize > 0) { "Maximum response body size must be positive" }
        require(securityReserve >= 0) { "Security reserve must not be negative" }
    }

    override fun toString(): String =
        "CoapClientConfig(security=${security != null}, requestTimeout=$requestTimeout, blockSize=$blockSize, " +
            "qBlock=${qBlock.mode})"
}

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
 * platform's exceptions through.
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
