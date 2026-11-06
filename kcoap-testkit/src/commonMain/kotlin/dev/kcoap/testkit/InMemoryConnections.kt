package dev.kcoap.testkit

import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.MessageConnection
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.TransportKind
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory connections for tests of CoAP over reliable transports (RFC 8323): [listen] binds a listener
 * at an address, and [connector] connects to it, with a byte stream for `coap+tcp` and `coaps+tcp` or a
 * message stream for `coap+ws` and `coaps+ws`. Delivery is immediate, ordered and lossless, as the
 * transports promise; what a test needs beyond that it injects: [InMemoryConnection.inject] hands raw
 * bytes to a side as if its peer had sent them, [InMemoryConnection.reset] breaks a connection.
 *
 * Byte streams deliver every write in pieces of at most [chunkSize] bytes, so stream reassembly is
 * exercised. [tap] sees every write.
 *
 * ```
 * val connections = InMemoryConnections()
 * val server = CoapServer(listOf(connections.listen(5683)), backgroundScope) { ... }
 * val client = CoapClient(listOf(connections.connector), backgroundScope)
 * client.get("coap+tcp://127.0.0.1:5683/hello")
 * ```
 *
 * TLS is not simulated; a listener only reports the identities it was given. The connector lists `coaps+tcp` and
 * `coaps+ws` as a connector that runs TLS does, so a CoAP client sends such URIs to it (RFC 8323 §9): it stands in
 * for TLS in tests and must never carry real traffic.
 */
public class InMemoryConnections(private val chunkSize: Int = Int.MAX_VALUE) {
    private val lock = Mutex()
    /** By the address clients reach them at, so that a listener is found by endpoint equality (RFC 7252 §6.6). */
    private val listeners = HashMap<Endpoint, InMemoryConnectionListener>()
    private val opened = mutableListOf<InMemoryConnection>()
    private var nextClientPort = FIRST_CLIENT_PORT

    init {
        require(chunkSize > 0) { "Chunk size must be positive: $chunkSize" }
    }

    /** Sees every write: the address of the side that wrote, its peer, and the bytes. Called on the writer's coroutine. */
    public var tap: (from: Endpoint, to: Endpoint, bytes: ByteArray) -> Unit = { _, _, _ -> }

    /** Every connection opened so far, client side then accepted side of each, in the order they were opened. */
    public suspend fun connections(): List<InMemoryConnection> = lock.withLock { opened.toList() }

    /**
     * A listener for [scheme] at [host] and [port]. Clients reach it at `Endpoint.Tcp(host, port)` or
     * `Endpoint.WebSocket.of(host, port)`, which is its [InMemoryConnectionListener.localEndpoint], and at every
     * endpoint equal to that, however its host is written (RFC 7252 §6.6: `Server` as `server`, `[2001:DB8::1]` as
     * `2001:db8::1`, a `ws` URI without a port at port 80); a WebSocket client reaches it at any path. It sees each
     * client at a port of its own. Accepted connections report [clientIdentity] as the client's identity, the
     * client's side reports [serverIdentity].
     *
     * @throws IllegalArgumentException if a listener has an equal address already, or [scheme] is not a reliable one.
     */
    public suspend fun listen(
        port: Int,
        scheme: CoapScheme = CoapScheme.COAP_TCP,
        host: String = LOOPBACK,
        clientIdentity: PeerIdentity = PeerIdentity.None,
        serverIdentity: PeerIdentity = PeerIdentity.None,
    ): InMemoryConnectionListener = lock.withLock {
        require(scheme.kind != TransportKind.DATAGRAM) { "$scheme is not a reliable transport; use InMemoryNetwork" }
        val address = endpointOf(scheme, host, port)
        require(address !in listeners) { "Address in use: ${scheme.scheme} $host:$port" }
        InMemoryConnectionListener(this, scheme, address, clientIdentity, serverIdentity)
            .also { listeners[address] = it }
    }

    /** Opens connections to the listeners, for every reliable scheme. */
    public val connector: Connector = object : Connector {
        override val schemes: Set<CoapScheme> =
            setOf(CoapScheme.COAP_TCP, CoapScheme.COAPS_TCP, CoapScheme.COAP_WS, CoapScheme.COAPS_WS)

        override suspend fun connect(peer: Endpoint): Connection = this@InMemoryConnections.connect(peer)

        override fun toString(): String = "InMemoryConnections.connector"
    }

