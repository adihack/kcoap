package dev.kcoap.oscore

/**
 * When a context must see a freshness proof before it trusts its replay window (RFC 8613 Appendix
 * B.1.2).
 *
 * The replay window (§7.4) lives in memory only. A context derived again, after a restart or within the
 * same process, starts with an empty window that would accept every request recorded before. Such a
 * context answers the first request with a protected 4.01 carrying Echo (RFC 9175 §2.3), processes
 * nothing until a request returns that Echo value, and then starts the window at that request's Partial
 * IV. The window guards requests only, so the policy has no effect on a context that only sends them.
 *
 * Deriving again is a restore only when the earlier context for the same keys is discarded first: at
 * most one live context may exist per store and context name ([SecurityContext.derive]).
 */
public enum class FreshnessPolicy {
    /**
     * Requires the proof unless the keys are new in this process run, that is unless the store is an
     * [EphemeralSequenceNumberStore] that had no value for the context. A context restored from its
     * store, or one with any other store, is challenged: a store other than the ephemeral one stands for
     * provisioned keys, which an earlier run may have used even when it never had to persist a number
     * (a server whose responses reuse the request nonce takes no sequence numbers).
     */
    AUTO,

    /** Always challenges the first request. */
    REQUIRE_PROOF,

    /**
     * Trusts the empty replay window from the start. Only correct when no request under these keys can
     * have been accepted before, for example right after EDHOC derived them (RFC 9528 Appendix A.1).
     */
    ASSUME_FRESH,
}
