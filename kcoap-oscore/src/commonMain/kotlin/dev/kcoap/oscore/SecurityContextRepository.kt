package dev.kcoap.oscore

/**
 * Finds the Recipient Context for an incoming request by its 'kid' and optional 'kid context'
 * (RFC 8613 §8.2 step 2). Implementations may derive a context on demand, as Appendix B.2 and EDHOC do.
 */
public fun interface SecurityContextRepository {
    public suspend fun find(recipientId: ByteArray, idContext: ByteArray?): SecurityContext?
}

/**
 * A [SecurityContextRepository] that contexts are added to and removed from while the server runs: where
 * kcoap-edhoc-oscore's `EdhocServer` puts the context of every EDHOC session it completes (RFC 9528
 * Appendix A.1), and where an application keeps the contexts it provisions or retires. [InMemoryContextRepository]
 * is the implementation kcoap ships.
 *
 * A context is held under its Recipient ID and ID Context, the pair a request names with 'kid' and 'kid context'
 * ([find]); RFC 8613 §3.3 makes that pair unique among the Recipient Contexts, so one pair holds one context.
 *
 * **Bound it.** Contexts from EDHOC accumulate: every completed handshake adds one, nothing in kcoap removes
 * them, and every client the server's EDHOC configuration trusts can run handshakes. A production server
 * supplies an implementation with a bound, which evicts, say, the context found least recently once it is full.
 * Eviction costs the client only a new handshake: its next request gets the unprotected 4.01 "Security context
 * not found" (RFC 8613 §8.2), and it runs EDHOC again.
 *
 * **Persisting contexts** is safe only for contexts whose Sender Sequence Numbers a durable [SequenceNumberStore]
 * has held from their first derivation, as [SecurityContext.derive] describes; a repository that keeps them
 * across restarts derives them again from their parameters and that store.
 *
 * Implementations are called from the request handling of many peers at once and must be safe for that.
 */
public interface MutableSecurityContextRepository : SecurityContextRepository {
    /** Adds [context], replacing any context with the same Recipient ID and ID Context. */
    public suspend fun add(context: SecurityContext)

    /**
     * Adds [context] unless a context with the same Recipient ID and ID Context is present, in one step that no
     * other writer can interleave with, and returns whether it did. A writer that must never replace another's
     * context uses this instead of [add]: `EdhocServer` adds every context it derives this way, and fails the
     * handshake when the Recipient ID was taken meanwhile (RFC 9668 §4.1.2).
     */
    public suspend fun addIfAbsent(context: SecurityContext): Boolean

    /**
     * Removes [context] itself, in one step that no other writer can interleave with, and returns whether it did:
     * only when [context] is the very context held under its Recipient ID and ID Context. A context that has
     * been replaced or evicted already leaves the newer one in place, so a late removal, such as an expiry timer
     * or a second revocation, never takes out another client's context that got the same Recipient ID since.
     */
    public suspend fun remove(context: SecurityContext): Boolean
}