    internal suspend fun unbind(listener: InMemoryConnectionListener) {
        lock.withLock { listeners.entries.removeAll { it.value === listener } }
    }

    private suspend fun connect(peer: Endpoint): Connection {
        val address = listenerAddress(peer)
        val (client, accepted, listener) = lock.withLock {
            val listener = listeners[address] ?: throw InMemoryConnectionRefused(peer)
            val scheme = listener.scheme
            val clientAddress = endpointOf(scheme, LOOPBACK, nextClientPort++)
            val client = Pipe(this, remote = peer, identity = listener.serverIdentity, isAccepted = false)
            val accepted = Pipe(this, remote = clientAddress, identity = listener.clientIdentity, isAccepted = true)
            client.other = accepted
            accepted.other = client
            val sides = if (scheme.kind == TransportKind.MESSAGE_STREAM) {
                InMemoryMessageStream(client) to InMemoryMessageStream(accepted)
            } else {
                InMemoryByteStream(client) to InMemoryByteStream(accepted)
            }
            opened += sides.first
            opened += sides.second
            Triple(sides.first, sides.second, listener)
        }
        listener.offer(accepted)
        return client
    }

    internal fun chunks(bytes: ByteArray): List<ByteArray> =
        if (bytes.size <= chunkSize) listOf(bytes) else bytes.toList().chunked(chunkSize).map { it.toByteArray() }

    /**
     * The address of the listener [peer] reaches: a TCP peer itself, a WebSocket the `/.well-known/coap` endpoint of
     * its URI's scheme and authority, whatever its path. Equality then compares them as RFC 7252 §6.6 compares URIs.
     */
    private fun listenerAddress(peer: Endpoint): Endpoint {
        when (peer) {
            is Endpoint.Tcp -> return peer
            is Endpoint.WebSocket -> {
                val uri = peer.uri
                val authorityStart = uri.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return peer
                val authorityEnd = uri.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: uri.length
                return Endpoint.WebSocket(uri.substring(0, authorityEnd) + CoapScheme.WEBSOCKET_PATH)
            }
            else -> throw IllegalArgumentException("$peer is not reached over a reliable transport")
        }
    }

    private fun endpointOf(scheme: CoapScheme, host: String, port: Int): Endpoint = when (scheme.kind) {
        TransportKind.MESSAGE_STREAM -> Endpoint.WebSocket.of(host, port, usesTls = scheme == CoapScheme.COAPS_WS)
        else -> Endpoint.Tcp(host, port, usesTls = scheme == CoapScheme.COAPS_TCP)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val FIRST_CLIENT_PORT = 40000
    }
}

/** Thrown by [InMemoryConnections.connector] when nobody listens at [peer]. */
public class InMemoryConnectionRefused(public val peer: Endpoint) : Exception("Connection refused by $peer")

/** Thrown when reading from a connection that [InMemoryConnection.reset] broke. */
public class InMemoryConnectionReset(public val peer: Endpoint) : Exception("Connection to $peer reset")

/** Accepts the connections [InMemoryConnections.connector] opens to its address. */
public class InMemoryConnectionListener internal constructor(
    private val connections: InMemoryConnections,
    override val scheme: CoapScheme,
    /** Where clients connect to. */
    public val localEndpoint: Endpoint,
    internal val clientIdentity: PeerIdentity,
    internal val serverIdentity: PeerIdentity,
) : ConnectionListener {
    private val pending = Channel<Connection>(Channel.UNLIMITED)

    override suspend fun accept(): Connection? = pending.receiveCatching().getOrNull()

    /** Stops accepting; connections that were not accepted yet are closed. */
    override suspend fun close() {
        pending.close()
        while (true) pending.tryReceive().getOrNull()?.close() ?: break
        connections.unbind(this)
    }

    internal fun offer(connection: Connection) {
        pending.trySend(connection)
    }

    override fun toString(): String = "InMemoryConnectionListener($localEndpoint)"
}

