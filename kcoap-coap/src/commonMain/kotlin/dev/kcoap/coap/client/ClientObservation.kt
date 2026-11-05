package dev.kcoap.coap.client

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.ClientExchange
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.ResponseMode
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One registration with a server (RFC 7641 §3.1), shared by every collector that observes the same
 * target resource, since a client MUST NOT register more than once for it.
 *
 * Notifications are ordered by §3.4 and stale ones dropped; with message security, ordering and replay
 * protection are the security layer's job (RFC 8613 §4.1.3.5.2), whose verified notifications are all
 * passed on. An unverified response, an error without message security (RFC 8613 §8.2), fails a registration
 * that has no verified answer yet with [CoapException.Reason.UNVERIFIED_RESPONSE] and is dropped afterwards, so
 * it never ends an observation or has the registration sent again with its Echo value (ADR 0008); a
 * notification whose Block2 follow-up gets only an unverified answer is dropped too, and the observation waits
 * for the next. On a reliable transport, which delivers in order, the Observe value MUST be ignored and every
 * notification is passed on (RFC 8323 §7.1). Large notifications are completed with Block2 requests
 * (RFC 7959 §2.6). A response without Observe, or a non-2.xx one, ends the observation (§3.2).
 *
 * Where the client's Q-Block (RFC 9177) applies to the server, the observation registers with Q-Block2, or
 * uploads a FETCH body in Q-Block1, and every notification body comes in Q-Block2 ([QBlockClient.observe],
 * §4.5): it is assembled per ETag and passed on once, ordered by §3.4 on its first block, or, on a protected
 * exchange, by the security layer, which takes the blocks of the freshest body in any order. Such an observation
 * keeps the token the registration was answered on, the token of the request that completed a Q-Block1
 * upload (Figure 14); one uploaded with Q-Block1 ends by forgetting that token, since its body sent again
 * with Observe 1 would need a new Request-Tag. A server that rejects Q-Block gets the RFC 7959 registration.
 *
 * Every observation ends with exactly one terminal event, [Event.Final] or [Event.Failure], whatever ends
 * it: the server, an error, or the endpoint closing, which cancels the coroutine the observation runs
 * in. Collectors therefore always complete or fail and never wait for a flow that will not emit again.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ClientObservation(
    private val client: CoapClient,
    val peer: Endpoint,
    val key: OperationKey,
    private val registration: CoapMessage,
    private val confirmable: Boolean,
    private val log: Logger,
) {
    sealed interface Event {
        class Notification(val response: CoapResponse) : Event

        /** The observation ended with [response], which is passed on last. */
        class Final(val response: CoapResponse) : Event

        class Failure(val cause: Throwable) : Event
    }

    private val mutableEvents = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> get() = mutableEvents

    /** Collectors, counted by [CoapClient] under its lock. */
    var subscribers: Int = 0

    private var job: Job? = null
    private val terminated = AtomicBoolean(false)

    @Volatile
    private var exchange: ClientExchange? = null

    @Volatile
    private var established = false
    private val order = NotificationOrder()
    private val isReliable = client.endpoint.isReliable(peer)

    /** The token of an established observation with Q-Block (RFC 9177 §4.5); null otherwise. */
    @Volatile
    private var qBlockToken: ByteArray? = null

    /** Whether that observation ends by forgetting its token: it was registered with a Q-Block1 upload. */
    @Volatile
    private var forgetsToken = false

    /** Whether that observation already ended: with a final response, or failed and forgot its token. */
    @Volatile
    private var qBlockEnded = false

    /** What the Q-Block layer reports to this observation. */
    private val qBlockObserver = object : QBlockClient.Observer {
        // The security layer orders what it protects (RFC 8613 §4.1.3.5.2); a client with security whose resolver
        // left this registration unprotected orders it here, like the RFC 7959 path does.
        override fun isFresh(value: Long, isProtected: Boolean): Boolean =
            isReliable || isProtected || order.accept(value, client.endpoint.now())

        override fun onEstablished(token: ByteArray, forgets: Boolean) {
            qBlockToken = token
            forgetsToken = forgets
            established = true
        }

        override suspend fun onNotification(response: CoapResponse) {
            mutableEvents.emit(Event.Notification(response))
        }
    }

    fun start() {
        val launched = client.endpoint.scope.launch { run() }
        job = launched
        // A coroutine launched into a closed endpoint never runs, and one the closing endpoint cancels
        // stops midway; either way the collectors get the endpoint's failure instead of waiting forever.
        launched.invokeOnCompletion { terminate(Event.Failure(client.endpoint.closedError())) }
    }

    private suspend fun run() {
        try {
            terminate(observe())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            terminate(Event.Failure(e))
        } finally {
            withContext(NonCancellable) { client.observationEnded(this@ClientObservation) }
        }
    }

    /** Registers and passes notifications on until the observation ends; returns the terminal event. */
    private suspend fun observe(): Event {
        client.qBlock?.let { qBlock ->
            val final = try {
                qBlock.observe(peer, registration, confirmable, qBlockObserver)
            } catch (e: CoapException) {
                // Its token is forgotten, so a notification still on its way gets a Reset (RFC 7641 §3.6).
                qBlockEnded = true
                afterRejection(e)
                throw e
            }
            if (final != null) {
                qBlockEnded = true
                return Event.Final(final)
            }
        }
        val (current, first) = try {
            register()
        } catch (e: CoapException) {
            afterRejection(e)
            throw e
        }
        if (first.options.observe == null || !first.code.isSuccess) {
            // §3.1: no Observe in the response means the server does not notify; §3.2 for errors.
            current.cancel()
            return Event.Final(client.blockwise.complete(peer, registration, first, confirmable))
        }
        established = true
        if (!isReliable) order.accept(first.options.observe!!, client.endpoint.now())
        whole(first)?.let { mutableEvents.emit(Event.Notification(it)) }
        while (true) {
            // A notification with an unrecognized critical option ends the exchange with BAD_RESPONSE: the
            // endpoint rejected it (RFC 7252 §5.4.1), see afterRejection for how that reaches the server.
            val result = current.receiveCatching()
            val notification = result.getOrNull() ?: run {
                val cause = result.exceptionOrNull() ?: CancellationException("Observation cancelled")
                afterRejection(cause)
                return Event.Failure(cause)
            }
            val value = notification.options.observe
            if (value == null) {
                current.cancel()
                return Event.Final(client.blockwise.complete(peer, registration, notification, confirmable))
            }
            if (current.protectedRequest == null && !isReliable && !order.accept(value, client.endpoint.now())) {
                log.debug { "Dropped a stale notification $value (RFC 7641 §3.4)" }
                continue
            }
            whole(notification)?.let { mutableEvents.emit(Event.Notification(it)) }
        }
    }

    /**
     * The notification [notification] of the established observation, completed with Block2 requests
     * (RFC 7959 §2.6); null when one of them got only an unverified answer, which ends neither the observation
     * nor reaches its collectors (ADR 0008): that notification is dropped, and the next change brings a fresh one.
     */
    private suspend fun whole(notification: CoapMessage): CoapResponse? = try {
        client.blockwise.complete(peer, registration, notification, confirmable)
    } catch (e: CoapException) {
        if (e.reason != CoapException.Reason.UNVERIFIED_RESPONSE) throw e
        log.warn { "Dropped a notification whose Block2 request got an unverified ${e.response?.code}" }
        null
    }

    /**
     * Sends the registration, repeating it once with Echo after a 4.01 challenge (RFC 9175 §2.3), under message
     * security only after a verified one: an unverified answer fails the exchange before it gets here.
     */
    private suspend fun register(): Pair<ClientExchange, CoapMessage> {
        var request = registration
        var echoed = false
        while (true) {
            val current = client.endpoint.openExchange(
                peer, request, confirmable, client.config.security, ResponseMode.Stream,
                recognizedOptions = client.recognizedOptions,
            )
            exchange = current
            val first = client.endpoint.awaitResponse(current, client.requestTimeout)
            val echo = first.options.echo
            if (first.code == CoapCode.UNAUTHORIZED && echo != null && client.config.echoRetry && !echoed) {
                current.cancel()
                request = request.toBuilder().options { echo(echo) }.build()
                echoed = true
                continue
            }
            return current to first
        }
    }

    /** Emits the terminal event, once; the buffer drops the oldest events, so this never suspends or fails. */
    private fun terminate(event: Event) {
        if (terminated.compareAndSet(false, true)) mutableEvents.tryEmit(event)
    }

    /**
     * The endpoint rejected a response to the registration (RFC 7252 §5.4.1) when [cause] is
     * [CoapException.Reason.BAD_RESPONSE], which ended the exchange, or took one it could not verify
     * ([CoapException.Reason.UNVERIFIED_RESPONSE], ADR 0008) in place of the server's answer. Over UDP the
     * endpoint's Reset ends the observation on the server (RFC 7641 §3.6), or a later notification gets one,
     * its token being forgotten. A reliable transport has no Reset, so there the client MUST deregister
     * (RFC 8323 §7.4), or the server notifies for as long as the connection lasts; it does so at once, whatever
     * the collectors do. A rejected registration response may have registered the client as well, and so may
     * the server's answer that an unverified error from a party on the path came ahead of; a deregistration that
     * finds no observer is an ordinary GET, so it deregisters after those too.
     */
    private fun afterRejection(cause: Throwable) {
        if (!isReliable || cause !is CoapException || cause.reason !in DEREGISTERING_FAILURES) return
        val rejected = qBlockToken ?: exchange?.token ?: return
        log.debug { "Deregistering after a rejected response (RFC 8323 §7.4)" }
        client.endpoint.launchGuarded("deregister a rejected observation") {
            withContext(NonCancellable) { deregister(rejected) }
        }
    }

    /**
     * The last collector left: stop, and deregister if the observation was established (§3.6). On a
     * reliable transport the client MUST deregister (RFC 8323 §7.4), since there is no Reset to answer the
     * next notification with, so [ObserveCancellation.FORGET] does not apply there. An exchange that ended
     * already needs nothing: the server ended the observation, or [afterRejection] deregistered.
     */
    fun stop() {
        val job = job
        job?.cancel()
        val qBlockToken = qBlockToken
        if (qBlockToken != null) {
            client.endpoint.launchGuarded("deregister an observation") {
                withContext(NonCancellable) {
                    // Its transfer forgets every token as it ends; the deregistration then takes the token over.
                    job?.join()
                    val forget = (client.config.observeCancellation == ObserveCancellation.FORGET || forgetsToken) && !isReliable
                    if (!forget && !qBlockEnded) deregister(qBlockToken)
                }
            }
            return
        }
        val current = exchange ?: return
        client.endpoint.launchGuarded("deregister an observation") {
            withContext(NonCancellable) {
                val forget = client.config.observeCancellation == ObserveCancellation.FORGET && !isReliable
                if (!established || forget || !current.isOpen) {
                    current.cancel()
                    return@withContext
                }
                deregister(current.token)
            }
        }
    }

    /** §3.6: the registration with [token] and Observe 1, which takes the token over from the observation. */
    private suspend fun deregister(token: ByteArray) {
        val deregistration = registration.toBuilder().options { observe(1) }.build()
        try {
            val exchange = client.endpoint.openExchange(
                peer, deregistration, confirmable, client.config.security, ResponseMode.Single,
                token = token, recognizedOptions = client.recognizedOptions,
            )
            client.endpoint.awaitResponse(exchange, client.requestTimeout)
        } catch (e: Exception) {
            log.debug { "Deregistration got no response: ${e.message}" }
        }
    }

    private companion object {
        const val BUFFER = 64

        /** The failures of a registration after which the server may still have the client as an observer. */
        val DEREGISTERING_FAILURES = setOf(CoapException.Reason.BAD_RESPONSE, CoapException.Reason.UNVERIFIED_RESPONSE)
    }
}
