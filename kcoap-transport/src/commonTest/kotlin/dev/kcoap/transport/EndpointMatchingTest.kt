package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Endpoint equality is the RFCs' matching rule: what RFC 7252 §5.3.2 and §4.4 match replies by over datagrams,
 * and the URI comparison of RFC 7252 §6.6 that RFC 8323 §8 adopts for TCP and WebSocket peers. ([EndpointTest]
 * has the DTLS rule of [Endpoint.Secure].)
 */
class EndpointMatchingTest {
    private fun assertSamePeer(a: Endpoint, b: Endpoint) {
        assertEquals(a, b)
        assertEquals(b, a)
        assertEquals(a.hashCode(), b.hashCode(), "$a and $b must hash alike")
    }

    private fun assertDifferentPeers(a: Endpoint, b: Endpoint) {
        assertNotEquals(a, b)
        assertNotEquals(b, a)
    }

    /** RFC 7252 §5.3.2: a response comes from the endpoint the request went to, an address and a port. */
    @Test
    fun rfc7252Section5_3_2UdpPeersAreAnAddressAndAPort() {
        assertSamePeer(Endpoint.Udp("192.0.2.1", 5683), Endpoint.Udp("192.0.2.1", 5683))
        assertDifferentPeers(Endpoint.Udp("192.0.2.1", 5683), Endpoint.Udp("192.0.2.1", 5684))
        assertDifferentPeers(Endpoint.Udp("192.0.2.1", 5683), Endpoint.Udp("192.0.2.2", 5683))
    }

    /** RFC 7252 §6.6: hosts compare case-insensitively, with IP literals in their RFC 5952 form. */
    @Test
    fun rfc7252Section6_6UdpAddressesCompareInTheirCanonicalForm() {
        val peer = Endpoint.Udp("2001:db8::1", 5683)
        for (text in listOf("2001:DB8::1", "2001:0db8:0:0:0:0:0:1", "[2001:db8::1]", "2001:db8:0::0:1")) {
            val same = Endpoint.Udp(text, 5683)
            assertSamePeer(peer, same)
            assertEquals("2001:db8::1", same.host, "the host is kept in canonical form")
        }
        assertSamePeer(Endpoint.Udp("192.0.2.1", 5683), Endpoint.Udp("::ffff:192.0.2.1", 5683))
        assertEquals("192.0.2.1", Endpoint.Udp("::FFFF:192.0.2.1", 5683).host, "as a dual-stack socket reports it")
        assertEquals("Udp(host=2001:db8::1, port=5683)", Endpoint.Udp("2001:DB8:0::1", 5683).toString())
    }

    @Test
    fun udpTextThatIsNoIpLiteralComparesCaseInsensitively() {
        assertSamePeer(Endpoint.Udp("Peer-A", 1), Endpoint.Udp("peer-a", 1))
        assertEquals("peer-a", Endpoint.Udp("Peer-A", 1).host)
    }

    /** RFC 6874: a zone follows the canonical address as given, and an address in another zone is another peer. */
    @Test
    fun udpZonesStayPartOfTheAddress() {
        assertEquals("fe80::1%Eth0", Endpoint.Udp("FE80::0:1%Eth0", 1).host)
        assertSamePeer(Endpoint.Udp("[fe80::1%eth0]", 1), Endpoint.Udp("FE80::1%eth0", 1))
        assertDifferentPeers(Endpoint.Udp("fe80::1%eth0", 1), Endpoint.Udp("fe80::1%eth1", 1))
        assertDifferentPeers(Endpoint.Udp("fe80::1%eth0", 1), Endpoint.Udp("fe80::1", 1))
    }

    /** RFC 8323 §8.2: `coaps+tcp` and `coap+tcp` are distinct namespaces, even on the same host and port. */
    @Test
    fun rfc8323Section8_2TlsAndPlainTcpAreDifferentPeers() {
        val plain = Endpoint.Tcp("192.0.2.1", 5684)
        val tls = Endpoint.Tcp("192.0.2.1", 5684, usesTls = true)
        assertDifferentPeers(plain, tls)
        assertSamePeer(tls, Endpoint.Tcp("192.0.2.1", 5684, usesTls = true))
        assertSamePeer(plain, Endpoint.Tcp("192.0.2.1", 5684, usesTls = false))
    }

    /** RFC 8323 §8.1 compares `coap+tcp` URIs as RFC 7252 §6.6 does: hosts case-insensitively, names unresolved. */
    @Test
    fun rfc8323Section8_1TcpHostsCompareAsUriHosts() {
        assertSamePeer(Endpoint.Tcp("Example.COM", 5683), Endpoint.Tcp("example.com", 5683))
        assertEquals("example.com", Endpoint.Tcp("Example.COM", 5683).host)
        assertSamePeer(Endpoint.Tcp("[2001:DB8::1]", 5683), Endpoint.Tcp("2001:db8::1", 5683))
        assertDifferentPeers(Endpoint.Tcp("localhost", 5683), Endpoint.Tcp("127.0.0.1", 5683))
        assertDifferentPeers(Endpoint.Tcp("example.com", 5683), Endpoint.Tcp("example.com", 5684))
        assertEquals("Tcp(host=example.com, port=5683, usesTls=true)", Endpoint.Tcp("EXAMPLE.com", 5683, true).toString())
    }

