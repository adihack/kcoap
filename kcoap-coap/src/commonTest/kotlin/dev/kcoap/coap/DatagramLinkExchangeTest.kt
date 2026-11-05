package dev.kcoap.coap

import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.PeerIdentity

/**
 * [ExchangeConformanceTest] over datagrams (RFC 7252), through a stand-in for DTLS sessions
 * ([dev.kcoap.testkit.InMemorySessions]), so that a lost link is an ended session (RFC 7252 §9.1.1).
 */
class DatagramLinkExchangeTest : ExchangeConformanceTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections, maxMessageSize: Int?, identity: PeerIdentity): InMemoryLink =
        InMemoryLink.Datagrams(network, maxMessageSize ?: InMemoryNetwork.DEFAULT_MAX_DATAGRAM_SIZE, identity)

    /** RFC 7959 §2.2: SZX 7 is reserved over datagrams. */
    override val carriesBert: Boolean get() = false

    /** RFC 7641 §4.5.2: a Confirmable notification awaits its ACK and is superseded meanwhile. */
    override val supersedes: Boolean get() = true

    /** RFC 7252 §4.2, RFC 7641 §3.6. */
    override val hasReset: Boolean get() = true

    override val lostLinkReason: CoapException.Reason get() = CoapException.Reason.SESSION_ENDED
}