/**
 * One side of an in-memory connection, for fault injection. Closing either side ends both: the other
 * reads what is already on its way, then the end of the stream.
 */
public sealed interface InMemoryConnection {
    /** The other side's address. */
    public val remote: Endpoint

    /** Whether either side closed or reset the connection. */
    public val isClosed: Boolean

    /** Whether this is the side a listener accepted. */
    public val isAccepted: Boolean

    /** Hands [bytes] to this side as if its peer had written them, for malformed or hand-made messages. */
    public fun inject(bytes: ByteArray)

    /** Breaks the connection at once in both directions; reading then fails with [InMemoryConnectionReset]. */
    public fun reset()
}

/** One direction's buffer and the shared state of a side. */
internal class Pipe(
    private val connections: InMemoryConnections,
    val remote: Endpoint,
    val identity: PeerIdentity,
    val isAccepted: Boolean,
) {
    lateinit var other: Pipe
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var isReset = false

    val isClosed: Boolean get() = inbox.isClosedForSend

    fun inject(bytes: ByteArray) {
        inbox.trySend(bytes)
    }

    fun close() {
        inbox.close()
        other.inbox.close()
    }

    fun reset() {
        isReset = true
        other.isReset = true
        close()
    }

    /** The next delivery, or null at the end. */
    suspend fun take(): ByteArray? {
        if (isReset) throw InMemoryConnectionReset(remote)
        val next = inbox.receiveCatching().getOrNull()
        if (isReset) throw InMemoryConnectionReset(remote)
        return next
    }

    fun deliver(bytes: ByteArray, chunked: Boolean) {
        check(!other.inbox.isClosedForSend) { "Connection to $remote is closed" }
        connections.tap(other.remote, remote, bytes)
        val copy = bytes.copyOf()
        for (piece in if (chunked) connections.chunks(copy) else listOf(copy)) other.inbox.trySend(piece)
    }
}

/** A byte stream side; writes arrive in pieces of at most the chunk size. */
public class InMemoryByteStream internal constructor(private val pipe: Pipe) : InMemoryConnection, ByteStreamConnection {
    private var pending: ByteArray = ByteArray(0)
    private var pendingOffset = 0

    override val remote: Endpoint get() = pipe.remote
    override val peerIdentity: PeerIdentity get() = pipe.identity
    override val isClosed: Boolean get() = pipe.isClosed
    override val isAccepted: Boolean get() = pipe.isAccepted

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (pendingOffset == pending.size) {
            pending = pipe.take() ?: return -1
            pendingOffset = 0
        }
        val count = minOf(length, pending.size - pendingOffset)
        pending.copyInto(into, offset, pendingOffset, pendingOffset + count)
        pendingOffset += count
        return count
    }

    /** @throws IllegalStateException once the connection is closed. */
    override suspend fun write(bytes: ByteArray) {
        pipe.deliver(bytes, chunked = true)
    }

    override fun inject(bytes: ByteArray) {
        pipe.inject(bytes)
    }

    override fun reset() {
        pipe.reset()
    }

    override suspend fun close() {
        pipe.close()
    }

    override fun toString(): String = "InMemoryByteStream(to $remote${if (isAccepted) ", accepted" else ""})"
}

/** A message stream side; every message arrives whole, as a WebSocket frame. */
public class InMemoryMessageStream internal constructor(private val pipe: Pipe) : InMemoryConnection, MessageConnection {
    override val remote: Endpoint get() = pipe.remote
    override val peerIdentity: PeerIdentity get() = pipe.identity
    override val isClosed: Boolean get() = pipe.isClosed
    override val isAccepted: Boolean get() = pipe.isAccepted

    /** @throws IllegalStateException once the connection is closed. */
    override suspend fun send(message: ByteArray) {
        pipe.deliver(message, chunked = false)
    }

    override suspend fun receive(): ByteArray? = pipe.take()

    override fun inject(bytes: ByteArray) {
        pipe.inject(bytes)
    }

    override fun reset() {
        pipe.reset()
    }

    override suspend fun close() {
        pipe.close()
    }

    override fun toString(): String = "InMemoryMessageStream(to $remote${if (isAccepted) ", accepted" else ""})"
}
