package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.SignalingOptions
import dev.kcoap.coap.codec.CoapFormatException
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.coap.codec.ReliableStreamDecoder
import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/** How messages are delimited on a connection. */
internal enum class Framing(val codec: ReliableMessageCodec) {
    /** TCP and TLS: the Len/TKL header on a byte stream (RFC 8323 §3.2). */
    BYTE_STREAM(ReliableMessageCodec.Tcp),

    /** WebSocket: one message per binary frame, with Len 0 (RFC 8323 §4.2). */
    MESSAGE_STREAM(ReliableMessageCodec.WebSocket),
}

/** What the peer's CSMs said (RFC 8323 §5.3), with the base values until then. */
internal class PeerSettings(val maxMessageSize: Long, val blockWiseTransfer: Boolean) {
    /** §5.3.2: Block-Wise-Transfer together with a Max-Message-Size above 1152 indicates BERT. */
    val supportsBert: Boolean get() = blockWiseTransfer && maxMessageSize > SignalingOptions.BASE_MAX_MESSAGE_SIZE

    override fun toString(): String = "PeerSettings(maxMessageSize=$maxMessageSize, blockWiseTransfer=$blockWiseTransfer)"

    companion object {
        val BASE: PeerSettings = PeerSettings(SignalingOptions.BASE_MAX_MESSAGE_SIZE, blockWiseTransfer = false)
    }
}

/** A Ping on its way, answered by the Pong with the same token (RFC 8323 §5.4). */
internal class PendingPing(val token: ByteArray, val custody: Boolean, val sentAt: Duration)

/**
 * What [ReliableMessaging] asks its driver to do. [Write] effects must reach the connection in the order
 * they were produced.
 */
internal sealed interface ConnectionEffect {
    /** Write one encoded message to the connection. */
    class Write(val bytes: ByteArray) : ConnectionEffect

    /** A request arrived; once it is answered (or deliberately not), call [ReliableMessaging.answered] with [sequence]. */
    class DeliverRequest(val message: CoapMessage, val sequence: Long) : ConnectionEffect

    /** A response or notification arrived. */
    class DeliverResponse(val message: CoapMessage) : ConnectionEffect

    /** The peer's first CSM arrived: the connection is ready, with the peer's [settings]. */
    class Established(val settings: PeerSettings) : ConnectionEffect

    /** [ping] got its Pong, with Custody or not. */
    class PingAnswered(val ping: PendingPing, val custody: Boolean) : ConnectionEffect

    /** [ping] will never be answered: the connection closed. */
    class PingFailed(val ping: PendingPing) : ConnectionEffect

    /** The peer sent a Release (RFC 8323 §5.5): send no new requests, and stay away for [holdOff]. */
    class PeerReleased(val alternativeAddresses: List<String>, val holdOff: Duration?) : ConnectionEffect

    /** The connection is over: write what is pending, then close the transport. Comes once, last. */
    class Closed(val reason: String) : ConnectionEffect

    /** Something worth reporting to [EndpointEventListener]. */
    class Event(val event: EndpointEvent) : ConnectionEffect
}

/**
 * The messaging layer of CoAP over reliable transports (RFC 8323 §3–§5) for one connection, as a sans-IO
 * state machine: received bytes and time go in, [ConnectionEffect]s come out (see [takeEffects]). The
 * transport is reliable and ordered, so there is no Type, Message ID, acknowledgement, retransmission or
 * deduplication (§3.1). What it does:
 *
 * - framing: Len/TKL on byte streams (§3.2), one message per WebSocket frame (§4.2), and the receive limit
 *   that its own Max-Message-Size announces; a larger message aborts the connection;
 * - the CSM exchange: its CSM goes first (§3.3), the peer's MUST come first, within
 *   [ReliableTransportConfig.csmTimeout], else Abort; Max-Message-Size and Block-Wise-Transfer are
 *   cumulative across CSMs (§5.3);
 * - Ping and Pong (§5.4), with Custody: a Pong with Custody waits until every request received before
 *   the Ping is answered (§5.4.1);
 * - Release (§5.5): sent when the connection this side opened is idle; received, it stops new requests
 *   and closes the connection once the requests received before it are answered and this side's own
 *   requests got their responses, within [ReliableTransportConfig.releaseTimeout];
 * - Abort (§5.6): sent on a missing CSM, a message format error, an oversize message or an unknown
 *   critical signaling option (§5.2), with Bad-CSM-Option for an invalid CSM; received, it closes;
 * - Empty messages are ignored (§3.4); unassigned signaling codes and reserved code classes too, since
 *   reliable transports have no Reset to reject them with;
 * - keep-alive pings when [ReliableTransportConfig.keepAliveInterval] is set (§3.4).
 *
 * Tokens are scoped to the connection, so one instance serves exactly one. Not thread safe: the driver
 * serializes all calls.
 */
