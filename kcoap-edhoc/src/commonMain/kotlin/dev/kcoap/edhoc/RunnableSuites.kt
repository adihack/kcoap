package dev.kcoap.edhoc

import dev.kcoap.crypto.CryptoBackend

/**
 * Configured cipher suites split by whether a crypto backend runs them for one method
 * ([CipherSuite.isSupportedBy]): the runnable ones in their configured order, and the others with what
 * each needs. EDHOC offers and accepts only the runnable ones (RFC 9528 §6.3,
 * docs/adr/0001-crypto-capabilities.md).
 */
internal class RunnableSuites private constructor(
    val suites: List<CipherSuite>,
    private val dropped: List<Pair<CipherSuite, String>>,
) {
    val hasDropped: Boolean get() = dropped.isNotEmpty()

    /** What [suite] needs, if it was dropped. */
    fun reasonFor(suite: CipherSuite): String? = dropped.firstOrNull { it.first == suite }?.second

    /** The dropped suites and what each needs, for diagnostics: "suite 4 needs CHACHA20_POLY1305, ...". */
    fun describeDropped(): String = dropped.joinToString { (suite, reason) -> "suite ${suite.value} $reason" }

    /**
     * What [of] answers once the configured suites are narrowed to [kept], which must hold every runnable
     * suite: the same runnable suites, and only the dropped ones that were kept.
     */
    fun restrictedTo(kept: Collection<CipherSuite>): RunnableSuites =
        RunnableSuites(suites, dropped.filter { it.first in kept })

    companion object {
        suspend fun of(suites: List<CipherSuite>, crypto: CryptoBackend, method: EdhocMethod): RunnableSuites {
            val runnable = mutableListOf<CipherSuite>()
            val dropped = mutableListOf<Pair<CipherSuite, String>>()
            for (suite in suites) {
                val missing = suite.missingFor(crypto, method)
                if (missing == null) runnable += suite else dropped += suite to missing
            }
            return RunnableSuites(runnable, dropped)
        }
    }
}
