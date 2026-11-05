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
 * same code runs wherever a ktor engine does: CIO on the JVM and native targets, Darwin on Apple, and
 * the browser's WebSocket in JS and Wasm. The opening handshake asks for the subprotocol `coap` at
 * `/.well-known/coap` (§4.1) and fails unless the server selects it; `wss` runs on the engine's TLS.
 *
 * ```
 * val client = CoapClient(listOf(KtorWebSocketConnector()), scope)
 * client.get("coap+ws://example.org/sensors/temperature?u=Cel")
 * ```
 *
 * The connection reports [PeerIdentity.None]: ktor exposes no TLS peer certificate in common code, and
 * the engine has already validated it against the URI's host.
 */
public class KtorWebSocketConnector private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
    loggerFactory: LoggerFactory,
) : Connector {
    /**
     * A connector on a client of its own, with the platform's default ktor engine and WebSocket pings
     * off (RFC 8323 §4.4); [close] closes it.
     */
    public constructor(loggerFactory: LoggerFactory = LoggerFactory.Global) : this(defaultClient(), true, loggerFactory)

    /**
     * A connector on [client], which must have the WebSockets plugin installed, preferably without a
     * ping interval (RFC 8323 §4.4: WebSocket pings SHOULD NOT be used). The application closes it.
     */
    public constructor(client: HttpClient, loggerFactory: LoggerFactory = LoggerFactory.Global) : this(client, false, loggerFactory)

    init {
        require(client.pluginOrNull(WebSockets) != null) { "The HttpClient needs the WebSockets plugin" }
    }

    private val log = loggerFactory.logger(LogTags.TRANSPORT)

    override val schemes: Set<CoapScheme> = setOf(CoapScheme.COAP_WS, CoapScheme.COAPS_WS)

    /**
     * @throws IllegalArgumentException for anything but an [Endpoint.WebSocket].
     * @throws IllegalStateException when the server does not select the subprotocol `coap`.
     */
    override suspend fun connect(peer: Endpoint): Connection {
        require(peer is Endpoint.WebSocket) { "A WebSocket connector cannot reach $peer" }
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

    private companion object {
        fun defaultClient(): HttpClient = HttpClient {
            install(WebSockets)
        }
    }
}