internal class ReliableMessaging(
    private val peer: Endpoint,
    private val framing: Framing,
    private val isInitiator: Boolean,
    private val config: ReliableTransportConfig,
    private val interceptors: List<MessageInterceptor> = emptyList(),
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    private val log = loggerFactory.logger(LogTags.COAP_MESSAGE).with("peer" to peer)
    private val effects = ArrayList<ConnectionEffect>()
    private val decoder = ReliableStreamDecoder(config.maxMessageSize)

    private enum class Phase { NEW, AWAITING_CSM, ESTABLISHED, CLOSED }

    private var phase = Phase.NEW

    /** The peer's settings: base values until its first CSM (§5.3). */
    var peerSettings: PeerSettings = PeerSettings.BASE
        private set

    val isEstablished: Boolean get() = phase == Phase.ESTABLISHED
    val isClosed: Boolean get() = phase == Phase.CLOSED

    /** A Release went out or came in, so the connection takes no new requests (§5.5). */
    val isReleasing: Boolean get() = releaseSentAt != null || releaseReceived != null

    private var startedAt = Duration.ZERO
    private var lastReceivedAt = Duration.ZERO

    /** The last request or response in either direction, for the idle timeout. */
    private var lastActivityAt = Duration.ZERO

    private val pings = ArrayDeque<PendingPing>()
    private var nextPingToken = 1L
    private var keepAlivePing: PendingPing? = null

    /** Requests delivered and not answered yet, by sequence number, ascending. */
    private val unanswered = LinkedHashSet<Long>()
    private var nextRequestSequence = 0L

    /** Pongs with Custody waiting for every request before [PendingPong.before] to be answered. */
    private class PendingPong(val token: ByteArray, val before: Long)

    private val custodyPongs = ArrayDeque<PendingPong>()

    /** Tokens of this side's requests that have no response yet. */
    private val awaitingResponse = HashSet<String>()
    private var openExchanges = 0

    private var releaseSentAt: Duration? = null

    /** A Release came in: the requests received before [before] are answered before closing, until [deadline]. */
    private class ReceivedRelease(val before: Long, val deadline: Duration)

    private var releaseReceived: ReceivedRelease? = null

    /** Returns and clears the effects produced since the last call. */
    fun takeEffects(): List<ConnectionEffect> {
        val taken = effects.toList()
        effects.clear()
        return taken
    }

    // ---------------------------------------------------------------- lifecycle

    /** The connection is up: send this side's CSM, its first message (§3.3), without waiting for the peer's. */
    fun start(now: Duration) {
        check(phase == Phase.NEW) { "Already started" }
        phase = Phase.AWAITING_CSM
        startedAt = now
        lastReceivedAt = now
        lastActivityAt = now
        // §5.3: absent options mean base values, so the base Max-Message-Size need not be sent.
        val maxMessageSize = config.maxMessageSize.takeIf { it != SignalingOptions.BASE_MAX_MESSAGE_SIZE }
        sendSignal(Signal.Csm(maxMessageSize, config.blockWiseTransfer))
    }

    /** The transport reported the end of the connection, or failed. */
    fun onTransportClosed(reason: String) {
        close(reason)
    }

    // ---------------------------------------------------------------- receiving

    /** Processes what arrived: any chunk of a byte stream, or one whole WebSocket message. */
    fun onReceived(now: Duration, bytes: ByteArray) {
        if (phase == Phase.CLOSED) return
        check(phase != Phase.NEW) { "Not started" }
        val messages = try {
            when (framing) {
                Framing.BYTE_STREAM -> decoder.feed(bytes)
                Framing.MESSAGE_STREAM -> {
                    if (bytes.size > config.maxMessageSize) {
                        throw CoapFormatException("Message of ${bytes.size} bytes exceeds the limit of ${config.maxMessageSize}")
                    }
                    listOf(framing.codec.decode(bytes))
                }
            }
        } catch (e: CoapFormatException) {
            // §5.6: a message with a syntax violation is rejected with an Abort and otherwise ignored.
            val reason = e.message ?: "message format error"
            effects += ConnectionEffect.Event(EndpointEvent.MalformedMessage(peer, reason))
            abort("Message format error: $reason")
            return
        }
        for (message in messages) {
            if (phase == Phase.CLOSED) return
            onMessage(now, message)
        }
    }

    private fun onMessage(now: Duration, decoded: CoapMessage) {
        if (log.isEnabled(LogLevel.TRACE)) log.trace { "Received $decoded" }
        lastReceivedAt = now
        keepAlivePing = null
        val message = interceptors.intercept(decoded, log) { interceptor, current -> interceptor.onReceive(peer, current) } ?: return
        when {
            // §3.4: Empty messages can always be sent and MUST be ignored, even before the CSM.
            message.isEmpty -> log.debug { "Ignored an Empty message" }
            phase == Phase.AWAITING_CSM && message.code != CoapCode.CSM ->
                abort("Expected a CSM as the first message, got ${message.code}")
            message.isSignaling -> onSignal(now, message)
            message.isRequest -> {
                lastActivityAt = now
                val sequence = nextRequestSequence++
                unanswered += sequence
                effects += ConnectionEffect.DeliverRequest(message, sequence)
            }
            message.isResponse -> {
                lastActivityAt = now
                awaitingResponse.remove(message.tokenHex)
                effects += ConnectionEffect.DeliverResponse(message)
                checkDrained()
            }
            else -> reject(message, "reserved code ${message.code}")
        }
    }

    private fun onSignal(now: Duration, message: CoapMessage) {
        when (val parsed = Signal.parse(message)) {
            Signal.Parsed.UnknownCode -> reject(message, "unassigned signaling code ${message.code}")
            is Signal.Parsed.Invalid -> abort(parsed.reason, parsed.badCsmOption)
            is Signal.Parsed.Known -> when (val signal = parsed.signal) {
                is Signal.Csm -> onCsm(signal)
                is Signal.Ping -> onPing(message.token, signal)
                is Signal.Pong -> onPong(message.token, signal)
                is Signal.Release -> onRelease(now, signal)
                is Signal.Abort -> onAbort(signal)
            }
        }
    }

    /** Messages with no meaning here: there is no Reset on reliable transports, so they are only dropped. */
    private fun reject(message: CoapMessage, reason: String) {
        log.warn { "Ignored a message with $reason" }
        effects += ConnectionEffect.Event(EndpointEvent.MessageRejected(peer, 0, reason))
    }

    /** §5.3: options are cumulative; an absent one leaves the value in force. */
    private fun onCsm(csm: Signal.Csm) {
        val previous = peerSettings
        peerSettings = PeerSettings(csm.maxMessageSize ?: previous.maxMessageSize, previous.blockWiseTransfer || csm.blockWiseTransfer)
        log.debug { "CSM: $peerSettings" }
        effects += ConnectionEffect.Event(
            EndpointEvent.CsmReceived(peer, peerSettings.maxMessageSize, peerSettings.blockWiseTransfer),
        )
        if (phase == Phase.AWAITING_CSM) {
            phase = Phase.ESTABLISHED
            effects += ConnectionEffect.Established(peerSettings)
        }
    }

    /** §5.4: a Pong with the identical token, at once unless the Ping asks for Custody (§5.4.1). */
    private fun onPing(token: ByteArray, ping: Signal.Ping) {
        if (!ping.custody || !hasUnansweredBefore(nextRequestSequence)) {
            sendSignal(Signal.Pong(custody = ping.custody), token)
        } else {
            log.debug { "Ping with Custody: the Pong waits for ${unanswered.size} requests" }
            custodyPongs.addLast(PendingPong(token, nextRequestSequence))
        }
    }

    private fun onPong(token: ByteArray, pong: Signal.Pong) {
        // Some peers (libcoap 4.3.5) answer with an empty token; Pongs come in order, so the oldest Ping matches.
        val ping = pings.firstOrNull { it.token.contentEquals(token) } ?: pings.firstOrNull()?.takeIf { token.isEmpty() }
        if (ping == null) {
            log.debug { "Ignored a Pong that answers no Ping" }
            return
        }
        pings.remove(ping)
        effects += ConnectionEffect.PingAnswered(ping, pong.custody)
    }

    private fun onRelease(now: Duration, release: Signal.Release) {
        log.info { "Released by the peer: ${release.diagnostic ?: "no reason given"}" }
        effects += ConnectionEffect.Event(
            EndpointEvent.ReleaseReceived(peer, release.alternativeAddresses, release.holdOff, release.diagnostic),
        )
        effects += ConnectionEffect.PeerReleased(release.alternativeAddresses, release.holdOff)
        if (releaseReceived == null) releaseReceived = ReceivedRelease(nextRequestSequence, now + config.releaseTimeout)
        checkDrained()
    }

    private fun onAbort(abort: Signal.Abort) {
        log.warn { "Aborted by the peer: ${abort.diagnostic ?: "no reason given"}" }
        effects += ConnectionEffect.Event(EndpointEvent.AbortReceived(peer, abort.badCsmOption, abort.diagnostic))
        close("aborted by the peer" + (abort.diagnostic?.let { ": $it" } ?: ""))
    }

    // ---------------------------------------------------------------- sending

    /**
     * Sends a request or a response. A request records its token until its first response, which a
     * Release waits for.
     *
     * @throws CoapException with [CoapException.Reason.CONNECTION_CLOSED] when the connection is closed,
     *   or released for a request; with [CoapException.Reason.MESSAGE_TOO_LARGE] above the peer's
     *   Max-Message-Size (§5.3.1).
     */
    fun send(now: Duration, message: CoapMessage) {
        require(message.isRequest || message.isResponse) { "Only requests and responses are sent this way: $message" }
        check(phase != Phase.NEW) { "Not started" }
        if (phase == Phase.CLOSED) throw CoapException("The connection to $peer is closed", CoapException.Reason.CONNECTION_CLOSED)
        if (message.isRequest && isReleasing) {
            throw CoapException("The connection to $peer is being released", CoapException.Reason.CONNECTION_CLOSED)
        }
        val outgoing = interceptors.intercept(message, log) { interceptor, current -> interceptor.onSend(peer, current) } ?: return
        val bytes = framing.codec.encode(outgoing)
        if (bytes.size > peerSettings.maxMessageSize) {
            throw CoapException(
                "Message of ${bytes.size} bytes exceeds the Max-Message-Size ${peerSettings.maxMessageSize} of $peer",
                CoapException.Reason.MESSAGE_TOO_LARGE,
            )
        }
        if (outgoing.isRequest) awaitingResponse += outgoing.tokenHex
        lastActivityAt = now
        write(outgoing, bytes)
    }

    /**
     * The request delivered with [sequence] was answered, or is deliberately left unanswered (RFC 7967).
     * Releases Pongs with Custody that waited for it, and completes a drain after a Release.
     */
    fun answered(sequence: Long) {
        if (!unanswered.remove(sequence)) return
        while (custodyPongs.isNotEmpty() && !hasUnansweredBefore(custodyPongs.first().before)) {
            val pong = custodyPongs.removeFirst()
            if (phase != Phase.CLOSED) sendSignal(Signal.Pong(custody = true), pong.token)
        }
        checkDrained()
    }

    /** An exchange that keeps the connection busy (its requests, or an observation) started. */
    fun exchangeOpened() {
        openExchanges++
    }

    /**
     * An exchange ended; a request it sent with [tokenHex] no longer waits for a response. Null when a newer
     * exchange took over the token, which still waits.
     */
    fun exchangeClosed(tokenHex: String?) {
        if (openExchanges > 0) openExchanges--
        if (tokenHex != null) awaitingResponse.remove(tokenHex)
        checkDrained()
    }

    /**
     * Sends a Ping (§5.4); [custody] asks the peer to answer after processing what it received before
     * (§5.4.1). The answer comes as [ConnectionEffect.PingAnswered], or [ConnectionEffect.PingFailed]
     * when the connection closes first.
     *
     * @throws CoapException with [CoapException.Reason.CONNECTION_CLOSED] when the connection is closed.
     */
    fun ping(now: Duration, custody: Boolean = false): PendingPing {
        check(phase != Phase.NEW) { "Not started" }
        if (phase == Phase.CLOSED) throw CoapException("The connection to $peer is closed", CoapException.Reason.CONNECTION_CLOSED)
        val ping = PendingPing(minimalUnsignedBytes(nextPingToken++), custody, now)
        pings.addLast(ping)
        sendSignal(Signal.Ping(custody), ping.token)
        return ping
    }

    /** Forgets [ping], whose sender stopped waiting. */
    fun cancelPing(ping: PendingPing) {
        pings.remove(ping)
    }

    /**
     * Sends a Release (§5.5) and takes no new requests; the peer is expected to close, else the
     * connection closes after [ReliableTransportConfig.releaseTimeout].
     */
    fun release(now: Duration, diagnostic: String? = null, alternativeAddresses: List<String> = emptyList(), holdOff: Duration? = null) {
        if (phase == Phase.CLOSED || phase == Phase.NEW || releaseSentAt != null) return
        log.debug { "Releasing: ${diagnostic ?: "no reason given"}" }
        releaseSentAt = now
        sendSignal(Signal.Release(alternativeAddresses, holdOff, diagnostic))
    }

    /** A Release followed by closing at once, for an endpoint that shuts down. */
    fun shutdown(now: Duration, diagnostic: String) {
        release(now, diagnostic)
        close("shut down: $diagnostic")
    }

    /** Sends an Abort (§5.6) and closes; nothing received afterwards is processed. */
    fun abort(diagnostic: String, badCsmOption: Int? = null) {
        if (phase == Phase.CLOSED || phase == Phase.NEW) return
        log.warn { "Aborting the connection: $diagnostic" }
        sendSignal(Signal.Abort(badCsmOption, diagnostic))
        effects += ConnectionEffect.Event(EndpointEvent.AbortSent(peer, diagnostic, badCsmOption))
        close("aborted: $diagnostic")
    }

    // ---------------------------------------------------------------- time

    /** When [onTimer] should run next, or null if nothing is scheduled. */
    fun nextDeadline(): Duration? {
        if (phase == Phase.NEW || phase == Phase.CLOSED) return null
        return listOfNotNull(
            (startedAt + config.csmTimeout).takeIf { phase == Phase.AWAITING_CSM },
            releaseSentAt?.plus(config.releaseTimeout),
            releaseReceived?.deadline,
            idleDeadline(),
            keepAliveDeadline(),
        ).minOrNull()
    }

    /** Fires every timer due at [now]. */
    fun onTimer(now: Duration) {
        if (phase == Phase.NEW || phase == Phase.CLOSED) return
        if (phase == Phase.AWAITING_CSM && now >= startedAt + config.csmTimeout) {
            abort("No CSM within ${config.csmTimeout}")
            return
        }
        val received = releaseReceived
        if (received != null && now >= received.deadline) {
            close("released by the peer; ${unanswered.size} requests unanswered")
            return
        }
        val sent = releaseSentAt
        if (sent != null && now >= sent + config.releaseTimeout) {
            close("released; the peer did not close the connection")
            return
        }
        idleDeadline()?.let { if (now >= it) release(now, "idle") }
        val keepAlive = keepAlivePing
        if (keepAlive != null && now >= keepAlive.sentAt + config.pingTimeout) {
            abort("Nothing received within ${config.pingTimeout} after a keep-alive Ping")
            return
        }
        keepAliveDeadline()?.let { if (keepAlive == null && now >= it) keepAlivePing = ping(now) }
    }

    /** §3: a connection this side opened is released once nothing used it for the idle timeout. */
    private fun idleDeadline(): Duration? {
        val timeout = config.idleTimeout ?: return null
        val idle = isInitiator && phase == Phase.ESTABLISHED && !isReleasing && openExchanges == 0 &&
            unanswered.isEmpty() && awaitingResponse.isEmpty()
        return if (idle) lastActivityAt + timeout else null
    }

    private fun keepAliveDeadline(): Duration? {
        val interval = config.keepAliveInterval ?: return null
        if (phase != Phase.ESTABLISHED) return null
        return keepAlivePing?.let { it.sentAt + config.pingTimeout } ?: (lastReceivedAt + interval)
    }

    // ---------------------------------------------------------------- internals

    /** Diagnostics for tests: unanswered requests, Pongs waiting for Custody, own requests awaiting a response. */
    internal val stateSizes: Triple<Int, Int, Int> get() = Triple(unanswered.size, custodyPongs.size, awaitingResponse.size)

    private fun hasUnansweredBefore(sequence: Long): Boolean = unanswered.firstOrNull()?.let { it < sequence } == true

    /** After a Release from the peer: close once the requests before it are answered and own requests got responses. */
    private fun checkDrained() {
        val release = releaseReceived ?: return
        if (phase == Phase.CLOSED || hasUnansweredBefore(release.before) || awaitingResponse.isNotEmpty()) return
        close("released by the peer")
    }

    private fun close(reason: String) {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        log.debug { "Connection closed: $reason" }
        pings.forEach { effects += ConnectionEffect.PingFailed(it) }
        pings.clear()
        custodyPongs.clear()
        effects += ConnectionEffect.Closed(reason)
        effects += ConnectionEffect.Event(EndpointEvent.ConnectionClosed(peer, reason))
    }

    private fun sendSignal(signal: Signal, token: ByteArray = ByteArray(0)) {
        val message = interceptors.intercept(signal.toMessage(token), log) { interceptor, current -> interceptor.onSend(peer, current) }
            ?: return
        write(message, framing.codec.encode(message))
    }

    private fun write(message: CoapMessage, bytes: ByteArray) {
        if (log.isEnabled(LogLevel.TRACE)) log.trace { "Sending $message: ${log.payload(bytes)}" }
        effects += ConnectionEffect.Write(bytes)
    }
}
