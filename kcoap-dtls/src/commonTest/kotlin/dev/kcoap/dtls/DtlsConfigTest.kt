package dev.kcoap.dtls

import dev.kcoap.crypto.CertificateValidator
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.SecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DtlsConfigTest {
    private val pskStore = object : PskStore {
        override suspend fun keyFor(identity: ByteArray): ByteArray? = null

        override suspend fun credentialFor(peer: Endpoint, identityHint: ByteArray?): PskCredential? = null
    }

    private fun signer(chain: List<ByteArray>) = object : DtlsSigner {
        override val subjectPublicKeyInfo: ByteArray = ByteArray(91)
        override val certificateChain: List<ByteArray> = chain

        override suspend fun sign(data: ByteArray): ByteArray = ByteArray(0)
    }

    private val pskOnly = DtlsCredentials(preSharedKeys = pskStore)

    @Test
    fun rfc7925Defaults() {
        val config = DtlsConfig(setOf(SecurityMode.PRE_SHARED_KEY), pskOnly)
        assertEquals(9.seconds, config.initialRetransmissionTimeout)
        assertEquals(60.seconds, config.maxRetransmissionTimeout)
        assertEquals(DtlsCipherSuite.entries, config.cipherSuites)
        assertNull(config.connectionIdLength)
        assertNull(config.maxFragmentLength)
        assertTrue(config.cookieExchange)
    }

    @Test
    fun noSecIsNotADtlsMode() {
        assertFailsWith<IllegalArgumentException> { DtlsConfig(emptySet(), pskOnly) }
        assertFailsWith<IllegalArgumentException> { DtlsConfig(setOf(SecurityMode.NO_SEC), pskOnly) }
    }

    @Test
    fun eachModeNeedsASuite() {
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(
                setOf(SecurityMode.PRE_SHARED_KEY),
                pskOnly,
                cipherSuites = listOf(DtlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8),
            )
        }
    }

    @Test
    fun eachModeNeedsItsCredentials() {
        assertFailsWith<IllegalArgumentException> { DtlsConfig(setOf(SecurityMode.PRE_SHARED_KEY), DtlsCredentials()) }
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(setOf(SecurityMode.RAW_PUBLIC_KEY), DtlsCredentials(signer = signer(emptyList())))
        }
        DtlsConfig(
            setOf(SecurityMode.RAW_PUBLIC_KEY),
            DtlsCredentials(signer = signer(emptyList()), rawPublicKeyTrust = RawPublicKeyTrust { _, _ -> true }),
        )
        val validator = CertificateValidator { }
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(
                setOf(SecurityMode.CERTIFICATE),
                DtlsCredentials(signer = signer(emptyList()), certificateValidator = validator),
            )
        }
        DtlsConfig(
            setOf(SecurityMode.CERTIFICATE),
            DtlsCredentials(signer = signer(listOf(ByteArray(300))), certificateValidator = validator),
        )
    }

    @Test
    fun rejectsInvalidLimits() {
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(setOf(SecurityMode.PRE_SHARED_KEY), pskOnly, maxFragmentLength = 1000)
        }
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(setOf(SecurityMode.PRE_SHARED_KEY), pskOnly, connectionIdLength = 256)
        }
        assertFailsWith<IllegalArgumentException> {
            DtlsConfig(
                setOf(SecurityMode.PRE_SHARED_KEY),
                pskOnly,
                initialRetransmissionTimeout = 10.seconds,
                maxRetransmissionTimeout = 5.seconds,
            )
        }
        DtlsConfig(setOf(SecurityMode.PRE_SHARED_KEY), pskOnly, maxFragmentLength = 512, connectionIdLength = 0)
    }
}
