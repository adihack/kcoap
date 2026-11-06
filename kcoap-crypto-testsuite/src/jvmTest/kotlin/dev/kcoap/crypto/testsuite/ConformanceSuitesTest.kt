package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The abstract suites fail a backend that does not conform, and say why. On the JVM a suite's test runs to
 * completion when it is called, so its failure is an [AssertionError] here; on JS and Wasm it would be a
 * rejected promise.
 */
class ConformanceSuitesTest {
    @Test
    fun theConformanceSuiteNamesTheBackendAndEveryViolation() {
        val suite = object : CryptoBackendConformanceTest() {
            override fun createBackend(): CryptoBackend = object : CryptoBackend by DefaultCryptoBackend() {
                override suspend fun supports(algorithm: HashAlgorithm): Boolean = algorithm != HashAlgorithm.SHA_384
            }

            override fun backendName(backend: CryptoBackend): String = "LyingBackend"
        }

        val failure = assertFailsWith<AssertionError> { suite.everyHashRunsExactlyWhenSupported() }

        assertEquals(
            "LyingBackend breaks the capability contract:\n" +
                "HashAlgorithm.SHA_384: supports answered no, but its operations ran instead of throwing " +
                "UnsupportedAlgorithmException",
            failure.message,
        )
        suite.everyAeadRunsExactlyWhenSupported()
        suite.theCapabilityReportAnswersEveryQuestion()
    }

    /** A known-answer suite never passes vacuously: a backend without the algorithm fails it. */
    @Test
    fun theKnownAnswerSuitesFailABackendWithoutTheirAlgorithm() {
        val kem = object : MlKemConformanceTest() {
            override fun createBackend(): CryptoBackend = DefaultCryptoBackend()
            override fun createBackend(random: ScriptedRandom): CryptoBackend = DefaultCryptoBackend()
        }
        val dsa = object : MlDsaConformanceTest() {
            override fun createBackend(): CryptoBackend = DefaultCryptoBackend()
            override fun createBackend(random: ScriptedRandom): CryptoBackend = DefaultCryptoBackend()
        }
        val kemTests = listOf(
            kem::fips203KeyGenMatchesAcvpVectors,
            kem::fips203SeedAndExpandedDecapsulationKeyAgree,
            kem::fips203EncapsulationMatchesAcvpVectors,
            kem::fips203ImplicitRejectionMatchesAcvpVector,
            kem::fips203ModulusCheckRejectsAcvpEncapsulationKey,
            kem::fips203HashCheckRejectsAcvpDecapsulationKey,
            kem::importRejectsADecapsulationKeyOfAnotherEncapsulationKey,
        )
        val dsaTests = listOf(
            dsa::fips204KeyGenMatchesAcvpVectors,
            dsa::fips204SeedAndExpandedPrivateKeyAgree,
            dsa::fips204SigGenMatchesAcvpVectors,
            dsa::fips204SigVerMatchesAcvpVectors,
            dsa::fips204VerifyRejectsModifiedAndMalformedSignatures,
            dsa::importChecksThatThePrivateKeyBelongsToThePublicKey,
        )

        for (test in kemTests) {
            val failure = assertFailsWith<AssertionError>(test.name) { test() }
            assertContains(failure.message.orEmpty(), "the backend supports no ML-KEM parameter set")
        }
        for (test in dsaTests) {
            val failure = assertFailsWith<AssertionError>(test.name) { test() }
            assertContains(failure.message.orEmpty(), "the backend supports no ML-DSA parameter set")
        }
    }
}
