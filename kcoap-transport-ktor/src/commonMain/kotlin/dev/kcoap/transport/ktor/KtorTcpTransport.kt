package dev.kcoap.transport.ktor

import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.ByteStreamConnection
import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.Connection
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.IpLiteral
import dev.kcoap.transport.PeerIdentity
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opens TCP connections for `coap+tcp` (RFC 8323 §8.1) on ktor-network, on every target ktor-network
 * supports. The connection is a plain byte stream; the CoAP endpoint does the framing, the CSM exchange
 * and everything else in common code.
 *
 * ```
 * val client = CoapClient(listOf(KtorTcpConnector()), scope)
 * client.get("coap+tcp://192.0.2.1/sensors/temp")
 * ```
 *
 * @param selector the ktor selector to use; when null, the connector creates one on `Dispatchers.IO` and
 *   closes it in [close].
 */
public class KtorTcpConnector(
    selector: SelectorManager? = null,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) : Connector {
    private val log = loggerFactory.logger(LogTags.TRANSPORT)
    private val owned = if (selector == null) SelectorManager(Dispatchers.IO) else null
    private val selector: SelectorManager = selector ?: owned!!

    override val schemes: Set<CoapScheme> = setOf(CoapScheme.COAP_TCP)

    /** @throws IllegalArgumentException for anything but a plain (non-TLS) [Endpoint.Tcp]. */
    override suspend fun connect(peer: Endpoint): Connection {
        require(peer is Endpoint.Tcp && !peer.usesTls) { "A plain TCP connector cannot reach $peer" }
        // Small messages, often a request at a time: Nagle's algorithm would only add latency.
        val socket = aSocket(selector).tcp().connect(peer.host, peer.port) { noDelay = true }
        log.debug { "Connected to $peer from ${socket.localAddress}" }
        return KtorTcpConnection(socket, peer, PeerIdentity.None)
    }

    override suspend fun close() {
        owned?.close()
    }

    override fun toString(): String = "KtorTcpConnector"
}

/**
 * Accepts TCP connections for `coap+tcp` (RFC 8323 §8.1) on ktor-network. Each accepted peer is an
 * [Endpoint.Tcp] with its address in [IpLiteral] form and its own port.
 *
 * ```
 * val server = CoapServer(listOf(KtorTcpListener.bind(port = 5683)), scope) { ... }
 * ```
 */
public class KtorTcpListener private constructor(
    private val server: ServerSocket,
    private val owned: SelectorManager?,
    loggerFactory: LoggerFactory,
) : ConnectionListener {
    private val log = loggerFactory.logger(LogTags.TRANSPORT)

    @Volatile
    private var isClosed = false

    override val scheme: CoapScheme get() = CoapScheme.COAP_TCP

    /** The address and port the listener is bound to. */
    public val localEndpoint: Endpoint.Tcp = (server.localAddress as InetSocketAddress).let { Endpoint.Tcp(canonicalHost(it), it.port) }

    override suspend fun accept(): Connection? {
        val socket = try {
            server.accept()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isClosed || server.isClosed) return null
            throw e
        }
        val address = socket.remoteAddress as InetSocketAddress
        val peer = Endpoint.Tcp(canonicalHost(address), address.port)
        log.debug { "Accepted a connection from $peer" }
        return KtorTcpConnection(socket, peer, PeerIdentity.None)
    }

    override suspend fun close() {
        if (isClosed) return
        isClosed = true
        server.close()
        owned?.close()
        log.info { "Closed TCP listener $localEndpoint" }
    }

    override fun toString(): String = "KtorTcpListener($localEndpoint)"

    public companion object {
        /**
         * Binds a TCP listener to [host] and [port] (0 picks a free one; RFC 8323 §8.1 uses 5683 by
         * default, the same number as UDP).
         *
         * @param selector the ktor selector to use; when null, the listener creates one on `Dispatchers.IO`
         *   and closes it with the listener.
         */
        public suspend fun bind(
            host: String = "0.0.0.0",
            port: Int = 0,
            selector: SelectorManager? = null,
            loggerFactory: LoggerFactory = LoggerFactory.Global,
        ): KtorTcpListener {
            require(port in 0..65535) { "Port out of range: $port" }
            val owned = if (selector == null) SelectorManager(Dispatchers.IO) else null
            val server = try {
                aSocket(selector ?: owned!!).tcp().bind(host, port)
            } catch (e: Throwable) {
                owned?.close()
                throw e
            }
            return KtorTcpListener(server, owned, loggerFactory).also { listener ->
                loggerFactory.logger(LogTags.TRANSPORT).info { "Listening for TCP on ${listener.localEndpoint}" }
            }
        }
    }
}

/** A TCP socket as a [ByteStreamConnection]: reads what has arrived, writes and flushes every message. */
internal class KtorTcpConnection(
    private val socket: Socket,
    override val remote: Endpoint,
    override val peerIdentity: PeerIdentity,
) : ByteStreamConnection {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = false)
    private val writeLock = Mutex()

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = input.readAvailable(into, offset, length)

    override suspend fun write(bytes: ByteArray) {
        writeLock.withLock {
            output.writeFully(bytes)
            output.flush()
        }
    }

    override suspend fun close() {
        try {
            output.flushAndClose()
        } catch (e: Exception) {
            // The peer may have gone already; the socket closes anyway.
        }
        socket.close()
    }

    override fun toString(): String = "KtorTcpConnection($remote)"
}

/** The canonical text of a socket address's IP, never a name from a reverse lookup. */
internal fun canonicalHost(address: InetSocketAddress): String {
    val raw = address.resolveAddress()
    return if (raw != null) IpLiteral.format(raw) else IpLiteral.canonical(address.hostname) ?: address.hostname
}
