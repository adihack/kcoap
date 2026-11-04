package dev.kcoap.oscore

/**
 * Persists Sender Sequence Numbers across reboots (RFC 8613 Appendix B.1.1), so a restarted endpoint
 * never reuses a nonce.
 *
 * The context does not write every number: it reserves a block of numbers ahead, persists the end of
 * the block, and only writes again when the block is used up. After a reboot it continues from the
 * persisted value, skipping at most one block.
 */
public interface SequenceNumberStore {
    /** The last value [store]d for [contextName], or null for a context seen for the first time. */
    public suspend fun load(contextName: String): Long?

    /** Records that numbers below [limit] may have been used. Must be durable before it returns. */
    public suspend fun store(contextName: String, limit: Long)
}

/** Keeps numbers in memory only: safe while the process runs, which is enough for tests and EDHOC-derived contexts. */
public class InMemorySequenceNumberStore : SequenceNumberStore {
    private val values = mutableMapOf<String, Long>()

    override suspend fun load(contextName: String): Long? = values[contextName]

    override suspend fun store(contextName: String, limit: Long) {
        values[contextName] = limit
    }
}