    /**
     * RFC 3986 §3.2.2: text with a signed group or a leading zero in an octet is no IP literal but a name, so it is
     * not normalized into an address; RFC 3986 §7.4: a resolver may read `010.0.0.1` as octal, another host.
     */
    @Test
    fun rfc3986Section3_2_2TextThatIsNoStrictLiteralComparesAsAName() {
        assertEquals("::-1", Endpoint.Tcp("::-1", 5683).host)
        assertDifferentPeers(Endpoint.Tcp("::-1", 5683), Endpoint.Tcp("::ffff", 5683))
        assertDifferentPeers(Endpoint.Tcp("::+1", 5683), Endpoint.Tcp("::1", 5683))
        assertEquals("010.0.0.1", Endpoint.Tcp("010.0.0.1", 5683).host)
        assertDifferentPeers(Endpoint.Tcp("010.0.0.1", 5683), Endpoint.Tcp("10.0.0.1", 5683))
        assertDifferentPeers(Endpoint.Udp("010.0.0.1", 5683), Endpoint.Udp("10.0.0.1", 5683))
        assertDifferentPeers(Endpoint.Udp("::-1", 5683), Endpoint.Udp("::ffff", 5683))
        assertDifferentPeers(
            Endpoint.WebSocket("ws://010.0.0.1/.well-known/coap"),
            Endpoint.WebSocket("ws://10.0.0.1/.well-known/coap"),
        )
        assertSamePeer(Endpoint.WebSocket("ws://[::-1]/.well-known/coap"), Endpoint.WebSocket("ws://[::-1]:80/.well-known/coap"))
        assertDifferentPeers(Endpoint.WebSocket("ws://[::+1]/.well-known/coap"), Endpoint.WebSocket.of("::1", 80))
    }

    /** RFC 8323 §8.3 and §8.4 with RFC 6455 §3: a missing port is 80 for `ws` and 443 for `wss`, an empty path `/`. */
    @Test
    fun rfc8323Section8_3WebSocketUrisCompareWithTheirDefaultPorts() {
        assertSamePeer(Endpoint.WebSocket("ws://example.com/.well-known/coap"), Endpoint.WebSocket.of("example.com", 80))
        assertSamePeer(
            Endpoint.WebSocket("wss://example.com/.well-known/coap"),
            Endpoint.WebSocket.of("example.com", 443, usesTls = true),
        )
        assertSamePeer(
            Endpoint.WebSocket("ws://example.com:/.well-known/coap"),
            Endpoint.WebSocket("ws://example.com:80/.well-known/coap"),
        )
        assertSamePeer(Endpoint.WebSocket("ws://192.0.2.1:8080"), Endpoint.WebSocket("ws://192.0.2.1:8080/"))
        assertSamePeer(Endpoint.WebSocket("wss://[2001:db8::1]/.well-known/coap"), Endpoint.WebSocket.of("2001:db8::1", 443, true))
        assertDifferentPeers(Endpoint.WebSocket("ws://example.com:8080/.well-known/coap"), Endpoint.WebSocket.of("example.com", 80))
        assertDifferentPeers(Endpoint.WebSocket("wss://example.com/.well-known/coap"), Endpoint.WebSocket.of("example.com", 80, usesTls = true))
    }

    /** RFC 8323 §8.4: `coaps+ws` and `coap+ws` are distinct, even on the same host and port. */
    @Test
    fun rfc8323Section8_4SecureAndPlainWebSocketsAreDifferentPeers() {
        val plain = Endpoint.WebSocket.of("example.com", 443)
        val secure = Endpoint.WebSocket.of("example.com", 443, usesTls = true)
        assertDifferentPeers(plain, secure)
        assertFalse(plain.usesTls)
        assertTrue(secure.usesTls)
    }

    /** RFC 3986 §3.2.2: an IPv6 literal in a URI is bracketed once, whether the host is given bracketed or not. */
    @Test
    fun aWebSocketOfABracketedLiteralIsBracketedOnce() {
        val bracketed = Endpoint.WebSocket.of("[2001:db8::1]", 443, usesTls = true)
        assertEquals("wss://[2001:db8::1]:443/.well-known/coap", bracketed.uri)
        assertSamePeer(bracketed, Endpoint.WebSocket.of("2001:db8::1", 443, usesTls = true))
        assertSamePeer(Endpoint.WebSocket.of("[2001:DB8::1]", 443, usesTls = true), bracketed)
        assertEquals("ws://example.com:80/.well-known/coap", Endpoint.WebSocket.of("example.com", 80).uri)
    }

