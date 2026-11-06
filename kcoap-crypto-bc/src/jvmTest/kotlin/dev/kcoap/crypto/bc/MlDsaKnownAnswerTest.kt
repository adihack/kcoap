package dev.kcoap.crypto.bc

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.MlDsaConformanceTest
import dev.kcoap.crypto.testsuite.ScriptedRandom

/**
 * FIPS 204 known-answer tests from NIST's ACVP vectors, replayed through the public backend by the conformance
 * suite (kcoap-crypto-testsuite). The backend signs hedged, its default: the suite scripts `rnd`, all zeros for
 * NIST's deterministic vectors.
 */
class MlDsaKnownAnswerTest : MlDsaConformanceTest() {
    override fun createBackend(): CryptoBackend = BouncyCastleCryptoBackend(DefaultCryptoBackend())

    override fun createBackend(random: ScriptedRandom): CryptoBackend =
        BouncyCastleCryptoBackend(ScriptedRandomBackend(random))
}
