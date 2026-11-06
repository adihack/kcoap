package dev.kcoap.crypto

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.CryptoBackendConformanceTest

/**
 * The default backend keeps the capability contract on every target (kcoap-crypto-testsuite, ADR 0001): what
 * the platform's cryptography lacks it reports as unsupported, and everything else it runs and gets right. The
 * capability report of each target goes to the build log.
 */
class DefaultCryptoBackendConformanceTest : CryptoBackendConformanceTest() {
    override fun createBackend(): CryptoBackend = DefaultCryptoBackend()
}
