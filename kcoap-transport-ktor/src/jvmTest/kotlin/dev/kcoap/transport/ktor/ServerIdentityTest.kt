package dev.kcoap.transport.ktor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC 9525 §6: how a server certificate names the host a client asked for. */
class ServerIdentityTest {
    private val authority = TestCertificates.authority("kcoap test CA")

    /** RFC 9525 §6.3: case-insensitive ASCII, a trailing dot ignored, U-labels compared as A-labels. */
    @Test
    fun rfc9525Section6_3DnsNamesMatchExactly() {
        assertTrue(ServerIdentity.dnsNameMatches("coap.example.org", "coap.example.org"))
        assertTrue(ServerIdentity.dnsNameMatches("COAP.Example.ORG", "coap.example.org"))
        assertTrue(ServerIdentity.dnsNameMatches("coap.example.org.", "coap.example.org"))
        assertTrue(ServerIdentity.dnsNameMatches("xn--bcher-kva.example", "bücher.example"))
        assertFalse(ServerIdentity.dnsNameMatches("coap.example.org", "example.org"))
        assertFalse(ServerIdentity.dnsNameMatches("example.org", "coap.example.org"))
        assertFalse(ServerIdentity.dnsNameMatches("", "coap.example.org"))
        assertFalse(ServerIdentity.dnsNameMatches("coap.example.org", "a..b"), "no valid name")
    }

    /** RFC 9525 §6.3: a wildcard is the whole left-most label and matches exactly one label. */
    @Test
    fun rfc9525Section6_3AWildcardMatchesOneLeftMostLabel() {
        assertTrue(ServerIdentity.dnsNameMatches("*.example.org", "coap.example.org"))
        assertTrue(ServerIdentity.dnsNameMatches("*.Example.org", "COAP.example.org"))
        assertFalse(ServerIdentity.dnsNameMatches("*.example.org", "a.coap.example.org"), "one label only")
        assertFalse(ServerIdentity.dnsNameMatches("*.example.org", "example.org"), "not the parent itself")
        assertFalse(ServerIdentity.dnsNameMatches("*.example.org", ".example.org"))
        assertFalse(ServerIdentity.dnsNameMatches("c*.example.org", "coap.example.org"), "no partial wildcard")
        assertFalse(ServerIdentity.dnsNameMatches("coap.*.org", "coap.example.org"), "only the left-most label")
        assertFalse(ServerIdentity.dnsNameMatches("*.*.org", "coap.example.org"), "one wildcard")
        assertFalse(ServerIdentity.dnsNameMatches("*.org", "example.org"), "not right below the top-level domain")
        assertFalse(ServerIdentity.dnsNameMatches("*", "example"))
    }

    /** RFC 9525 §6.4: an IP literal matches an iPAddress entry with the same octets, in any textual form. */
    @Test
    fun rfc9525Section6_4IpAddressesMatchByOctets() {
        val v4 = TestCertificates.issue(authority, "server", ipAddresses = listOf("192.0.2.1"))
        assertTrue(ServerIdentity.isNamedBy(v4.certificate, "192.0.2.1"))
        assertFalse(ServerIdentity.isNamedBy(v4.certificate, "192.0.2.2"))
        val v6 = TestCertificates.issue(authority, "server", ipAddresses = listOf("2001:db8::1"))
        assertTrue(ServerIdentity.isNamedBy(v6.certificate, "2001:db8::1"))
        assertTrue(ServerIdentity.isNamedBy(v6.certificate, "[2001:DB8:0:0::1]"))
        assertFalse(ServerIdentity.isNamedBy(v6.certificate, "2001:db8::2"))
    }

    /** RFC 9525 §6.2, §6.4: names and addresses never stand in for each other, and the Common Name is no name (§2). */
    @Test
    fun namesAndAddressesAreKeptApartAndTheCommonNameIsIgnored() {
        val named = TestCertificates.issue(authority, "192.0.2.1", dnsNames = listOf("192.0.2.1", "coap.example.org"))
        assertFalse(ServerIdentity.isNamedBy(named.certificate, "192.0.2.1"))
        assertTrue(ServerIdentity.isNamedBy(named.certificate, "coap.example.org"))
        val addressed = TestCertificates.issue(authority, "coap.example.org", ipAddresses = listOf("192.0.2.1"))
        assertFalse(ServerIdentity.isNamedBy(addressed.certificate, "coap.example.org"))
        val common = TestCertificates.issue(authority, "coap.example.org")
        assertFalse(ServerIdentity.isNamedBy(common.certificate, "coap.example.org"), "no subjectAltName at all")
    }
}
