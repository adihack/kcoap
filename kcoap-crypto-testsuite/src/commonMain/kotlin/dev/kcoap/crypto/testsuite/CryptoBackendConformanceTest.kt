package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The conformance suite of a `CryptoBackend`: its `supports` answers hold ([CapabilityInvariant],
 * docs/adr/0001-crypto-capabilities.md) for every AEAD, key type, signature algorithm, KEM and hash, and the
 * capability report goes to the test output. A backend's tests extend it in their common source set, so it
 * runs on every target the backend builds for:
 *
 * ```
 * class MyBackendConformanceTest : CryptoBackendConformanceTest() {
 *     override fun createBackend(): CryptoBackend = MyBackend(DefaultCryptoBackend())
 * }
 * ```
 *
 * A decorator that adds algorithms and forgets to override the matching `supports` members fails here: its
 * delegate answers no for operations the decorator runs. Each test asks [createBackend] for a backend of its
 * own, so memoized answers start empty.
 */
public abstract class CryptoBackendConformanceTest {
    /** A new instance of the backend under test. */
    protected abstract fun createBackend(): CryptoBackend

    /** The backend's name in the capability report: its class name unless a subclass says otherwise. */
    protected open fun backendName(backend: CryptoBackend): String = backend::class.simpleName ?: "the backend"

    @Test
    public fun everyAeadRunsExactlyWhenSupported(): TestResult = runTest { assertHolds(AeadAlgorithm.entries) }

    @Test
    public fun everyKeyTypeRunsExactlyWhenSupported(): TestResult = runTest { assertHolds(KeyType.entries) }

    @Test
    public fun everySignatureAlgorithmRunsExactlyWhenSupported(): TestResult =
        runTest { assertHolds(SignatureAlgorithm.entries) }

    @Test
    public fun everyKemRunsExactlyWhenSupported(): TestResult = runTest { assertHolds(KemAlgorithm.entries) }

    /** SHA-256, SHA-384 and their HMACs where supported; SHAKE256 always. */
    @Test
    public fun everyHashRunsExactlyWhenSupported(): TestResult = runTest { assertHolds(HashAlgorithm.entries) }

    /**
     * Prints the capability report, one line per kind of question starting with
     * [CapabilityReport.LINE_PREFIX], which the build can copy into its log for each target.
     */
    @Test
    public fun theCapabilityReportAnswersEveryQuestion(): TestResult = runTest {
        val backend = createBackend()
        val report = CapabilityReport.of(backend)
        assertEquals(CapabilityInvariant.questions.toSet(), (report.supported + report.unsupported).toSet())
        assertTrue(HashAlgorithm.SHAKE256 in report.supported, "every backend computes SHAKE256")
        report.lines(backendName(backend)).forEach(::println)
    }

    private suspend fun assertHolds(questions: List<Enum<*>>) {
        val backend = createBackend()
        val violations = questions.mapNotNull { CapabilityInvariant.check(backend, it) }
        if (violations.isNotEmpty()) {
            fail("${backendName(backend)} breaks the capability contract:\n" + violations.joinToString("\n"))
        }
    }
}
