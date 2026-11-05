package dev.kcoap.oscore

import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork

/** [OscoreOverLinksTest] on connections (RFC 8323), CoAP over TCP framing in pieces of 7 bytes. */
class OscoreOverConnectionsTest : OscoreOverLinksTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections): InMemoryLink = InMemoryLink.Connections(connections)
}
