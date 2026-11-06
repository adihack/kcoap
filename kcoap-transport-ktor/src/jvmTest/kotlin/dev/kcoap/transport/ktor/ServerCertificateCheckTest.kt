package dev.kcoap.transport.ktor

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trust manager behind every KtorTlsConnector handshake, on its own. */
class ServerCertificateCheckTest {
    private val authority = TestCertificates.authority("kcoap test CA")
    private val trust = TlsTrust.Anchors(listOf(authority.certificate)).trustManager()

    @Test
    fun aChainThatPassesEveryCheckIsKeptAsTheValidatedPath() {
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org"))
        val check = ServerCertificateCheck(trust, "coap.example.org", null)
        assertNull(check.validatedChain)
        check.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        assertEquals(listOf(leaf.certificate, authority.certificate), check.validatedChain)
        assertContentEquals(arrayOf(authority.certificate), check.acceptedIssuers)
    }

    @Test
    fun nothingIsKeptFromAChainThatFails() {
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org"))
        val check = ServerCertificateCheck(trust, "other.example.org", null)
        assertFailsWith<CertificateException> { check.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA") }
        assertFailsWith<CertificateException> { check.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertFailsWith<CertificateException> { check.checkServerTrusted(null, "ECDHE_ECDSA") }
        assertNull(check.validatedChain)
    }

    /** The path ends at the first trust anchor that signed it, whatever else the server sent. */
    @Test
    fun theRebuiltPathEndsAtTheTrustAnchor() {
        val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org"))
        val chains = mutableListOf<List<X509Certificate>>()
        val check = ServerCertificateCheck(trust, "coap.example.org", { _, chain -> chains += chain; true })
        // The authority, sent twice after the leaf, is not followed: the anchor ends the path first.
        check.checkServerTrusted(arrayOf(leaf.certificate, authority.certificate, authority.certificate), "ECDHE_ECDSA")
        assertEquals(listOf(leaf.certificate, authority.certificate), chains.single())
    }

    @Test
    fun clientsAreNeverAuthenticated() {
        val check = ServerCertificateCheck(trust, "coap.example.org", null)
        val error = assertFailsWith<CertificateException> { check.checkClientTrusted(arrayOf(authority.certificate), "ECDHE_ECDSA") }
        assertTrue("does not authenticate clients" in error.message.orEmpty())
    }
}
