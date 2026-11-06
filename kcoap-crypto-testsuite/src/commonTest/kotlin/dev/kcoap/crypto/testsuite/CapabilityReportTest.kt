package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The capability report: every question asked once, one line per kind, each line findable in a log. */
class CapabilityReportTest {
    private val yes = setOf<Enum<*>>(
        AeadAlgorithm.A128GCM, KeyType.X25519, SignatureAlgorithm.EDDSA, HashAlgorithm.SHA_256, HashAlgorithm.SHAKE256,
    )

    /** Answers yes to [yes] only, and counts the questions. */
    private class Answering(private val yes: Set<Enum<*>>) : CryptoBackend by DefaultCryptoBackend() {
        val asked = mutableListOf<Enum<*>>()

        private fun answer(question: Enum<*>): Boolean = (question in yes).also { asked += question }

        override suspend fun supports(algorithm: AeadAlgorithm): Boolean = answer(algorithm)
        override suspend fun supports(type: KeyType): Boolean = answer(type)
        override suspend fun supports(algorithm: SignatureAlgorithm): Boolean = answer(algorithm)
        override suspend fun supports(algorithm: KemAlgorithm): Boolean = answer(algorithm)
        override suspend fun supports(algorithm: HashAlgorithm): Boolean = answer(algorithm)
    }

    @Test
    fun theReportAsksEveryQuestionOnceAndSortsTheAnswers() = runTest {
        val backend = Answering(yes)

        val report = CapabilityReport.of(backend)

        assertEquals(CapabilityInvariant.questions, backend.asked)
        assertEquals(CapabilityInvariant.questions.filter { it in yes }, report.supported)
        assertEquals(CapabilityInvariant.questions.filter { it !in yes }, report.unsupported)
        assertEquals("CapabilityReport(5 supported, 30 not)", report.toString())
    }

    @Test
    fun eachLineNamesTheBackendAndOneKindOfQuestion() = runTest {
        val lines = CapabilityReport.of(Answering(yes)).lines("Fake")

        val noAead = AeadAlgorithm.entries.filter { it != AeadAlgorithm.A128GCM }.joinToString(", ")
        val noKeyTypes = KeyType.entries.filter { it != KeyType.X25519 }.joinToString(", ")
        assertEquals(
            listOf(
                "kcoap-crypto capabilities of Fake: AEAD yes A128GCM; no $noAead",
                "kcoap-crypto capabilities of Fake: key types yes X25519; no $noKeyTypes",
                "kcoap-crypto capabilities of Fake: signatures yes EDDSA; " +
                    "no ES256, ES384, ML_DSA_44, ML_DSA_65, ML_DSA_87",
                "kcoap-crypto capabilities of Fake: KEMs yes none; no ML_KEM_512, ML_KEM_768, ML_KEM_1024",
                "kcoap-crypto capabilities of Fake: hashes yes SHA_256, SHAKE256; no SHA_384",
            ),
            lines,
        )
        assertTrue(lines.all { it.startsWith(CapabilityReport.LINE_PREFIX) })
    }

    @Test
    fun aBackendThatSupportsEverythingHasNothingUnsupported() = runTest {
        val report = CapabilityReport.of(Answering(CapabilityInvariant.questions.toSet()))

        assertEquals(emptyList(), report.unsupported)
        assertTrue(report.lines("All").all { it.endsWith("; no none") })
    }
}
