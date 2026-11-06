package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Endpoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A WebSocket server on Node.js, from the npm package `ws`, at `/.well-known/coap` of 127.0.0.1, that
 * echoes every message. Its `handleProtocols` hook sees the subprotocols each opening handshake offers
 * (RFC 6455 §4.2.1) and answers with [select]'s choice, or with none when that is null (§4.2.2).
 */
internal class NodeWebSocketServer private constructor(
    private val server: dynamic,
    /** Where the server listens. */
    val endpoint: Endpoint.WebSocket,
    private val offeredByHandshake: List<Set<String>>,
    private val selectedByConnection: List<String>,
) {
    /** The subprotocols each opening handshake offered, in order. */
    val offered: List<Set<String>> get() = offeredByHandshake.toList()

    /** The subprotocol of each accepted WebSocket, as the server's side reads it. */
    val selected: List<String> get() = selectedByConnection.toList()

    /** Closes the WebSockets still open and stops listening. */
    suspend fun close() {
        server.clients.forEach { socket: dynamic -> socket.terminate() }
        suspendCancellableCoroutine<Unit> { continuation -> server.close { continuation.resume(Unit) } }
    }

    companion object {
        /** Starts a server on a free port that selects what [select] returns for the offered subprotocols. */
        suspend fun start(select: (Set<String>) -> String?): NodeWebSocketServer {
            val offered = mutableListOf<Set<String>>()
            val selected = mutableListOf<String>()
            // A dynamic import: a browser never evaluates it, so the browser run needs no bundler for ws.
            val ws: dynamic = (js("import('ws')") as Promise<dynamic>).await()
            val serverClass = ws.WebSocketServer
            val options: dynamic = js("({})")
            options.host = "127.0.0.1"
            options.port = 0
            options.path = CoapScheme.WEBSOCKET_PATH
            options.handleProtocols = { protocols: dynamic, _: dynamic ->
                val offers = (js("Array").from(protocols) as Array<String>).toSet()
                offered += offers
                select(offers) ?: false
            }
            val server: dynamic = js("new serverClass(options)")
            server.on("connection") { socket: dynamic, _: dynamic ->
                selected += socket.protocol as String
                socket.on("message") { data: dynamic, isBinary: Boolean ->
                    val sendOptions: dynamic = js("({})")
                    sendOptions.binary = isBinary
                    socket.send(data, sendOptions)
                }
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                server.once("listening") { if (continuation.isActive) continuation.resume(Unit) }
                server.once("error") { error: dynamic ->
                    val failure = IllegalStateException("ws server: ${error.message}")
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
            val port = server.address().port as Int
            return NodeWebSocketServer(server, Endpoint.WebSocket.of("127.0.0.1", port), offered, selected)
        }
    }
}
