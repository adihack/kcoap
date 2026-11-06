package dev.kcoap.crypto

import dev.kcoap.crypto.backend.CapabilityMemo
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.Stress
import dev.whyoleg.cryptography.CryptographyAlgorithm
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographyProviderApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The default backend probes each capability once per instance (docs/adr/0001-crypto-capabilities.md):
 * a provider that counts its lookups shows that only the first question about an algorithm reaches it.
 */
@OptIn(ExperimentalAtomicApi::class)
class SupportsMemoTest {
    @Test
    fun eachSupportsQuestionReachesTheProviderOnce() = runTest {
        val provider = CountingProvider()
        val backend = DefaultCryptoBackend(provider)
        val questions: List<Pair<String, suspend () -> Boolean>> = listOf(
            "A128GCM" to { backend.supports(AeadAlgorithm.A128GCM) },
            "AES-CCM-16-64-128" to { backend.supports(AeadAlgorithm.AES_CCM_16_64_128) },
            "X25519" to { backend.supports(KeyType.X25519) },
            "P-256" to { backend.supports(KeyType.P256) },
            "ES256" to { backend.supports(SignatureAlgorithm.ES256) },
            "SHA-256" to { backend.supports(HashAlgorithm.SHA_256) },
        )
        for ((name, ask) in questions) {
            val before = provider.lookups()
            val answer = ask()
            val probed = provider.lookups()
            assertTrue(probed > before, "the first question about $name reaches the provider")
            repeat(3) { assertEquals(answer, ask(), name) }
            assertEquals(probed, provider.lookups(), "later questions about $name are answered from the memo")
        }
    }

    /**
     * The KEM and SHAKE256 probes never reach the provider (this backend refuses ML-KEM key types before any
     * lookup, and SHAKE256 is common code), so the memo itself shows that they run once: the first question
     * leaves its answer there, and a question that finds an answer there is not probed.
     */
    @Test
    fun theKemAndShake256QuestionsAreProbedOnce() = runTest {
        val backend = DefaultCryptoBackend(CountingProvider())

        assertFalse(backend.supports(KemAlgorithm.ML_KEM_512), "this backend has no KEM")
        assertTrue(backend.supports(HashAlgorithm.SHAKE256))

        assertFalse(backend.capabilities.answer(KemAlgorithm.ML_KEM_512) { error("ML-KEM-512 is probed again") })
        assertTrue(backend.capabilities.answer(HashAlgorithm.SHAKE256) { error("SHAKE256 is probed again") })
        // A recorded answer is what supports returns, even one no probe would give.
        val recorded = DefaultCryptoBackend(CountingProvider())
        recorded.capabilities.answer(KemAlgorithm.ML_KEM_512) { true }
        recorded.capabilities.answer(HashAlgorithm.SHAKE256) { false }
        repeat(3) { assertTrue(recorded.supports(KemAlgorithm.ML_KEM_512), "ML-KEM-512 comes from the memo") }
        repeat(3) { assertFalse(recorded.supports(HashAlgorithm.SHAKE256), "SHAKE256 comes from the memo") }
    }

    /** Callers racing on real threads (one event loop on JS and Wasm) share the first caller's probe. */
    @Test
    fun racingCallersShareOneProbe() = runTest {
        val oneProbe = CountingProvider().also { DefaultCryptoBackend(it).supports(AeadAlgorithm.A128GCM) }.lookups()
        Stress.run(iterations = 20) {
            val provider = CountingProvider()
            val backend = DefaultCryptoBackend(provider)
            val ask: suspend () -> Boolean = { backend.supports(AeadAlgorithm.A128GCM) }
            val answers = Stress.race(*Array(8) { ask })
            assertTrue(answers.all { it.getOrThrow() })
            assertEquals(oneProbe, provider.lookups())
        }
    }

    @Test
    fun aProbeThatFailsLeavesNoAnswer() = runTest {
        val memo = CapabilityMemo()
        var probes = 0
        assertFailsWith<IllegalStateException> { memo.answer("question") { probes++; error("device busy") } }
        assertTrue(memo.answer("question") { probes++; true })
        assertTrue(memo.answer("question") { probes++; false })
        assertEquals(2, probes)
    }

    /** A caller waiting for a probe that is cancelled probes itself. */
    @Test
    fun aWaitingCallerTakesOverACancelledProbe() = runTest {
        val memo = CapabilityMemo()
        val never = CompletableDeferred<Boolean>()
        val first = launch { memo.answer("question") { never.await() } }
        runCurrent()
        val second = async { memo.answer("question") { false } }
        runCurrent()
        first.cancel()
        assertFalse(second.await())
        assertFalse(memo.answer("question") { true }, "the second caller's answer is kept")
    }

    /** The platform's provider, counting lookups. */
    @OptIn(CryptographyProviderApi::class)
    private class CountingProvider : CryptographyProvider() {
        private val count = AtomicInt(0)

        override val name: String get() = "counting"

        fun lookups(): Int = count.load()

        override fun <A : CryptographyAlgorithm> getOrNull(identifier: CryptographyAlgorithmId<A>): A? {
            count.addAndFetch(1)
            return Default.getOrNull(identifier)
        }
    }
}
