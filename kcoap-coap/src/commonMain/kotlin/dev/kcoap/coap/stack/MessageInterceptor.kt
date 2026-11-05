package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint

/**
 * Sees, and may change or drop, every message at the wire: after OSCORE protection on the way out, before
 * deduplication and OSCORE verification on the way in. Use it for tracing, metrics or fault injection.
 *
 * Interceptors form a chain in the order of [CoapEndpointConfig.interceptors]: each one receives what the
 * previous returned, and returning null drops the message. Outgoing interception happens once per
 * message, not per retransmission (see [EndpointEvent.Retransmitted]).
 *
 * Changing the type, Message ID or token breaks the messaging layer's bookkeeping; change only options and
 * payload unless that is the point of the test. Interceptors run on the endpoint's coroutines and must be
 * quick and must not throw; an exception drops the message and is logged.
 */
public interface MessageInterceptor {
    /** [message] is about to be encoded and sent to [to]. */
    public fun onSend(to: Endpoint, message: CoapMessage): CoapMessage? = message

    /** [message] was decoded from a datagram that came from [from]. */
    public fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage? = message
}

/**
 * Passes [message] through the chain; null when an interceptor dropped it. An interceptor that throws
 * drops the message too, and is logged.
 */
internal inline fun List<MessageInterceptor>.intercept(
    message: CoapMessage,
    log: Logger,
    call: (MessageInterceptor, CoapMessage) -> CoapMessage?,
): CoapMessage? {
    var current = message
    for (interceptor in this) {
        current = try {
            call(interceptor, current)
        } catch (e: Exception) {
            log.error(e) { "Interceptor $interceptor failed; dropping $current" }
            null
        } ?: return null
    }
    return current
}
