package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage

/** What an exchange in [ResponseMode.Inbox] reports to its [ExchangeInbox]. */
internal sealed interface ExchangeEvent {
    /** The exchange the event belongs to; its token tells the requests of one body apart. */
    val exchange: ClientExchange

    /**
     * A response that passed verification and whose critical options are all recognized (RFC 7252 §5.4.1).
     * The exchange stays open for more.
     */
    class Response(override val exchange: ClientExchange, val message: CoapMessage) : ExchangeEvent {
        override fun toString(): String = "Response(${exchange.key.token}, ${message.code})"
    }

    /**
     * A response that message security handed on unverified, an error without message security (RFC 8613 §8.2;
     * [dev.kcoap.coap.security.ResponseVerification.Unverified]). Nothing may act on it as on a response: the
     * body either ends with [CoapException.Reason.UNVERIFIED_RESPONSE] or, for an established observation,
     * ignores it (ADR 0008). The exchange stays open.
     */
    class Unverified(override val exchange: ClientExchange, val message: CoapMessage) : ExchangeEvent {
        override fun toString(): String = "Unverified(${exchange.key.token}, ${message.code})"
    }

    /**
     * A Confirmable request was acknowledged with an Empty ACK (RFC 7252 §5.2.2): it arrived, and a response
     * may follow separately. The exchange stays open. A Confirmable block of a Q-Block1 body is answered this
     * way, and the next block may go (RFC 9177 §7.1).
     */
    class Acknowledged(override val exchange: ClientExchange) : ExchangeEvent {
        override fun toString(): String = "Acknowledged(${exchange.key.token})"
    }

    /** The peer rejected the request with a Reset (RFC 7252 §4.2, §4.3). The exchange has ended. */
    class Reset(override val exchange: ClientExchange) : ExchangeEvent {
        override fun toString(): String = "Reset(${exchange.key.token})"
    }

    /**
     * A Confirmable request went unacknowledged through MAX_RETRANSMIT retransmissions (§4.2). The exchange
     * has ended.
     */
    class TimedOut(override val exchange: ClientExchange) : ExchangeEvent {
        override fun toString(): String = "TimedOut(${exchange.key.token})"
    }

    /**
     * The exchange ended for [cause]: a response it rejected ([CoapException.Reason.BAD_RESPONSE]), the
     * endpoint closing, the peer's DTLS session ending, its connection closing, or a transport that refused
     * the request.
     */
    class Failed(override val exchange: ClientExchange, val cause: CoapException) : ExchangeEvent {
        override fun toString(): String = "Failed(${exchange.key.token}, ${cause.reason})"
    }
}
