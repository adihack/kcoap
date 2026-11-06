package dev.kcoap.coap

import dev.kcoap.coap.stack.CoapException
import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.PeerIdentity

/**
 * [ExchangeConformanceTest] on connections (RFC 8323), CoAP over TCP framing in pieces of 7 bytes, with both
 * sides announcing the default Max-Message-Size and so BERT (RFC 8323 §5.3.2, §6).
 */
class ConnectionLinkExchangeTest : ExchangeConformanceTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections, maxMessageSize: Int?, identity: PeerIdentity): InMemoryLink =
        InMemoryLink.Connections(connections, clientIdentity = identity)

    override val carriesBert: Boolean get() = true

    /** RFC 8323 §7.2: a notification is written and done, with nothing to acknowledge. */
    override val supersedes: Boolean get() = false

    /** RFC 8323 §3.1 has no Reset, so a client deregisters (§7.4). */
    override val hasReset: Boolean get() = false

    override val lostLinkReason: CoapException.Reason get() = CoapException.Reason.CONNECTION_CLOSED
}
