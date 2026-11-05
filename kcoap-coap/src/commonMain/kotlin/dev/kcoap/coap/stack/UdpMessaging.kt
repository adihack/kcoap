package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.CoapFormatException
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogTags
import dev.kcoap.log.Logger
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.time.Duration

/**
 * The CoAP over UDP messaging layer of RFC 7252 §4 as a sans-IO state machine: datagrams and time go in,
 * [MessagingEffect]s come out (see [takeEffects]). It implements
 *
 * - Message IDs that are not reused towards a peer within EXCHANGE_LIFETIME (§4.4),
 * - retransmission of Confirmable messages with exponential back-off inside MAX_TRANSMIT_SPAN (§4.2),
 * - deduplication over EXCHANGE_LIFETIME / NON_LIFETIME, answering duplicate Confirmable messages with
 *   the same ACK or RST (§4.5),
 * - Empty ACKs for slow requests, switching to a separate response (§5.2.2),
 * - Resets for Empty Confirmable messages (CoAP ping), reserved code classes and format errors
 *   (§4.2, §4.3), and silently ignored invalid ACKs and RSTs,
 * - NSTART outstanding interactions per peer (§4.7, RFC 7641 §4.5.1), with Non-confirmable messages
 *   holding a slot for as long as the caller asks (PROBING_RATE for requests),
 * - superseding an outstanding notification with a newer one at its next timeout (RFC 7641 §4.5.2),
 * - failing, never retransmitting, everything towards an ended DTLS session (§9.1.1).
 *
 * Peers are compared by [Endpoint] equality only. Not thread safe: the driver serializes all calls.
 *
 * @param separateResponseDelay how long a Confirmable request may stay unanswered before it gets an Empty
 *   ACK; null leaves that to the upper layer.
 */
