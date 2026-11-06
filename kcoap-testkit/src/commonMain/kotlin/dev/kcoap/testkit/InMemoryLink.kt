package dev.kcoap.testkit

import dev.kcoap.transport.CoapScheme
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.Connector
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.TransportKind

/**
 * One of the two ways a CoAP endpoint reaches a peer, in memory, so that one test runs over both and checks
 * that the exchanges behave the same: [Datagrams] on an [InMemoryNetwork] (RFC 7252), [Connections] on
 * [InMemoryConnections] (RFC 8323).
 *
 * [server] attaches what a server endpoint is built on, [client] what a client endpoint is, each a [Side] with
 * the arguments of the endpoint's constructor; [uri] is the server's base URI and [cut] ends every way between
 * the two at once.
 *
 * ```kotlin
 * val link = InMemoryLink.Connections(InMemoryConnections())
 * val side = link.server()
 * val endpoint = CoapEndpoint(backgroundScope, config, side.transport, side.connectors, side.listeners)
 * ```
 */
public sealed class InMemoryLink {
    /**
     * What one endpoint is built on: a datagram [transport], [connectors] and [listeners], as the CoAP
     * endpoint's constructor takes them, and how it addresses the other side ([peer]).
     */
    public class Side internal constructor(
        public val transport: DatagramTransport? = null,
        public val connectors: List<Connector> = emptyList(),
        public val listeners: List<ConnectionListener> = emptyList(),
        private val address: suspend (Endpoint) -> Endpoint = { it },
    ) {
        /**
         * The endpoint this side reaches [resolved] at, where [resolved] is what a URI of the other side names:
         * over [Datagrams], the session to that address; on [Connections], [resolved] itself.
         */
        public suspend fun peer(resolved: Endpoint): Endpoint = address(resolved)
    }

    /** The URI scheme of the server. */
    public abstract val scheme: CoapScheme

    /** `<scheme>://<host>:<port>` of the server; known once [server] attached it. */
    public abstract val uri: String

    /** Attaches the server side; once per link. */
    public abstract suspend fun server(): Side

    /** Attaches a client side; as often as the test needs clients. */
    public abstract suspend fun client(): Side

    /**
     * Ends every way between the sides at once: every session over [Datagrams] ends, as a DTLS layer reports
     * (RFC 7252 §9.1.1), and every connection breaks (RFC 8323 §3.4). What comes later opens new ones.
     */
    public abstract suspend fun cut()

    /**
     * Datagrams on [network], through [InMemorySessions] on both sides, so that [cut] has sessions to end: the
     * endpoints see each other as [Endpoint.Secure] peers whose DTLS layer authenticated [identity], by default
     * nobody, and messages go in datagrams of at most [maxDatagramSize] bytes.
     */
    public class Datagrams(
        public val network: InMemoryNetwork,
        private val maxDatagramSize: Int = InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE,
        private val identity: PeerIdentity = PeerIdentity.None,
    ) : InMemoryLink() {
        private val sides = mutableListOf<InMemorySessions>()
        private var serverAddress: Endpoint.Udp? = null

        override val scheme: CoapScheme get() = CoapScheme.COAP

        override val uri: String
            get() {
                val address = checkNotNull(serverAddress) { "The server side is not attached yet" }
                return "coap://${address.host}:${address.port}"
            }

        override suspend fun server(): Side {
            val sessions = attach()
            serverAddress = sessions.localEndpoint
            return Side(transport = sessions)
        }

        override suspend fun client(): Side {
            val sessions = attach()
            return Side(transport = sessions) { resolved -> if (resolved is Endpoint.Udp) sessions.sessionTo(resolved) else resolved }
        }

        override suspend fun cut() {
            sides.forEach { it.endAll(SessionEndReason.CLOSED_BY_PEER) }
        }

        private suspend fun attach(): InMemorySessions =
            InMemorySessions(network.attach(maxDatagramSize = maxDatagramSize), identity).also { sides += it }

        override fun toString(): String = "InMemoryLink.Datagrams"
    }

    /**
     * Connections of [scheme] on [connections] to a listener at 127.0.0.1:[port], whose TLS stand-in reports
     * [clientIdentity] for every client (see [InMemoryConnections.listen]).
     */
    public class Connections(
        public val connections: InMemoryConnections,
        override val scheme: CoapScheme = CoapScheme.COAP_TCP,
        public val port: Int = DEFAULT_PORT,
        private val clientIdentity: PeerIdentity = PeerIdentity.None,
    ) : InMemoryLink() {
        init {
            require(scheme.kind != TransportKind.DATAGRAM) { "$scheme is not carried on connections" }
        }

        override val uri: String get() = "${scheme.scheme}://127.0.0.1:$port"

        /** Listens at [port]. */
        override suspend fun server(): Side = Side(listeners = listOf(listen()))

        /** Listens at [port] for [scheme], with [clientIdentity]. */
        public suspend fun listen(): InMemoryConnectionListener = connections.listen(port, scheme, clientIdentity = clientIdentity)

        override suspend fun client(): Side = Side(connectors = listOf(connections.connector))

        override suspend fun cut() {
            connections.connections().forEach { it.reset() }
        }

        override fun toString(): String = "InMemoryLink.Connections(${scheme.scheme})"

        public companion object {
            /** Below 40000, where [InMemoryConnections] starts numbering its clients. */
            public const val DEFAULT_PORT: Int = 5683
        }
    }
}
