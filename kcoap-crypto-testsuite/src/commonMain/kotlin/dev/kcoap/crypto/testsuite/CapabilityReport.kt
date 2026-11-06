package dev.kcoap.crypto.testsuite

import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm

/**
 * What a backend answers to every capability question ([CapabilityInvariant.questions]), as a few lines of
 * text for a test log: one line per kind of question, each starting with [LINE_PREFIX], so a build or a CI
 * log can pick them out. Run on every target, the lines show what each platform's cryptography offers
 * through the backend, for instance that WebCrypto has no ChaCha20/Poly1305.
 *
 * ```
 * kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, ..., A256GCM; no CHACHA20_POLY1305
 * ```
 */
public class CapabilityReport private constructor(private val answers: Map<Enum<*>, Boolean>) {
    /** The questions answered yes, in the order of [CapabilityInvariant.questions]. */
    public val supported: List<Enum<*>> get() = answers.filterValues { it }.keys.toList()

    /** The questions answered no, in the order of [CapabilityInvariant.questions]. */
    public val unsupported: List<Enum<*>> get() = answers.filterValues { !it }.keys.toList()

    /**
     * One line per kind of question, AEADs, key types, signature algorithms, KEMs and hashes, naming what the
     * backend called [backendName] supports and what it does not.
     */
    public fun lines(backendName: String): List<String> = KINDS.map { (kind, type) ->
        val asked = answers.filterKeys { type.isInstance(it) }
        val yes = asked.filterValues { it }.keys.joinToString(", ") { it.name }.ifEmpty { "none" }
        val no = asked.filterValues { !it }.keys.joinToString(", ") { it.name }.ifEmpty { "none" }
        "$LINE_PREFIX of $backendName: $kind yes $yes; no $no"
    }

    override fun toString(): String = "CapabilityReport(${supported.size} supported, ${unsupported.size} not)"

    public companion object {
        /** The start of every line, for picking the report out of a log. */
        public const val LINE_PREFIX: String = "kcoap-crypto capabilities"

        private val KINDS = listOf(
            "AEAD" to AeadAlgorithm::class,
            "key types" to KeyType::class,
            "signatures" to SignatureAlgorithm::class,
            "KEMs" to KemAlgorithm::class,
            "hashes" to HashAlgorithm::class,
        )

        /** Asks [backend] every question of [CapabilityInvariant.questions], once each. */
        public suspend fun of(backend: CryptoBackend): CapabilityReport {
            val answers = LinkedHashMap<Enum<*>, Boolean>()
            for (question in CapabilityInvariant.questions) {
                answers[question] = CapabilityInvariant.ask(backend, question)
            }
            return CapabilityReport(answers)
        }
    }
}
