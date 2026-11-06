package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.SignalingOptions
import dev.kcoap.coap.codec.ReliableMessageCodec
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ReliableMessagingTest {
    private val peer = Endpoint.Tcp("192.0.2.1", 5683)
    private val codec = ReliableMessageCodec.Tcp

    private fun messaging(
        config: ReliableTransportConfig = ReliableTransportConfig(),
        isInitiator: Boolean = true,
        framing: Framing = Framing.BYTE_STREAM,
        interceptors: List<MessageInterceptor> = emptyList(),
    ) = ReliableMessaging(peer, framing, isInitiator, config, interceptors)

    /** Started, with the own CSM taken, and the peer's CSM received unless [peerCsm] is null. */
    private fun established(
        config: ReliableTransportConfig = ReliableTransportConfig(),
        isInitiator: Boolean = true,
        peerCsm: CoapMessage? = csm(maxMessageSize = 2048, blockWiseTransfer = true),
        interceptors: List<MessageInterceptor> = emptyList(),
    ): ReliableMessaging = messaging(config, isInitiator, interceptors = interceptors).apply {
        start(Duration.ZERO)
        takeEffects()
        if (peerCsm != null) {
            receive(Duration.ZERO, peerCsm)
            takeEffects()
        }
    }

    private fun csm(maxMessageSize: Long? = null, blockWiseTransfer: Boolean = false, vararg extra: CoapOption) = CoapMessage.build {
        code = CoapCode.CSM
        options {
            maxMessageSize?.let { add(CoapOption.uint(2, it)) }
            if (blockWiseTransfer) add(CoapOption.empty(4))
            extra.forEach { add(it) }
        }
    }

    private fun signal(code: CoapCode, token: String = "", vararg options: CoapOption, payload: String = "") = CoapMessage.build {
        this.code = code
        this.token = token.hexToBytes()
        options { options.forEach { add(it) } }
        payload(payload)
    }

    private fun request(token: String = "a1", path: String = "test", payloadSize: Int = 0) = CoapMessage.request(CoapCode.GET) {
        this.token = token.hexToBytes()
        options { uriPath(path) }
        payload = ByteArray(payloadSize)
    }

    private fun response(token: String = "a1", code: CoapCode = CoapCode.CONTENT) = CoapMessage.build {
        this.code = code
        this.token = token.hexToBytes()
        payload("ok")
    }

    private fun ReliableMessaging.receive(now: Duration, message: CoapMessage) = onReceived(now, codec.encode(message))

    private fun List<ConnectionEffect>.written(): List<CoapMessage> =
        filterIsInstance<ConnectionEffect.Write>().map { codec.decode(it.bytes) }

    private fun List<ConnectionEffect>.events(): List<EndpointEvent> = filterIsInstance<ConnectionEffect.Event>().map { it.event }

    private inline fun <reified T : ConnectionEffect> List<ConnectionEffect>.only(): T = filterIsInstance<T>().single()

    // ---------------------------------------------------------------- RFC 8323 figures, byte for byte

    /** RFC 8323 §5.7 Figures 11 and 12: Ping with token 0x42 is 01 e2 42, its Pong 01 e3 42. */
    @Test
    fun rfc8323Section5_7PingAndPongExamples() {
        val messaging = established()
        messaging.onReceived(1.seconds, "01e242".hexToBytes())
        val written = messaging.takeEffects().filterIsInstance<ConnectionEffect.Write>()
        assertEquals(listOf("01e342"), written.map { it.bytes.toHex() })
    }

    /** RFC 8323 §3.2 Figure 5: a 2.03 response with token 7f and nothing else is 01 43 7f. */
    @Test
    fun rfc8323Section3_2Figure5ResponseIsDelivered() {
        val messaging = established()
        messaging.onReceived(1.seconds, "01437f".hexToBytes())
        val delivered = messaging.takeEffects().only<ConnectionEffect.DeliverResponse>().message
        assertEquals(CoapCode.VALID, delivered.code)
        assertEquals("7f", delivered.tokenHex)
    }

    // ---------------------------------------------------------------- CSM

    /** RFC 8323 §3.3, §5.3.1, §5.3.2: the CSM goes first, with Max-Message-Size and Block-Wise-Transfer. */
    @Test
    fun rfc8323Section3_3TheOwnCsmIsTheFirstMessage() {
        val messaging = messaging()
        messaging.start(Duration.ZERO)
        val sent = messaging.takeEffects().written().single()
        assertEquals(CoapCode.CSM, sent.code)
        assertEquals(ReliableTransportConfig.DEFAULT_MAX_MESSAGE_SIZE, sent.options.first(2)?.uintValue())
        assertTrue(4 in sent.options)
        assertEquals("7.01 CSM [Max-Message-Size: 8704, Block-Wise-Transfer]", sent.toString())
        assertFailsWith<IllegalStateException> { messaging.start(Duration.ZERO) }
        assertFalse(messaging.isEstablished)
    }

    /** RFC 8323 §5.3: absent options mean base values, so the base Max-Message-Size is not sent. */
    @Test
    fun rfc8323Section5_3BaseValuesAreNotSent() {
        val messaging = messaging(ReliableTransportConfig(maxMessageSize = 1152, blockWiseTransfer = false))
        messaging.start(Duration.ZERO)
        val sent = messaging.takeEffects().written().single()
        assertTrue(sent.options.isEmpty(), "an empty CSM: $sent")
        assertFalse(ReliableTransportConfig(maxMessageSize = 1152).announcesBert)
        assertTrue(ReliableTransportConfig().announcesBert)
    }

    /** RFC 8323 §5.3: CSM options are cumulative; Max-Message-Size is replaced, Block-Wise-Transfer stays. */
    @Test
    fun rfc8323Section5_3PeerSettingsAreCumulative() {
        val messaging = messaging()
        messaging.start(Duration.ZERO)
        messaging.takeEffects()
        assertEquals(1152, messaging.peerSettings.maxMessageSize, "the base value until the CSM")

        messaging.receive(1.seconds, csm(maxMessageSize = 4096, blockWiseTransfer = true))
        val effects = messaging.takeEffects()
        val established = effects.only<ConnectionEffect.Established>()
        assertEquals(4096, established.settings.maxMessageSize)
        assertTrue(established.settings.supportsBert, "§5.3.2: BWT with Max-Message-Size above 1152 is BERT")
        val event = assertIs<EndpointEvent.CsmReceived>(effects.events().single())
        assertEquals(4096, event.maxMessageSize)
        assertTrue(messaging.isEstablished)

        messaging.receive(2.seconds, csm(maxMessageSize = 1000))
        assertTrue(messaging.takeEffects().none { it is ConnectionEffect.Established }, "established only once")
        assertEquals(1000, messaging.peerSettings.maxMessageSize)
        assertTrue(messaging.peerSettings.blockWiseTransfer, "not repeated, still in force")
        assertFalse(messaging.peerSettings.supportsBert, "§5.3.2: 1152 or less withdraws BERT")

        messaging.receive(3.seconds, csm())
        assertEquals(1000, messaging.peerSettings.maxMessageSize, "a CSM without options is a no-op")
    }

    /** RFC 8323 §3.3: a missing CSM is a connection error: Abort. */
    @Test
    fun rfc8323Section3_3AFirstMessageOtherThanACsmAborts() {
        val messaging = established(peerCsm = null)
        messaging.receive(1.seconds, request())
        val effects = messaging.takeEffects()
        assertTrue(effects.none { it is ConnectionEffect.DeliverRequest })
        val abort = effects.written().single()
        assertEquals(CoapCode.ABORT, abort.code)
        assertEquals("Expected a CSM as the first message, got 0.01 GET", abort.payloadAsText())
        assertIs<ConnectionEffect.Closed>(effects.first { it is ConnectionEffect.Closed })
        assertIs<EndpointEvent.AbortSent>(effects.events().first())
        assertIs<EndpointEvent.ConnectionClosed>(effects.events().last())
        assertTrue(messaging.isClosed)

        messaging.receive(2.seconds, csm())
        assertTrue(messaging.takeEffects().isEmpty(), "nothing is processed after an Abort")
    }

    /** RFC 8323 §3.3: a CSM that never comes is missing too. */
    @Test
    fun rfc8323Section3_3NoCsmWithinTheTimeoutAborts() {
        val messaging = established(ReliableTransportConfig(csmTimeout = 5.seconds), peerCsm = null)
        assertEquals(5.seconds, messaging.nextDeadline())
        messaging.onTimer(4.seconds)
        assertTrue(messaging.takeEffects().isEmpty())
        messaging.onTimer(5.seconds)
        val abort = messaging.takeEffects().written().single()
        assertEquals(CoapCode.ABORT, abort.code)
        assertTrue(messaging.isClosed)
        assertNull(messaging.nextDeadline())
    }

    /** RFC 8323 §5.2, §5.6: an unknown critical CSM option aborts with Bad-CSM-Option; elective ones are ignored. */
    @Test
    fun rfc8323Section5_6UnknownCriticalCsmOptionAbortsWithBadCsmOption() {
        val tolerant = established(peerCsm = csm(maxMessageSize = 2000, extra = arrayOf(CoapOption.uint(6, 40))))
        assertTrue(tolerant.isEstablished, "option 6 (Extended-Token-Length, RFC 8974) is elective")

        val messaging = established(peerCsm = null)
        messaging.receive(1.seconds, csm(maxMessageSize = 2000, extra = arrayOf(CoapOption.empty(3))))
        val abort = messaging.takeEffects().written().single()
        assertEquals(CoapCode.ABORT, abort.code)
        assertEquals(3L, abort.options.first(SignalingOptions.BAD_CSM_OPTION.number)?.uintValue())
        assertEquals("7.05 Abort [Bad-CSM-Option: 3] ${abort.payloadSize} bytes", abort.toString())
        assertTrue(messaging.isClosed)
    }

    /** RFC 8323 §5.6: a parameter problem with an elective CSM option's value is a Bad-CSM-Option too. */
    @Test
    fun rfc8323Section5_6InvalidMaxMessageSizeAborts() {
        val messaging = established(peerCsm = null)
        messaging.receive(1.seconds, csm(extra = arrayOf(CoapOption(2, ByteArray(5) { 1 }))))
        val effects = messaging.takeEffects()
        val abort = effects.written().single()
        assertEquals(2L, abort.options.first(2)?.uintValue())
        assertEquals(2, assertIs<EndpointEvent.AbortSent>(effects.events().first()).badCsmOption)
    }

    // ---------------------------------------------------------------- requests and responses

    @Test
    fun requestsAndResponsesAreDeliveredAndSent() {
        val messaging = established(isInitiator = false)
        messaging.receive(1.seconds, request(token = "01"))
        val delivered = messaging.takeEffects().only<ConnectionEffect.DeliverRequest>()
        assertEquals("01", delivered.message.tokenHex)

        messaging.send(2.seconds, response(token = "01"))
        messaging.answered(delivered.sequence)
        assertEquals(listOf(CoapCode.CONTENT), messaging.takeEffects().written().map { it.code })

        messaging.send(3.seconds, request(token = "02"))
        assertEquals(Triple(0, 0, 1), messaging.stateSizes, "the request awaits its response")
        messaging.receive(4.seconds, response(token = "02"))
        assertEquals("02", messaging.takeEffects().only<ConnectionEffect.DeliverResponse>().message.tokenHex)
        assertEquals(Triple(0, 0, 0), messaging.stateSizes)
        assertFailsWith<IllegalArgumentException> { messaging.send(5.seconds, CoapMessage.build { code = CoapCode.PING }) }
    }

    /** RFC 8323 §3.3: an initiator may send requests before the acceptor's CSM arrives. */
    @Test
    fun rfc8323Section3_3RequestsMayGoBeforeThePeersCsm() {
        val messaging = established(peerCsm = null)
        messaging.send(1.seconds, request())
        assertEquals(CoapCode.GET, messaging.takeEffects().written().single().code)
    }

    /** RFC 8323 §3.4: Empty messages are always allowed and ignored, even before the CSM. */
    @Test
    fun rfc8323Section3_4EmptyMessagesAreIgnored() {
        val messaging = established(peerCsm = null)
        messaging.onReceived(1.seconds, "0000".hexToBytes())
        assertTrue(messaging.takeEffects().isEmpty())
        assertFalse(messaging.isClosed)
    }

    /** Reliable transports have no Reset: unassigned signaling codes and reserved classes are dropped. */
    @Test
    fun unassignedSignalingCodesAndReservedClassesAreDropped() {
        val messaging = established()
        messaging.receive(1.seconds, signal(CoapCode.of(7, 9), "aa"))
        messaging.receive(1.seconds, CoapMessage.build { code = CoapCode.of(6, 1) })
        val events = messaging.takeEffects().events()
        assertEquals(2, events.size)
        assertTrue(events.all { it is EndpointEvent.MessageRejected && it.messageId == 0 })
        assertEquals(
            listOf(EndpointEvent.MessageRejected.Reason.UNKNOWN_SIGNAL, EndpointEvent.MessageRejected.Reason.RESERVED_CODE),
            events.map { (it as EndpointEvent.MessageRejected).reason },
        )
        assertEquals(listOf("7.09", "6.01"), events.map { (it as EndpointEvent.MessageRejected).detail?.substringBefore(' ') })
        assertFalse(messaging.isClosed)
    }

    /** RFC 8323 §5.6: a message format error is answered with an Abort and otherwise ignored. */
    @Test
    fun rfc8323Section5_6MalformedMessagesAbort() {
        val messaging = established()
        // TKL 9 is reserved.
        messaging.onReceived(1.seconds, "0901".hexToBytes())
        val effects = messaging.takeEffects()
        val malformed = assertIs<EndpointEvent.MalformedMessage>(effects.events().first())
        assertEquals(EndpointEvent.MalformedMessage.Reason.FORMAT_ERROR, malformed.reason)
        assertEquals("Reserved token length 9", malformed.detail)
        assertEquals(CoapCode.ABORT, effects.written().single().code)
        assertTrue(messaging.isClosed)
    }

    /** RFC 8323 §3.2: messages split across reads, and several in one read, are reassembled. */
    @Test
    fun rfc8323Section3_2TheByteStreamIsReassembled() {
        val messaging = established(isInitiator = false)
        val bytes = codec.encode(request(token = "01", payloadSize = 600)) + codec.encode(request(token = "02"))
        for (chunk in bytes.toList().chunked(7)) messaging.onReceived(1.seconds, chunk.toByteArray())
        val delivered = messaging.takeEffects().filterIsInstance<ConnectionEffect.DeliverRequest>()
        assertEquals(listOf("01", "02"), delivered.map { it.message.tokenHex })
        assertEquals(600, delivered[0].message.payloadSize)
        assertEquals(listOf(0L, 1L), delivered.map { it.sequence })
    }

    /** RFC 8323 §4.2: one message per WebSocket frame, Len 0. */
    @Test
    fun rfc8323Section4_2WebSocketFramesCarryOneMessage() {
        val messaging = ReliableMessaging(peer, Framing.MESSAGE_STREAM, false, ReliableTransportConfig())
        messaging.start(Duration.ZERO)
        val csm = messaging.takeEffects().filterIsInstance<ConnectionEffect.Write>().single().bytes
        assertEquals(0, (csm[0].toInt() and 0xF0), "Len MUST be 0")
        messaging.onReceived(1.seconds, ReliableMessageCodec.WebSocket.encode(csm(blockWiseTransfer = true)))
        messaging.onReceived(1.seconds, ReliableMessageCodec.WebSocket.encode(request(payloadSize = 2000)))
        assertEquals(2000, messaging.takeEffects().only<ConnectionEffect.DeliverRequest>().message.payloadSize)

        messaging.onReceived(2.seconds, ByteArray(9000))
        assertEquals(CoapCode.ABORT, ReliableMessageCodec.WebSocket.decode(messaging.takeEffects().only<ConnectionEffect.Write>().bytes).code)
    }

    // ---------------------------------------------------------------- Max-Message-Size

    /** RFC 8323 §5.3.1: a message above the announced Max-Message-Size aborts the connection. */
    @Test
    fun rfc8323Section5_3_1OversizeMessagesAbort() {
        val messaging = established(ReliableTransportConfig(maxMessageSize = 1152), isInitiator = false)
        messaging.receive(1.seconds, request(payloadSize = 1100))
        assertIs<ConnectionEffect.DeliverRequest>(messaging.takeEffects().single())
        // Only the header has arrived: the limit applies before the message is buffered.
        messaging.onReceived(2.seconds, codec.encode(request(payloadSize = 5000)).copyOfRange(0, 8))
        val abort = messaging.takeEffects().written().single()
        assertEquals(CoapCode.ABORT, abort.code)
        assertTrue("exceeds the limit of 1152" in abort.payloadAsText(), abort.payloadAsText())
    }

    /** RFC 8323 §5.3.1: nothing larger than the peer's Max-Message-Size is sent. */
    @Test
    fun rfc8323Section5_3_1MessagesAboveThePeersLimitAreRefused() {
        val messaging = established(peerCsm = csm(maxMessageSize = 100))
        messaging.send(1.seconds, request(payloadSize = 80))
        val error = assertFailsWith<CoapException> { messaging.send(1.seconds, request(payloadSize = 100)) }
        assertEquals(CoapException.Reason.MESSAGE_TOO_LARGE, error.reason)

        val base = established(peerCsm = csm())
        assertFailsWith<CoapException> { base.send(1.seconds, request(payloadSize = 1152)) }
    }

    // ---------------------------------------------------------------- Ping and Pong

    /** RFC 8323 §5.4: Pong with the identical token; elective options are ignored, critical ones abort. */
    @Test
    fun rfc8323Section5_4PingIsAnsweredWithTheSameToken() {
        val messaging = established()
        messaging.receive(1.seconds, signal(CoapCode.PING, "0102", CoapOption.empty(8)))
        val pong = messaging.takeEffects().written().single()
        assertEquals(CoapCode.PONG, pong.code)
        assertEquals("0102", pong.tokenHex)
        assertFalse(SignalingOptions.CUSTODY.number in pong.options)

        messaging.receive(2.seconds, signal(CoapCode.PING, "03", CoapOption.empty(9)))
        assertEquals(CoapCode.ABORT, messaging.takeEffects().written().single().code, "§5.2: unknown critical option")
    }

    /** RFC 8323 §5.4.1: with Custody, the Pong waits until the requests before the Ping are processed. */
    @Test
    fun rfc8323Section5_4_1CustodyWaitsForEarlierRequests() {
        val messaging = established(isInitiator = false)
        messaging.receive(1.seconds, request(token = "01"))
        messaging.receive(1.seconds, request(token = "02"))
        val (first, second) = messaging.takeEffects().filterIsInstance<ConnectionEffect.DeliverRequest>()
        messaging.receive(2.seconds, signal(CoapCode.PING, "aa", CoapOption.empty(2)))
        messaging.receive(2.seconds, request(token = "03"))
        val later = messaging.takeEffects().only<ConnectionEffect.DeliverRequest>()
        assertEquals(Triple(3, 1, 0), messaging.stateSizes)

        messaging.answered(second.sequence)
        assertTrue(messaging.takeEffects().written().isEmpty(), "one request before the Ping is still open")
        messaging.answered(first.sequence)
        val pong = messaging.takeEffects().written().single()
        assertEquals(CoapCode.PONG, pong.code)
        assertEquals("aa", pong.tokenHex)
        assertTrue(SignalingOptions.CUSTODY.number in pong.options)
        messaging.answered(later.sequence)
        messaging.answered(later.sequence)
        assertTrue(messaging.takeEffects().isEmpty())

        messaging.receive(3.seconds, signal(CoapCode.PING, "bb", CoapOption.empty(2)))
        assertEquals("7.03 Pong token=bb [Custody]", messaging.takeEffects().written().single().toString(), "nothing open: at once")
    }

    @Test
    fun ownPingsAreAnsweredByTheirPong() {
        val messaging = established()
        val first = messaging.ping(1.seconds)
        val second = messaging.ping(1.seconds, custody = true)
        val pings = messaging.takeEffects().written()
        assertEquals(listOf("01", "02"), pings.map { it.tokenHex })
        assertTrue(SignalingOptions.CUSTODY.number in pings[1].options)

        messaging.receive(2.seconds, signal(CoapCode.PONG, "02", CoapOption.empty(2)))
        val answered = messaging.takeEffects().only<ConnectionEffect.PingAnswered>()
        assertSame(second, answered.ping)
        assertTrue(answered.custody)

        // libcoap 4.3.5 answers without the token; Pongs come in order, so it is the oldest Ping's.
        messaging.receive(3.seconds, signal(CoapCode.PONG))
        assertSame(first, messaging.takeEffects().only<ConnectionEffect.PingAnswered>().ping)

        messaging.receive(4.seconds, signal(CoapCode.PONG, "77"))
        assertTrue(messaging.takeEffects().isEmpty(), "an unsolicited Pong is ignored")

        val cancelled = messaging.ping(5.seconds)
        messaging.cancelPing(cancelled)
        val open = messaging.ping(5.seconds)
        messaging.takeEffects()
        messaging.onTransportClosed("reset")
        assertSame(open, messaging.takeEffects().only<ConnectionEffect.PingFailed>().ping)
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, assertFailsWith<CoapException> { messaging.ping(6.seconds) }.reason)
    }

    // ---------------------------------------------------------------- Release

    /** RFC 8323 §5.5: Alternative-Address and Hold-Off are reported; no new requests are sent. */
    @Test
    fun rfc8323Section5_5ReleaseIsReportedAndStopsNewRequests() {
        val messaging = established()
        val release = signal(
            CoapCode.RELEASE, "",
            CoapOption.string(2, "[2001:db8::1]:5683"), CoapOption.string(2, "192.0.2.9"), CoapOption.uint(4, 60),
            payload = "maintenance",
        )
        assertEquals("7.04 Release [Alternative-Address: [2001:db8::1]:5683, Alternative-Address: 192.0.2.9, Hold-Off: 60] 11 bytes", release.toString())
        messaging.receive(1.seconds, release)
        val effects = messaging.takeEffects()
        val released = effects.only<ConnectionEffect.PeerReleased>()
        assertEquals(listOf("[2001:db8::1]:5683", "192.0.2.9"), released.alternativeAddresses)
        assertEquals(60.seconds, released.holdOff)
        val event = assertIs<EndpointEvent.ReleaseReceived>(effects.events().first())
        assertEquals("maintenance", event.diagnostic)
        assertIs<EndpointEvent.ConnectionClosed>(effects.events().last())
        assertTrue(effects.any { it is ConnectionEffect.Closed }, "nothing pending: close at once")
    }

    /**
     * RFC 8323 §5.5: the receiver of a Release SHOULD answer the requests received before it, and MAY
     * wait for the responses to its own requests, before closing.
     */
    @Test
    fun rfc8323Section5_5ReleaseDrainsBeforeClosing() {
        val messaging = established(isInitiator = false)
        messaging.receive(1.seconds, request(token = "01"))
        val pending = messaging.takeEffects().only<ConnectionEffect.DeliverRequest>()
        messaging.send(1.seconds, request(token = "b1"))
        messaging.takeEffects()

        messaging.receive(2.seconds, signal(CoapCode.RELEASE))
        messaging.takeEffects()
        assertTrue(messaging.isReleasing)
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, assertFailsWith<CoapException> { messaging.send(2.seconds, request(token = "b2")) }.reason)
        messaging.receive(2.seconds, request(token = "02"))
        val after = messaging.takeEffects().only<ConnectionEffect.DeliverRequest>()

        messaging.send(3.seconds, response(token = "01"))
        messaging.answered(pending.sequence)
        assertTrue(messaging.takeEffects().none { it is ConnectionEffect.Closed }, "own request b1 still waits")
        messaging.receive(4.seconds, response(token = "b1"))
        val effects = messaging.takeEffects()
        assertTrue(effects.any { it is ConnectionEffect.Closed }, "request 02 came after the Release: not waited for")
        assertFailsWith<CoapException> { messaging.send(5.seconds, response(token = "02")) }
        messaging.answered(after.sequence)
    }

    @Test
    fun aReleaseThatDoesNotDrainClosesAfterTheReleaseTimeout() {
        val messaging = established(ReliableTransportConfig(releaseTimeout = 3.seconds), isInitiator = false)
        messaging.receive(1.seconds, request(token = "01"))
        messaging.receive(1.seconds, signal(CoapCode.RELEASE))
        messaging.takeEffects()
        assertEquals(4.seconds, messaging.nextDeadline())
        messaging.onTimer(4.seconds)
        assertTrue("1 requests unanswered" in messaging.takeEffects().only<ConnectionEffect.Closed>().reason)
    }

    /** RFC 8323 §3, §5.5: a connection this side opened is released when idle; the peer then closes. */
    @Test
    fun rfc8323Section5_5IdleConnectionsAreReleased() {
        val messaging = established(ReliableTransportConfig(idleTimeout = 10.seconds, releaseTimeout = 2.seconds))
        messaging.exchangeOpened()
        messaging.send(1.seconds, request(token = "01"))
        messaging.takeEffects()
        assertNull(messaging.nextDeadline(), "an open exchange keeps it busy")
        messaging.receive(3.seconds, response(token = "01"))
        messaging.exchangeClosed("01")
        assertEquals(13.seconds, messaging.nextDeadline())

        messaging.onTimer(13.seconds)
        val release = messaging.takeEffects().written().single()
        assertEquals(CoapCode.RELEASE, release.code)
        assertEquals("idle", release.payloadAsText())
        assertTrue(messaging.isReleasing)
        assertFailsWith<CoapException> { messaging.send(13.seconds, request(token = "02")) }
        messaging.release(13.seconds, "again")
        assertTrue(messaging.takeEffects().isEmpty(), "released once")

        assertEquals(15.seconds, messaging.nextDeadline())
        messaging.onTimer(15.seconds)
        assertEquals("released; the peer did not close the connection", messaging.takeEffects().only<ConnectionEffect.Closed>().reason)
    }

    @Test
    fun acceptedConnectionsAreNeverReleasedForIdleness() {
        val messaging = established(ReliableTransportConfig(idleTimeout = 10.seconds), isInitiator = false)
        assertNull(messaging.nextDeadline())
        val unlimited = established(ReliableTransportConfig(idleTimeout = null))
        assertNull(unlimited.nextDeadline())
    }

    @Test
    fun shutdownReleasesAndClosesAtOnce() {
        val messaging = established()
        messaging.shutdown(1.seconds, "endpoint closed")
        val effects = messaging.takeEffects()
        assertEquals(CoapCode.RELEASE, effects.written().single().code)
        assertEquals("shut down: endpoint closed", effects.only<ConnectionEffect.Closed>().reason)
        messaging.abort("too late")
        messaging.onTimer(100.seconds)
        assertTrue(messaging.takeEffects().isEmpty())
    }

    // ---------------------------------------------------------------- Abort

    /** RFC 8323 §5.6: an Abort closes the connection; what follows is not processed. */
    @Test
    fun rfc8323Section5_6AbortClosesTheConnection() {
        val messaging = established()
        messaging.receive(1.seconds, signal(CoapCode.ABORT, "", CoapOption.uint(2, 4), payload = "bad block option"))
        val effects = messaging.takeEffects()
        val event = assertIs<EndpointEvent.AbortReceived>(effects.events().first())
        assertEquals(4, event.badCsmOption)
        assertEquals("bad block option", event.diagnostic)
        assertEquals("aborted by the peer: bad block option", effects.only<ConnectionEffect.Closed>().reason)
        assertTrue(effects.written().isEmpty(), "no answer to an Abort")
        messaging.receive(2.seconds, request())
        assertTrue(messaging.takeEffects().isEmpty())
        assertEquals(CoapException.Reason.CONNECTION_CLOSED, assertFailsWith<CoapException> { messaging.send(2.seconds, response()) }.reason)
    }

    // ---------------------------------------------------------------- keep-alive

    /** RFC 8323 §3.4, §4.4: a Ping tests a quiet connection; no answer at all aborts it. */
    @Test
    fun keepAlivePingsDetectADeadConnection() {
        val messaging = established(ReliableTransportConfig(keepAliveInterval = 20.seconds, pingTimeout = 5.seconds, idleTimeout = null))
        assertEquals(20.seconds, messaging.nextDeadline())
        messaging.onTimer(20.seconds)
        val ping = messaging.takeEffects().written().single()
        assertEquals(CoapCode.PING, ping.code)
        assertEquals(25.seconds, messaging.nextDeadline())

        messaging.receive(22.seconds, signal(CoapCode.PONG, ping.tokenHex))
        messaging.takeEffects()
        assertEquals(42.seconds, messaging.nextDeadline(), "any message proves the connection alive")

        messaging.onTimer(42.seconds)
        messaging.takeEffects()
        messaging.onTimer(47.seconds)
        val effects = messaging.takeEffects()
        assertEquals(CoapCode.ABORT, effects.written().single().code)
        assertTrue(messaging.isClosed)
    }

    // ---------------------------------------------------------------- interceptors

    @Test
    fun interceptorsSeeEveryMessageAndMayDropIt() {
        val seen = mutableListOf<String>()
        val interceptor = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage? {
                seen += "out ${message.code}"
                return message.takeUnless { it.code == CoapCode.PONG }
            }

            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage? {
                seen += "in ${message.code}"
                return message.takeUnless { it.isRequest && it.tokenHex == "dd" }
            }
        }
        val messaging = established(interceptors = listOf(interceptor))
        messaging.receive(1.seconds, signal(CoapCode.PING, "01"))
        messaging.receive(1.seconds, request(token = "dd"))
        assertTrue(messaging.takeEffects().isEmpty())
        val throwing = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = error("broken")
        }
        val dropping = established(interceptors = listOf(throwing))
        dropping.send(1.seconds, request())
        assertTrue(dropping.takeEffects().isEmpty(), "a failing interceptor drops the message")
        assertEquals(listOf("out 7.01 CSM", "in 7.01 CSM", "in 7.02 Ping", "out 7.03 Pong", "in 0.01 GET"), seen)
    }

    @Test
    fun notStartedMessagingRefusesWork() {
        val messaging = messaging()
        assertNull(messaging.nextDeadline())
        messaging.onTimer(1.seconds)
        messaging.release(1.seconds)
        messaging.abort("nothing to abort")
        assertTrue(messaging.takeEffects().isEmpty())
        assertFailsWith<IllegalStateException> { messaging.onReceived(1.seconds, byteArrayOf(0)) }
        assertFailsWith<IllegalStateException> { messaging.send(1.seconds, request()) }
        assertFailsWith<IllegalStateException> { messaging.ping(1.seconds) }
        assertEquals("PeerSettings(maxMessageSize=1152, blockWiseTransfer=false)", messaging.peerSettings.toString())
        assertContentEquals(ByteArray(0), Signal.Release().toMessage().payload)
    }
}
