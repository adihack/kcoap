package dev.kcoap.coap.stack

/**
 * How much a [CoapEndpoint] holds at the moment [CoapEndpoint.stats] was called, for gauges in metrics and for
 * diagnostics; set each against the bound of [CoapEndpointConfig] it names. Every value is a count of what is held
 * now, not of what happened: count what happened from [EndpointEvent]s, such as [EndpointEvent.RequestCompleted]
 * and [EndpointEvent.LimitReached].
 *
 * Reading costs no lock: each layer writes its counts as it changes them, so [CoapEndpoint.stats] can be called from
 * any thread at any rate, and two values may be from moments a few messages apart.
 *
 * @property exchanges the client exchanges open: requests waiting for a response, observations, and the requests of
 *   Q-Block bodies; bounded by [CoapEndpointConfig.maxExchanges].
 * @property runningHandlers the request handlers running now, at most [CoapEndpointConfig.maxConcurrentRequests].
 * @property transmissions the messages the datagram messaging layer keeps (RFC 7252 §4): Confirmable ones until they
 *   are acknowledged or given up, those queued behind NSTART, and Non-confirmable ones for NON_LIFETIME so a Reset
 *   still matches; bounded by [CoapEndpointConfig.maxExchanges]. 0 without a datagram transport.
 * @property deduplicationEntries the received messages remembered for deduplication (RFC 7252 §4.5), at most
 *   [CoapEndpointConfig.maxDeduplicationEntries]. 0 without a datagram transport.
 * @property reservedMessageIds the Message IDs reserved towards any peer (RFC 7252 §4.4), at most
 *   [CoapEndpointConfig.maxReservedMessageIds]. 0 without a datagram transport.
 * @property connections the connections open (RFC 8323), opened or accepted, the CSM exchanged or not; at most
 *   [ReliableTransportConfig.maxConnections] accepted ones.
 */
public class EndpointStats internal constructor(
    public val exchanges: Int,
    public val runningHandlers: Int,
    public val transmissions: Int,
    public val deduplicationEntries: Int,
    public val reservedMessageIds: Int,
    public val connections: Int,
) {
    override fun toString(): String =
        "EndpointStats(exchanges=$exchanges, runningHandlers=$runningHandlers, transmissions=$transmissions, " +
            "deduplicationEntries=$deduplicationEntries, reservedMessageIds=$reservedMessageIds, connections=$connections)"
}
