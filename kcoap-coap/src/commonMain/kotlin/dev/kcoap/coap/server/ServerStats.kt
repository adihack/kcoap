package dev.kcoap.coap.server

/**
 * How much a [CoapServer] holds at the moment [CoapServer.stats] was called, for gauges in metrics and for
 * diagnostics; set each against the bound of [CoapServerConfig] it names. What its endpoint holds is
 * `CoapEndpoint.stats()`, and what happened is counted from its `EndpointEvent`s.
 *
 * Reading costs no lock: the server writes these counts as it changes them, so [CoapServer.stats] can be called from
 * any thread at any rate, and two values may be from moments a few requests apart.
 *
 * @property observers the observers registered on all resources (RFC 7641 §4.1), at most
 *   [CoapServerConfig.maxObservers].
 * @property block1Transfers the Block1 request bodies being assembled (RFC 7959 §2.5), at most
 *   [CoapServerConfig.maxConcurrentTransfers]; one that expired after EXCHANGE_LIFETIME may count until the next
 *   transfer starts.
 * @property block2Transfers the response bodies kept for their later Block2 blocks (RFC 7959 §2.4), bounded and
 *   expiring like [block1Transfers].
 * @property qBlock1Bodies the Q-Block1 request bodies being assembled (RFC 9177 §4.3), at most
 *   `QBlockServerConfig.maxPartialBodies`.
 * @property qBlock2Bodies the Q-Block2 response bodies kept for Continues and requests of missing blocks, or still
 *   sending their sets (RFC 9177 §4.4), at most twice `QBlockServerConfig.maxCachedBodies`.
 */
public class ServerStats internal constructor(
    public val observers: Int,
    public val block1Transfers: Int,
    public val block2Transfers: Int,
    public val qBlock1Bodies: Int,
    public val qBlock2Bodies: Int,
) {
    override fun toString(): String =
        "ServerStats(observers=$observers, block1Transfers=$block1Transfers, block2Transfers=$block2Transfers, " +
            "qBlock1Bodies=$qBlock1Bodies, qBlock2Bodies=$qBlock2Bodies)"
}
