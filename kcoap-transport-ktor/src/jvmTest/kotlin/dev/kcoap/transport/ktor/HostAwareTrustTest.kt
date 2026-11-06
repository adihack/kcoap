package dev.kcoap.transport.ktor

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.X509TrustManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stands in for Android's `X509TrustManagerExtensions`, which the JVM lacks: it takes a trust manager in its
 * constructor and has the host-aware `checkServerTrusted(X509Certificate[], String, String)`. It records each host
 * and accepts every chain, as a network security configuration that trusts the test authority would, except for
 * [REFUSED], which a `<domain-config>` refuses, and [BROKEN], whose check fails unexpectedly.
 */
class RecordingTrustManagerExtensions(@Suppress("unused") private val trust: X509TrustManager) {
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String): List<X509Certificate> {
        checks += "$host $authType"
        if (host == REFUSED) throw CertificateException("Pin verification failed for $host")
        check(host != BROKEN) { "the configuration could not be read" }
        return chain.toList()
    }

    companion object {
        const val REFUSED = "refused.example.org"
        const val BROKEN = "broken.example.org"
        val checks: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }
}

/** Like Android's wrapper for a trust manager that has no host-aware check: its constructor refuses it. */
class RefusingTrustManagerExtensions(trust: X509TrustManager) {
    init {
        throw IllegalArgumentException("Required method checkServerTrusted(X509Certificate[], String, String) missing: $trust")
    }
}

/** The host-aware platform check behind TlsTrust.Platform on Android, through a stand-in for its wrapper. */
class HostAwareTrustTest {
    private val authority = TestCertificates.authority("kcoap test CA")
    private val anchors = TlsTrust.Anchors(listOf(authority.certificate)).trustManager()
    private val leaf = TestCertificates.issue(authority, "server", dnsNames = listOf("coap.example.org", RecordingTrustManagerExtensions.REFUSED, RecordingTrustManagerExtensions.BROKEN))

    /** Counts the two-argument checks, which a platform with a host-aware check must not get. */
    private class Counting(private val inner: X509TrustManager) : X509TrustManager by inner {
        var checks = 0

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            checks++
            inner.checkServerTrusted(chain, authType)
        }
    }

    private fun recording(trust: X509TrustManager) =
        assertNotNull(HostAwareTrust.find(trust, RecordingTrustManagerExtensions::class.java.name))

    @BeforeTest
    fun forgetEarlierChecks() = RecordingTrustManagerExtensions.checks.clear()

    /** On the JVM, Android's class does not exist, so the connector keeps the two-argument check. */
    @Test
    fun theJvmHasNoHostAwareCheck() {
        assertEquals("android.net.http.X509TrustManagerExtensions", HostAwareTrust.ANDROID_EXTENSIONS)
        assertNull(HostAwareTrust.find(anchors))
        val counting = Counting(anchors)
        ServerCertificateCheck(counting, "coap.example.org", null, HostAwareTrust.find(counting))
            .checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        assertEquals(1, counting.checks)
    }

    /** Where the platform has the host-aware check, it gets the server's host, and the two-argument one is not used. */
    @Test
    fun theHostAwareCheckIsGivenTheServersHost() {
        val counting = Counting(anchors)
        val check = ServerCertificateCheck(counting, "coap.example.org", null, recording(counting))
        check.checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        assertEquals(listOf("coap.example.org ECDHE_ECDSA"), RecordingTrustManagerExtensions.checks)
        assertEquals(0, counting.checks)
        assertEquals(listOf(leaf.certificate, authority.certificate), check.validatedChain)
    }

    /** The platform's refusal ends the handshake as it is; an unexpected failure as a CertificateException. */
    @Test
    fun aRefusalOfThePlatformEndsTheHandshake() {
        val hostAware = recording(anchors)
        val refused = assertFailsWith<CertificateException> {
            ServerCertificateCheck(anchors, RecordingTrustManagerExtensions.REFUSED, null, hostAware)
                .checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        }
        assertEquals("Pin verification failed for ${RecordingTrustManagerExtensions.REFUSED}", refused.message)
        val broken = assertFailsWith<CertificateException> {
            ServerCertificateCheck(anchors, RecordingTrustManagerExtensions.BROKEN, null, hostAware)
                .checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        }
        assertIs<IllegalStateException>(broken.cause)
        assertTrue("platform trust manager failed for ${RecordingTrustManagerExtensions.BROKEN}" in broken.message.orEmpty(), broken.message)
    }

    /** RFC 9525 §6 and the pinner still apply after the platform accepted the chain. */
    @Test
    fun theNameCheckAndThePinnerStillApply() {
        val hostAware = recording(anchors)
        val unnamed = assertFailsWith<CertificateException> {
            ServerCertificateCheck(anchors, "other.example.org", null, hostAware).checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        }
        assertTrue("does not name other.example.org" in unnamed.message.orEmpty(), unnamed.message)
        val pinned = assertFailsWith<CertificateException> {
            ServerCertificateCheck(anchors, "coap.example.org", { _, _ -> false }, hostAware).checkServerTrusted(arrayOf(leaf.certificate), "ECDHE_ECDSA")
        }
        assertTrue("pinner refused" in pinned.message.orEmpty(), pinned.message)
    }

    /** A wrapper that refuses the trust manager, or a class of another shape, leaves the two-argument check. */
    @Test
    fun withoutAUsableWrapperTheTwoArgumentCheckRuns() {
        assertNull(HostAwareTrust.find(anchors, RefusingTrustManagerExtensions::class.java.name))
        assertNull(HostAwareTrust.find(anchors, String::class.java.name))
        assertNull(HostAwareTrust.find(anchors, "dev.kcoap.NoSuchClass"))
    }
}
