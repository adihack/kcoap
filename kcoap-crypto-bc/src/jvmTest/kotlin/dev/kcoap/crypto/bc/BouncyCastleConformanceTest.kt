package dev.kcoap.crypto.bc

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.CryptoBackendConformanceTest

/**
 * The post-quantum backend keeps the capability contract (kcoap-crypto-testsuite, ADR 0001): it answers yes for
 * ML-KEM and ML-DSA and runs them, NIST's ACVP answers included, and every other question is the classical
 * backend's, answered as that backend runs it.
 */
class BouncyCastleConformanceTest : CryptoBackendConformanceTest() {
    override fun createBackend(): CryptoBackend = BouncyCastleCryptoBackend(DefaultCryptoBackend())
}
