package dev.kcoap.coap.client

import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.security.ClientMessageSecurity
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
 *   (early negotiation, RFC 7959 §2.4); null uses the largest size the datagram fits and lets the
 *   server choose the response block size.
 * @property maxResponseBodySize the largest body assembled from Block2 transfers.
 * @property resolver turns the host of a URI into a transport [Endpoint].
 * @property observeCancellation what a cancelled observation does (RFC 7641 §3.6).
 * @property echoRetry whether a 4.01 with Echo is answered by repeating the request once with that Echo
 *   value (RFC 9175 §2.3: SHOULD).
 * @property securityReserve bytes kept free in each message for [security]'s overhead when choosing
 *   block sizes.
 */
public class CoapClientConfig(
    public val security: ClientMessageSecurity? = null,
    public val requestTimeout: Duration? = null,
    public val blockSize: Int? = null,
    public val maxResponseBodySize: Int = 1 shl 20,
    public val resolver: EndpointResolver = EndpointResolver.IpLiterals,
    public val observeCancellation: ObserveCancellation = ObserveCancellation.DEREGISTER,
    public val echoRetry: Boolean = true,
    public val securityReserve: Int = 64,
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
        "CoapClientConfig(security=${security != null}, requestTimeout=$requestTimeout, blockSize=$blockSize)"
}

/** How a [CoapClient] ends an observation whose collector went away (RFC 7641 §3.6). */
public enum class ObserveCancellation {
    /** Send a GET with Observe 1 and the same token, so the server forgets the observer at once. */
    DEREGISTER,

    /** Forget the token; the next notification is answered with a Reset, which ends it on the server. */
    FORGET,
}

/** Finds the transport endpoint for a URI's host and port. */
public fun interface EndpointResolver {
    /** @throws IllegalArgumentException when the host cannot be resolved. */
    public suspend fun resolve(uri: CoapUri): Endpoint

    public companion object {
        /**
         * IP literals in their canonical form ([IpLiteral], so that responses match by endpoint
         * equality), and `localhost` as 127.0.0.1; anything else needs a resolver that knows DNS.
         */
        public val IpLiterals: EndpointResolver = EndpointResolver { uri ->
            require(uri.scheme.kind == TransportKind.DATAGRAM) { "${uri.scheme.scheme} needs a ${uri.scheme.kind} transport" }
            val host = when {
                uri.host == "localhost" -> "127.0.0.1"
                else -> IpLiteral.canonical(uri.host)
                    ?: throw IllegalArgumentException("Cannot resolve '${uri.host}': configure an EndpointResolver")
            }
            Endpoint.Udp(host, uri.port)
        }
    }
}
