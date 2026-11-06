package dev.kcoap.dtls

import dev.kcoap.crypto.AesBlockCipher
import dev.kcoap.crypto.AesCcm
import dev.kcoap.transport.SecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DtlsCipherSuiteTest {
    /** IANA TLS Cipher Suites registry, from RFC 6655 §3 and RFC 7251 §2. */
    @Test
    fun codesMatchIana() {
        assertEquals(0xC0A8, DtlsCipherSuite.TLS_PSK_WITH_AES_128_CCM_8.code)
        assertEquals(0xC0AE, DtlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8.code)
        for (suite in DtlsCipherSuite.entries) assertEquals(suite, DtlsCipherSuite.fromCode(suite.code))
        assertNull(DtlsCipherSuite.fromCode(0x002F))
    }

    /** RFC 7252 §9.1.3.1 to §9.1.3.3. */
    @Test
    fun mandatorySuitePerMode() {
        assertEquals(
            DtlsCipherSuite.TLS_PSK_WITH_AES_128_CCM_8,
            DtlsCipherSuite.mandatoryFor(SecurityMode.PRE_SHARED_KEY),
        )
        assertEquals(
            DtlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
            DtlsCipherSuite.mandatoryFor(SecurityMode.RAW_PUBLIC_KEY),
        )
        assertEquals(
            DtlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
            DtlsCipherSuite.mandatoryFor(SecurityMode.CERTIFICATE),
        )
        assertNull(DtlsCipherSuite.mandatoryFor(SecurityMode.NO_SEC))
    }

    /** The common-code CCM accepts the TLS parameters: 8-byte tag, 12-byte nonce (RFC 6655 §3). */
    @Test
    fun ccm8FitsCommonAesCcm() = kotlinx.coroutines.test.runTest {
        for (suite in DtlsCipherSuite.entries) {
            assertEquals(12, suite.nonceLength)
            val aead = AesCcm(AesBlockCipher { it.copyOf() }, suite.tagLength, suite.nonceLength)
            val sealed = aead.encrypt(ByteArray(suite.nonceLength), ByteArray(5), ByteArray(13))
            assertEquals(5 + suite.tagLength, sealed.size)
        }
    }

    @Test
    fun recordOverhead() {
        val suite = DtlsCipherSuite.TLS_PSK_WITH_AES_128_CCM_8
        // 13-byte header, 8-byte explicit nonce, 8-byte tag.
        assertEquals(1280 - 29, suite.maxPlaintextLength(1280))
        // RFC 9146: CID in the header plus the inner content-type byte.
        assertEquals(1280 - 29 - 4 - 1, suite.maxPlaintextLength(1280, connectionIdLength = 4))
        assertFailsWith<IllegalArgumentException> { suite.maxPlaintextLength(1280, connectionIdLength = 256) }
    }
}
