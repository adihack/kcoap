package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.common.toHex
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult

/**
 * One request on the wire and the responses it gets, matched by peer [Endpoint] and token
 * (RFC 7252 §5.3.2, §9.1.2), and on a connection only on the [link] it went on (RFC 8323 §3.3). How the
 * request's delivery ends comes from the link as a [DeliveryOutcome]: an ACK, perhaps with the response
 * piggybacked, a Reset or a timeout over datagrams, or a failure on any link, such as a closed connection.
 * Its [mode] says how responses are handed on: [ResponseMode.Single] ends the
 * exchange with the first one, [ResponseMode.Stream] keeps it open for every notification of an
 * observation (RFC 7641 §3.2), and [ResponseMode.Inbox] reports responses and outcomes to the inbox of a
 * Q-Block body, which may get several responses on one token (RFC 9177 §4.3, §4.4).
 *
 * Every response is [inspect]ed before the endpoint answers it at the messaging layer: verified by the
 * [ProtectedRequest] of the request, here between the messaging layer and the layers above, where RFC 8613
 * Figure 1 places OSCORE, and then checked for critical options the caller does not recognize
 * (RFC 7252 §5.4.1). A response that fails verification is dropped (RFC 8613 §8.4 step 8) and does not end
 * the exchange, but is counted and reported as [EndpointEvent.ResponseRejected]: when no response verifies, the
 * [responseTimeout] carries the last such failure as its cause. One with an unrecognized critical option is
 * rejected and ends the exchange with [CoapException.Reason.BAD_RESPONSE].
 *
 * A response the security layer hands on as [ResponseVerification.Unverified], an error without message
 * security (RFC 8613 §8.2), is never handed on as a response, so nothing above acts on it (ADR 0008): it ends
 * a [ResponseMode.Single] exchange, and a [ResponseMode.Stream] one that has no verified response yet, with
 * [CoapException.Reason.UNVERIFIED_RESPONSE]; an observation that has its verified registration response drops
 * it; an inbox exchange reports it as [ExchangeEvent.Unverified] for the Q-Block layer to decide.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ClientExchange(
    private val endpoint: CoapEndpoint,
    /** The link the request goes on; on a connection, only responses on that connection match it. */
    val link: PeerLink,
    val token: ByteArray,
    val mode: ResponseMode,
    /** The critical options the caller processes in a response; any other one rejects it (RFC 7252 §5.4.1). */
    private val recognizedOptions: Set<Int>,
    private val log: Logger,
) : DeliveryOwner {
    val peer: Endpoint = link.peer
    val key: ExchangeKey = ExchangeKey(peer, token.toHex())

    /** The request as sent, after protection. */
    var request: CoapMessage? = null
    var protectedRequest: ProtectedRequest? = null

    /**
     * How the request goes out on a datagram link, until it is answered or settled; set under the link's lock.
     * Null on a connection, where it is written and done.
     */
    @Volatile
    var delivery: Delivery? = null

    /** Set once by [CoapEndpoint.forget], under its lock. */
    var isForgotten: Boolean = false

    @Volatile
    var isOpen: Boolean = true
        private set

    /** Whether a verified response was handed on: from then on a [ResponseMode.Stream] exchange drops unverified ones. */
    @Volatile
    private var hasVerifiedResponse = false

    /** How many responses failed verification and were dropped (RFC 8613 §8.4), for [responseTimeout]. */
    private val droppedResponses = AtomicInt(0)

    /** The last of [droppedResponses], with when it came, which [responseTimeout] gives as cause. */
    @Volatile
    private var lastDropped: Dropped? = null

    private class Dropped(val error: MessageSecurityException, val at: Duration)

    /** Where [ResponseMode.Single] and [ResponseMode.Stream] hand on responses; an inbox exchange has none. */
    private val responses: Channel<CoapMessage>? = when (mode) {
        ResponseMode.Single -> Channel(Channel.CONFLATED)
        ResponseMode.Stream -> Channel(OBSERVATION_BUFFER, BufferOverflow.DROP_OLDEST)
        is ResponseMode.Inbox -> null
    }

    override suspend fun onOutcome(delivery: Delivery, outcome: DeliveryOutcome) {
        when (outcome) {
            is DeliveryOutcome.Delivered -> {
                val piggybacked = outcome.piggybacked
                when {
                    piggybacked != null -> onResponse(piggybacked)
                    mode is ResponseMode.Inbox && isOpen -> report(mode.inbox, ExchangeEvent.Acknowledged(this))
                }
            }
            DeliveryOutcome.Rejected -> end(
                CoapException("Request rejected with RST by $peer", CoapException.Reason.RESET),
                ExchangeEvent.Reset(this),
            )
            DeliveryOutcome.TimedOut -> end(
                CoapException("No ACK from $peer after MAX_RETRANSMIT retransmissions", CoapException.Reason.TRANSMISSION_TIMEOUT),
                ExchangeEvent.TimedOut(this),
            )
            DeliveryOutcome.Released -> Unit
            is DeliveryOutcome.Failed -> fail(outcome.cause)
        }
    }

    /** What [inspect] found, which decides how the endpoint answers the response at the messaging layer. */
    sealed interface Inspection {
        /**
         * Verified, or the answer to a request sent without message security, with every critical option
         * recognized: [accept] it, then acknowledge it if it was taken.
         */
        class Accepted(val response: CoapMessage) : Inspection

        /** Verified, with a critical option the caller does not recognize: reject it, then [refuse] it. */
        class Rejected(val cause: CoapException) : Inspection

        /**
         * The security layer handed it on unverified ([ResponseVerification.Unverified]): [acceptUnverified] it,
         * which acts on nothing in it, then acknowledge it if it was taken.
         */
        class Unverified(val response: CoapMessage, val reason: String) : Inspection

        /**
         * It failed verification (RFC 8613 §8.4) and is dropped, and the exchange goes on. It is still
         * acknowledged: the ACK says only that the message arrived, and a Reset would cancel an observation,
         * which RFC 8613 §8.4.2 says an error in one notification does not do.
         */
        data object Dropped : Inspection
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
            val verification = try {
                security.unprotectResponse(outer)
            } catch (e: MessageSecurityException) {
                // A peer's input, expected in RFC 8613 §8.4: logged once, at DEBUG and without a stack trace.
                droppedResponses.incrementAndFetch()
                lastDropped = Dropped(e, endpoint.now())
                log.debug { "Dropped a response that failed verification: ${e.message}" }
                endpoint.report(EndpointEvent.ResponseRejected(peer, EndpointEvent.ResponseRejected.Reason.VERIFICATION_FAILED, e.message))
                return Inspection.Dropped
            }
            when (verification) {
                is ResponseVerification.Verified -> verification.message
                is ResponseVerification.Unverified -> {
                    val detail = "${verification.message.code}: ${verification.reason}"
                    endpoint.report(EndpointEvent.ResponseRejected(peer, EndpointEvent.ResponseRejected.Reason.UNVERIFIED, detail))
                    return Inspection.Unverified(verification.message, verification.reason)
                }
            }
        }
        val unrecognized = unrecognizedCritical(inner.options, recognizedOptions)
        if (unrecognized.isNotEmpty()) {
            val numbers = unrecognized.joinToString { it.number.toString() }
            log.debug { "Rejected a response ${inner.code} with unrecognized critical options $numbers" }
            val message = "Response with unrecognized critical options $numbers"
            return Inspection.Rejected(CoapException(message, CoapException.Reason.BAD_RESPONSE))
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
            is Inspection.Unverified -> acceptUnverified(inspection.response, inspection.reason)
            Inspection.Dropped -> Unit
        }
    }

    /**
     * Hands on an [Inspection.Accepted] response. A separate response also ends the request's
     * retransmission (RFC 7252 §5.2.2: it serves as an acknowledgement) and frees a Non-confirmable request's
     * NSTART slot; one that failed verification does neither.
     *
     * An inbox exchange queues it in the room [reservation] holds, which the endpoint took before verifying a
     * Confirmable response ([ExchangeInbox.tryReserve]); the caller releases a reservation this did not use.
     * Returns false only when the [ExchangeInbox] of an inbox exchange had no room for it, so it was not taken:
     * the endpoint then leaves a Confirmable one unacknowledged, for its sender to send again (RFC 7252 §4.2).
     * True otherwise, also when the exchange is closed and drops it.
     */
    suspend fun accept(response: CoapMessage, reservation: ExchangeInbox.Reservation? = null): Boolean {
        settleRequest()
        if (!isOpen) return true
        log.debug { "Response ${response.code}" }
        hasVerifiedResponse = true
        when (mode) {
            ResponseMode.Single -> {
                close()
                responses?.trySend(response)
                responses?.close()
            }
            ResponseMode.Stream -> responses?.trySend(response)
            is ResponseMode.Inbox -> return report(mode.inbox, ExchangeEvent.Response(this, response), reservation)
        }
        return true
    }

    /**
     * Takes an [Inspection.Unverified] response without acting on it (ADR 0008). An observation that has its
     * verified registration response drops it: a server sends no unprotected notifications, and an error of
     * OSCORE belongs to the verification of a request (RFC 8613 §8.2), so it ends neither the observation nor
     * reaches its collectors. An exchange still waiting for its response ends with
     * [CoapException.Reason.UNVERIFIED_RESPONSE], which carries it, and so stops retransmitting its request;
     * the caller then gets a distinct failure instead of waiting for its timeout. An inbox exchange reports it.
     *
     * An inbox exchange queues it in the room [reservation] holds, and returns false only when its [ExchangeInbox]
     * had no room for it, as [accept] does.
     */
    suspend fun acceptUnverified(response: CoapMessage, reason: String, reservation: ExchangeInbox.Reservation? = null): Boolean {
        if (!isOpen) return true
        when {
            mode is ResponseMode.Inbox -> return report(mode.inbox, ExchangeEvent.Unverified(this, response), reservation)
            mode == ResponseMode.Stream && hasVerifiedResponse -> log.debug { "Dropped an unverified ${response.code} to an observation: $reason" }
            else -> {
                log.debug { "Unverified ${response.code} to a protected request: $reason" }
                settleRequest()
                fail(unverified(response, reason))
            }
        }
        return true
    }

    /** Ends the exchange after an [Inspection.Rejected] response. */
    suspend fun refuse(cause: CoapException) {
        settleRequest()
        fail(cause)
    }

    private suspend fun settleRequest() {
        delivery?.takeIf { !it.isDone }?.let { link.settle(it) }
    }

    /**
     * The failure of this exchange when no response it could take came within [timeout]:
     * [CoapException.Reason.RESPONSE_TIMEOUT], with the last response that failed verification as cause.
     */
    fun responseTimeout(timeout: Duration): CoapException =
        responseTimeout("No response from $peer within $timeout", listOf(this))

    /** The next response; throws [CoapException] when the exchange failed. Not for an inbox exchange. */
    suspend fun awaitResponse(): CoapMessage = channel().receive()

    /** The next response, or the failure that closed the exchange. Not for an inbox exchange. */
    suspend fun receiveCatching(): ChannelResult<CoapMessage> = channel().receiveCatching()

    private fun channel(): Channel<CoapMessage> =
        checkNotNull(responses) { "An exchange in inbox mode reports to its inbox: $this" }

    /** Ends the exchange with [cause]; an inbox exchange reports [ExchangeEvent.Failed]. */
    suspend fun fail(cause: CoapException) {
        end(cause, ExchangeEvent.Failed(this, cause))
    }

    private suspend fun end(cause: CoapException, event: ExchangeEvent) {
        if (!isOpen) return
        log.debug { "Exchange ended: ${cause.message}" }
        close()
        when (mode) {
            is ResponseMode.Inbox -> report(mode.inbox, event)
            else -> responses?.close(cause)
        }
    }

    /** Queues [event] in [inbox], in the room [reservation] holds if any; false when it did not fit and was not taken. */
    private fun report(inbox: ExchangeInbox, event: ExchangeEvent, reservation: ExchangeInbox.Reservation? = null): Boolean {
        val queued = inbox.offer(event, reservation)
        if (!queued) log.debug { "The body's inbox is full; $event was not taken" }
        return queued
    }

    /** Stops the exchange: forgets the token and stops retransmitting the request. Reports nothing. */
    suspend fun cancel() {
        if (!isOpen) return
        close()
        responses?.close()
        delivery?.let { link.settle(it) }
    }

    private suspend fun close() {
        isOpen = false
        endpoint.forget(this)
    }

    override fun toString(): String = "ClientExchange($peer, token=${key.token}, mode=$mode)"

    companion object {
        /**
         * [CoapException.Reason.RESPONSE_TIMEOUT] with [message], for a request or a body whose [exchanges] got no
         * response they could take. When responses to them failed verification and were dropped (RFC 8613 §8.4),
         * the message says how many, and the cause is the last of them, so a caller can tell a stale or wrong
         * security context from a silent peer.
         */
        fun responseTimeout(message: String, exchanges: Collection<ClientExchange>): CoapException {
            val dropped = exchanges.sumOf { it.droppedResponses.load() }
            val last = exchanges.mapNotNull { it.lastDropped }.maxByOrNull { it.at }
            if (dropped == 0 || last == null) return CoapException(message, CoapException.Reason.RESPONSE_TIMEOUT)
            val count = if (dropped == 1) "1 response" else "$dropped responses"
            return CoapException(
                "$message; $count failed verification and were dropped (RFC 8613 §8.4), the last: ${last.error.message}",
                CoapException.Reason.RESPONSE_TIMEOUT,
                last.error,
            )
        }

        /** The failure of a request whose only answer was [response], unverified for [reason]. */
        fun unverified(response: CoapMessage, reason: String): CoapException = CoapException(
            "Unverified ${response.code} to a protected request: $reason",
            CoapException.Reason.UNVERIFIED_RESPONSE,
            response = CoapResponse.from(response),
        )

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
