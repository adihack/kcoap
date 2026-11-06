package dev.kcoap.crypto.bc

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.CapabilityInvariant
import dev.kcoap.crypto.testsuite.CryptoBackendConformanceTest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `supports` overrides of [BouncyCastleCryptoBackend] are what the capability contract needs (ADR 0001): a
 * decorator forwards `supports` to its delegate, which has no ML-KEM or ML-DSA, so without them the backend
 * would deny the algorithms it adds. The conformance suite catches exactly that.
 */
class SupportsOverridesTest {
    @Test
    fun withoutItsSupportsOverridesTheBackendBreaksTheContractForWhatItAdds() = runTest {
        val classical = DefaultCryptoBackend()

        val violations = CapabilityInvariant.violations(WithoutSupportsOverrides(classical))

        val added = KemAlgorithm.entries + KemAlgorithm.entries.map { it.keyType } +
            mlDsa + mlDsa.map { it.keyType }
        assertEquals(added.toSet(), violations.map { it.question }.toSet(), violations.joinToString("\n"))
        val denied = violations.all { !it.answer && it.problem.startsWith("its operations ran") }
        assertTrue(denied, violations.joinToString("\n"))
    }

    @Test
    fun withThemTheContractHolds() = runTest {
        assertEquals(emptyList(), CapabilityInvariant.violations(BouncyCastleCryptoBackend(DefaultCryptoBackend())))
    }

    /**
     * The suite [BouncyCastleConformanceTest] extends fails the backend without its overrides, for KEMs, key types
     * and signatures, and passes it for what the classical backend answers itself. On the JVM a suite's test runs
     * to completion when called, so its failure is an [AssertionError] here.
     */
    @Test
    fun theConformanceSuiteFailsTheBackendWithoutThem() {
        val suite = object : CryptoBackendConformanceTest() {
            override fun createBackend(): CryptoBackend = WithoutSupportsOverrides(DefaultCryptoBackend())
        }

        val kems = assertFailsWith<AssertionError> { suite.everyKemRunsExactlyWhenSupported() }
        assertFailsWith<AssertionError> { suite.everyKeyTypeRunsExactlyWhenSupported() }
        assertFailsWith<AssertionError> { suite.everySignatureAlgorithmRunsExactlyWhenSupported() }

        assertContains(kems.message.orEmpty(), "KemAlgorithm.ML_KEM_512: supports answered no, but its operations ran")
        suite.everyAeadRunsExactlyWhenSupported()
        suite.everyHashRunsExactlyWhenSupported()
    }

    private val mlDsa = SignatureAlgorithm.entries.filter { it.trLength != null }

    /**
     * [BouncyCastleCryptoBackend] as it would be without its `supports` overrides: the operations are its own, and
     * every `supports` question goes to [classical], as `CryptoBackend by classical` would forward it. Test-only:
     * it lies about what the backend runs, to show that the suite notices.
     */
    private class WithoutSupportsOverrides(private val classical: CryptoBackend) :
        CryptoBackend by BouncyCastleCryptoBackend(classical) {
        override suspend fun supports(algorithm: AeadAlgorithm): Boolean = classical.supports(algorithm)
        override suspend fun supports(type: KeyType): Boolean = classical.supports(type)
        override suspend fun supports(algorithm: SignatureAlgorithm): Boolean = classical.supports(algorithm)
        override suspend fun supports(algorithm: KemAlgorithm): Boolean = classical.supports(algorithm)
        override suspend fun supports(algorithm: HashAlgorithm): Boolean = classical.supports(algorithm)
    }
}
