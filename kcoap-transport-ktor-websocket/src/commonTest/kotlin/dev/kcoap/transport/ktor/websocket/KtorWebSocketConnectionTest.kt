package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest

class KtorWebSocketConnectionTest {
    /** A WebSocket session whose frames the test writes and reads. */
    private class FakeSession : WebSocketSession {
        override val coroutineContext: CoroutineContext = Job()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun flush() {}

        @Deprecated("Use cancel() instead.", replaceWith = ReplaceWith("cancel()"))
        override fun terminate() {}
    }

    private val peer = Endpoint.WebSocket.of("192.0.2.1", 80)

    /** RFC 8323 §4.2: every message is one binary frame. */
    @Test
    fun rfc8323Section4_2MessagesAreBinaryFrames() = runTest {
        val session = FakeSession()
        val connection = KtorWebSocketConnection(session, peer)
        assertEquals(peer, connection.remote)
        assertEquals(PeerIdentity.None, connection.peerIdentity)

        connection.send(byteArrayOf(1, 2, 3))
        val sent = assertIs<Frame.Binary>(session.outgoing.receive())
        assertTrue(sent.fin)
        assertContentEquals(byteArrayOf(1, 2, 3), sent.data)

        session.incoming.send(Frame.Binary(true, byteArrayOf(4, 5)))
        assertContentEquals(byteArrayOf(4, 5), connection.receive())
        assertEquals("KtorWebSocketConnection($peer)", connection.toString())
    }

    /** RFC 8323 §4.2 by RFC 6455 §5.4: a fragmented message is reassembled; control frames in between are skipped. */
    @Test
    fun rfc8323Section4_2FragmentedMessagesAreReassembled() = runTest {
        val session = FakeSession()
        val connection = KtorWebSocketConnection(session, peer)
        session.incoming.send(Frame.Binary(false, byteArrayOf(1, 2)))
        session.incoming.send(Frame.Ping(byteArrayOf()))
        session.incoming.send(Frame.Binary(false, byteArrayOf(3)))
        session.incoming.send(Frame.Binary(true, byteArrayOf(4)))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), connection.receive())
    }

    /** RFC 8323 §4.2: a text frame is not CoAP; the WebSocket closes with 1003 and the connection ends. */
    @Test
    fun textFramesCloseTheConnection() = runTest {
        val session = FakeSession()
        val connection = KtorWebSocketConnection(session, peer)
        session.incoming.send(Frame.Text("hello"))
        assertNull(connection.receive())
        val close = assertIs<Frame.Close>(session.outgoing.receive())
        assertEquals(CloseReason.Codes.CANNOT_ACCEPT.code, close.readReason()?.code)
    }

    @Test
    fun theEndOfTheSessionIsTheEndOfTheConnection() = runTest {
        val session = FakeSession()
        val connection = KtorWebSocketConnection(session, peer)
        connection.close()
        assertEquals(CloseReason.Codes.NORMAL.code, (session.outgoing.receive() as Frame.Close).readReason()?.code)
        session.outgoing.close()
        connection.close()
        session.incoming.close()
        assertNull(connection.receive())
    }
}
