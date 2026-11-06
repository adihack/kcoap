package dev.kcoap.transport.ktor

import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * A UDP [DatagramTransport] on ktor-network, for `coap` on the JVM (and Android) and Apple platforms.
 *
 * Peers are reported as [Endpoint.Udp] with the address in [dev.kcoap.transport.IpLiteral] canonical form, never by host
 * name, so responses match requests by endpoint equality. Datagrams from addresses that are not IP
 * (none exist on UDP sockets) are dropped. [maxDatagramSize] is what the CoAP layer may send; RFC 7252
 * §4.6 recommends 1152 bytes when the path MTU is unknown, which the default follows. Received datagrams
 * may be larger.
 *
 * ```
 * val transport = KtorUdpTransport.bind(port = 5683)
 * ```
 */
public class KtorUdpTransport private constructor(
    private val socket: BoundDatagramSocket,
    private val selector: SelectorManager?,
    override val maxDatagramSize: Int,
    loggerFactory: LoggerFactory,
) : DatagramTransport {
    private val log = loggerFactory.logger(LogTags.TRANSPORT)

    /** The address and port the socket is bound to. */
    public val localEndpoint: Endpoint.Udp = endpointOf(socket.localAddress as InetSocketAddress)

    /**
     * @throws IllegalArgumentException for anything but an [Endpoint.Udp] or a datagram above
     *   [maxDatagramSize].
     */
    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        require(to is Endpoint.Udp) { "A UDP transport cannot reach $to" }
        require(datagram.size <= maxDatagramSize) { "Datagram of ${datagram.size} bytes exceeds $maxDatagramSize" }
        val packet = Buffer().apply { write(datagram) }
        socket.send(Datagram(packet, InetSocketAddress(to.host, to.port)))
        log.trace { "Sent ${datagram.size} bytes to $to" }
    }

    override suspend fun receive(): DatagramEvent {
        while (true) {
            val datagram = socket.receive()
            val bytes = datagram.packet.readByteArray()
            val address = datagram.address as? InetSocketAddress
            val from = address?.let(::endpointOf)
            if (from == null) {
                log.warn { "Dropped a datagram from the non-IP address ${datagram.address}" }
                continue
            }
            log.trace { "Received ${bytes.size} bytes from $from" }
            return DatagramEvent.Received(from, bytes)
        }
    }

    override suspend fun close() {
        socket.close()
        selector?.close()
        log.info { "Closed UDP socket $localEndpoint" }
    }

    override fun toString(): String = "KtorUdpTransport($localEndpoint)"

    public companion object {
        /** RFC 7252 §4.6: the message size to assume when nothing is known about the path. */
        public const val DEFAULT_MAX_DATAGRAM_SIZE: Int = 1152

        /**
         * Binds a UDP socket to [host] and [port] (0 picks a free port).
         *
         * @param selector the ktor selector to use; when null, the transport creates one on
         *   `Dispatchers.IO` and closes it with the transport.
         */
        public suspend fun bind(
            host: String = "0.0.0.0",
            port: Int = 0,
            maxDatagramSize: Int = DEFAULT_MAX_DATAGRAM_SIZE,
            selector: SelectorManager? = null,
            loggerFactory: LoggerFactory = LoggerFactory.Global,
        ): KtorUdpTransport {
            require(port in 0..65535) { "Port out of range: $port" }
            require(maxDatagramSize in MIN_DATAGRAM_SIZE..MAX_UDP_PAYLOAD) { "Datagram size out of range: $maxDatagramSize" }
            val owned = if (selector == null) SelectorManager(Dispatchers.IO) else null
            val socket = try {
                aSocket(selector ?: owned!!).udp().bind(InetSocketAddress(host, port))
            } catch (e: Throwable) {
                owned?.close()
                throw e
            }
            return KtorUdpTransport(socket, owned, maxDatagramSize, loggerFactory).also { transport ->
                loggerFactory.logger(LogTags.TRANSPORT).info { "Bound UDP socket ${transport.localEndpoint}" }
            }
        }

        /** The largest UDP payload over IPv4. */
        private const val MAX_UDP_PAYLOAD = 65507

        /** A CoAP header and a 16-byte block need at least this much. */
        private const val MIN_DATAGRAM_SIZE = 64

        /** Uses the raw address, never a reverse DNS lookup, and its canonical text. */
        private fun endpointOf(address: InetSocketAddress): Endpoint.Udp = Endpoint.Udp(canonicalHost(address), address.port)
    }
}
