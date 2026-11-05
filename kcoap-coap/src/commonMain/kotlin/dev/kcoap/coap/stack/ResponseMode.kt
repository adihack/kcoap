package dev.kcoap.coap.stack

/** How a [ClientExchange] hands on the responses to its request. */
internal sealed interface ResponseMode {
    /** The first response ends the exchange (RFC 7252 §5.2): an ordinary request. */
    data object Single : ResponseMode

    /**
     * Every response is handed on and the exchange stays open until it is cancelled or fails: an
     * observation, whose notifications all answer the registration (RFC 7641 §3.2). A collector that falls
     * more than 64 behind loses the oldest, which §3.3.1 allows, since a newer state replaces them.
     */
    data object Stream : ResponseMode

    /**
     * Responses, and how the request's transmission ended, go to [inbox] as [ExchangeEvent]s, and the
     * exchange stays open until it is cancelled or ends: a request of a Q-Block body, which may get several
     * responses on one token (RFC 9177 §4.3, §4.4), sharing one inbox with the other requests of its body.
     */
    class Inbox(val inbox: ExchangeInbox) : ResponseMode {
        override fun toString(): String = "Inbox"
    }
}
