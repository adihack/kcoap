package dev.kcoap.transport.ktor.websocket

import dev.kcoap.common.ByteArrayBuilder
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.PeerIdentity
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedSendChannelException

/**
 * A ktor WebSocket session as a [MessageConnection] for `coap+ws` and `coaps+ws` (RFC 8323 §4), for a
 * client session or a server one. Every CoAP message is one binary frame (§4.2); a fragmented message
 * is reassembled. A text frame is a protocol error that closes the WebSocket with 1003, and the
 * connection reports its end.
 *
 * RFC 8323 §4.4: WebSocket Ping frames SHOULD NOT be used; give the session no ping interval, CoAP's
 * own Ping and Pong test the connection. Construct it for a session whose subprotocol is `coap`.
 */
public class KtorWebSocketConnection(
    private val session: WebSocketSession,
    override val remote: Endpoint,
    override val peerIdentity: PeerIdentity = PeerIdentity.None,
) : MessageConnection {
    /** @throws ClosedSendChannelException once the WebSocket is closed. */
    override suspend fun send(message: ByteArray) {
        session.send(Frame.Binary(true, message))
    }

    override suspend fun receive(): ByteArray? {
        var assembled: ByteArrayBuilder? = null
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return null
            when (frame) {
                is Frame.Binary -> {
                    if (frame.fin && assembled == null) return frame.data
                    val buffer = assembled ?: ByteArrayBuilder(frame.data.size * 2).also { assembled = it }
                    buffer.append(frame.data)
                    if (frame.fin) return buffer.toByteArray()
                }
                is Frame.Text -> {
                    // RFC 8323 §4.2: CoAP messages travel in binary frames only.
                    session.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "CoAP uses binary frames"))
                    return null
                }
                // Control frames: ktor answers pings itself and ends the session on Close.
                else -> Unit
            }
        }
    }

    override suspend fun close() {
        try {
            session.close(CloseReason(CloseReason.Codes.NORMAL, ""))
        } catch (e: Exception) {
            // Already closed by the peer.
        }
    }

    override fun toString(): String = "KtorWebSocketConnection($remote)"
}
