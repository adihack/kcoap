package dev.kcoap.oscore

import dev.kcoap.testkit.InMemoryConnections
import dev.kcoap.testkit.InMemoryLink
import dev.kcoap.testkit.InMemoryNetwork

/** [OscoreOverLinksTest] over datagrams (RFC 7252), through kcoap-testkit's stand-in for DTLS sessions. */
class OscoreOverDatagramsTest : OscoreOverLinksTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections): InMemoryLink = InMemoryLink.Datagrams(network)
}
