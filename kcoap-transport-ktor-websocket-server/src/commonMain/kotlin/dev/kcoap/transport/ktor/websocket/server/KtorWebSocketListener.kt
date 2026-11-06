package dev.kcoap.transport.ktor.websocket.server

import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.IpLiteral
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnection
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.Channel

/**
 * Accepts WebSockets for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor server, at
 * `/.well-known/coap` and only with the subprotocol `coap` (§4.1, §8.3). [start] runs a standalone server
 * on the ktor engine the application chooses, such as CIO (`io.ktor:ktor-server-cio`) or Netty; an
 * application that runs its own ktor server mounts the endpoint in its routing with [coapOverWebSockets],
 * with the WebSockets plugin installed and, for `coaps+ws`, behind its TLS. This module brings no engine.
 *
 * ```
 * val server = CoapServer(listOf(KtorWebSocketListener.start(CIO, port = 8080)), scope) { ... }
 *
 * // or inside an existing ktor application:
 * val listener = KtorWebSocketListener()
 * routing { coapOverWebSockets(listener) }
 * ```
 *
 * Each accepted peer is an [Endpoint.WebSocket] named after the scheme and the client's address, such as
 * `ws://192.0.2.7:52144`.
 *
 * A listener for `coaps+ws` reports a scheme that says its connections run TLS (RFC 8323 §9.2), which a CoAP
 * client on the same endpoint relies on, so it checks that each session arrived over TLS: one whose request the
 * engine received on a plain connector (`request.local.scheme` neither `https` nor `wss`) is closed with 1008,
 * policy violation (RFC 6455 §7.4.1), and never accepted. This matters where an application mounts the endpoint
 * on an engine with both a plain and a TLS connector. Behind a TLS-terminating proxy the engine sees plain HTTP,
 * so the listener there serves `coap+ws` (docs/adr/0010-server-side-tls.md).
 *
 * @property scheme [CoapScheme.COAP_WS], or [CoapScheme.COAPS_WS] when the server runs TLS.
 */
public class KtorWebSocketListener(
    override val scheme: CoapScheme = CoapScheme.COAP_WS,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) : ConnectionListener {
    init {
        require(scheme == CoapScheme.COAP_WS || scheme == CoapScheme.COAPS_WS) { "Not a WebSocket scheme: $scheme" }
    }

    private val log = loggerFactory.logger(LogTags.TRANSPORT)
    private val pending = Channel<Connection>(Channel.UNLIMITED)

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    /** Where a server started by [start] listens; null when the listener is mounted elsewhere. */
    @Volatile
    public var localEndpoint: Endpoint.WebSocket? = null
        private set

    override suspend fun accept(): Connection? = pending.receiveCatching().getOrNull()

    /** Stops accepting, closes the WebSockets not accepted yet, and stops a server started by [start]. */
    override suspend fun close() {
        pending.close()
        while (true) pending.tryReceive().getOrNull()?.close() ?: break
        server?.let {
            server = null
            it.stopSuspend(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MILLIS)
            log.info { "Stopped the WebSocket server at $localEndpoint" }
        }
    }

    /**
     * Hands [session] to [accept] and keeps it open, as ktor ends a session when its handler returns. A `coaps+ws`
     * listener first closes a session that did not arrive over TLS.
     */
    internal suspend fun serve(session: DefaultWebSocketServerSession) {
        val point = session.call.request.origin
        val host = IpLiteral.canonical(point.remoteAddress) ?: point.remoteAddress
        val authority = if (':' in host) "[$host]" else host
        val prefix = if (scheme == CoapScheme.COAPS_WS) "wss" else "ws"
        val peer = Endpoint.WebSocket("$prefix://$authority:${point.remotePort}")
        val arrivedOver = session.call.request.local.scheme
        if (scheme == CoapScheme.COAPS_WS && arrivedOver.lowercase() !in TLS_REQUEST_SCHEMES) {
            log.warn { "Refused a WebSocket from $peer: this listener serves coaps+ws, but it arrived over $arrivedOver" }
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "coaps+ws needs TLS"))
            return
        }
        val connection = KtorWebSocketConnection(session, peer)
        if (pending.trySend(connection).isFailure) {
            session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "not accepting"))
            return
        }
        log.debug { "Accepted a WebSocket from $peer" }
        session.closeReason.await()
    }

    override fun toString(): String = "KtorWebSocketListener(${localEndpoint ?: scheme.scheme})"

    public companion object {
        private const val STOP_TIMEOUT_MILLIS = 1000L

        /** What ktor engines report as the scheme of a request that arrived on a TLS connector. */
        private val TLS_REQUEST_SCHEMES = setOf("https", "wss")

        /** RFC 8323 §8.3: `coap+ws` defaults to port 80. */
        private const val DEFAULT_PORT = 80

        /**
         * Starts a ktor server on [engineFactory] that serves CoAP over WebSockets on [host] and [port]
         * (0 picks a free one), with WebSocket pings off (RFC 8323 §4.4). Frames above [maxFrameSize] are
         * refused by the WebSocket layer; set it at least to the endpoint's Max-Message-Size, above which
         * CoAP itself aborts.
         *
         * The engine is the application's choice and dependency: `io.ktor.server.cio.CIO` from
         * `io.ktor:ktor-server-cio` runs on the JVM and the native targets, Netty on the JVM.
         *
         * @throws IllegalArgumentException for a port outside 0..65535 or a frame size that is not positive.
         */
        public suspend fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> start(
            engineFactory: ApplicationEngineFactory<TEngine, TConfiguration>,
            host: String = "0.0.0.0",
            port: Int = DEFAULT_PORT,
            maxFrameSize: Long = DEFAULT_MAX_FRAME_SIZE,
            loggerFactory: LoggerFactory = LoggerFactory.Global,
        ): KtorWebSocketListener {
            require(port in 0..65535) { "Port out of range: $port" }
            require(maxFrameSize > 0) { "Frame size must be positive: $maxFrameSize" }
            val listener = KtorWebSocketListener(CoapScheme.COAP_WS, loggerFactory)
            val server = embeddedServer(engineFactory, port = port, host = host) {
                install(WebSockets) {
                    // RFC 8323 §4.4: WebSocket pings SHOULD NOT be used; CoAP pings test the connection.
                    pingPeriodMillis = 0
                    this.maxFrameSize = maxFrameSize
                }
                routing { coapOverWebSockets(listener) }
            }
            server.startSuspend(wait = false)
            val bound = server.engine.resolvedConnectors().first().port
            listener.server = server
            listener.localEndpoint = Endpoint.WebSocket.of(host, bound)
            loggerFactory.logger(LogTags.TRANSPORT).info { "Serving CoAP over WebSockets at ${listener.localEndpoint}" }
            return listener
        }

        /** 1 MiB: far above the default Max-Message-Size, so CoAP's own limit answers first with an Abort. */
        public const val DEFAULT_MAX_FRAME_SIZE: Long = 1L shl 20
    }
}

/**
 * Serves CoAP over WebSockets at `/.well-known/coap` with the subprotocol `coap` (RFC 8323 §4.1, §8.3),
 * handing every session to [listener]. Requires the ktor WebSockets plugin; give it no ping period
 * (RFC 8323 §4.4).
 */
public fun Route.coapOverWebSockets(listener: KtorWebSocketListener) {
    webSocket(CoapScheme.WEBSOCKET_PATH, protocol = CoapScheme.WEBSOCKET_SUBPROTOCOL) { listener.serve(this) }
}
