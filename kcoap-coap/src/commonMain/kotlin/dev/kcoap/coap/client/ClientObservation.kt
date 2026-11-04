package dev.kcoap.coap.client

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.ClientExchange
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
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
 * passed on. Large notifications are completed with Block2 requests (RFC 7959 §2.6). A response without
 * Observe, or a non-2.xx one, ends the observation (§3.2).
 */
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

    @Volatile
    private var exchange: ClientExchange? = null

    @Volatile
    private var established = false
    private val order = NotificationOrder()

    fun start() {
        job = client.endpoint.scope.launch { run() }
    }

    private suspend fun run() {
        try {
            val (current, first) = register()
            if (first.options.observe == null || !first.code.isSuccess) {
                // §3.1: no Observe in the response means the server does not notify; §3.2 for errors.
                current.cancel()
                finish(Event.Final(client.blockwise.complete(peer, registration, first, confirmable)))
                return
            }
            established = true
            order.accept(first.options.observe!!, client.endpoint.now())
            mutableEvents.emit(Event.Notification(client.blockwise.complete(peer, registration, first, confirmable)))
            while (true) {
                val result = current.receiveCatching()
                val notification = result.getOrNull()
                if (notification == null) {
                    finish(Event.Failure(result.exceptionOrNull() ?: CancellationException("Observation cancelled")))
                    return
                }
                client.checkResponse(notification)
                val value = notification.options.observe
                if (value == null) {
                    current.cancel()
                    finish(Event.Final(client.blockwise.complete(peer, registration, notification, confirmable)))
                    return
                }
                if (current.protectedRequest == null && !order.accept(value, client.endpoint.now())) {
                    log.debug { "Dropped a stale notification $value (RFC 7641 §3.4)" }
                    continue
                }
                mutableEvents.emit(Event.Notification(client.blockwise.complete(peer, registration, notification, confirmable)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(Event.Failure(e))
        }
    }

    /** Sends the registration, repeating it once with Echo after a 4.01 challenge (RFC 9175 §2.3). */
    private suspend fun register(): Pair<ClientExchange, CoapMessage> {
        var request = registration
        var echoed = false
        while (true) {
            val current = client.endpoint.openExchange(peer, request, confirmable, client.config.security, isObservation = true)
            exchange = current
            val first = client.endpoint.awaitResponse(current, client.requestTimeout)
            client.checkResponse(first)
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

    private suspend fun finish(event: Event) {
        client.observationEnded(this)
        mutableEvents.emit(event)
    }

    /** The last collector left: stop, and deregister if the observation was established (§3.6). */
    fun stop() {
        job?.cancel()
        val current = exchange ?: return
        client.endpoint.scope.launch {
            withContext(NonCancellable) {
                if (!established || client.config.observeCancellation == ObserveCancellation.FORGET || !current.isOpen) {
                    current.cancel()
                    return@withContext
                }
                // §3.6: same token and options, Observe 1; it takes over the token from the observation.
                val deregistration = registration.toBuilder().options { observe(1) }.build()
                try {
                    val exchange = client.endpoint.openExchange(
                        peer, deregistration, confirmable, client.config.security, isObservation = false, token = current.token,
                    )
                    client.endpoint.awaitResponse(exchange, client.requestTimeout)
                } catch (e: Exception) {
                    log.debug { "Deregistration got no response: ${e.message}" }
                }
            }
        }
    }

    private companion object {
        const val BUFFER = 64
    }
}
