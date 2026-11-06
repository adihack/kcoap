package dev.kcoap.sample.android

import dev.kcoap.crypto.CryptoBackend
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The app's one OSCORE association, derived on first use and then shared by everything in the process
 * (RFC 8613 §3.2; [deriveOscoreContexts]).
 *
 * A security context hands out Sender Sequence Numbers, and at most one live context may do so per store
 * and context name (`SequenceNumberStore`): two would both continue from the stored limit and protect
 * different messages with the same nonce (RFC 8613 Appendix B.1.1). So the contexts belong to the
 * process, held by [SampleApplication], and not to an Activity, of which Android may run several at once.
 * [contexts] derives them once, also when callers race; after a failed derivation the next call tries
 * again, which kcoap counts as a restore.
 *
 * @param dataDirectory where the sequence numbers are kept.
 */
internal class OscoreAssociation(private val crypto: CryptoBackend, private val dataDirectory: File) {
    private val lock = Mutex()
    private var derived: OscoreContexts? = null

    /** The client and server contexts, the same ones for every caller in this process. */
    suspend fun contexts(): OscoreContexts = lock.withLock {
        derived ?: deriveOscoreContexts(crypto, dataDirectory).also { derived = it }
    }
}
