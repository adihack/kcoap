package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint

/**
 * What a [dev.kcoap.coap.client.CoapClient] with Q-Block turned on
 * ([dev.kcoap.coap.client.QBlockClientConfig]) reports about its Q-Block bodies (RFC 9177), through its
 * endpoint's [CoapEndpointConfig.eventListener]: when it falls back to RFC 7959, when it recovers lost
 * blocks, and when it gives a partial body up. A rising [Recovery] count shows a lossy path; [Fallback] shows
 * servers that do not take Q-Block.
 */
public sealed interface QBlockClientEvent : EndpointEvent {
    /**
     * Requests to [peer] use Block1 and Block2 (RFC 7959) instead of Q-Block, for [reason]: the probe was
     * answered without Q-Block or not at all (RFC 9177 §4.1), or the server rejected a Q-Block message with
     * 4.02 or a Reset, in which case that request is sent again with RFC 7959. Reported once per finding, not
     * per request.
     */
    public class Fallback(override val peer: Endpoint, public val reason: String) : QBlockClientEvent {
        override fun toString(): String = "QBlockClientEvent.Fallback(peer=$peer, reason=$reason)"
    }

    /**
     * Lost [blocks] of a body were recovered: [sent] is true when the client asked [peer] for missing blocks
     * of a response body (Q-Block2, RFC 9177 §4.4), false when [peer] asked for blocks of an uploaded body
     * with a 4.08 (Request Entity Incomplete) and the client sent them again (Q-Block1, §4.3, §5).
     */
    public class Recovery(override val peer: Endpoint, public val sent: Boolean, public val blocks: List<Long>) : QBlockClientEvent {
        override fun toString(): String = "QBlockClientEvent.Recovery(peer=$peer, sent=$sent, blocks=$blocks)"
    }

    /**
     * A partially received response body from [peer] was given up for [reason]: its missing blocks did not
     * arrive after NON_MAX_RETRANSMIT requests, or before its lifetime ran out (RFC 9177 §4.4, §7.2). The
     * request fails.
     */
    public class PartialBodyDropped(override val peer: Endpoint, public val reason: String) : QBlockClientEvent {
        override fun toString(): String = "QBlockClientEvent.PartialBodyDropped(peer=$peer, reason=$reason)"
    }
}
