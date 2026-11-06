package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.log.LogTags
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndedException
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CoAP over a [DatagramTransport] (RFC 7252): the driver of [UdpMessaging], the sans-IO messaging layer, for
 * every datagram peer of one [CoapEndpoint], and the [PeerLink] to each of them ([to]). It owns the messaging
 * state behind one lock that is never held
 * across a suspension, a receive loop that feeds it datagrams and a timer loop that fires its deadlines, and
 * performs its effects (datagrams to send, messages to deliver, outcomes of transmissions) after releasing the
 * lock, under `NonCancellable`, because the state machine has committed to them and they may belong to other
 * exchanges.
 *
 * Everything RFC 7252 adds below the exchange layer stays here: message types and Message IDs, ACKs and
 * Resets, retransmission, deduplication, NSTART and the [BodyLease]s of Non-confirmable Q-Block bodies
 * (RFC 9177 §7.2), and the end of a DTLS session (§9.1.1). The endpoint's closed state is checked under this
 * lock before anything new is sent, and [close] fails what is in progress, so nothing new goes out after the
 * endpoint's terminal transition.
 */
internal class DatagramLink(
    val transport: DatagramTransport,
    private val endpoint: CoapEndpoint,
    private val config: CoapEndpointConfig,
) {
    private val log = config.loggerFactory.logger(LogTags.COAP_EXCHANGE)

    /** Guards [messaging]; never held across a suspension. */
    private val lock = Mutex()
    private val messaging = UdpMessaging(
        config.parameters,
        transport.maxDatagramSize,
        config.separateResponseDelay,
        config.random,
        config.interceptors,
        config.loggerFactory,
        config.maxDeduplicationEntries,
        config.maxExchangesPerPeer,
        config.maxDeduplicationEntriesPerPeer,
        config.maxExchanges,
        config.maxReservedMessageIds,
        config.maxReservedMessageIdsPerPeer,
    )
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /**
     * What [messaging] keeps, for [CoapEndpoint.stats]: written under [lock] after every step of the state machine,
     * and read without it, which may see a value one step old.
     */
    @Volatile
    var transmissionCount: Int = 0
        private set

    @Volatile
    var deduplicationEntryCount: Int = 0
        private set

    @Volatile
    var reservedMessageIdCount: Int = 0
        private set

    /** What one datagram may be (RFC 7252 §4.6): the transport's datagram size, in the UDP framing. */
    val limits: MessageLimits = MessageLimits(transport.maxDatagramSize, UdpMessageCodec, allowsBert = false, isReliable = false)

    /** The link to [peer] over this transport. Links to one peer are equal, so they match the same exchanges. */
    fun to(peer: Endpoint): PeerLink = Peer(peer)

    /**
     * Receives and fires timers until the endpoint closes. Returns null when it closed, or the failure when the
     * transport's receive failed: a transport that cannot receive is gone for good (a socket the system closed,
     * a network that went away), so the endpoint closes with [CoapException.Reason.TRANSPORT_FAILED] instead of
     * looking alive while every request runs into a timeout.
     */
    suspend fun run(): CoapException? = coroutineScope {
        val timers = launch(CoroutineName("coap-timers")) { timerLoop() }
        receiveLoop().also { timers.cancel() }
    }

    // ---------------------------------------------------------------- what the exchange layer asks for

    /** A Message ID for a new message to [peer] (RFC 7252 §4.4); throws like [CoapEndpoint.closeCause] once closed. */
    suspend fun allocateMessageId(peer: Endpoint): Int = withMessaging { now ->
        endpoint.ensureOpen()
        allocateMessageId(peer, now)
    }

    /**
     * Sends [message], a Confirmable or Non-confirmable message to [peer], and records its transmission with
     * [queued] under the lock, before the datagram goes out: a cancellation while sending can then still settle
     * it, which would otherwise retransmit for nobody and hold NSTART. A Non-confirmable message holds its
     * NSTART slot for [slotHold]; [lease] is the granted body lease a message of a body goes out under.
     */
    suspend fun send(
        peer: Endpoint,
        message: CoapMessage,
        owner: TransmissionOwner?,
        slotHold: Duration?,
        lease: BodyLease? = null,
        queued: (Transmission) -> Unit = {},
    ): Transmission = withMessaging { now ->
        endpoint.ensureOpen()
        lease?.checkUsable()
        send(now, peer, message, owner, slotHold).also(queued)
    }

    /**
     * Sends a notification or another response in a message of its own, of [type], with a new Message ID; see
     * [UdpMessaging.send].
     */
    suspend fun sendSeparately(
        peer: Endpoint,
        message: CoapMessage,
        type: CoapMessageType,
        owner: TransmissionOwner,
        slotHold: Duration?,
    ): Transmission = withMessaging { now ->
        endpoint.ensureOpen()
        send(now, peer, message.withHeader(type, allocateMessageId(peer, now)), owner, slotHold)
    }

    /**
     * RFC 7252 §4.7: a Non-confirmable request occupies its NSTART slot until its response arrives, or for as
     * long as sending it took at PROBING_RATE, so an endpoint that never answers is probed no faster than that.
     */
    fun nonConfirmableSlotHold(message: CoapMessage): Duration =
        (UdpMessageCodec.encode(message).size.toDouble() / config.parameters.probingRate).seconds

    /** Stops working on [transmission]; cleanup, so it runs to the end in a cancelled coroutine too. */
    suspend fun settle(transmission: Transmission): Unit = withContext(NonCancellable) {
        withMessaging { now -> settle(now, transmission) }
    }

    /** Replaces an outstanding notification with a newer one; see [UdpMessaging.supersede]. */
    suspend fun supersede(transmission: Transmission, message: CoapMessage): Boolean = withMessaging { supersede(transmission, message) }

    /**
     * A CoAP ping (RFC 7252 §4.3): an Empty Confirmable message, which a live peer answers with a Reset. True
     * if it answered, false if it never did within MAX_TRANSMIT_WAIT.
     */
    suspend fun ping(peer: Endpoint): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val owner = TransmissionOwner { _, outcome ->
            when (outcome) {
                // A peer that acknowledges instead of resetting is alive as well.
                TransmissionOutcome.Reset, is TransmissionOutcome.Acknowledged -> answer.complete(true)
                TransmissionOutcome.TimedOut -> answer.complete(false)
                is TransmissionOutcome.Failed -> answer.completeExceptionally(outcome.cause)
                TransmissionOutcome.SlotReleased -> Unit
            }
        }
        var transmission: Transmission? = null
        try {
            withMessaging { now ->
                endpoint.ensureOpen()
                transmission = send(now, peer, CoapMessage.empty(CoapMessageType.CON, allocateMessageId(peer, now)), owner)
            }
            return answer.await()
        } catch (e: Throwable) {
            transmission?.let { settle(it) }
            throw e
        }
    }

    /** See [CoapEndpoint.leaseBody]. */
    suspend fun lease(peer: Endpoint, bodyBytes: Int, nonTimeoutRandom: Duration?): BodyLease {
        val lease = withMessaging { now ->
            endpoint.ensureOpen()
            lease(now, peer, bodyBytes, nonTimeoutRandom)
        }
        try {
            lease.granted.await()
        } catch (e: Throwable) {
            // A caller cancelled while waiting, or just after the grant, must not keep the peer's slot.
            release(lease)
            throw e
        }
        return lease
    }

    /** See [CoapEndpoint.releaseBody]. Cleanup, so it runs to the end in a cancelled coroutine too. */
    suspend fun release(lease: BodyLease): Unit = withContext(NonCancellable) {
        withMessaging { now -> releaseLease(now, lease) }
    }

    /** Body leases that hold a slot or wait for one, for tests. */
    suspend fun leaseCount(): Int = lock.withLock { messaging.leaseCount }

    /** What the messaging layer keeps, for tests. */
    suspend fun sizes(): UdpMessaging.Sizes = lock.withLock { messaging.sizes }

    // ---------------------------------------------------------------- answering what arrived

    /**
     * Answers the request [requestMessageId] from [peer], which arrived as [requestType]: piggybacked if still
     * possible, else separately as [separateType] (Confirmable requests only); returns the transmission of a
     * separate response. [repeatable] says that nothing acted on the request ([UdpMessaging.respond]).
     */
    suspend fun respond(
        peer: Endpoint,
        requestMessageId: Int,
        requestType: CoapMessageType,
        response: CoapMessage,
        owner: TransmissionOwner?,
        separateType: CoapMessageType,
        repeatable: Boolean = false,
    ): Transmission? = withMessaging { now -> respond(now, peer, requestMessageId, requestType, response, owner, separateType, repeatable) }

    /** Accepts a Confirmable message from [peer] with an Empty ACK (RFC 7252 §5.2.2); nothing for others. */
    suspend fun acknowledge(peer: Endpoint, messageId: Int) {
        withMessaging { acknowledge(peer, messageId) }
    }

    /**
     * Leaves a Confirmable message from [peer] unanswered and forgets that it arrived, so its retransmission is
     * delivered again (RFC 7252 §4.2, §4.5); see [UdpMessaging.forgetUnanswered].
     */
    suspend fun forgetUnanswered(peer: Endpoint, messageId: Int) {
        withMessaging { forgetUnanswered(peer, messageId) }
    }

    /** Rejects a message from [peer] with a Reset (RFC 7252 §4.2, §4.3), for [reason]. */
    suspend fun reject(peer: Endpoint, messageId: Int, reason: EndpointEvent.MessageRejected.Reason, detail: String?) {
        withMessaging { reject(peer, messageId, reason, detail) }
    }

    /** Nothing acted on the message [messageId] from [peer]; see [UdpMessaging.markRepeatable]. */
    suspend fun markRepeatable(peer: Endpoint, messageId: Int) {
        withMessaging { markRepeatable(peer, messageId) }
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * The endpoint's terminal transition: the messaging layer fails every transmission and lease with [cause],
     * and the owners hear of it. Runs to the end even in a cancelled coroutine.
     */
    suspend fun close(cause: CoapException): Unit = withContext(NonCancellable) {
        withMessaging(signal = false) { close(cause) }
    }

    /** Closes the transport; a failure is only logged, since the endpoint ends either way. */
    suspend fun closeTransport() {
        try {
            transport.close()
        } catch (e: Exception) {
            log.debug { "Closing the transport failed: ${e.message}" }
        }
    }

    // ---------------------------------------------------------------- driving the state machine

    /**
     * Runs [block] on the state machine under the lock, then executes its effects outside the lock. The
     * effects run to the end even when the calling coroutine is cancelled meanwhile: the state machine has
     * committed to them, and they may belong to other exchanges, such as a queued request that a released
     * NSTART slot started. A cancelled caller sees its cancellation at its next suspension.
     */
    private suspend fun <T> withMessaging(signal: Boolean = true, block: UdpMessaging.(now: Duration) -> T): T {
        var effects: List<MessagingEffect> = emptyList()
        var wakeup = false
        val result = try {
            lock.withLock {
                try {
                    messaging.block(endpoint.now())
                } finally {
                    effects = messaging.takeEffects()
                    wakeup = messaging.takeWakeup()
                    transmissionCount = messaging.outboundCount
                    deduplicationEntryCount = messaging.deduplicationEntryCount
                    reservedMessageIdCount = messaging.reservedMessageIdCount
                }
            }
        } finally {
            if (effects.isNotEmpty()) withContext(NonCancellable) { execute(effects) }
            // Only a deadline earlier than the one the timer loop waits for wakes it (CLR-4).
            if (signal && wakeup) wakeups.trySend(Unit)
        }
        return result
    }

    private suspend fun execute(effects: List<MessagingEffect>) {
        for (effect in effects) {
            when (effect) {
                is MessagingEffect.Transmit -> transmit(effect)
                is MessagingEffect.DeliverRequest -> endpoint.onRequest(to(effect.from), effect.message)
                is MessagingEffect.DeliverResponse -> endpoint.onResponse(to(effect.from), effect.message)
                is MessagingEffect.Completed -> effect.transmission.owner?.let { owner ->
                    try {
                        owner.onOutcome(effect.transmission, effect.outcome)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error(e) { "Transmission owner failed on ${effect.outcome}" }
                    }
                }
                is MessagingEffect.Event -> endpoint.report(effect.event)
                is MessagingEffect.LeaseGranted -> effect.lease.granted.complete(Unit)
                is MessagingEffect.LeaseFailed -> effect.lease.granted.completeExceptionally(endpoint.closedError(effect.cause))
            }
        }
    }

    /**
     * Sends one datagram. A [SessionEndedException] ends the peer's DTLS session. An
     * [IllegalArgumentException] means the transport cannot carry this datagram to this peer at all, such
     * as an endpoint type it does not serve: a local error that no retransmission fixes, so the message
     * fails at once with [CoapException.Reason.TRANSPORT_FAILED] instead of looking like a silent peer
     * after MAX_TRANSMIT_WAIT. Any other failure is transient, and retransmission covers it, as it covers a
     * datagram the network lost.
     */
    private suspend fun transmit(effect: MessagingEffect.Transmit) {
        val to = effect.to
        try {
            transport.send(to, effect.bytes)
        } catch (e: SessionEndedException) {
            onSessionEnded(e.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            log.error(e) { "The transport refused to send ${effect.bytes.size} bytes to $to" }
            val transmission = effect.transmission ?: return
            val cause = CoapException("The transport cannot send to $to: ${e.message}", CoapException.Reason.TRANSPORT_FAILED, e)
            withMessaging { now -> abandon(now, transmission, cause) }
        } catch (e: Exception) {
            // UDP gives no delivery guarantee anyway; retransmission covers a failed send.
            log.warn(e) { "Sending ${effect.bytes.size} bytes to $to failed" }
        }
    }

    /**
     * RFC 7252 §9.1.1: the DTLS session to [peer] ended. Its pending messages fail without being retransmitted,
     * and its Message ID and deduplication state go; then the endpoint ends what the exchange layer had with it.
     */
    private suspend fun onSessionEnded(peer: Endpoint) {
        log.info { "Session to $peer ended" }
        withMessaging { onSessionEnded(peer) }
        endpoint.onLinkEnded(to(peer), CoapException("DTLS session to $peer ended", CoapException.Reason.SESSION_ENDED))
    }

    private suspend fun receiveLoop(): CoapException? {
        while (true) {
            val event = try {
                transport.receive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (endpoint.isClosed) return null
                log.error(e) { "The datagram transport failed; the endpoint closes" }
                return CoapException("The datagram transport failed: ${e.message}", CoapException.Reason.TRANSPORT_FAILED, e)
            }
            try {
                when (event) {
                    is DatagramEvent.Received -> withMessaging { now -> onDatagram(now, event.from, event.datagram) }
                    is DatagramEvent.SessionEnded -> onSessionEnded(event.endpoint)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Failed to process a datagram" }
            }
        }
    }

    /**
     * Fires the messaging layer's timers. It waits with the dispatcher's delays for deadlines read on the
     * time source, so the two must be one clock ([CoapEndpoint.clockCheck] warns when they are not). A wakeup
     * means a call added a deadline earlier than the one it waits for ([UdpMessaging.takeWakeup]): the next
     * deadline is recomputed, and the timers run only once one is due.
     */
    private suspend fun timerLoop() {
        while (true) {
            val next = lock.withLock { messaging.nextDeadline() }
            val wait = next?.minus(endpoint.now())
            if (wait == null) {
                wakeups.receive()
                continue
            }
            if (wait.isPositive()) {
                val before = endpoint.now()
                if (withTimeoutOrNull(wait) { wakeups.receive() } != null) continue
                endpoint.clockCheck.afterTimeout(wait, endpoint.now() - before)
            }
            try {
                withMessaging(signal = false) { now -> onTimer(now) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "Timer processing failed" }
            }
        }
    }

    override fun toString(): String = "DatagramLink($transport)"

    /** RFC 7252 messaging with one [peer], on the messaging layer this driver runs for all of them. */
    private inner class Peer(override val peer: Endpoint) : PeerLink {
        override val limits: MessageLimits get() = this@DatagramLink.limits

        override val capabilities: LinkCapabilities get() = LinkCapabilities.DATAGRAM

        /** What the DTLS session authenticated, for an [Endpoint.Secure] peer. */
        override val peerIdentity: PeerIdentity get() = (peer as? Endpoint.Secure)?.identity ?: PeerIdentity.None

        override fun checkPacing(confirmable: Boolean, pacing: Pacing) {
            if (pacing !is Pacing.Body) return
            require(!confirmable) { "A Confirmable body takes a slot per message (RFC 9177 §7.1), not a body lease" }
            require(pacing.lease.peer == peer) { "The body lease is towards ${pacing.lease.peer}, not $peer" }
        }

        override suspend fun address(request: CoapMessage, token: ByteArray, confirmable: Boolean): CoapMessage {
            val messageId = allocateMessageId(peer)
            return request.toBuilder().apply {
                type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
                this.messageId = messageId
                this.token = token
            }.build()
        }

        override suspend fun sendRequest(request: CoapMessage, pacing: Pacing, owner: DeliveryOwner, queued: (Delivery) -> Unit) {
            val lease = (pacing as? Pacing.Body)?.lease
            // A message of a body takes no slot of its own: the body's lease holds one for all of them.
            val slotHold = if (request.type == CoapMessageType.CON || lease != null) null else nonConfirmableSlotHold(request)
            send(peer, request, owner.forTransmissions(), slotHold, lease, queued)
        }

        override suspend fun settle(delivery: Delivery) {
            if (delivery is Transmission) this@DatagramLink.settle(delivery)
        }

        /** Exchanges count only on connections. */
        override suspend fun exchangeClosed(token: String?) = Unit

        /** RFC 7252 §4.3; [custody] is RFC 8323's and does not apply. */
        override suspend fun ping(custody: Boolean): Boolean = this@DatagramLink.ping(peer)

        override suspend fun leaseBody(bodyBytes: Int, nonTimeoutRandom: Duration?): BodyLease = lease(peer, bodyBytes, nonTimeoutRandom)

        override suspend fun acceptResponse(response: CoapMessage) {
            this@DatagramLink.acknowledge(peer, response.messageId)
        }

        override suspend fun deferResponse(response: CoapMessage) {
            forgetUnanswered(peer, response.messageId)
        }

        override suspend fun rejectResponse(response: CoapMessage, reason: EndpointEvent.MessageRejected.Reason, detail: String?) {
            this@DatagramLink.reject(peer, response.messageId, reason, detail)
        }

        override suspend fun respond(request: InboundRequest, response: CoapMessage, owner: DeliveryOwner?, confirmable: Boolean): Delivery? {
            val separateType = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
            return this@DatagramLink.respond(
                peer, request.message.messageId, request.message.type, response, owner?.forTransmissions(), separateType, request.isRepeatable,
            )
        }

        override suspend fun acknowledge(request: InboundRequest) {
            this@DatagramLink.acknowledge(peer, request.message.messageId)
        }

        /** RFC 7967: an Empty ACK for a Confirmable request, nothing for a Non-confirmable one. */
        override suspend fun finishWithoutResponse(request: InboundRequest) {
            acknowledge(request)
        }

        override suspend fun reject(request: InboundRequest, reason: EndpointEvent.MessageRejected.Reason, detail: String?) {
            this@DatagramLink.reject(peer, request.message.messageId, reason, detail)
        }

        override suspend fun leaveUnanswered(request: InboundRequest) {
            if (request.isRepeatable) markRepeatable(peer, request.message.messageId)
        }

        override suspend fun notify(message: CoapMessage, confirmable: Boolean, owner: DeliveryOwner, slotHold: Duration?): Delivery {
            val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
            return sendSeparately(peer, message, type, owner.forTransmissions(), slotHold)
        }

        override suspend fun supersede(delivery: Delivery, message: CoapMessage): Boolean =
            delivery is Transmission && this@DatagramLink.supersede(delivery, message)

        override fun notificationPolicy(registeredAt: Duration, isAuthenticated: Boolean, confirmableInterval: Duration): NotificationPolicy =
            NotificationPolicy.Interspersed(registeredAt, isAuthenticated, confirmableInterval)

        override fun equals(other: Any?): Boolean = other is Peer && other.link === this@DatagramLink && other.peer == peer

        override fun hashCode(): Int = peer.hashCode()

        override fun toString(): String = "DatagramLink($peer)"

        private val link: DatagramLink get() = this@DatagramLink
    }

    private companion object {
        /** The [TransmissionOwner] that reports a datagram's outcome to this [DeliveryOwner]. */
        fun DeliveryOwner.forTransmissions(): TransmissionOwner = TransmissionOwner { transmission, outcome ->
            onOutcome(transmission, outcome.asDelivery())
        }

        fun TransmissionOutcome.asDelivery(): DeliveryOutcome = when (this) {
            is TransmissionOutcome.Acknowledged -> DeliveryOutcome.Delivered(piggybackedResponse)
            TransmissionOutcome.Reset -> DeliveryOutcome.Rejected
            TransmissionOutcome.TimedOut -> DeliveryOutcome.TimedOut
            TransmissionOutcome.SlotReleased -> DeliveryOutcome.Released
            is TransmissionOutcome.Failed -> DeliveryOutcome.Failed(cause)
        }
    }
}
