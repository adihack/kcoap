package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.common.toHex
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

/**
 * One request on the wire and the responses it gets, matched by peer [Endpoint] and token
 * (RFC 7252 §5.3.2, §9.1.2). An observation keeps its exchange open for every notification
 * (RFC 7641 §3.2); any other exchange ends with its first response.
 *
 * Responses are verified by the [ProtectedRequest] of the request, here between the messaging layer
 * and the layers above, where RFC 8613 Figure 1 places OSCORE. A response that fails verification is
 * dropped (RFC 8613 §8.4 step 8) and does not end the exchange.
 */
internal class ClientExchange(
    private val endpoint: CoapEndpoint,
    val peer: Endpoint,
    val token: ByteArray,
    val isObservation: Boolean,
    private val log: Logger,
) : TransmissionOwner {
    val key: ExchangeKey = ExchangeKey(peer, token.toHex())

    /** The request as sent, after protection. */
    var request: CoapMessage? = null
    var protectedRequest: ProtectedRequest? = null
    var transmission: Transmission? = null

    @Volatile
    var isOpen: Boolean = true
        private set

    private val responses = if (isObservation) {
        Channel<CoapMessage>(OBSERVATION_BUFFER, BufferOverflow.DROP_OLDEST)
    } else {
        Channel(Channel.CONFLATED)
    }

    override suspend fun onOutcome(transmission: Transmission, outcome: TransmissionOutcome) {
        when (outcome) {
            is TransmissionOutcome.Acknowledged -> outcome.piggybackedResponse?.let { receive(it) }
            TransmissionOutcome.Reset -> fail(CoapException("Request rejected with RST by $peer", CoapException.Reason.RESET))
            TransmissionOutcome.TimedOut -> fail(
                CoapException("No ACK from $peer after MAX_RETRANSMIT retransmissions", CoapException.Reason.TRANSMISSION_TIMEOUT),
            )
            TransmissionOutcome.SlotReleased -> Unit
            is TransmissionOutcome.Failed -> fail(outcome.cause)
        }
    }

    /**
     * A separate response matched this exchange. It also ends the request's retransmission (RFC 7252
     * §5.2.2: it serves as an acknowledgement) and frees a Non-confirmable request's NSTART slot.
     */
    suspend fun onSeparateResponse(response: CoapMessage) {
        transmission?.let { endpoint.settle(it) }
        receive(response)
    }

    private suspend fun receive(outer: CoapMessage) {
        val security = protectedRequest
        val inner = if (security == null) {
            outer
        } else {
            try {
                security.unprotectResponse(outer)
            } catch (e: MessageSecurityException) {
                log.warn(e) { "Dropped a response that failed verification: ${e.message}" }
                return
            }
        }
        log.debug { "Response ${inner.code}" }
        if (isObservation) {
            responses.trySend(inner)
        } else {
            close()
            responses.trySend(inner)
            responses.close()
        }
    }

    /** The next response; throws [CoapException] when the exchange failed. */
    suspend fun awaitResponse(): CoapMessage = responses.receive()

    suspend fun receiveCatching(): ChannelResult<CoapMessage> = responses.receiveCatching()

    suspend fun fail(cause: CoapException) {
        if (!isOpen) return
        log.debug { "Exchange failed: ${cause.message}" }
        close()
        responses.close(cause)
    }

    /** Stops the exchange: forgets the token and stops retransmitting the request. */
    suspend fun cancel() {
        if (!isOpen) return
        close()
        responses.close()
        transmission?.let { endpoint.settle(it) }
    }

    private suspend fun close() {
        isOpen = false
        endpoint.forget(this)
    }

    override fun toString(): String = "ClientExchange($peer, token=${key.token}, observation=$isObservation)"

    private companion object {
        /** Notifications waiting for a slow collector; the oldest are dropped (RFC 7641 §3.3.1 allows skipping states). */
        const val OBSERVATION_BUFFER = 64
    }
}

/** What responses are matched by: the peer, compared by [Endpoint] equality, and the token. */
internal data class ExchangeKey(val peer: Endpoint, val token: String)
