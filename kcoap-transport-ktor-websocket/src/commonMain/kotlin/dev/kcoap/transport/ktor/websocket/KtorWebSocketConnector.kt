package dev.kcoap.transport.ktor.websocket

import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.Connector
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close

/**
 * Opens WebSockets for `coap+ws` and `coaps+ws` (RFC 8323 §4, §8.3, §8.4) with the ktor client, so the
 * same code runs wherever a ktor engine does. The default client runs on CIO on the JVM and Linux, on
 * Darwin (URLSession, which brings up the cellular radio and honours VPN On Demand) on Apple, and on the
 * runtime's own WebSocket in JS and Wasm. It names that engine, so another ktor engine the application
 * links does not replace it; pass an [HttpClient] for a different one. The opening handshake asks for
 * the subprotocol `coap` at `/.well-known/coap` (§4.1) and fails unless the server selects it.
 *
 * ```
 * val client = CoapClient(listOf(KtorWebSocketConnector()), scope)
 * client.get("coap+ws://example.org/sensors/temperature?u=Cel")
 * ```
 *
 * `coaps+ws` runs `wss` on the engine's TLS, which must authenticate the server (RFC 8323 §9.2): its chain
 * valid under the trust store and naming the URI's host (RFC 9525 §6). [schemes] lists `coaps+ws` only where
 * the engine does that, as listing it is the connector's word that it runs TLS, and a CoAP client sends
 * `coaps+ws` requests only then:
 *
 * - iOS and macOS: yes. URLSession checks the chain against the system's trust store, and the host name.
 * - `js` and `wasmJs`: yes. The browser's or Node.js's own `wss` does.
 * - JVM and Android: no. CIO's TLS, ktor-network-tls 3.6.1, accepts a certificate without DNS names for any
 *   host name, and CIO checks an IP literal by the name its reverse DNS lookup gives.
 * - Linux: no. CIO has no TLS on Linux native.
 *
 * An [HttpClient] the application passes serves both schemes: passing one is the application's word that its
 * engine authenticates `wss` servers. A CIO client on the JVM or Android has the name check above, so trust only
 * certificate authorities that issue nothing but DNS names for your servers, or pass a client on an engine
 * whose TLS checks names, such as one on the JDK's own TLS.
 *
 * The connection reports [PeerIdentity.None]: ktor exposes no TLS peer certificate in common code.
 */
public class KtorWebSocketConnector private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
    override val schemes: Set<CoapScheme>,
    loggerFactory: LoggerFactory,
) : Connector {
    /**
     * A connector on a client of its own, on the platform's engine named above and with WebSocket pings
     * off (RFC 8323 §4.4); [close] closes it. It serves `coap+ws` everywhere, and `coaps+ws` only where that
     * engine authenticates `wss` servers (the table above).
     */
    public constructor(loggerFactory: LoggerFactory = LoggerFactory.Global) : this(
        defaultClient(),
        true,
        if (defaultClientAuthenticatesServers) BOTH_SCHEMES else setOf(CoapScheme.COAP_WS),
        loggerFactory,
    )

    /**
     * A connector on [client], which must have the WebSockets plugin installed, preferably without a
     * ping interval (RFC 8323 §4.4: WebSocket pings SHOULD NOT be used). The application closes it.
     *
     * It serves `coap+ws` and `coaps+ws`: passing [client] is the application's word that its engine
     * authenticates a `wss` server by a chain its trust store accepts and that names the host (RFC 8323 §9.2,
     * RFC 9525 §6). On the JVM and Android, CIO's TLS does not check names fully (see above).
     */
    public constructor(client: HttpClient, loggerFactory: LoggerFactory = LoggerFactory.Global) :
        this(client, false, BOTH_SCHEMES, loggerFactory)

    init {
        require(client.pluginOrNull(WebSockets) != null) { "The HttpClient needs the WebSockets plugin" }
    }

    private val log = loggerFactory.logger(LogTags.TRANSPORT)

    /**
     * @throws IllegalArgumentException for anything but an [Endpoint.WebSocket], and for a `wss` one when
     * [schemes] leaves out `coaps+ws`; nothing is sent then.
     * @throws IllegalStateException when the handshake completes without the subprotocol `coap`. Where
     * the WebSocket itself fails such a handshake, as browsers and the npm `ws` client do, the engine's
     * own exception comes instead.
     */
    override suspend fun connect(peer: Endpoint): Connection {
        require(peer is Endpoint.WebSocket) { "A WebSocket connector cannot reach $peer" }
        require(peer.scheme in schemes) {
            "This connector's default client does not authenticate wss servers (RFC 8323 §9.2), so it does not " +
                "reach $peer; pass an HttpClient whose engine does"
        }
        val session = client.webSocketSession(peer.uri) {
            // RFC 8323 §4.1: the client MUST include the subprotocol name "coap".
            header(HttpHeaders.SecWebSocketProtocol, CoapScheme.WEBSOCKET_SUBPROTOCOL)
        }
        val selected = session.call.response.headers[HttpHeaders.SecWebSocketProtocol]
        if (selected == null || !selected.equals(CoapScheme.WEBSOCKET_SUBPROTOCOL, ignoreCase = true)) {
            session.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "subprotocol coap required"))
            throw IllegalStateException("$peer did not select the WebSocket subprotocol coap (got $selected)")
        }
        log.debug { "WebSocket to $peer open" }
        return KtorWebSocketConnection(session, peer, PeerIdentity.None)
    }

    override suspend fun close() {
        if (ownsClient) client.close()
    }

    override fun toString(): String = "KtorWebSocketConnector"
}

private val BOTH_SCHEMES = setOf(CoapScheme.COAP_WS, CoapScheme.COAPS_WS)
