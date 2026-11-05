package dev.kcoap.oscore

/**
 * Persists Sender Sequence Numbers across restarts (RFC 8613 Appendix B.1.1), so a restarted endpoint
 * never reuses an AEAD nonce under the same Sender Key.
 *
 * The application supplies the store, because only it knows where durable storage is on its platform;
 * `kcoap-oscore/README.md` has recipes. [EphemeralSequenceNumberStore] keeps numbers in memory and is
 * only correct for keys that are new in this process run.
 *
 * A [SecurityContext] does not write every number: it reserves a block of numbers ahead, persists the
 * end of the block, and writes again only when the block is used up. Every number it hands out is below
 * a limit that [store] has already returned for, so after a restart it continues from the persisted
 * value, skipping at most one block. [SecurityContext.derive] records a context it sees for the first
 * time with the limit 0, so deriving it again counts as a restore ([FreshnessPolicy.AUTO]).
 *
 * Implementations shared by several contexts must be safe for concurrent calls. Keep the values out of
 * backups that can be restored onto a device: an older value brought back is exactly a nonce reuse.
 */
public interface SequenceNumberStore {
    /** The last value [store]d for [contextName], or null when the store never saw that context. */
    public suspend fun load(contextName: String): Long?

    /**
     * Records that numbers below [limit] may have been used by the context [contextName].
     *
     * - Durable when it returns: after a crash or power loss right afterwards, [load] returns at least
     *   [limit].
     * - It may throw or be cancelled. The persisted value is then the previous or the new limit, never a
     *   torn or lower one. The context assumes nothing was persisted: it hands out no number at or above
     *   the previous limit and writes again on its next use. Its exception reaches the caller unchanged.
     * - The limits of one context never decrease; a retry repeats the same value.
     */
    public suspend fun store(contextName: String, limit: Long)
}