internal class UdpMessaging(
    private val parameters: TransmissionParameters,
    private val maxDatagramSize: Int,
    private val separateResponseDelay: Duration?,
    private val random: Random,
    private val interceptors: List<MessageInterceptor> = emptyList(),
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    private val log: Logger = loggerFactory.logger(LogTags.COAP_MESSAGE)
    private val effects = ArrayList<MessagingEffect>()
    private val messageIds = MessageIdAllocator(random.nextInt(0x10000))
    private val received = DeduplicationCache(parameters.exchangeLifetime, parameters.nonLifetime)

    /** Our Confirmable messages until they end, and Non-confirmable ones until NON_LIFETIME, by Message ID. */
    private val outbound = HashMap<MessageKey, Transmission>()
    private val nonConfirmableExpiries = ArrayDeque<Pair<MessageKey, Duration>>()

    /** Transmissions with a running timer: Confirmable ones awaiting an ACK, Non-confirmable ones holding a slot. */
    private val timed = LinkedHashSet<Transmission>()
    private val windows = HashMap<Endpoint, PeerWindow>()

    private class PeerWindow {
        var outstanding = 0
        val queue = ArrayDeque<Transmission>()
    }

    /** Returns and clears the effects produced since the last call. */
    fun takeEffects(): List<MessagingEffect> {
        val taken = effects.toList()
        effects.clear()
        return taken
    }

    // ---------------------------------------------------------------- sending

    /** A Message ID for a new message to [peer], reserved for EXCHANGE_LIFETIME (§4.4). */
    fun allocateMessageId(peer: Endpoint, now: Duration): Int =
        messageIds.allocate(peer, now, now + parameters.exchangeLifetime)

    /**
     * Sends a Confirmable or Non-confirmable [message] whose Message ID came from [allocateMessageId]. A
     * Confirmable message, or a Non-confirmable one with a [slotHold], waits while [peer] has NSTART
     * outstanding interactions.
     *
     * @throws CoapException with [CoapException.Reason.MESSAGE_TOO_LARGE] if it does not fit a datagram.
     */
    fun send(
        now: Duration,
        peer: Endpoint,
        message: CoapMessage,
        owner: TransmissionOwner?,
        slotHold: Duration? = null,
    ): Transmission {
        require(message.type == CoapMessageType.CON || message.type == CoapMessageType.NON) {
            "Only CON and NON messages are sent as transmissions: $message"
        }
        val transmission = Transmission(peer, message, encodeForSending(peer, message), owner, slotHold)
        outbound[MessageKey(peer, message.messageId)] = transmission
        if (transmission.holdsSlot) {
            val window = windows.getOrPut(peer) { PeerWindow() }
            if (window.outstanding >= parameters.nstart) {
                transmission.state = Transmission.State.QUEUED
                window.queue.addLast(transmission)
                debug(peer, message.messageId) { "Queued behind NSTART=${parameters.nstart}: $message" }
                return transmission
            }
            window.outstanding++
        }
        start(now, transmission)
        return transmission
    }

    /**
     * Stops working on [transmission] without reporting an outcome: a Confirmable request answered by a
     * separate response before its ACK (§5.2.2), a Non-confirmable request that got its response, or an
     * exchange the application cancelled. Frees its NSTART slot. A Non-confirmable message stays
     * matchable to a Reset.
     */
    fun settle(now: Duration, transmission: Transmission) {
        when (transmission.state) {
            Transmission.State.QUEUED -> {
                windows[transmission.peer]?.queue?.remove(transmission)
                retire(transmission)
            }
            Transmission.State.IN_FLIGHT -> {
                if (transmission.isConfirmable) {
                    retire(transmission)
                } else {
                    timed.remove(transmission)
                }
                if (transmission.occupiesSlot) releaseSlot(now, transmission)
            }
            else -> Unit
        }
    }

    /**
     * Replaces an outstanding Confirmable notification with [message] (RFC 7641 §4.5.2): a queued one is
     * replaced right away; one in flight is replaced at its next timeout, with a new Message ID and the
     * retransmission counter and timeout retained. Returns false when [transmission] is not outstanding,
     * so the caller sends [message] normally.
     */
    fun supersede(transmission: Transmission, message: CoapMessage): Boolean {
        if (!transmission.isConfirmable) return false
        return when (transmission.state) {
            Transmission.State.QUEUED -> {
                val replacement = message.withHeader(CoapMessageType.CON, transmission.message.messageId)
                transmission.replaceWith(replacement, encodeForSending(transmission.peer, replacement))
                true
            }
            Transmission.State.IN_FLIGHT -> {
                transmission.replacement = message
                true
            }
            else -> false
        }
    }

    // ---------------------------------------------------------------- answering received messages

    /** Accepts a delivered Confirmable message with an Empty ACK; no effect for Non-confirmable ones. */
    fun acknowledge(from: Endpoint, messageId: Int) {
        val entry = received[MessageKey(from, messageId)] ?: return
        if (entry.type == CoapMessageType.CON && entry.state == InboundEntry.State.PENDING) sendEmptyAck(entry)
    }

    /** Rejects a delivered message with a Reset (§4.2, §4.3), remembered for duplicates of a Confirmable one. */
    fun reject(from: Endpoint, messageId: Int, reason: String) {
        val entry = received[MessageKey(from, messageId)] ?: return
        if (entry.state != InboundEntry.State.PENDING) return
        sendReset(entry, reason)
    }

    /**
     * Answers the request [requestMessageId] from [from], which arrived as [requestType]: piggybacked in
     * the ACK if the request is Confirmable and still unanswered (§5.2.1), otherwise as a separate
     * response (§5.2.2, §5.2.3) of [separateType] for a Confirmable request, or Non-confirmable for a
     * Non-confirmable one. Returns the transmission of a separate response, or null if piggybacked.
     */
    fun respond(
        now: Duration,
        from: Endpoint,
        requestMessageId: Int,
        requestType: CoapMessageType,
        response: CoapMessage,
        owner: TransmissionOwner? = null,
        separateType: CoapMessageType = CoapMessageType.CON,
    ): Transmission? {
        val entry = received[MessageKey(from, requestMessageId)]
        if (requestType == CoapMessageType.CON && entry != null && entry.state == InboundEntry.State.PENDING) {
            val ack = response.withHeader(CoapMessageType.ACK, requestMessageId)
            val bytes = encodeForSending(from, ack)
            entry.answer(bytes)
            received.pending.remove(entry)
            if (bytes != null) transmit(from, ack, bytes)
            return null
        }
        val type = if (requestType == CoapMessageType.CON) separateType else CoapMessageType.NON
        val separate = response.withHeader(type, allocateMessageId(from, now))
        return send(now, from, separate, owner)
    }

    // ---------------------------------------------------------------- receiving

    /** Processes one datagram from [from]. */
    fun onDatagram(now: Duration, from: Endpoint, datagram: ByteArray) {
        val decoded = try {
            UdpMessageCodec.decode(datagram)
        } catch (e: CoapFormatException) {
            onFormatError(from, e)
            return
        }
        if (log.isEnabled(LogLevel.TRACE)) log.trace { "Received $decoded from $from: ${log.payload(datagram)}" }
        val message = interceptors.intercept(decoded, log) { interceptor, current -> interceptor.onReceive(from, current) } ?: return
        when (message.type) {
            CoapMessageType.ACK -> onAcknowledgement(now, from, message)
            CoapMessageType.RST -> onReset(now, from, message)
            CoapMessageType.CON -> onConfirmable(now, from, message)
            CoapMessageType.NON -> onNonConfirmable(now, from, message)
        }
    }

    private fun onFormatError(from: Endpoint, error: CoapFormatException) {
        effects += MessagingEffect.Event(EndpointEvent.MalformedMessage(from, error.message ?: "format error"))
        val messageId = error.messageId
        // §4.2: a Confirmable message with a format error is rejected with a Reset; everything else is
        // silently dropped (§4.3), and so is a message of an unknown version (§3).
        if (error.silentlyIgnore || error.type != CoapMessageType.CON || messageId == null) {
            log.warn { "Dropped a malformed datagram from $from: ${error.message}" }
            return
        }
        log.warn { "Rejecting a malformed Confirmable message $messageId from $from: ${error.message}" }
        val reset = CoapMessage.empty(CoapMessageType.RST, messageId)
        encodeForSending(from, reset)?.let { transmit(from, reset, it) }
    }

    private fun onAcknowledgement(now: Duration, from: Endpoint, ack: CoapMessage) {
        // §4.2: an ACK carrying a request or a reserved code class is rejected by silently ignoring it.
        if (!ack.isEmpty && !ack.isResponse) {
            log.warn { "Ignored an ACK that carries ${ack.code} from $from" }
            return
        }
        val transmission = outbound[MessageKey(from, ack.messageId)]
        if (transmission == null || !transmission.isConfirmable || transmission.state != Transmission.State.IN_FLIGHT) {
            debug(from, ack.messageId) { "Ignored an ACK that matches no outstanding message" }
            return
        }
        if (ack.messageId != transmission.message.messageId) {
            // RFC 7641 §4.5.2: an ACK for a superseded notification shows interest but not consistency.
            debug(from, ack.messageId) { "ACK for a superseded message; the newer one stays outstanding" }
            return
        }
        // §5.2.2: an ACK of a separate response or notification ends it, whatever it carries.
        val piggybacked = ack.takeIf { it.isResponse && transmission.message.isRequest }
        if (piggybacked != null && !piggybacked.hasToken(transmission.message.token)) {
            // §5.3.2 rule 2: a piggybacked response matches by Message ID and token; this one does not.
            log.warn { "Ignored a piggybacked response with a foreign token from $from" }
            return
        }
        debug(from, ack.messageId) { if (piggybacked != null) "Piggybacked response ${piggybacked.code}" else "Acknowledged" }
        finish(now, transmission, TransmissionOutcome.Acknowledged(piggybacked))
    }

    private fun onReset(now: Duration, from: Endpoint, reset: CoapMessage) {
        // §4.2: a Reset that is not Empty is silently ignored.
        if (!reset.isEmpty) {
            log.warn { "Ignored a non-empty RST from $from" }
            return
        }
        val transmission = outbound[MessageKey(from, reset.messageId)]
        if (transmission == null || transmission.state == Transmission.State.DONE) {
            debug(from, reset.messageId) { "Ignored an RST that matches no message" }
            return
        }
        debug(from, reset.messageId) { "Reset by peer" }
        effects += MessagingEffect.Event(EndpointEvent.ResetReceived(from, reset.messageId))
        finish(now, transmission, TransmissionOutcome.Reset)
    }

    private fun onConfirmable(now: Duration, from: Endpoint, message: CoapMessage) {
        val key = MessageKey(from, message.messageId)
        received[key]?.let { return onDuplicate(it) }
        val entry = received.add(key, CoapMessageType.CON, now)
        when {
            // §4.2, §4.3: an Empty Confirmable message elicits a Reset ("CoAP ping").
            message.isEmpty -> sendReset(entry, "CoAP ping")
            message.isRequest || message.isResponse -> {
                entry.ackDeadline = separateResponseDelay?.let { now + it }
                received.pending += entry
                effects += if (message.isRequest) {
                    MessagingEffect.DeliverRequest(from, message)
                } else {
                    MessagingEffect.DeliverResponse(from, message)
                }
            }
            // Reserved code classes 1, 6 and 7 (signaling exists only on reliable transports, RFC 8323).
            else -> sendReset(entry, "reserved code ${message.code}")
        }
    }

    private fun onNonConfirmable(now: Duration, from: Endpoint, message: CoapMessage) {
        val key = MessageKey(from, message.messageId)
        received[key]?.let { return onDuplicate(it) }
        if (!message.isRequest && !message.isResponse) {
            // §4.3: Empty NON and reserved classes are rejected; we drop them silently.
            effects += MessagingEffect.Event(EndpointEvent.MessageRejected(from, message.messageId, "NON ${message.code}"))
            log.warn { "Dropped a Non-confirmable ${message.code} from $from" }
            return
        }
        received.add(key, CoapMessageType.NON, now)
        effects += if (message.isRequest) {
            MessagingEffect.DeliverRequest(from, message)
        } else {
            MessagingEffect.DeliverResponse(from, message)
        }
    }

    private fun onDuplicate(entry: InboundEntry) {
        val peer = entry.key.peer
        debug(peer, entry.key.messageId) { "Duplicate ${entry.type} in state ${entry.state}" }
        effects += MessagingEffect.Event(EndpointEvent.DuplicateReceived(peer, entry.key.messageId))
        // §4.5: the same ACK or RST again for a Confirmable message, nothing for a Non-confirmable one.
        val reply = entry.reply ?: return
        effects += MessagingEffect.Transmit(peer, reply)
    }

    // ---------------------------------------------------------------- time

    /** When [onTimer] should run next, or null if nothing is scheduled. */
    fun nextDeadline(): Duration? {
        var next: Duration? = null
        fun consider(candidate: Duration?) {
            if (candidate != null && (next == null || candidate < next!!)) next = candidate
        }
        timed.forEach { consider(it.deadline) }
        received.pending.forEach { consider(it.ackDeadline) }
        consider(received.nextExpiry())
        consider(messageIds.nextExpiry())
        consider(nonConfirmableExpiries.firstOrNull()?.second)
        return next
    }

    /** Fires every timer due at [now]. */
    fun onTimer(now: Duration) {
        for (transmission in timed.filter { it.deadline <= now }) {
            if (transmission.isConfirmable) {
                onRetransmissionTimeout(now, transmission)
            } else {
                timed.remove(transmission)
                if (transmission.occupiesSlot) releaseSlot(now, transmission)
                effects += MessagingEffect.Completed(transmission, TransmissionOutcome.SlotReleased)
            }
        }
        for (entry in received.pending.filter { deadline -> deadline.ackDeadline?.let { it <= now } == true }) {
            debug(entry.key.peer, entry.key.messageId) { "No response yet; sending an Empty ACK" }
            sendEmptyAck(entry)
        }
        received.prune(now)
        messageIds.prune(now)
        pruneNonConfirmable(now)
    }

    private fun onRetransmissionTimeout(now: Duration, transmission: Transmission) {
        val peer = transmission.peer
        if (transmission.retransmissions >= parameters.maxRetransmit) {
            debug(peer, transmission.message.messageId) { "Gave up after ${transmission.retransmissions} retransmissions" }
            effects += MessagingEffect.Event(EndpointEvent.TransmissionTimedOut(peer, transmission.message.messageId))
            finish(now, transmission, TransmissionOutcome.TimedOut)
            return
        }
        transmission.retransmissions++
        transmission.timeout *= 2
        transmission.deadline = now + transmission.timeout
        val replacement = transmission.replacement
        if (replacement != null) {
            // RFC 7641 §4.5.2 step 4: new Message ID, counter and timeout retained.
            transmission.replacement = null
            val messageId = allocateMessageId(peer, now)
            val message = replacement.withHeader(CoapMessageType.CON, messageId)
            transmission.replaceWith(message, encodeForSending(peer, message))
            outbound[MessageKey(peer, messageId)] = transmission
            debug(peer, messageId) { "Superseded MID ${transmission.messageIds[transmission.messageIds.size - 2]}" }
            transmission.bytes?.let { transmit(peer, message, it) }
            return
        }
        // §4.2: every retransmission stays within MAX_TRANSMIT_SPAN, even if one is missed.
        if (now - transmission.firstSentAt > parameters.maxTransmitSpan) {
            debug(peer, transmission.message.messageId) { "Skipped a late retransmission outside MAX_TRANSMIT_SPAN" }
            return
        }
        debug(peer, transmission.message.messageId) { "Retransmission ${transmission.retransmissions}, next timeout ${transmission.timeout}" }
        effects += MessagingEffect.Event(
            EndpointEvent.Retransmitted(peer, transmission.message.messageId, transmission.retransmissions),
        )
        transmission.bytes?.let { effects += MessagingEffect.Transmit(peer, it) }
    }

    private fun pruneNonConfirmable(now: Duration) {
        while (true) {
            val (key, expiry) = nonConfirmableExpiries.firstOrNull() ?: return
            if (expiry > now) return
            nonConfirmableExpiries.removeFirst()
            val transmission = outbound[key]
            if (transmission != null && !transmission.isConfirmable) {
                outbound.remove(key)
                if (transmission !in timed) transmission.state = Transmission.State.DONE
            }
        }
    }

    // ---------------------------------------------------------------- sessions and shutdown

    /**
     * RFC 7252 §9.1.1: the DTLS session of [peer] ended. Every transmission towards it fails instead of
     * being retransmitted across epochs, and its deduplication state is dropped, since the endpoint is
     * never seen again.
     */
    fun onSessionEnded(peer: Endpoint) {
        val cause = CoapException("DTLS session to $peer ended", CoapException.Reason.SESSION_ENDED)
        failAll(cause) { it.peer == peer }
        received.forget(peer)
        messageIds.forget(peer)
        effects += MessagingEffect.Event(EndpointEvent.SessionEnded(peer))
    }

    /** Fails every transmission with [cause]; the endpoint is closing. */
    fun close(cause: CoapException = CoapException("Endpoint closed", CoapException.Reason.CLOSED)) {
        failAll(cause) { true }
    }

    private fun failAll(cause: CoapException, predicate: (Transmission) -> Boolean) {
        val affected = (outbound.values + windows.values.flatMap { it.queue })
            .filter { it.state != Transmission.State.DONE && predicate(it) }
            .distinct()
        windows.entries.removeAll { (peer, window) ->
            window.queue.removeAll(predicate)
            affected.any { it.peer == peer } && window.queue.isEmpty()
        }
        for (transmission in affected) {
            transmission.occupiesSlot = false
            retire(transmission)
            effects += MessagingEffect.Completed(transmission, TransmissionOutcome.Failed(cause))
        }
    }

    // ---------------------------------------------------------------- internals

    /** Diagnostics for tests: deduplication entries, reserved Message IDs, and timed transmissions. */
    internal val stateSizes: Triple<Int, Int, Int> get() = Triple(received.size, messageIds.reservedCount, timed.size)

    private fun start(now: Duration, transmission: Transmission) {
        val peer = transmission.peer
        transmission.state = Transmission.State.IN_FLIGHT
        transmission.occupiesSlot = transmission.holdsSlot
        transmission.firstSentAt = now
        val messageId = transmission.message.messageId
        messageIds.extend(peer, messageId, now + parameters.exchangeLifetime)
        if (transmission.isConfirmable) {
            transmission.timeout = initialTimeout()
            transmission.deadline = now + transmission.timeout
            timed += transmission
        } else {
            nonConfirmableExpiries.addLast(MessageKey(peer, messageId) to now + parameters.nonLifetime)
            transmission.slotHold?.let {
                transmission.deadline = now + it
                timed += transmission
            }
        }
        transmission.bytes?.let { transmit(peer, transmission.message, it) }
    }

    /** §4.2: a random duration between ACK_TIMEOUT and ACK_TIMEOUT × ACK_RANDOM_FACTOR. */
    private fun initialTimeout(): Duration =
        parameters.ackTimeout * (1.0 + random.nextDouble() * (parameters.ackRandomFactor - 1.0))

    private fun finish(now: Duration, transmission: Transmission, outcome: TransmissionOutcome) {
        if (transmission.state == Transmission.State.DONE) return
        retire(transmission)
        if (transmission.occupiesSlot) releaseSlot(now, transmission)
        effects += MessagingEffect.Completed(transmission, outcome)
    }

    private fun retire(transmission: Transmission) {
        transmission.state = Transmission.State.DONE
        timed.remove(transmission)
        for (messageId in transmission.messageIds) {
            val key = MessageKey(transmission.peer, messageId)
            if (outbound[key] === transmission) outbound.remove(key)
        }
    }

    private fun releaseSlot(now: Duration, transmission: Transmission) {
        transmission.occupiesSlot = false
        val window = windows[transmission.peer] ?: return
        window.outstanding--
        while (window.outstanding < parameters.nstart) {
            val next = window.queue.removeFirstOrNull() ?: break
            window.outstanding++
            start(now, next)
        }
        if (window.outstanding == 0 && window.queue.isEmpty()) windows.remove(transmission.peer)
    }

    private fun sendEmptyAck(entry: InboundEntry) {
        val ack = CoapMessage.empty(CoapMessageType.ACK, entry.key.messageId)
        val bytes = encodeForSending(entry.key.peer, ack)
        entry.state = InboundEntry.State.ACKNOWLEDGED
        entry.reply = bytes
        received.pending.remove(entry)
        if (bytes != null) transmit(entry.key.peer, ack, bytes)
    }

    private fun sendReset(entry: InboundEntry, reason: String) {
        val peer = entry.key.peer
        debug(peer, entry.key.messageId) { "Rejecting with RST: $reason" }
        effects += MessagingEffect.Event(EndpointEvent.MessageRejected(peer, entry.key.messageId, reason))
        val reset = CoapMessage.empty(CoapMessageType.RST, entry.key.messageId)
        val bytes = encodeForSending(peer, reset)
        entry.answer(if (entry.type == CoapMessageType.CON) bytes else null)
        received.pending.remove(entry)
        if (bytes != null) transmit(peer, reset, bytes)
    }

    private fun InboundEntry.answer(bytes: ByteArray?) {
        state = InboundEntry.State.ANSWERED
        reply = bytes
    }

    /** Runs the interceptors and encodes; null when an interceptor dropped the message. */
    private fun encodeForSending(peer: Endpoint, message: CoapMessage): ByteArray? {
        val intercepted = interceptors.intercept(message, log) { interceptor, current -> interceptor.onSend(peer, current) } ?: return null
        val bytes = UdpMessageCodec.encode(intercepted)
        if (bytes.size > maxDatagramSize) {
            throw CoapException(
                "Message of ${bytes.size} bytes exceeds the datagram size $maxDatagramSize",
                CoapException.Reason.MESSAGE_TOO_LARGE,
            )
        }
        return bytes
    }

    private fun transmit(peer: Endpoint, message: CoapMessage, bytes: ByteArray) {
        if (log.isEnabled(LogLevel.TRACE)) log.trace { "Sending $message to $peer: ${log.payload(bytes)}" }
        effects += MessagingEffect.Transmit(peer, bytes)
    }

    private inline fun debug(peer: Endpoint, messageId: Int, message: () -> String) {
        if (log.isEnabled(LogLevel.DEBUG)) log.with("peer" to peer, "mid" to messageId).debug(message = message)
    }
}

/** A copy of this message with the messaging-layer header fields set. */
internal fun CoapMessage.withHeader(type: CoapMessageType, messageId: Int): CoapMessage =
    toBuilder().apply {
        this.type = type
        this.messageId = messageId
    }.build()
