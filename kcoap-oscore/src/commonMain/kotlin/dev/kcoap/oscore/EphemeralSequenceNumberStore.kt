package dev.kcoap.oscore

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps Sender Sequence Numbers in memory for the life of the process: the [SequenceNumberStore] for
 * keys that are new in this process run and are never used after it ends, such as EDHOC output
 * (RFC 9528 Appendix A.1), and for tests.
 *
 * Never use it with a provisioned Master Secret that outlives the process. After a restart such a
 * context starts at sequence number 0 again, which reuses AEAD nonces (RFC 8613 Appendix B.1.1), and,
 * unless [FreshnessPolicy.REQUIRE_PROOF] is chosen, its empty replay window accepts recorded requests
 * once more (Appendix B.1.2). Use a durable store there; `kcoap-oscore/README.md` has recipes.
 *
 * Thread safe: one store may serve several contexts at once, each under its own context name (one live
 * context per name, see [SecurityContext.derive]). It keeps the highest value stored for each name, so
 * a value never goes back.
 */
public class EphemeralSequenceNumberStore : SequenceNumberStore {
    private val lock = Mutex()
    private val values = mutableMapOf<String, Long>()

    override suspend fun load(contextName: String): Long? = lock.withLock { values[contextName] }

    override suspend fun store(contextName: String, limit: Long) {
        lock.withLock {
            val current = values[contextName]
            if (current == null || limit > current) values[contextName] = limit
        }
    }

    override fun toString(): String = "EphemeralSequenceNumberStore"
}