    /**
     * RFC 7252 §6.6: the scheme and the host compare case-insensitively, IP literals in RFC 5952 form, the path
     * case-sensitively, with unreserved characters not percent-encoded and other percent-encodings in upper case.
     */
    @Test
    fun rfc7252Section6_6WebSocketUrisCompareInNormalForm() {
        val wellKnown = Endpoint.WebSocket("wss://example.com/.well-known/coap")
        assertSamePeer(Endpoint.WebSocket("WSS://Example.COM:443/.well-known/coap"), wellKnown)
        assertSamePeer(Endpoint.WebSocket("ws://[2001:DB8:0::1]:80/.well-known/coap"), Endpoint.WebSocket.of("2001:db8::1", 80))
        assertSamePeer(Endpoint.WebSocket("ws://h/%2Ewell-known/co%61p"), Endpoint.WebSocket("ws://h/.well-known/coap"))
        assertSamePeer(Endpoint.WebSocket("ws://h/a%2fb"), Endpoint.WebSocket("ws://h/a%2Fb"))
        assertDifferentPeers(Endpoint.WebSocket("ws://h/a%2Fb"), Endpoint.WebSocket("ws://h/a/b"))
        assertDifferentPeers(Endpoint.WebSocket("ws://h/.Well-Known/coap"), Endpoint.WebSocket("ws://h/.well-known/coap"))
        assertSamePeer(Endpoint.WebSocket("ws://h?x=1"), Endpoint.WebSocket("ws://h/?x=1"))
        assertDifferentPeers(Endpoint.WebSocket("ws://h/?x=1"), Endpoint.WebSocket("ws://h/?x=2"))
        assertSamePeer(Endpoint.WebSocket("ws://h/100%"), Endpoint.WebSocket("ws://h/100%"))
        assertSamePeer(Endpoint.WebSocket("ws://h/%zz"), Endpoint.WebSocket("ws://h/%zz"))
        assertDifferentPeers(Endpoint.WebSocket("ws://h/100%"), Endpoint.WebSocket("ws://h/100%25"))
    }

    @Test
    fun webSocketUrisAreKeptAsGiven() {
        val given = Endpoint.WebSocket("WSS://Example.COM/%2Ewell-known/coap")
        assertEquals("WSS://Example.COM/%2Ewell-known/coap", given.uri)
        assertEquals("WebSocket(uri=WSS://Example.COM/%2Ewell-known/coap)", given.toString())
        assertTrue(given.usesTls)
    }

    @Test
    fun textThatIsNoWebSocketUriComparesExactly() {
        val notWebSocketUris = listOf(
            "example.com/.well-known/coap",
            "http://example.com/",
            "ws://user@example.com/",
            "ws://[::1/",
            "ws://[::1]x/",
            "ws://h:port/",
            "ws://h:70000/",
            "ws://h:123456/",
            "ws:///.well-known/coap",
        )
        for (text in notWebSocketUris) {
            assertSamePeer(Endpoint.WebSocket(text), Endpoint.WebSocket(text))
            assertDifferentPeers(Endpoint.WebSocket(text), Endpoint.WebSocket(text.uppercase()))
        }
    }

    /** Link-specific addresses have no normal form in any RFC, so they compare exactly. */
    @Test
    fun opaqueAddressesCompareExactly() {
        assertSamePeer(Endpoint.Opaque("ble", "aa:bb"), Endpoint.Opaque("ble", "aa:bb"))
        assertDifferentPeers(Endpoint.Opaque("ble", "aa:bb"), Endpoint.Opaque("ble", "AA:BB"))
        assertDifferentPeers(Endpoint.Opaque("ble", "aa:bb"), Endpoint.Opaque("lora", "aa:bb"))
        assertEquals("Opaque(link=ble, address=aa:bb)", Endpoint.Opaque("ble", "aa:bb").toString())
    }

    /** A UDP, a TCP, a TLS, a WebSocket and a DTLS peer on one host and port are five different peers. */
    @Test
    fun variantsNeverMatchOneAnother() {
        val udp = Endpoint.Udp("192.0.2.1", 5683)
        val peers = listOf(
            udp,
            Endpoint.Tcp("192.0.2.1", 5683),
            Endpoint.Tcp("192.0.2.1", 5683, usesTls = true),
            Endpoint.WebSocket.of("192.0.2.1", 5683),
            Endpoint.Opaque("udp", "192.0.2.1:5683"),
            Endpoint.Secure(SessionId(1), 0, udp, PeerIdentity.None),
        )
        for (a in peers) {
            assertEquals(a, a)
            for (b in peers) if (a !== b) assertNotEquals(a, b)
        }
    }

    /** The messaging layer keys its state by endpoint: an equal endpoint, however written, finds the entry. */
    @Test
    fun equalEndpointsFindTheSameState() {
        val state = hashMapOf<Endpoint, String>(
            Endpoint.Udp("2001:db8::1", 5683) to "udp",
            Endpoint.Tcp("example.com", 5684, usesTls = true) to "tls",
            Endpoint.WebSocket.of("example.com", 80) to "ws",
        )
        assertEquals("udp", state[Endpoint.Udp("2001:DB8:0:0::1", 5683)])
        assertEquals("tls", state[Endpoint.Tcp("EXAMPLE.COM", 5684, usesTls = true)])
        assertEquals(null, state[Endpoint.Tcp("example.com", 5684)])
        assertEquals("ws", state[Endpoint.WebSocket("WS://EXAMPLE.com/.well-known/coap")])
    }
}
