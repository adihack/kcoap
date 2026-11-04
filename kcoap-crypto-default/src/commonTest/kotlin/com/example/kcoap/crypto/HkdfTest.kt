package com.example.kcoap.crypto

import com.example.kcoap.crypto.backend.DefaultCryptoBackend
import com.example.kcoap.crypto.vectors.RFC5869_VECTORS
import com.example.kcoap.crypto.vectors.RFC8613_KDF_VECTORS
import kotlin.test.Test
import kotlin.test.assertEquals

class HkdfTest {
    private val hkdf = Hkdf(DefaultCryptoBackend(), HashAlgorithm.SHA_256)

    @Test
    fun rfc5869ExtractAndExpand() {
        assertEquals(3, RFC5869_VECTORS.size)
        RFC5869_VECTORS.forEach { v ->
            val prk = hkdf.extract(v.salt.hex(), v.ikm.hex())
            assertEquals(v.prk, prk.hex(), "${v.name} PRK")
            assertEquals(v.okm, hkdf.expand(prk, v.info.hex(), v.length).hex(), "${v.name} OKM")
        }
    }

    /** OSCORE key derivation (RFC 8613 §3.2.1), with the CBOR `info` taken verbatim from Appendix C. */
    @Test
    fun rfc8613SecurityContextDerivation() {
        assertEquals(18, RFC8613_KDF_VECTORS.size)
        RFC8613_KDF_VECTORS.forEach { v ->
            assertEquals(v.okm, hkdf.derive(v.salt.hex(), v.ikm.hex(), v.info.hex(), v.length).hex(), v.name)
        }
    }
}
