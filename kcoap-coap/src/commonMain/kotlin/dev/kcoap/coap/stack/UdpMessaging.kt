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
import kotlin.time.Duration.Companion.seconds

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
 *   holding a slot for as long as the caller asks (PROBING_RATE for requests), and Non-confirmable
 *   Q-Block bodies holding one slot for the whole body through a [BodyLease] (RFC 9177 §7.2),
 * - superseding an outstanding notification with a newer one at its next timeout (RFC 7641 §4.5.2),
 * - failing, never retransmitting, everything towards an ended DTLS session (§9.1.1).
 *
 * Peers are compared by [Endpoint] equality only. Not thread safe: the driver serializes all calls.
 *
 * @param separateResponseDelay how long a Confirmable request may stay unanswered before it gets an Empty
 *   ACK; null leaves that to the upper layer.
 * @param maxDeduplicationEntries the most received messages remembered; new ones beyond it are dropped
 *   without state or answer (RFC 7252 §11.3).
 * @param maxPerPeer the most slot-holding messages and body leases outstanding or queued towards one peer;
 *   [send] and [lease] refuse more.
 */
internal class UdpMessaging(
    private val parameters: TransmissionParameters,
    private val maxDatagramSize: Int,
    private val separateResponseDelay: Duration?,
    private val random: Random,
    private val interceptors: List<MessageInterceptor> = emptyList(),
    loggerFactory: LoggerFactory = LoggerFactory.Global,
    maxDeduplicationEntries: Int = Int.MAX_VALUE,
    private val maxPerPeer: Int = Int.MAX_VALUE,
) {
    private val log: Logger = loggerFactory.logger(LogTags.COAP_MESSAGE)
    private val timing = QBlockTiming(parameters)
    private val effects = ArrayList<MessagingEffect>()
    private val messageIds = MessageIdAllocator(random.nextInt(0x10000))
    private val received = DeduplicationCache(parameters.exchangeLifetime, parameters.nonLifetime, maxDeduplicationEntries)

    /** Our Confirmable messages until they end, and Non-confirmable ones until NON_LIFETIME, by Message ID. */
    private val outbound = HashMap<MessageKey, Transmission>()
    private val nonConfirmableExpiries = ArrayDeque<Pair<MessageKey, Duration>>()

    /** Transmissions with a running timer: Confirmable ones awaiting an ACK, Non-confirmable ones holding a slot. */
    private val timed = LinkedHashSet<Transmission>()

    /** Body leases holding a slot, each until its deadline at the latest. */
    private val leases = LinkedHashSet<BodyLease>()
    private val windows = HashMap<Endpoint, PeerWindow>()

    /** The deadline [nextDeadline] last returned, which the timer loop waits for; null while it waits for none. */
    private var armedDeadline: Duration? = null

    /** Whether a call since [takeWakeup] added a deadline earlier than [armedDeadline]. */
    private var wakeupNeeded = false

    /** A peer's NSTART slots: how many are taken, and who waits for one, first come first served. */
    private class PeerWindow {
        var outstanding = 0
        val queue = ArrayDeque<SlotHolder>()
    }

    /** Returns and clears the effects produced since the last call. */
    fun takeEffects(): List<MessagingEffect> {
        val taken = effects.toList()
        effects.clear()
        return taken
    }

    /**
     * Whether the timer loop must recompute its wait, then clears it: true only when a call since the last
     * one added a deadline earlier than the one [nextDeadline] last returned. A deadline that goes away or
     * moves later needs no wakeup, since the loop recomputes after every wait, so most calls, such as a
     * datagram that adds only a deduplication lifetime, leave the timers alone however many transmissions
     * are outstanding.
     */
    fun takeWakeup(): Boolean = wakeupNeeded.also { wakeupNeeded = false }

    /** Notes a deadline a call added, for [takeWakeup]. */
    private fun schedule(deadline: Duration) {
        val armed = armedDeadline
        if (armed == null || deadline < armed) {
            armedDeadline = deadline
            wakeupNeeded = true
        }
    }

    // ---------------------------------------------------------------- sending

    /** A Message ID for a new message to [peer], reserved for EXCHANGE_LIFETIME (§4.4). */
    fun allocateMessageId(peer: Endpoint, now: Duration): Int =
        messageIds.allocate(peer, now, now + parameters.exchangeLifetime).also { schedule(now + parameters.exchangeLifetime) }

    /**
     * Sends a Confirmable or Non-confirmable [message] whose Message ID came from [allocateMessageId]. A
     * Confirmable message, or a Non-confirmable one with a [slotHold], waits while [peer] has NSTART
     * outstanding interactions.
     *
     * @throws CoapException with [CoapException.Reason.MESSAGE_TOO_LARGE] if it does not fit a datagram,
     *   with [CoapException.Reason.IDENTIFIERS_EXHAUSTED] if [peer] has the most slot-holding messages
     *   outstanding or queued that the endpoint allows.
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
        val existing = windows[peer]
        if (transmission.holdsSlot && existing.isFull()) throw beyondLimit(peer)
        outbound[MessageKey(peer, message.messageId)] = transmission
        if (transmission.holdsSlot) {
            val window = existing ?: PeerWindow().also { windows[peer] = it }
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

    /**
     * Takes one NSTART slot towards [peer] for a whole Non-confirmable Q-Block body of [bodyBytes] bytes
     * (RFC 9177 §7.2), waiting in the peer's queue like a transmission while its slots are taken. Once
     * granted it holds the slot until [releaseLease], or for min([bodyBytes] ÷ PROBING_RATE,
     * NON_PROBING_WAIT) at most (RFC 7252 §4.7, RFC 9177 §7.2). NON_PROBING_WAIT takes the body's
     * [nonTimeoutRandom], drawn here when it is null. [MessagingEffect.LeaseGranted] says when it is granted.
     *
     * @throws CoapException with [CoapException.Reason.IDENTIFIERS_EXHAUSTED] if [peer] has the most
     *   slot-holding messages and leases outstanding or queued that the endpoint allows.
     */
    fun lease(now: Duration, peer: Endpoint, bodyBytes: Int, nonTimeoutRandom: Duration? = null): BodyLease {
        require(bodyBytes >= 0) { "A body cannot have a negative size: $bodyBytes" }
        require(nonTimeoutRandom == null || !nonTimeoutRandom.isNegative()) {
            "NON_TIMEOUT_RANDOM cannot be negative: $nonTimeoutRandom"
        }
        val existing = windows[peer]
        if (existing.isFull()) throw beyondLimit(peer)
        val drawn = nonTimeoutRandom ?: timing.drawNonTimeoutRandom(random)
        val atProbingRate = (bodyBytes.toDouble() / parameters.probingRate).seconds
        val lease = BodyLease(peer, minOf(atProbingRate, timing.nonProbingWait(drawn)), drawn)
        val window = existing ?: PeerWindow().also { windows[peer] = it }
        if (window.outstanding >= parameters.nstart) {
            lease.state = BodyLease.State.QUEUED
            window.queue.addLast(lease)
            log.with("peer" to peer).debug { "Body lease queued behind NSTART=${parameters.nstart}" }
            return lease
        }
        window.outstanding++
        grant(now, lease)
        return lease
    }

    /**
     * The body of [lease] ended: a queued lease leaves the queue, a held one frees its slot at once, and one
     * that expired is only marked released. Idempotent.
     */
    fun releaseLease(now: Duration, lease: BodyLease) {
        when (lease.state) {
            BodyLease.State.QUEUED -> {
                windows[lease.peer]?.queue?.remove(lease)
                lease.state = BodyLease.State.RELEASED
            }
            BodyLease.State.HELD -> {
                lease.state = BodyLease.State.RELEASED
                if (lease.isPaced) {
                    leases.remove(lease)
                    releaseSlot(now, lease)
                }
            }
            BodyLease.State.CREATED, BodyLease.State.EXPIRED -> lease.state = BodyLease.State.RELEASED
            BodyLease.State.RELEASED, BodyLease.State.FAILED -> Unit
        }
    }

    /** Whether a peer with this window has [maxPerPeer] slot holders outstanding or queued already. */
    private fun PeerWindow?.isFull(): Boolean = this != null && outstanding + queue.size >= maxPerPeer

    /**
     * RFC 7252 §11.3: a peer that never acknowledges, such as a spoofed one, must not grow the queue. Reports
     * the limit and returns the refusal to throw.
     */
    private fun beyondLimit(peer: Endpoint): CoapException {
        val limit = EndpointEvent.LimitReached.Limit.EXCHANGES_PER_PEER
        effects += MessagingEffect.Event(EndpointEvent.LimitReached(peer, limit))
        return CoapException("$peer has $maxPerPeer messages in progress", CoapException.Reason.IDENTIFIERS_EXHAUSTED)
    }

    private fun grant(now: Duration, lease: BodyLease) {
        lease.state = BodyLease.State.HELD
        lease.deadline = now + lease.hold
        leases += lease
        schedule(lease.deadline)
        log.with("peer" to lease.peer).debug { "Body lease granted for ${lease.hold} at most" }
        effects += MessagingEffect.LeaseGranted(lease)
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
        if (!received.hasRoom(now)) return dropForLimit(from, message)
        val entry = received.add(key, CoapMessageType.CON, now)
        schedule(entry.expiresAt)
        when {
            // §4.2, §4.3: an Empty Confirmable message elicits a Reset ("CoAP ping").
            message.isEmpty -> sendReset(entry, "CoAP ping")
            message.isRequest -> {
                entry.ackDeadline = separateResponseDelay?.let { now + it }?.also(::schedule)
                received.pending += entry
                effects += MessagingEffect.DeliverRequest(from, message)
            }
            // No timer acknowledges a response: the upper layer answers it with an ACK or a Reset only after
            // verifying it and checking its critical options (RFC 7252 §5.4.1), however long that takes.
            message.isResponse -> effects += MessagingEffect.DeliverResponse(from, message)
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
        if (!received.hasRoom(now)) return dropForLimit(from, message)
        schedule(received.add(key, CoapMessageType.NON, now).expiresAt)
        effects += if (message.isRequest) {
            MessagingEffect.DeliverRequest(from, message)
        } else {
            MessagingEffect.DeliverResponse(from, message)
        }
    }

    /**
     * RFC 7252 §11.3: with the deduplication cache full, a new message is dropped without state and without
     * an answer, which might go to a spoofed source; its sender retransmits a Confirmable one.
     */
    private fun dropForLimit(from: Endpoint, message: CoapMessage) {
        debug(from, message.messageId) { "Dropped a new ${message.type}: the deduplication cache is full" }
        effects += MessagingEffect.Event(EndpointEvent.LimitReached(from, EndpointEvent.LimitReached.Limit.DEDUPLICATION_ENTRIES))
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
        leases.forEach { consider(it.deadline) }
        // Pending entries are in arrival order, and so in deadline order: the first is the earliest.
        consider(received.pending.firstOrNull()?.ackDeadline)
        consider(received.nextExpiry())
        consider(messageIds.nextExpiry())
        consider(nonConfirmableExpiries.firstOrNull()?.second)
        armedDeadline = next
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
        for (lease in leases.filter { it.deadline <= now }) {
            // RFC 9177 §7.2: the body has taken its time at PROBING_RATE, or NON_PROBING_WAIT; the next may go.
            leases.remove(lease)
            lease.state = BodyLease.State.EXPIRED
            log.with("peer" to lease.peer).debug { "Body lease expired after ${lease.hold}" }
            releaseSlot(now, lease)
        }
        while (true) {
            // In deadline order, so the scan stops at the first entry not due yet.
            val entry = received.pending.firstOrNull() ?: break
            val deadline = entry.ackDeadline
            if (deadline == null || deadline > now) break
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
        schedule(transmission.deadline)
        val replacement = transmission.replacement
        if (replacement != null) {
            // RFC 7641 §4.5.2 step 4: new Message ID, counter and timeout retained.
            transmission.replacement = null
            val messageId = allocateMessageId(peer, now)
            val message = replacement.withHeader(CoapMessageType.CON, messageId)
            transmission.replaceWith(message, encodeForSending(peer, message))
            outbound[MessageKey(peer, messageId)] = transmission
            debug(peer, messageId) { "Superseded MID ${transmission.messageIds[transmission.messageIds.size - 2]}" }
            transmission.bytes?.let { transmit(peer, message, it, transmission) }
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
        transmission.bytes?.let { effects += MessagingEffect.Transmit(peer, it, transmission) }
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
        failAll(cause) { it == peer }
        received.forget(peer)
        messageIds.forget(peer)
        effects += MessagingEffect.Event(EndpointEvent.SessionEnded(peer))
    }

    /**
     * The transport refused to carry [transmission] at all, a local error no retransmission fixes: it fails
     * at once with [cause] instead of timing out after MAX_TRANSMIT_WAIT as if the peer were silent.
     */
    fun abandon(now: Duration, transmission: Transmission, cause: CoapException) {
        finish(now, transmission, TransmissionOutcome.Failed(cause))
    }

    /** Fails every transmission and body lease with [cause]; the endpoint is closing. */
    fun close(cause: CoapException = CoapException("Endpoint closed", CoapException.Reason.CLOSED)) {
        failAll(cause) { true }
    }

    /** Fails every transmission and body lease towards the peers that [peers] selects. */
    private fun failAll(cause: CoapException, peers: (Endpoint) -> Boolean) {
        val queued = windows.filterKeys(peers).values.flatMap { it.queue }
        val affected = (outbound.values + queued.filterIsInstance<Transmission>())
            .filter { it.state != Transmission.State.DONE && peers(it.peer) }
            .distinct()
        val endedLeases = queued.filterIsInstance<BodyLease>() + leases.filter { peers(it.peer) }
        // Every slot holder of these peers fails below, so their windows have nothing left to count.
        windows.keys.removeAll(peers)
        for (transmission in affected) {
            transmission.occupiesSlot = false
            retire(transmission)
            effects += MessagingEffect.Completed(transmission, TransmissionOutcome.Failed(cause))
        }
        for (lease in endedLeases) {
            leases.remove(lease)
            lease.state = BodyLease.State.FAILED
            lease.failure = cause
            effects += MessagingEffect.LeaseFailed(lease, cause)
        }
    }

    // ---------------------------------------------------------------- internals

    /** Diagnostics for tests: deduplication entries, reserved Message IDs, and timed transmissions. */
    internal val stateSizes: Triple<Int, Int, Int> get() = Triple(received.size, messageIds.reservedCount, timed.size)

    /** Diagnostics for tests: body leases that hold a slot or wait for one. */
    internal val leaseCount: Int
        get() = leases.size + windows.values.sumOf { window -> window.queue.count { it is BodyLease } }

    private fun start(now: Duration, transmission: Transmission) {
        val peer = transmission.peer
        transmission.state = Transmission.State.IN_FLIGHT
        transmission.occupiesSlot = transmission.holdsSlot
        transmission.firstSentAt = now
        val messageId = transmission.message.messageId
        messageIds.extend(peer, messageId, now + parameters.exchangeLifetime)
        schedule(now + parameters.exchangeLifetime)
        if (transmission.isConfirmable) {
            transmission.timeout = initialTimeout()
            transmission.deadline = now + transmission.timeout
            timed += transmission
            schedule(transmission.deadline)
        } else {
            nonConfirmableExpiries.addLast(MessageKey(peer, messageId) to now + parameters.nonLifetime)
            schedule(now + parameters.nonLifetime)
            transmission.slotHold?.let {
                transmission.deadline = now + it
                timed += transmission
                schedule(transmission.deadline)
            }
        }
        transmission.bytes?.let { transmit(peer, transmission.message, it, transmission) }
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

    /** [holder] gives its slot back; the transmissions and leases waiting for one get it in turn. */
    private fun releaseSlot(now: Duration, holder: SlotHolder) {
        if (holder is Transmission) holder.occupiesSlot = false
        val window = windows[holder.peer] ?: return
        window.outstanding--
        while (window.outstanding < parameters.nstart) {
            val next = window.queue.removeFirstOrNull() ?: break
            window.outstanding++
            when (next) {
                is Transmission -> start(now, next)
                is BodyLease -> grant(now, next)
            }
        }
        if (window.outstanding == 0 && window.queue.isEmpty()) windows.remove(holder.peer)
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

    private fun transmit(peer: Endpoint, message: CoapMessage, bytes: ByteArray, transmission: Transmission? = null) {
        if (log.isEnabled(LogLevel.TRACE)) log.trace { "Sending $message to $peer: ${log.payload(bytes)}" }
        effects += MessagingEffect.Transmit(peer, bytes, transmission)
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
