package dev.kcoap.coap.server

/**
 * Whether and how a [CoapServer] serves Q-Block (RFC 9177): Q-Block1 uploads and Q-Block2 bodies, sent and
 * repaired in bursts of MAX_PAYLOADS Non-confirmable blocks ([dev.kcoap.coap.stack.TransmissionParameters]).
 *
 * Off by default: a request carrying Q-Block1 or Q-Block2 then gets 4.02 Bad Option, or a Reset when it is
 * Non-confirmable, so the client falls back to RFC 7959 (RFC 9177 §4.1). Turned on, the server recognizes
 * both options (§4.1 requires both or neither) and answers with Q-Block2 exactly when the request carried
 * Q-Block1 or Q-Block2, never because the client used Q-Block before. A body follows the message type of
 * the request: Non-confirmable bursts for a Non-confirmable request, Confirmable blocks in lock-step for a
 * Confirmable one (§7), back to back on a reliable transport (Appendix B).
 *
 * RFC 9177 §11 does not recommend Q-Block without security: the server logs a warning once at start when it
 * is on, that it runs without message security when [CoapServerConfig.security] is null, and otherwise that
 * requests without OSCORE, which such a server still answers unprotected, use it too. Before a burst to a
 * client whose address it has not verified it asks for an Echo (RFC 9175 §2.4;
 * [CoapServerConfig.amplification]).
 *
 * Every Q-Block2 body gets a 4-byte ETag of its own from a counter with a random start, which replaces an
 * ETag the resource set (§4.4: unique for every transmitted body), so 2.03 validation by the resource's own
 * ETag does not work on such bodies. A registration for Observe that carries Q-Block2 is served as a plain
 * request, without adding an observer (RFC 7641 §4.1).
 *
 * Memory is bounded: at most [maxPartialBodies] uploads, each up to [CoapServerConfig.maxBodySize] bytes,
 * are assembled at a time, each holding only the blocks that arrived (never what its Size1 claims before
 * they do), and at most [maxCachedBodies] response bodies, and as many records of completed uploads, are
 * kept for NON_PARTIAL_TIMEOUT after their last use for requests of missing blocks.
 *
 * @property isEnabled whether the server takes Q-Block requests at all.
 * @property withMessageSecurity whether requests protected by [CoapServerConfig.security] may use Q-Block.
 *   Several responses to one request fall outside the single-response model of OSCORE (RFC 8613 §7.4), so
 *   protected requests use RFC 7959 block-wise transfer by default: a protected Q-Block request gets 4.02.
 *   Turned on, every response after the first to a request takes a new Partial IV (RFC 8613 §8.3), and the
 *   client's OSCORE must take several responses per request, as kcoap-oscore's `OscoreClient` does for requests
 *   carrying Q-Block; libcoap 4.3.5b's client verifies only the first, so it does not get Q-Block2 bodies.
 * @property onReliableTransports whether requests over TCP and WebSockets (RFC 8323) may use Q-Block. There
 *   the blocks of a body go back to back and only the final response matters (RFC 9177 Appendix B).
 * @property maxPartialBodies the most Q-Block1 uploads being assembled at a time, across all clients; one
 *   more gets 5.03 Service Unavailable with Max-Age and is reported as
 *   [dev.kcoap.coap.stack.EndpointEvent.LimitReached] with `PARTIAL_BODIES`.
 * @property maxCachedBodies the most Q-Block2 bodies kept for requests of missing blocks and Continues, and
 *   separately the most completed uploads remembered so that a block sent again gets the final response
 *   again (§4.3); the one used longest ago gives way (`CACHED_BODIES`), among bodies first the one used
 *   longest ago that has sent all its sets. A body that gives way while still sending keeps sending its sets,
 *   up to as many such bodies again. A GET or FETCH body of one block is not kept: the handler runs again.
 * @property maxBodiesPerPeer the most uploads one client endpoint may have in progress, and the most response
 *   bodies kept for it: a further upload gets 5.03 with Max-Age, a further body replaces the client's oldest,
 *   as for [maxCachedBodies] (`BODIES_PER_PEER`).
 */
public class QBlockServerConfig(
    public val isEnabled: Boolean = false,
    public val withMessageSecurity: Boolean = false,
    public val onReliableTransports: Boolean = true,
    public val maxPartialBodies: Int = DEFAULT_MAX_BODIES,
    public val maxCachedBodies: Int = DEFAULT_MAX_BODIES,
    public val maxBodiesPerPeer: Int = DEFAULT_MAX_BODIES_PER_PEER,
) {
    init {
        require(maxPartialBodies > 0) { "At least one partial body must be allowed: $maxPartialBodies" }
        require(maxCachedBodies > 0) { "At least one cached body must be allowed: $maxCachedBodies" }
        require(maxBodiesPerPeer > 0) { "At least one body per peer must be allowed: $maxBodiesPerPeer" }
    }

    public companion object {
        /** See [maxPartialBodies] and [maxCachedBodies]. */
        public const val DEFAULT_MAX_BODIES: Int = 64

        /** See [maxBodiesPerPeer]. */
        public const val DEFAULT_MAX_BODIES_PER_PEER: Int = 4
    }

    override fun toString(): String =
        "QBlockServerConfig(isEnabled=$isEnabled, withMessageSecurity=$withMessageSecurity, " +
            "onReliableTransports=$onReliableTransports, maxPartialBodies=$maxPartialBodies, " +
            "maxCachedBodies=$maxCachedBodies, maxBodiesPerPeer=$maxBodiesPerPeer)"
}
