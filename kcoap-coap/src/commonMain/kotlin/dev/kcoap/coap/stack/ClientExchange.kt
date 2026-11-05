package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.OptionSet
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
 * Every response is [inspect]ed before the endpoint answers it at the messaging layer: verified by the
 * [ProtectedRequest] of the request, here between the messaging layer and the layers above, where RFC 8613
 * Figure 1 places OSCORE, and then checked for critical options the caller does not recognize
 * (RFC 7252 §5.4.1). A response that fails verification is dropped (RFC 8613 §8.4 step 8) and does not end
 * the exchange; one with an unrecognized critical option is rejected and ends it with
 * [CoapException.Reason.BAD_RESPONSE].
 */
internal class ClientExchange(
    private val endpoint: CoapEndpoint,
    val peer: Endpoint,
    val token: ByteArray,
    val isObservation: Boolean,
    /** The critical options the caller processes in a response; any other one rejects it (RFC 7252 §5.4.1). */
    private val recognizedOptions: Set<Int>,
    private val log: Logger,
) : TransmissionOwner {
    val key: ExchangeKey = ExchangeKey(peer, token.toHex())

    /** The request as sent, after protection. */
    var request: CoapMessage? = null
    var protectedRequest: ProtectedRequest? = null

    /** The datagram transmission of the request; null on a connection. Set under the endpoint's lock. */
    @Volatile
    var transmission: Transmission? = null

    /** The connection the request went on (RFC 8323), whose responses alone match; null for datagrams. */
    var connection: ConnectionDriver? = null

    /** Set once by [CoapEndpoint.forget], under its lock. */
    var isForgotten: Boolean = false

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
            is TransmissionOutcome.Acknowledged -> outcome.piggybackedResponse?.let { onResponse(it) }
            TransmissionOutcome.Reset -> fail(CoapException("Request rejected with RST by $peer", CoapException.Reason.RESET))
            TransmissionOutcome.TimedOut -> fail(
                CoapException("No ACK from $peer after MAX_RETRANSMIT retransmissions", CoapException.Reason.TRANSMISSION_TIMEOUT),
            )
            TransmissionOutcome.SlotReleased -> Unit
            is TransmissionOutcome.Failed -> fail(outcome.cause)
        }
    }

    /** What [inspect] found, which decides how the endpoint answers the response at the messaging layer. */
    sealed interface Inspection {
        /** Verified, with every critical option recognized: acknowledge it, then [accept] it. */
        class Accepted(val response: CoapMessage) : Inspection

        /** Verified, with a critical option the caller does not recognize: reject it, then [refuse] it. */
        class Rejected(val cause: CoapException) : Inspection

        /**
         * It failed verification (RFC 8613 §8.4) and is dropped, and the exchange goes on. It is still
         * acknowledged: the ACK says only that the message arrived, and a Reset would cancel an observation,
         * which RFC 8613 §8.4.2 says an error in one notification does not do.
         */
        data object Unverified : Inspection
    }

    /**
     * Verifies [outer] and checks its critical options (RFC 7252 §5.4.1), without handing it on, so the
     * endpoint decides between an ACK and a Reset only after both.
     */
    suspend fun inspect(outer: CoapMessage): Inspection {
        val security = protectedRequest
        val inner = if (security == null) {
            outer
        } else {
            try {
                security.unprotectResponse(outer)
            } catch (e: MessageSecurityException) {
                log.warn(e) { "Dropped a response that failed verification: ${e.message}" }
                return Inspection.Unverified
            }
        }
        val unrecognized = unrecognizedCritical(inner.options, recognizedOptions)
        if (unrecognized.isNotEmpty()) {
            val numbers = unrecognized.joinToString { it.number.toString() }
            log.warn { "Rejected a response ${inner.code} with unrecognized critical options $numbers" }
            return Inspection.Rejected(
                CoapException("Response with unrecognized critical options $numbers", CoapException.Reason.BAD_RESPONSE),
            )
        }
        return Inspection.Accepted(inner)
    }

    /**
     * A response with nothing to answer at the messaging layer: piggybacked in an ACK, which cannot be
     * rejected (RFC 7252 §5.4.1 rejects the response only), or on a connection (RFC 8323).
     */
    suspend fun onResponse(outer: CoapMessage) {
        when (val inspection = inspect(outer)) {
            is Inspection.Accepted -> accept(inspection.response)
            is Inspection.Rejected -> refuse(inspection.cause)
            Inspection.Unverified -> Unit
        }
    }

    /**
     * Hands on an [Inspection.Accepted] response. A separate response also ends the request's
     * retransmission (RFC 7252 §5.2.2: it serves as an acknowledgement) and frees a Non-confirmable request's
     * NSTART slot; one that failed verification does neither.
     */
    suspend fun accept(response: CoapMessage) {
        settleRequest()
        if (!isOpen) return
        log.debug { "Response ${response.code}" }
        if (isObservation) {
            responses.trySend(response)
        } else {
            close()
            responses.trySend(response)
            responses.close()
        }
    }

    /** Ends the exchange after an [Inspection.Rejected] response. */
    suspend fun refuse(cause: CoapException) {
        settleRequest()
        fail(cause)
    }

    private suspend fun settleRequest() {
        transmission?.takeIf { it.state != Transmission.State.DONE }?.let { endpoint.settle(it) }
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

    companion object {
        /** Notifications waiting for a slow collector; the oldest are dropped (RFC 7641 §3.3.1 allows skipping states). */
        private const val OBSERVATION_BUFFER = 64

        /** The critical options of a client that does Block-wise transfers (RFC 7959): Block1 and Block2. */
        val BLOCKWISE_OPTIONS: Set<Int> = setOf(CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number)

        /** RFC 7252 §5.4.1: the critical (odd-numbered) options in [options] that are not in [recognized]. */
        fun unrecognizedCritical(options: OptionSet, recognized: Set<Int>): List<CoapOption> =
            options.filter { it.number % 2 == 1 && it.number !in recognized }
    }
}

/** What responses are matched by: the peer, compared by [Endpoint] equality, and the token. */
internal data class ExchangeKey(val peer: Endpoint, val token: String)
