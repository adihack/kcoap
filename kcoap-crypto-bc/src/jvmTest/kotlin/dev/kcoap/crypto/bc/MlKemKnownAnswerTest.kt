package dev.kcoap.crypto.bc

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.MlKemConformanceTest
import dev.kcoap.crypto.testsuite.ScriptedRandom

/**
 * FIPS 203 known-answer tests from NIST's ACVP vectors, replayed through the public backend by the conformance
 * suite (kcoap-crypto-testsuite), with the randomness drawn from the classical backend scripted.
 */
class MlKemKnownAnswerTest : MlKemConformanceTest() {
    override fun createBackend(): CryptoBackend = BouncyCastleCryptoBackend(DefaultCryptoBackend())

    override fun createBackend(random: ScriptedRandom): CryptoBackend =
        BouncyCastleCryptoBackend(ScriptedRandomBackend(random))
}
