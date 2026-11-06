package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.common.hexToBytes
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UdpMessagingTest {
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val otherPeer = Endpoint.Udp("192.0.2.2", 5683)
    private val params = TransmissionParameters.Default

    /** Random source whose doubles are fixed, so the initial timeout is exactly predictable. */
    private class FixedRandom(private val double: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = double
    }

    private fun messaging(
        random: Random = FixedRandom(0.0),
        separateResponseDelay: Duration? = 1.seconds,
        parameters: TransmissionParameters = params,
        interceptors: List<MessageInterceptor> = emptyList(),
        maxDatagramSize: Int = 1152,
        maxDeduplicationEntries: Int = Int.MAX_VALUE,
        maxPerPeer: Int = Int.MAX_VALUE,
        maxDeduplicationEntriesPerPeer: Int = Int.MAX_VALUE,
        maxTransmissions: Int = Int.MAX_VALUE,
        maxReservedMessageIds: Int = Int.MAX_VALUE,
        maxReservedMessageIdsPerPeer: Int = 0x10000,
    ) = UdpMessaging(
        parameters, maxDatagramSize, separateResponseDelay, random, interceptors,
        maxDeduplicationEntries = maxDeduplicationEntries, maxPerPeer = maxPerPeer,
        maxDeduplicationEntriesPerPeer = maxDeduplicationEntriesPerPeer, maxTransmissions = maxTransmissions,
        maxReservedMessageIds = maxReservedMessageIds, maxReservedMessageIdsPerPeer = maxReservedMessageIdsPerPeer,
    )

    private fun post(type: CoapMessageType, messageId: Int, token: String = "c3d4") = CoapMessage.request(CoapCode.POST) {
        this.type = type
        this.messageId = messageId
        this.token = token.hexToBytes()
        options { uriPath("test") }
    }

    private fun List<MessagingEffect>.limits(): List<EndpointEvent.LimitReached.Limit> =
        filterIsInstance<MessagingEffect.Event>().mapNotNull { (it.event as? EndpointEvent.LimitReached)?.limit }

    private fun List<MessagingEffect>.delivered(): List<Int> =
        filterIsInstance<MessagingEffect.DeliverRequest>().map { it.message.messageId }

    private fun request(type: CoapMessageType, messageId: Int, token: String = "a1b2") = CoapMessage.request(CoapCode.GET) {
        this.type = type
        this.messageId = messageId
        this.token = token.hexToBytes()
        options { uriPath("test") }
    }

    private fun response(type: CoapMessageType, messageId: Int, token: String = "a1b2", code: CoapCode = CoapCode.CONTENT) =
        CoapMessage.build {
            this.type = type
            this.code = code
            this.messageId = messageId
            this.token = token.hexToBytes()
            payload("ok")
        }

    private fun UdpMessaging.receive(now: Duration, message: CoapMessage, from: Endpoint = peer) =
        onDatagram(now, from, UdpMessageCodec.encode(message))

    private fun List<MessagingEffect>.transmitted(): List<CoapMessage> =
        filterIsInstance<MessagingEffect.Transmit>().map { UdpMessageCodec.decode(it.bytes) }

    private fun List<MessagingEffect>.outcomes(): List<TransmissionOutcome> =
        filterIsInstance<MessagingEffect.Completed>().map { it.outcome }

    /** Runs the timers at every deadline up to [until] and returns the times something was sent. */
    private fun UdpMessaging.runTimers(until: Duration, sends: MutableList<Duration> = mutableListOf()): List<Duration> {
        while (true) {
            val next = nextDeadline() ?: break
            if (next > until) break
            onTimer(next)
            repeat(takeEffects().transmitted().size) { sends += next }
        }
        return sends
    }

    private fun UdpMessaging.sendConfirmable(now: Duration, to: Endpoint = peer, token: String = "a1b2"): Transmission {
        val messageId = allocateMessageId(to, now)
        return send(now, to, request(CoapMessageType.CON, messageId, token), owner = null)
    }

    @Test
    fun rfc7252Section4_2RetransmitsWithExponentialBackOffAndGivesUp() {
        val messaging = messaging(FixedRandom(0.0))
        messaging.sendConfirmable(Duration.ZERO)
        assertEquals(1, messaging.takeEffects().transmitted().size)

        val sends = messaging.runTimers(until = 200.seconds)
        // Initial timeout ACK_TIMEOUT, doubled after each of the MAX_RETRANSMIT = 4 retransmissions.
        assertEquals(listOf(2.seconds, 6.seconds, 14.seconds, 30.seconds), sends)
    }

    @Test
    fun rfc7252Section4_8_2GivingUpTakesAtMostMaxTransmitWait() {
        val messaging = messaging(FixedRandom(1.0))
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        var timedOutAt: Duration? = null
        while (timedOutAt == null) {
            val next = assertNotNullDeadline(messaging)
            messaging.onTimer(next)
            if (messaging.takeEffects().outcomes().any { it == TransmissionOutcome.TimedOut }) timedOutAt = next
        }
        assertEquals(params.maxTransmitWait, timedOutAt)
        assertEquals(93.seconds, timedOutAt)
        assertEquals(4, transmission.retransmissions)
        assertEquals(Transmission.State.DONE, transmission.state)
    }

    private fun assertNotNullDeadline(messaging: UdpMessaging): Duration = messaging.nextDeadline() ?: error("No deadline")

    @Test
    fun initialTimeoutIsRandomWithinTheAckRandomFactor() {
        val messaging = messaging(Random(7))
        repeat(20) {
            val transmission = messaging.sendConfirmable(Duration.ZERO, Endpoint.Udp("192.0.2.9", 1000 + it))
            assertTrue(transmission.timeout in 2.seconds..3.seconds, "timeout ${transmission.timeout}")
        }
    }

    @Test
    fun anAckEndsRetransmission() {
        val messaging = messaging()
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.ACK, transmission.message.messageId))
        val outcome = messaging.takeEffects().outcomes().single()
        assertIs<TransmissionOutcome.Acknowledged>(outcome)
        assertNull(outcome.piggybackedResponse)
        assertEquals(emptyList(), messaging.runTimers(until = 100.seconds))
    }

    @Test
    fun rfc7252Section5_3_2PiggybackedResponsesMatchMessageIdAndToken() {
        val messaging = messaging()
        val transmission = messaging.sendConfirmable(Duration.ZERO, token = "a1b2")
        val messageId = transmission.message.messageId
        messaging.takeEffects()

        messaging.receive(1.seconds, response(CoapMessageType.ACK, messageId, token = "ffff"))
        assertEquals(emptyList(), messaging.takeEffects().outcomes(), "a foreign token is not a piggybacked response")

        messaging.receive(1.seconds, response(CoapMessageType.ACK, messageId, token = "a1b2"))
        val outcome = assertIs<TransmissionOutcome.Acknowledged>(messaging.takeEffects().outcomes().single())
        assertEquals("ok", outcome.piggybackedResponse?.payloadAsText())
    }

    @Test
    fun anAckForASeparateResponseIgnoresWhatItCarries() {
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        messaging.send(Duration.ZERO, peer, response(CoapMessageType.CON, messageId), owner = null)
        messaging.takeEffects()
        messaging.receive(1.seconds, response(CoapMessageType.ACK, messageId, code = CoapCode.BAD_REQUEST))
        val outcome = assertIs<TransmissionOutcome.Acknowledged>(messaging.takeEffects().outcomes().single())
        assertNull(outcome.piggybackedResponse)
    }

    @Test
    fun rfc7252Section4_2InvalidAcksAndResetsAreSilentlyIgnored() {
        val messaging = messaging()
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        val messageId = transmission.message.messageId
        messaging.takeEffects()
        // An ACK carrying a request, a non-empty RST, an ACK and an RST for unknown Message IDs.
        messaging.receive(1.seconds, request(CoapMessageType.ACK, messageId))
        messaging.receive(1.seconds, response(CoapMessageType.RST, messageId))
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.ACK, (messageId + 1) and 0xFFFF))
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.RST, (messageId + 1) and 0xFFFF))
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.ACK, messageId), from = otherPeer)
        assertEquals(emptyList(), messaging.takeEffects().filterNot { it is MessagingEffect.Event })
        assertEquals(Transmission.State.IN_FLIGHT, transmission.state)
    }

    @Test
    fun aResetCancelsTheTransmission() {
        val messaging = messaging()
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.RST, transmission.message.messageId))
        val effects = messaging.takeEffects()
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.Reset), effects.outcomes())
        assertTrue(effects.any { (it as? MessagingEffect.Event)?.event is EndpointEvent.ResetReceived })
        // A second RST matches nothing any more.
        messaging.receive(2.seconds, CoapMessage.empty(CoapMessageType.RST, transmission.message.messageId))
        assertEquals(emptyList(), messaging.takeEffects().outcomes())
    }

    @Test
    fun aResetRejectingANonConfirmableMessageIsMatched() {
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        messaging.send(Duration.ZERO, peer, response(CoapMessageType.NON, messageId), owner = null)
        messaging.takeEffects()
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.RST, messageId))
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.Reset), messaging.takeEffects().outcomes())
    }

    @Test
    fun aResetForANonConfirmableMessageNoLongerMatchesAfterNonLifetime() {
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val transmission = messaging.send(Duration.ZERO, peer, response(CoapMessageType.NON, messageId), owner = null)
        messaging.onTimer(params.nonLifetime)
        messaging.takeEffects()
        assertEquals(Transmission.State.DONE, transmission.state)
        messaging.receive(params.nonLifetime + 1.seconds, CoapMessage.empty(CoapMessageType.RST, messageId))
        assertEquals(emptyList(), messaging.takeEffects().outcomes())
    }

    @Test
    fun rfc7252Section4_3CoapPingIsAnsweredWithReset() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, CoapMessage.empty(CoapMessageType.CON, 0x1234))
        val reset = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessageType.RST, reset.type)
        assertEquals(0x1234, reset.messageId)
        // §4.5: a duplicate gets the same Reset.
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.CON, 0x1234))
        assertEquals(listOf(reset), messaging.takeEffects().transmitted())
    }

    @Test
    fun rfc7252Section4_5DuplicateConfirmableRequestsGetTheSameAck() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 77))
        val delivered = messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().single()
        assertEquals(77, delivered.message.messageId)

        // While the request is processed, a duplicate is ignored.
        messaging.receive(100.milliseconds, request(CoapMessageType.CON, 77))
        val duringProcessing = messaging.takeEffects()
        assertEquals(emptyList(), duringProcessing.transmitted())
        assertTrue(duringProcessing.any { (it as? MessagingEffect.Event)?.event is EndpointEvent.DuplicateReceived })

        assertNull(messaging.respond(200.milliseconds, peer, 77, CoapMessageType.CON, response(CoapMessageType.CON, 0)))
        val ack = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessageType.ACK, ack.type)
        assertEquals(77, ack.messageId)
        assertEquals("ok", ack.payloadAsText())

        messaging.receive(3.seconds, request(CoapMessageType.CON, 77))
        assertEquals(listOf(ack), messaging.takeEffects().transmitted())
        assertTrue(messaging.takeEffects().none { it is MessagingEffect.DeliverRequest }, "processed only once")
    }

    @Test
    fun deduplicationIsPerPeer() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 77))
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 77), from = otherPeer)
        assertEquals(2, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
    }

    @Test
    fun rfc7252Section4_5DuplicatesAreForgottenAfterExchangeLifetime() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 77))
        messaging.respond(Duration.ZERO, peer, 77, CoapMessageType.CON, response(CoapMessageType.CON, 0))
        messaging.takeEffects()
        messaging.onTimer(params.exchangeLifetime - 1.milliseconds)
        assertEquals(1, messaging.stateSizes.first)
        messaging.onTimer(params.exchangeLifetime)
        assertEquals(0, messaging.stateSizes.first)
        messaging.receive(params.exchangeLifetime, request(CoapMessageType.CON, 77))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
    }

    @Test
    fun rfc7252Section5_2_2SlowRequestsGetAnEmptyAckAndASeparateResponse() {
        val messaging = messaging(separateResponseDelay = 1.seconds)
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 5))
        messaging.takeEffects()
        assertEquals(1.seconds, messaging.nextDeadline())
        messaging.onTimer(1.seconds)
        val emptyAck = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessage.empty(CoapMessageType.ACK, 5), emptyAck)

        // A retransmitted request gets another Empty ACK, never the response (§5.2.2).
        messaging.receive(2.seconds, request(CoapMessageType.CON, 5))
        assertEquals(listOf(emptyAck), messaging.takeEffects().transmitted())

        val separate = messaging.respond(3.seconds, peer, 5, CoapMessageType.CON, response(CoapMessageType.ACK, 0))
        val sent = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessageType.CON, sent.type)
        assertEquals(separate?.message?.messageId, sent.messageId)
        assertTrue(sent.messageId != 5)
        assertEquals("a1b2", sent.tokenHex)

        messaging.receive(4.seconds, request(CoapMessageType.CON, 5))
        assertEquals(listOf(emptyAck), messaging.takeEffects().transmitted())
    }

    @Test
    fun separateResponsesCanBeNonConfirmable() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 5))
        messaging.acknowledge(peer, 5)
        messaging.takeEffects()
        messaging.respond(
            Duration.ZERO, peer, 5, CoapMessageType.CON, response(CoapMessageType.CON, 0),
            separateType = CoapMessageType.NON,
        )
        assertEquals(CoapMessageType.NON, messaging.takeEffects().transmitted().single().type)
    }

    @Test
    fun withoutASeparateResponseDelayRequestsWaitForTheUpperLayer() {
        val messaging = messaging(separateResponseDelay = null)
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 5))
        messaging.takeEffects()
        messaging.onTimer(10.seconds)
        assertEquals(emptyList(), messaging.takeEffects().transmitted())
    }

    @Test
    fun rfc7252Section5_2_3NonConfirmableRequestsGetNonConfirmableResponses() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, request(CoapMessageType.NON, 9))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
        messaging.receive(1.seconds, request(CoapMessageType.NON, 9))
        assertEquals(emptyList(), messaging.takeEffects().filterNot { it is MessagingEffect.Event }, "duplicate NON ignored")
        messaging.acknowledge(peer, 9)
        assertEquals(emptyList(), messaging.takeEffects())

        messaging.respond(1.seconds, peer, 9, CoapMessageType.NON, response(CoapMessageType.CON, 0))
        val sent = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessageType.NON, sent.type)
        assertTrue(sent.messageId != 9)
    }

    @Test
    fun rfc7252Section5_4_1NoTimerAnswersADeliveredResponse() {
        val messaging = messaging(separateResponseDelay = 1.seconds)
        messaging.receive(Duration.ZERO, response(CoapMessageType.CON, 11))
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 12))
        messaging.takeEffects()
        assertEquals(1.seconds, messaging.nextDeadline(), "only the request's Empty ACK is due")

        messaging.onTimer(1.seconds)
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.ACK, 12)), messaging.takeEffects().transmitted())
        messaging.onTimer(10.seconds)
        assertEquals(emptyList(), messaging.takeEffects().transmitted(), "the response waits for the upper layer")

        // Verified only now, with a critical option the exchange does not recognize (RFC 7252 §5.4.1).
        messaging.reject(peer, 11, "unrecognized critical option")
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.RST, 11)), messaging.takeEffects().transmitted())
    }

    @Test
    fun separateResponsesAreDeliveredForTheUpperLayerToAcceptOrReject() {
        val messaging = messaging()
        messaging.receive(Duration.ZERO, response(CoapMessageType.CON, 11))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverResponse>().size)
        messaging.acknowledge(peer, 11)
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.ACK, 11)), messaging.takeEffects().transmitted())

        messaging.receive(Duration.ZERO, response(CoapMessageType.CON, 12))
        messaging.takeEffects()
        messaging.reject(peer, 12, "unknown token")
        val reset = messaging.takeEffects().transmitted().single()
        assertEquals(CoapMessage.empty(CoapMessageType.RST, 12), reset)
        messaging.reject(peer, 12, "again")
        assertEquals(emptyList(), messaging.takeEffects().transmitted(), "answered only once")
        messaging.receive(1.seconds, response(CoapMessageType.CON, 12))
        assertEquals(listOf(reset), messaging.takeEffects().transmitted(), "duplicates get the same RST")

        messaging.receive(Duration.ZERO, response(CoapMessageType.NON, 13))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverResponse>().size)
        messaging.reject(peer, 13, "unknown token")
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.RST, 13)), messaging.takeEffects().transmitted())
        messaging.reject(otherPeer, 13, "never received")
        messaging.acknowledge(otherPeer, 13)
        assertEquals(emptyList(), messaging.takeEffects())
    }

    /**
     * RFC 7252 §4.2, §4.5: a Confirmable response the upper layer could not take is left unanswered and forgotten,
     * so its retransmission is delivered as new; an answered one, a Non-confirmable one and an unknown one stay.
     */
    @Test
    fun rfc7252Section4_2AResponseNotTakenIsForgottenSoItsRetransmissionIsDelivered() {
        val messaging = messaging(maxDeduplicationEntries = 2)
        fun delivered() = messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverResponse>().map { it.message.messageId }
        messaging.receive(Duration.ZERO, response(CoapMessageType.CON, 11))
        assertEquals(listOf(11), delivered())

        messaging.forgetUnanswered(peer, 11)
        assertEquals(emptyList(), messaging.takeEffects(), "nothing is sent")
        assertEquals(0, messaging.stateSizes.first, "nor remembered")
        messaging.receive(2.seconds, response(CoapMessageType.CON, 11))
        assertEquals(listOf(11), delivered(), "the retransmission is new")
        messaging.acknowledge(peer, 11)
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.ACK, 11)), messaging.takeEffects().transmitted())
        assertEquals(2.seconds + params.exchangeLifetime, messaging.nextDeadline(), "the forgotten message's expiry went with it")
        messaging.forgetUnanswered(peer, 11)
        messaging.receive(5.seconds, response(CoapMessageType.CON, 11))
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.ACK, 11)), messaging.takeEffects().transmitted(), "an answered one stays")

        messaging.receive(5.seconds, response(CoapMessageType.NON, 12))
        assertEquals(listOf(12), delivered())
        messaging.forgetUnanswered(peer, 12)
        messaging.forgetUnanswered(otherPeer, 11)
        messaging.receive(6.seconds, response(CoapMessageType.NON, 12))
        assertEquals(emptyList(), delivered(), "a Non-confirmable one stays, and counts as lost")
        assertEquals(2, messaging.stateSizes.first)
        messaging.onTimer(params.exchangeLifetime + 5.seconds)
        assertEquals(0, messaging.stateSizes.first)
        assertNull(messaging.nextDeadline())
    }

    @Test
    fun rfc7252Section4_2ReservedCodesAreRejected() {
        val messaging = messaging()
        val signaling = CoapMessage.build {
            type = CoapMessageType.CON
            code = CoapCode.PING
            messageId = 21
        }
        messaging.receive(Duration.ZERO, signaling)
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.RST, 21)), messaging.takeEffects().transmitted())

        messaging.receive(Duration.ZERO, signaling.toBuilder().apply { type = CoapMessageType.NON; messageId = 22 }.build())
        messaging.receive(Duration.ZERO, CoapMessage.empty(CoapMessageType.NON, 23))
        val effects = messaging.takeEffects()
        assertEquals(emptyList(), effects.transmitted())
        assertEquals(2, effects.count { (it as? MessagingEffect.Event)?.event is EndpointEvent.MessageRejected })
    }

    @Test
    fun rfc7252Section4_2FormatErrorsResetConfirmableMessagesOnly() {
        val messaging = messaging()
        // CON, MID 0x0102, token length 9 (reserved).
        messaging.onDatagram(Duration.ZERO, peer, "49010102".hexToBytes())
        assertEquals(listOf(CoapMessage.empty(CoapMessageType.RST, 0x0102)), messaging.takeEffects().transmitted())
        // The same header as NON is dropped silently, and so is an unknown version and a short datagram.
        messaging.onDatagram(Duration.ZERO, peer, "59010102".hexToBytes())
        messaging.onDatagram(Duration.ZERO, peer, "80010102".hexToBytes())
        messaging.onDatagram(Duration.ZERO, peer, "40".hexToBytes())
        val effects = messaging.takeEffects()
        assertEquals(emptyList(), effects.transmitted())
        assertEquals(3, effects.count { (it as? MessagingEffect.Event)?.event is EndpointEvent.MalformedMessage })
    }

    @Test
    fun rfc7252Section4_7NstartLimitsOutstandingConfirmableMessagesPerPeer() {
        val messaging = messaging()
        val first = messaging.sendConfirmable(Duration.ZERO, token = "01")
        val second = messaging.sendConfirmable(Duration.ZERO, token = "02")
        val elsewhere = messaging.sendConfirmable(Duration.ZERO, to = otherPeer, token = "03")
        assertEquals(listOf("01", "03"), messaging.takeEffects().transmitted().map { it.tokenHex })
        assertEquals(Transmission.State.QUEUED, second.state)
        assertEquals(Transmission.State.IN_FLIGHT, elsewhere.state)

        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.ACK, first.message.messageId))
        val effects = messaging.takeEffects()
        assertEquals(listOf("02"), effects.transmitted().map { it.tokenHex })
        assertEquals(1.seconds, second.firstSentAt)
    }

    @Test
    fun nstartCanBeRaised() {
        val messaging = messaging(parameters = TransmissionParameters(nstart = 2))
        messaging.sendConfirmable(Duration.ZERO, token = "01")
        messaging.sendConfirmable(Duration.ZERO, token = "02")
        messaging.sendConfirmable(Duration.ZERO, token = "03")
        assertEquals(listOf("01", "02"), messaging.takeEffects().transmitted().map { it.tokenHex })
    }

    @Test
    fun rfc7252Section4_7NonConfirmableRequestsHoldTheirSlotUntilReleased() {
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val non = messaging.send(Duration.ZERO, peer, request(CoapMessageType.NON, messageId, "01"), null, slotHold = 20.seconds)
        val queued = messaging.sendConfirmable(Duration.ZERO, token = "02")
        assertEquals(listOf("01"), messaging.takeEffects().transmitted().map { it.tokenHex })

        messaging.onTimer(20.seconds)
        val effects = messaging.takeEffects()
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.SlotReleased), effects.outcomes())
        assertEquals(listOf("02"), effects.transmitted().map { it.tokenHex })
        assertEquals(Transmission.State.IN_FLIGHT, queued.state)
        assertFalse(non.occupiesSlot)
    }

    @Test
    fun settlingFreesTheSlotWithoutAnOutcome() {
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val non = messaging.send(Duration.ZERO, peer, request(CoapMessageType.NON, messageId, "01"), null, slotHold = 20.seconds)
        val confirmable = messaging.sendConfirmable(Duration.ZERO, token = "02")
        val queued = messaging.sendConfirmable(Duration.ZERO, token = "03")
        messaging.takeEffects()

        messaging.settle(1.seconds, queued)
        assertEquals(Transmission.State.DONE, queued.state)
        messaging.settle(1.seconds, non)
        assertEquals(listOf("02"), messaging.takeEffects().transmitted().map { it.tokenHex })
        messaging.settle(2.seconds, confirmable)
        assertEquals(Transmission.State.DONE, confirmable.state)
        assertEquals(emptyList(), messaging.runTimers(until = 100.seconds), "no retransmission after settling")
        assertEquals(emptyList(), messaging.takeEffects().outcomes())
        messaging.settle(3.seconds, confirmable)
    }

    @Test
    fun rfc7641Section4_5_2ANewerNotificationSupersedesTheOutstandingOne() {
        val messaging = messaging(FixedRandom(0.0))
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val notification = messaging.send(Duration.ZERO, peer, response(CoapMessageType.CON, messageId), null)
        messaging.takeEffects()

        assertTrue(messaging.supersede(notification, response(CoapMessageType.CON, 0).toBuilder().payload("newer").build()))
        messaging.onTimer(2.seconds)
        val replaced = messaging.takeEffects().transmitted().single()
        assertEquals("newer", replaced.payloadAsText())
        assertTrue(replaced.messageId != messageId, "a new Message ID")
        assertEquals(1, notification.retransmissions, "the counter is retained and incremented")
        assertEquals(4.seconds, notification.timeout, "the timeout is doubled")

        // A late ACK for the superseded message does not end the newer one.
        messaging.receive(3.seconds, CoapMessage.empty(CoapMessageType.ACK, messageId))
        assertEquals(emptyList(), messaging.takeEffects().outcomes())
        // An RST for the superseded message still rejects the notification.
        messaging.receive(3.seconds, CoapMessage.empty(CoapMessageType.RST, messageId))
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.Reset), messaging.takeEffects().outcomes())
        assertFalse(messaging.supersede(notification, response(CoapMessageType.CON, 0)))
    }

    @Test
    fun aQueuedNotificationIsReplacedInPlace() {
        val messaging = messaging()
        messaging.sendConfirmable(Duration.ZERO, token = "01")
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val queued = messaging.send(Duration.ZERO, peer, response(CoapMessageType.CON, messageId, token = "02"), null)
        assertTrue(messaging.supersede(queued, response(CoapMessageType.CON, 0, token = "02").toBuilder().payload("newer").build()))
        assertEquals("newer", queued.message.payloadAsText())
        assertEquals(messageId, queued.message.messageId)
        val nonMessageId = messaging.allocateMessageId(otherPeer, Duration.ZERO)
        val non = messaging.send(Duration.ZERO, otherPeer, response(CoapMessageType.NON, nonMessageId), null)
        assertFalse(messaging.supersede(non, response(CoapMessageType.NON, 0)))
    }

    @Test
    fun rfc7252Section4_2LateRetransmissionsOutsideMaxTransmitSpanAreSkipped() {
        val messaging = messaging(FixedRandom(0.0))
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        // The driver was asleep: the first timer fires only after MAX_TRANSMIT_SPAN.
        messaging.onTimer(params.maxTransmitSpan + 1.seconds)
        val effects = messaging.takeEffects()
        assertEquals(emptyList(), effects.transmitted())
        assertEquals(1, transmission.retransmissions)
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionFailsEverythingTowardsIt() {
        val messaging = messaging()
        val inFlight = messaging.sendConfirmable(Duration.ZERO, token = "01")
        val queued = messaging.sendConfirmable(Duration.ZERO, token = "02")
        val elsewhere = messaging.sendConfirmable(Duration.ZERO, to = otherPeer, token = "03")
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 99))
        messaging.takeEffects()

        messaging.onSessionEnded(peer)
        val effects = messaging.takeEffects()
        val failures = effects.filterIsInstance<MessagingEffect.Completed>()
        assertEquals(setOf(inFlight, queued), failures.map { it.transmission }.toSet())
        failures.forEach {
            assertEquals(CoapException.Reason.SESSION_ENDED, assertIs<TransmissionOutcome.Failed>(it.outcome).cause.reason)
        }
        assertTrue(effects.any { (it as? MessagingEffect.Event)?.event is EndpointEvent.SessionEnded })
        assertEquals(emptyList(), effects.transmitted())
        assertEquals(Transmission.State.IN_FLIGHT, elsewhere.state)

        val retransmitted = messaging.runTimers(until = 3.seconds)
        assertEquals(listOf(2.seconds), retransmitted, "only the other peer's message is retransmitted")
        // The deduplication state is gone too.
        messaging.receive(4.seconds, request(CoapMessageType.CON, 99))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
    }

    @Test
    fun closingFailsEveryTransmission() {
        val messaging = messaging()
        messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        messaging.close()
        val outcome = assertIs<TransmissionOutcome.Failed>(messaging.takeEffects().outcomes().single())
        assertEquals(CoapException.Reason.CLOSED, outcome.cause.reason)
    }

    @Test
    fun rfc7252Section4_4MessageIdsAreNotReusedWithinExchangeLifetime() {
        val messaging = messaging(Random(3))
        val first = messaging.allocateMessageId(peer, Duration.ZERO)
        val seen = mutableSetOf(first)
        repeat(0xFFFF) { seen += messaging.allocateMessageId(peer, Duration.ZERO) }
        assertEquals(0x10000, seen.size, "every ID handed out once")
        val exhausted = assertFailsWith<CoapException> { messaging.allocateMessageId(peer, 1.seconds) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, exhausted.reason)
        // Another peer has its own space, and after EXCHANGE_LIFETIME the IDs are free again.
        messaging.allocateMessageId(otherPeer, 1.seconds)
        assertTrue(messaging.allocateMessageId(peer, params.exchangeLifetime) in seen)
        messaging.onTimer(params.exchangeLifetime)
        assertEquals(2, messaging.stateSizes.second, "the new reservation and the other peer's remain")
    }

    @Test
    fun messagesLargerThanTheDatagramAreRefused() {
        val messaging = messaging(maxDatagramSize = 32)
        val large = response(CoapMessageType.CON, 1).toBuilder().apply { payload = ByteArray(64) }.build()
        val error = assertFailsWith<CoapException> { messaging.send(Duration.ZERO, peer, large, null) }
        assertEquals(CoapException.Reason.MESSAGE_TOO_LARGE, error.reason)
        assertFailsWith<IllegalArgumentException> {
            messaging.send(Duration.ZERO, peer, CoapMessage.empty(CoapMessageType.ACK, 1), null)
        }
    }

    @Test
    fun interceptorsChangeOrDropMessagesInOrder() {
        val seen = mutableListOf<String>()
        val addQuery = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage =
                message.toBuilder().options { uriQuery("traced") }.build()
        }
        val recorder = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage {
                seen += "send ${message.options.uriQuery}"
                return message
            }

            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage? {
                seen += "receive ${message.messageId}"
                return if (message.messageId == 2) null else message
            }
        }
        val messaging = messaging(interceptors = listOf(addQuery, recorder))
        messaging.sendConfirmable(Duration.ZERO)
        assertEquals(listOf("traced"), messaging.takeEffects().transmitted().single().options.uriQuery)
        messaging.receive(Duration.ZERO, request(CoapMessageType.NON, 1))
        messaging.receive(Duration.ZERO, request(CoapMessageType.NON, 2))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
        assertEquals(listOf("send [traced]", "receive 1", "receive 2"), seen)
    }

    @Test
    fun aDroppedConfirmableMessageIsNeverSentButStillTimesOut() {
        val dropAll = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage? = null
        }
        val failing = object : MessageInterceptor {
            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = error("boom")
        }
        val messaging = messaging(interceptors = listOf(dropAll, failing))
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        assertNull(transmission.bytes)
        messaging.takeEffects()
        val outcomes = mutableListOf<TransmissionOutcome>()
        while (true) {
            messaging.onTimer(messaging.nextDeadline() ?: break)
            val effects = messaging.takeEffects()
            assertEquals(emptyList(), effects.transmitted())
            outcomes += effects.outcomes()
            if (outcomes.isNotEmpty()) break
        }
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.TimedOut), outcomes)
        messaging.receive(Duration.ZERO, request(CoapMessageType.NON, 1))
        assertEquals(emptyList(), messaging.takeEffects(), "a failing interceptor drops the message")
    }

    @Test
    fun theTransmissionIsTheHandleGivenToTheOwner() {
        val owner = TransmissionOwner { _, _ -> }
        val messaging = messaging()
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val transmission = messaging.send(Duration.ZERO, peer, request(CoapMessageType.CON, messageId), owner)
        messaging.takeEffects()
        messaging.receive(Duration.ZERO, CoapMessage.empty(CoapMessageType.ACK, messageId))
        val completed = messaging.takeEffects().filterIsInstance<MessagingEffect.Completed>().single()
        assertSame(transmission, completed.transmission)
        assertSame(owner, completed.transmission.owner)
        assertContentEquals(UdpMessageCodec.encode(transmission.message), transmission.bytes)
        assertTrue(transmission.toString().contains("DONE"))
    }

    @Test
    fun rfc7252Section11_3AFullDeduplicationCacheDropsNewMessagesWithoutAnAnswer() {
        val messaging = messaging(maxDeduplicationEntries = 2)
        messaging.receive(Duration.ZERO, post(CoapMessageType.CON, 1))
        messaging.receive(Duration.ZERO, post(CoapMessageType.NON, 2), from = otherPeer)
        assertEquals(2, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)

        // Both POSTs must be processed at most once, so nothing may be forgotten: a new Confirmable message, an
        // Empty one (a ping) and a Non-confirmable one get no state and no answer.
        messaging.receive(1.seconds, post(CoapMessageType.CON, 3))
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.CON, 4))
        messaging.receive(1.seconds, request(CoapMessageType.NON, 5))
        val dropped = messaging.takeEffects()
        assertTrue(dropped.transmitted().isEmpty())
        assertTrue(dropped.none { it is MessagingEffect.DeliverRequest })
        assertEquals(List(3) { EndpointEvent.LimitReached.Limit.DEDUPLICATION_ENTRIES }, dropped.limits())
        assertEquals(2, messaging.stateSizes.first)

        // A duplicate of a remembered message is still answered as before (§4.5).
        messaging.respond(1.seconds, peer, 1, CoapMessageType.CON, response(CoapMessageType.ACK, 1))
        messaging.takeEffects()
        messaging.receive(2.seconds, post(CoapMessageType.CON, 1))
        assertEquals(CoapMessageType.ACK, messaging.takeEffects().transmitted().single().type)

        // Once NON_LIFETIME passed, the Non-confirmable entry makes room again.
        messaging.receive(params.nonLifetime + 1.seconds, post(CoapMessageType.CON, 6))
        assertEquals(1, messaging.takeEffects().filterIsInstance<MessagingEffect.DeliverRequest>().size)
    }

    @Test
    fun rfc7252Section4_5AFullCacheForgetsTheOldestMessageThatMayBeProcessedAgain() {
        val messaging = messaging(maxDeduplicationEntries = 2)
        messaging.receive(Duration.ZERO, post(CoapMessageType.CON, 1))
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 2))
        messaging.respond(Duration.ZERO, peer, 2, CoapMessageType.CON, response(CoapMessageType.ACK, 2))
        messaging.takeEffects()

        // The answered GET is idempotent, so its entry goes for the new request, which is processed.
        messaging.receive(1.seconds, request(CoapMessageType.CON, 3))
        val effects = messaging.takeEffects()
        assertEquals(listOf(3), effects.delivered())
        assertEquals(emptyList(), effects.limits(), "nothing was refused")
        assertEquals(2, messaging.stateSizes.first)

        // Answered too, GET 3 makes room for a late duplicate of GET 2, which is processed as new (§4.5 relaxed).
        messaging.respond(1.seconds, peer, 3, CoapMessageType.CON, response(CoapMessageType.ACK, 3))
        messaging.takeEffects()
        messaging.receive(2.seconds, request(CoapMessageType.CON, 2))
        assertEquals(listOf(2), messaging.takeEffects().delivered())

        // The POST is never forgotten: its duplicate is still recognized.
        messaging.receive(3.seconds, post(CoapMessageType.CON, 1))
        val duplicate = messaging.takeEffects()
        assertEquals(emptyList(), duplicate.delivered())
        assertTrue(duplicate.any { (it as? MessagingEffect.Event)?.event is EndpointEvent.DuplicateReceived })
    }

    @Test
    fun rfc7252Section4_5ResetsAndRequestsRefusedBeforeAnyEffectMayBeForgotten() {
        val messaging = messaging(maxDeduplicationEntries = 2)
        messaging.receive(Duration.ZERO, CoapMessage.empty(CoapMessageType.CON, 1))
        messaging.receive(Duration.ZERO, post(CoapMessageType.CON, 2))
        // A POST to a path that does not exist: the server says nothing acted on it.
        messaging.respond(Duration.ZERO, peer, 2, CoapMessageType.CON, response(CoapMessageType.ACK, 2, code = CoapCode.NOT_FOUND), repeatable = true)
        messaging.takeEffects()

        messaging.receive(1.seconds, post(CoapMessageType.CON, 3))
        messaging.receive(1.seconds, post(CoapMessageType.NON, 4))
        val effects = messaging.takeEffects()
        assertEquals(listOf(3, 4), effects.delivered(), "the ping's Reset and the refused POST made room")
        assertEquals(emptyList(), effects.limits())
        // A separate answer marks a Non-confirmable request too.
        messaging.respond(1.seconds, peer, 4, CoapMessageType.NON, response(CoapMessageType.NON, 0), repeatable = true)
        messaging.takeEffects()
        messaging.receive(2.seconds, post(CoapMessageType.CON, 5))
        assertEquals(listOf(5), messaging.takeEffects().delivered())
    }

    @Test
    fun rfc7252Section4_5ARequestDroppedBeforeAnyEffectMayBeForgotten() {
        val messaging = messaging(maxDeduplicationEntries = 1)
        messaging.receive(Duration.ZERO, post(CoapMessageType.NON, 1))
        messaging.takeEffects()
        messaging.receive(1.seconds, post(CoapMessageType.NON, 2))
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.DEDUPLICATION_ENTRIES), messaging.takeEffects().limits())

        // An overloaded endpoint dropped the first POST unanswered: it makes room for the next one.
        messaging.markRepeatable(peer, 1)
        messaging.markRepeatable(peer, 99)
        messaging.receive(2.seconds, post(CoapMessageType.NON, 2))
        assertEquals(listOf(2), messaging.takeEffects().delivered())
    }

    @Test
    fun rfc7252Section11_3AQuarterOfTheCacheIsKeptForMessagesThatMayBeProcessedAgain() {
        val messaging = messaging(maxDeduplicationEntries = 4)
        val peers = List(4) { Endpoint.Udp("192.0.2.${10 + it}", 5683) }
        for (index in 0..2) messaging.receive(Duration.ZERO, post(CoapMessageType.CON, index), from = peers[index])
        messaging.takeEffects()

        // Three POSTs fill the three quarters that must be kept: a fourth is refused although one place is free.
        messaging.receive(1.seconds, post(CoapMessageType.CON, 3), from = peers[3])
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.DEDUPLICATION_ENTRIES), messaging.takeEffects().limits())
        // A GET takes the free place; once answered, it gives it to the next GET.
        messaging.receive(1.seconds, request(CoapMessageType.CON, 4), from = peers[3])
        messaging.respond(1.seconds, peers[3], 4, CoapMessageType.CON, response(CoapMessageType.ACK, 4))
        messaging.receive(2.seconds, request(CoapMessageType.NON, 5), from = peers[3])
        assertEquals(listOf(4, 5), messaging.takeEffects().delivered())
        assertEquals(4, messaging.stateSizes.first)
    }

    @Test
    fun rfc7252Section11_3OnePeerCannotTakeTheWholeCache() {
        val messaging = messaging(maxDeduplicationEntriesPerPeer = 2)
        messaging.receive(Duration.ZERO, post(CoapMessageType.CON, 1))
        messaging.receive(Duration.ZERO, request(CoapMessageType.NON, 2))
        messaging.takeEffects()

        // The peer's own Non-confirmable GET makes room for its next message; then nothing of its own is left.
        messaging.receive(1.seconds, post(CoapMessageType.CON, 3))
        assertEquals(listOf(3), messaging.takeEffects().delivered())
        messaging.receive(1.seconds, post(CoapMessageType.CON, 4))
        val refused = messaging.takeEffects()
        assertEquals(emptyList(), refused.delivered())
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.DEDUPLICATION_ENTRIES_PER_PEER), refused.limits())
        // Another peer is not affected.
        messaging.receive(1.seconds, post(CoapMessageType.CON, 4), from = otherPeer)
        assertEquals(listOf(4), messaging.takeEffects().delivered())
        assertEquals(3, messaging.stateSizes.first)
        // The bookkeeping of a peer goes with its last entry.
        messaging.onTimer(params.exchangeLifetime + 1.seconds)
        assertEquals(0, messaging.stateSizes.first)
        assertEquals(0, messaging.deduplicationPeers)
        assertEquals(0, messaging.repeatableEntries)
    }

    @Test
    fun rfc7252Section4_4MessageIdsAreBoundedInTotalAndPerPeer() {
        val messaging = messaging(maxReservedMessageIds = 3, maxReservedMessageIdsPerPeer = 2)
        messaging.allocateMessageId(peer, Duration.ZERO)
        messaging.allocateMessageId(peer, Duration.ZERO)
        val perPeer = assertFailsWith<CoapException> { messaging.allocateMessageId(peer, Duration.ZERO) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, perPeer.reason)
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.MESSAGE_IDS_PER_PEER), messaging.takeEffects().limits())

        messaging.allocateMessageId(otherPeer, Duration.ZERO)
        val total = assertFailsWith<CoapException> { messaging.allocateMessageId(Endpoint.Udp("192.0.2.3", 5683), Duration.ZERO) }
        assertTrue("MESSAGE_IDS" in total.message!!)
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.MESSAGE_IDS), messaging.takeEffects().limits())
        assertEquals(3, messaging.stateSizes.second)

        // §4.4 forbids reusing them until EXCHANGE_LIFETIME has passed; then they are free again.
        messaging.allocateMessageId(peer, params.exchangeLifetime)
        assertEquals(1, messaging.stateSizes.second)
    }

    @Test
    fun rfc7252Section5_2_3ResponsesToNonConfirmableRequestsLeaveAQuarterOfTheMessageIdsToTheRest() {
        val messaging = messaging(maxReservedMessageIds = 4)
        val sources = List(4) { Endpoint.Udp("198.51.100.${it + 1}", 5683) }
        sources.forEachIndexed { id, from -> messaging.receive(Duration.ZERO, request(CoapMessageType.NON, id), from) }
        messaging.takeEffects()
        val answer = response(CoapMessageType.NON, 0)
        val answered = sources.mapIndexed { id, from ->
            runCatching { messaging.respond(Duration.ZERO, from, id, CoapMessageType.NON, answer) }
        }
        assertEquals(listOf(true, true, true, false), answered.map { it.isSuccess }, "three of four IDs at most")
        val refused = assertIs<CoapException>(answered.last().exceptionOrNull())
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, refused.reason)
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.MESSAGE_IDS), messaging.takeEffects().limits())

        // The last quarter is left for a message that must go, here a separate response to a Confirmable request.
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 9))
        messaging.acknowledge(peer, 9)
        messaging.takeEffects()
        val separate = messaging.respond(1.seconds, peer, 9, CoapMessageType.CON, response(CoapMessageType.CON, 0))
        assertEquals(CoapMessageType.CON, separate?.message?.type)
        assertEquals(4, messaging.stateSizes.second)
    }

    @Test
    fun rfc7641Section4_5_2ASupersedingNotificationWithoutAFreeMessageIdWaitsForTheNextTimeout() {
        val messaging = messaging(FixedRandom(0.0), maxReservedMessageIds = 1)
        val messageId = messaging.allocateMessageId(peer, Duration.ZERO)
        val notification = messaging.send(Duration.ZERO, peer, response(CoapMessageType.CON, messageId), null)
        messaging.takeEffects()

        assertTrue(messaging.supersede(notification, response(CoapMessageType.CON, 0).toBuilder().payload("newer").build()))
        messaging.onTimer(2.seconds)
        val effects = messaging.takeEffects()
        assertEquals(listOf(messageId to "ok"), effects.transmitted().map { it.messageId to it.payloadAsText() }, "the old one again")
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.MESSAGE_IDS), effects.limits())
        assertEquals("newer", notification.replacement?.payloadAsText(), "kept for the next timeout")
        // Once the old reservation expired, the next timeout sends the newer notification with a new ID.
        messaging.onTimer(params.exchangeLifetime)
        val replaced = messaging.takeEffects().transmitted().single()
        assertEquals("newer", replaced.payloadAsText())
        assertTrue(replaced.messageId != messageId)
    }

    @Test
    fun rfc7252Section4_3NonConfirmableMessagesKeptForAResetMakeRoomOldestFirst() {
        val messaging = messaging(maxTransmissions = 3)
        // A Non-confirmable request holding its NSTART slot, and one that a Reset already ended.
        val holding = messaging.send(Duration.ZERO, peer, request(CoapMessageType.NON, messaging.allocateMessageId(peer, Duration.ZERO)), null, 100.seconds)
        val resetId = messaging.allocateMessageId(otherPeer, Duration.ZERO)
        messaging.send(Duration.ZERO, otherPeer, response(CoapMessageType.NON, resetId), owner = null)
        messaging.receive(Duration.ZERO, CoapMessage.empty(CoapMessageType.RST, resetId), from = otherPeer)

        val sent = List(200) {
            val to = Endpoint.Udp("198.51.100.${it % 250 + 1}", 5683)
            messaging.send(1.seconds, to, response(CoapMessageType.NON, messaging.allocateMessageId(to, 1.seconds)), owner = null)
        }
        assertEquals(emptyList(), messaging.takeEffects().limits(), "far more than the scan window, and nothing refused")
        assertEquals(3, messaging.outboundCount)
        assertEquals(Transmission.State.IN_FLIGHT, holding.state, "the one holding a slot stays")
        assertEquals(listOf(Transmission.State.IN_FLIGHT, Transmission.State.IN_FLIGHT), sent.takeLast(2).map { it.state })
        assertEquals(Transmission.State.DONE, sent.first().state)
    }

    @Test
    fun rfc7252Section11_3MessagesKeptInAllAreBounded() {
        val messaging = messaging(FixedRandom(0.0), maxTransmissions = 2)
        val third = Endpoint.Udp("192.0.2.3", 5683)
        val nonId = messaging.allocateMessageId(peer, Duration.ZERO)
        val non = messaging.send(Duration.ZERO, peer, response(CoapMessageType.NON, nonId), owner = null)
        val first = messaging.sendConfirmable(Duration.ZERO, to = otherPeer)
        messaging.takeEffects()

        // The Non-confirmable message, kept only to match a Reset, makes room.
        val second = messaging.sendConfirmable(Duration.ZERO, to = third)
        assertEquals(Transmission.State.DONE, non.state)
        assertEquals(emptyList(), messaging.takeEffects().limits(), "forgetting it refuses nothing")
        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.RST, nonId))
        assertEquals(emptyList(), messaging.takeEffects().outcomes(), "a Reset for it matches nothing (§4.3)")

        // Two Confirmable messages not yet retransmitted: the next one is refused.
        val refused = assertFailsWith<CoapException> { messaging.sendConfirmable(1.seconds) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, refused.reason)
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.EXCHANGES), messaging.takeEffects().limits())

        // After one retransmission without an ACK, the oldest is given up for the next message.
        messaging.onTimer(2.seconds)
        messaging.takeEffects()
        assertEquals(listOf(1, 1), listOf(first.retransmissions, second.retransmissions))
        messaging.sendConfirmable(3.seconds)
        val effects = messaging.takeEffects()
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.TimedOut), effects.outcomes())
        assertEquals(Transmission.State.DONE, first.state)
        assertEquals(listOf(EndpointEvent.LimitReached.Limit.EXCHANGES), effects.limits())
        assertTrue(effects.any { (it as? MessagingEffect.Event)?.event is EndpointEvent.TransmissionTimedOut })
        assertEquals(Transmission.State.IN_FLIGHT, second.state)
        assertEquals(2, messaging.outboundCount)
    }

    @Test
    fun rfc7252Section4_7GivingUpAMessageToThePeerAboutToSendKeepsItsNstartWindow() {
        val messaging = messaging(FixedRandom(0.0), maxTransmissions = 2)
        val first = messaging.sendConfirmable(Duration.ZERO)
        messaging.sendConfirmable(Duration.ZERO, to = otherPeer)
        messaging.onTimer(2.seconds)
        messaging.takeEffects()

        // The table is full, and making room gives up the peer's only slot holder, which ends its window.
        val second = messaging.sendConfirmable(3.seconds, token = "b1")
        assertEquals(listOf<TransmissionOutcome>(TransmissionOutcome.TimedOut), messaging.takeEffects().outcomes())
        assertEquals(Transmission.State.DONE, first.state)
        assertEquals(Transmission.State.IN_FLIGHT, second.state)
        // The new message holds the peer's one slot (NSTART = 1), so the next one waits for it.
        val third = messaging.sendConfirmable(3.seconds, token = "c1")
        assertEquals(Transmission.State.QUEUED, third.state)
        messaging.takeEffects()
        messaging.receive(4.seconds, CoapMessage.empty(CoapMessageType.ACK, second.message.messageId))
        assertEquals(Transmission.State.IN_FLIGHT, third.state)
        assertEquals(listOf(third.message.messageId), messaging.takeEffects().transmitted().map { it.messageId })
    }

    @Test
    fun rfc7252Section11_3MessagesQueuedTowardsOnePeerAreBounded() {
        val messaging = messaging(maxPerPeer = 2)
        messaging.sendConfirmable(Duration.ZERO)
        val queued = messaging.sendConfirmable(Duration.ZERO, token = "b1")
        assertEquals(Transmission.State.QUEUED, queued.state)
        messaging.takeEffects()

        val error = assertFailsWith<CoapException> { messaging.sendConfirmable(Duration.ZERO, token = "c1") }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        val event = messaging.takeEffects().filterIsInstance<MessagingEffect.Event>().single().event
        assertEquals(EndpointEvent.LimitReached.Limit.EXCHANGES_PER_PEER, assertIs<EndpointEvent.LimitReached>(event).limit)
        assertTrue(event.toString().contains("EXCHANGES_PER_PEER"))
        // Another peer, and a message that holds no slot, are not affected.
        messaging.sendConfirmable(Duration.ZERO, to = otherPeer)
        messaging.send(Duration.ZERO, peer, request(CoapMessageType.NON, messaging.allocateMessageId(peer, Duration.ZERO)), owner = null)
    }

    @Test
    fun rfc7252Section5_2_2EmptyAcksGoOutAtEachRequestsOwnDeadlineInArrivalOrder() {
        val messaging = messaging(separateResponseDelay = 1.seconds)
        messaging.receive(Duration.ZERO, request(CoapMessageType.CON, 1))
        messaging.receive(500.milliseconds, request(CoapMessageType.CON, 2))
        messaging.receive(600.milliseconds, request(CoapMessageType.CON, 3))
        messaging.respond(700.milliseconds, peer, 2, CoapMessageType.CON, response(CoapMessageType.ACK, 2))
        messaging.takeEffects()
        assertEquals(1.seconds, messaging.nextDeadline())

        messaging.onTimer(1.seconds)
        assertEquals(listOf(1), messaging.takeEffects().transmitted().map { it.messageId })
        assertEquals(1600.milliseconds, messaging.nextDeadline(), "the answered request no longer counts")
        messaging.onTimer(1600.milliseconds)
        assertEquals(listOf(3), messaging.takeEffects().transmitted().map { it.messageId })
    }

    @Test
    fun theTimersAreWokenOnlyWhenACallBringsTheNextDeadlineForward() {
        val messaging = messaging(separateResponseDelay = 1.seconds)
        assertNull(messaging.nextDeadline(), "the timer loop waits for nothing")
        val transmission = messaging.sendConfirmable(Duration.ZERO)
        messaging.takeEffects()
        assertTrue(messaging.takeWakeup(), "the first deadline wakes the timers")
        assertEquals(params.ackTimeout, messaging.nextDeadline())

        // Datagrams whose deadlines all lie later, here their deduplication lifetimes, leave the timers alone,
        // however many transmissions are outstanding.
        messaging.receive(500.milliseconds, request(CoapMessageType.NON, 7))
        messaging.receive(500.milliseconds, response(CoapMessageType.ACK, 99))
        messaging.takeEffects()
        assertFalse(messaging.takeWakeup())

        // A Confirmable request due for its Empty ACK before the retransmission wakes them.
        messaging.receive(600.milliseconds, request(CoapMessageType.CON, 8))
        messaging.takeEffects()
        assertTrue(messaging.takeWakeup())
        assertEquals(1600.milliseconds, messaging.nextDeadline())

        // A deadline that goes away needs no wakeup: the loop recomputes after its wait anyway.
        messaging.receive(700.milliseconds, response(CoapMessageType.ACK, transmission.message.messageId))
        messaging.takeEffects()
        assertFalse(messaging.takeWakeup())
        assertFalse(messaging.takeWakeup(), "taking it clears it")
    }

    @Test
    fun aTransmissionTheTransportRefusedFailsAtOnceAndFreesItsSlot() {
        val messaging = messaging()
        val refused = messaging.sendConfirmable(Duration.ZERO)
        val next = messaging.sendConfirmable(Duration.ZERO, token = "b1")
        assertEquals(Transmission.State.QUEUED, next.state)
        assertSame(refused, messaging.takeEffects().filterIsInstance<MessagingEffect.Transmit>().single().transmission)

        val cause = CoapException("refused", CoapException.Reason.TRANSPORT_FAILED)
        messaging.abandon(Duration.ZERO, refused, cause)
        val effects = messaging.takeEffects()
        assertSame(cause, assertIs<TransmissionOutcome.Failed>(effects.outcomes().single()).cause)
        assertEquals(Transmission.State.IN_FLIGHT, next.state, "the queued message took the slot")
        assertTrue(messaging.runTimers(until = 1.seconds).isEmpty(), "the refused one is not retransmitted")
    }

    private fun List<MessagingEffect>.granted(): List<BodyLease> =
        filterIsInstance<MessagingEffect.LeaseGranted>().map { it.lease }

    @Test
    fun rfc9177Section7_2ABodyLeaseHoldsOneSlotAndASecondLeaseWaits() {
        val messaging = messaging()
        val first = messaging.lease(Duration.ZERO, peer, 100)
        assertEquals(listOf(first), messaging.takeEffects().granted())
        assertEquals(BodyLease.State.HELD, first.state)

        val second = messaging.lease(Duration.ZERO, peer, 100)
        val request = messaging.sendConfirmable(Duration.ZERO, token = "01")
        val elsewhere = messaging.lease(Duration.ZERO, otherPeer, 100)
        val effects = messaging.takeEffects()
        assertEquals(listOf(elsewhere), effects.granted(), "another peer has slots of its own")
        assertEquals(emptyList(), effects.transmitted(), "NSTART 1: the request waits behind the leases")
        assertEquals(BodyLease.State.QUEUED, second.state)
        assertEquals(Transmission.State.QUEUED, request.state)
        assertEquals(3, messaging.leaseCount)

        messaging.releaseLease(5.seconds, first)
        val afterRelease = messaging.takeEffects()
        assertEquals(BodyLease.State.RELEASED, first.state)
        assertEquals(listOf(second), afterRelease.granted(), "first come, first served")
        assertEquals(105.seconds, second.deadline)
        assertEquals(Transmission.State.QUEUED, request.state)

        messaging.releaseLease(6.seconds, second)
        assertEquals(listOf("01"), messaging.takeEffects().transmitted().map { it.tokenHex })
        assertEquals(1, messaging.leaseCount)
    }

    @Test
    fun rfc9177Section7_2ALeaseReleasesItselfAfterTheBodyTimeAtProbingRate() {
        val messaging = messaging()
        val lease = messaging.lease(Duration.ZERO, peer, 100)
        messaging.sendConfirmable(Duration.ZERO, token = "01")
        messaging.takeEffects()
        assertEquals(100.seconds, lease.hold, "100 bytes at PROBING_RATE 1 byte/s")
        assertEquals(100.seconds, messaging.nextDeadline())

        val sends = messaging.runTimers(until = 100.seconds)
        assertEquals(listOf(100.seconds), sends, "the queued request goes when the lease expires")
        assertEquals(BodyLease.State.EXPIRED, lease.state)
        assertEquals(0, messaging.leaseCount)

        messaging.releaseLease(101.seconds, lease)
        assertEquals(BodyLease.State.RELEASED, lease.state, "an expired lease is only marked released")
        assertEquals(emptyList(), messaging.takeEffects().transmitted())
    }

    @Test
    fun rfc9177Section7_2ALeaseIsCappedAtNonProbingWait() {
        // NON_PROBING_WAIT = NON_TIMEOUT × (2^4 − 1) × 1.5 + 2 × MAX_LATENCY + NON_TIMEOUT_RANDOM = 245 s + [2, 3] s.
        val shortest = messaging(FixedRandom(0.0)).lease(Duration.ZERO, peer, 3000)
        assertEquals(2.seconds, shortest.nonTimeoutRandom)
        assertEquals(247.seconds, shortest.hold)
        val longest = messaging(FixedRandom(1.0)).lease(Duration.ZERO, peer, 3000)
        assertEquals(3.seconds, longest.nonTimeoutRandom)
        assertEquals(248.seconds, longest.hold)
        val given = messaging(FixedRandom(1.0)).lease(Duration.ZERO, peer, 3000, nonTimeoutRandom = 2500.milliseconds)
        assertEquals(2500.milliseconds, given.nonTimeoutRandom, "the body's own draw is kept")
        assertEquals(247500.milliseconds, given.hold)
        val faster = messaging(parameters = TransmissionParameters(probingRate = 100)).lease(Duration.ZERO, peer, 3000)
        assertEquals(30.seconds, faster.hold, "below the cap the body's bytes at PROBING_RATE decide")
    }

    @Test
    fun aQueuedLeaseThatIsReleasedLeavesTheQueue() {
        val messaging = messaging()
        val held = messaging.lease(Duration.ZERO, peer, 100)
        val queued = messaging.lease(Duration.ZERO, peer, 100)
        val request = messaging.sendConfirmable(Duration.ZERO, token = "01")
        messaging.takeEffects()

        messaging.releaseLease(1.seconds, queued)
        assertEquals(BodyLease.State.RELEASED, queued.state)
        messaging.releaseLease(2.seconds, held)
        val effects = messaging.takeEffects()
        assertEquals(emptyList(), effects.granted())
        assertEquals(listOf("01"), effects.transmitted().map { it.tokenHex })
        assertEquals(Transmission.State.IN_FLIGHT, request.state)

        messaging.releaseLease(3.seconds, held)
        messaging.releaseLease(3.seconds, queued)
        assertEquals(emptyList(), messaging.takeEffects(), "releasing is idempotent")
        val created = BodyLease(peer, 1.seconds, 2.seconds)
        messaging.releaseLease(3.seconds, created)
        assertEquals(BodyLease.State.RELEASED, created.state)
    }

    @Test
    fun aLeaseWaitsBehindAQueuedTransmissionAndViceVersa() {
        val messaging = messaging()
        val first = messaging.sendConfirmable(Duration.ZERO, token = "01")
        val lease = messaging.lease(Duration.ZERO, peer, 100)
        val last = messaging.sendConfirmable(Duration.ZERO, token = "02")
        messaging.takeEffects()
        assertEquals(BodyLease.State.QUEUED, lease.state)

        messaging.receive(1.seconds, CoapMessage.empty(CoapMessageType.ACK, first.message.messageId))
        val effects = messaging.takeEffects()
        assertEquals(listOf(lease), effects.granted())
        assertEquals(emptyList(), effects.transmitted().filter { it.isRequest })
        assertEquals(Transmission.State.QUEUED, last.state)
        assertEquals(101.seconds, lease.deadline)
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionFailsItsLeases() {
        val messaging = messaging()
        val held = messaging.lease(Duration.ZERO, peer, 100)
        val queued = messaging.lease(Duration.ZERO, peer, 100)
        val request = messaging.sendConfirmable(Duration.ZERO, token = "01")
        val elsewhere = messaging.lease(Duration.ZERO, otherPeer, 100)
        messaging.takeEffects()

        messaging.onSessionEnded(peer)
        val effects = messaging.takeEffects()
        val failed = effects.filterIsInstance<MessagingEffect.LeaseFailed>()
        assertEquals(setOf(held, queued), failed.map { it.lease }.toSet())
        assertTrue(failed.all { it.cause.reason == CoapException.Reason.SESSION_ENDED })
        assertEquals(BodyLease.State.FAILED, held.state)
        assertEquals(CoapException.Reason.SESSION_ENDED, queued.failure?.reason)
        assertIs<TransmissionOutcome.Failed>(effects.outcomes().single())
        assertEquals(Transmission.State.DONE, request.state)
        assertEquals(BodyLease.State.HELD, elsewhere.state)

        val fresh = messaging.lease(1.seconds, peer, 100)
        assertEquals(listOf(fresh), messaging.takeEffects().granted(), "the peer's window went with its session")
        messaging.releaseLease(2.seconds, held)
        assertEquals(BodyLease.State.FAILED, held.state, "a failed lease stays failed")
    }

    @Test
    fun closingFailsEveryLease() {
        val messaging = messaging()
        val held = messaging.lease(Duration.ZERO, peer, 100)
        val queued = messaging.lease(Duration.ZERO, peer, 100)
        messaging.takeEffects()

        messaging.close()
        val failed = messaging.takeEffects().filterIsInstance<MessagingEffect.LeaseFailed>()
        assertEquals(listOf(queued, held), failed.map { it.lease })
        assertTrue(failed.all { it.cause.reason == CoapException.Reason.CLOSED })
        assertEquals(0, messaging.leaseCount)
        assertNull(messaging.nextDeadline(), "no lease timer is left")
    }

    @Test
    fun rfc7252Section11_3LeasesCountTowardsTheLimitPerPeer() {
        val messaging = messaging(maxPerPeer = 2)
        messaging.lease(Duration.ZERO, peer, 100)
        messaging.lease(Duration.ZERO, peer, 100)
        messaging.takeEffects()

        val error = assertFailsWith<CoapException> { messaging.lease(Duration.ZERO, peer, 100) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertFailsWith<CoapException> { messaging.sendConfirmable(Duration.ZERO, token = "01") }
        val events = messaging.takeEffects().filterIsInstance<MessagingEffect.Event>().map { it.event }
        assertEquals(2, events.count { it is EndpointEvent.LimitReached })
        assertFailsWith<IllegalArgumentException> { messaging.lease(Duration.ZERO, otherPeer, -1) }
        assertFailsWith<IllegalArgumentException> { messaging.lease(Duration.ZERO, otherPeer, 1, nonTimeoutRandom = (-1).seconds) }
    }

    @Test
    fun aLeaseIsUsableOnlyWhileGrantedOrExpired() {
        val lease = BodyLease(peer, 1.seconds, 2.seconds)
        assertFailsWith<IllegalStateException> { lease.checkUsable() }
        lease.state = BodyLease.State.HELD
        lease.checkUsable()
        lease.state = BodyLease.State.EXPIRED
        lease.checkUsable()
        lease.state = BodyLease.State.RELEASED
        assertFailsWith<IllegalStateException> { lease.checkUsable() }
        lease.state = BodyLease.State.FAILED
        lease.failure = CoapException("gone", CoapException.Reason.SESSION_ENDED)
        assertEquals(CoapException.Reason.SESSION_ENDED, assertFailsWith<CoapException> { lease.checkUsable() }.reason)
        assertTrue(lease.toString().contains("FAILED"))

        val unpaced = BodyLease.unpaced(peer, Duration.ZERO)
        assertFalse(unpaced.isPaced)
        assertEquals(BodyLease.State.HELD, unpaced.state)
        assertTrue(unpaced.granted.isCompleted)
        val messaging = messaging()
        messaging.releaseLease(Duration.ZERO, unpaced)
        assertEquals(BodyLease.State.RELEASED, unpaced.state)
        assertEquals(emptyList(), messaging.takeEffects(), "an unpaced lease holds no slot to give back")
    }

    @Test
    fun transmissionParametersDeriveTheRfcTimeValues() {
        assertEquals(45.seconds, params.maxTransmitSpan)
        assertEquals(93.seconds, params.maxTransmitWait)
        assertEquals(2.seconds, params.processingDelay)
        assertEquals(202.seconds, params.maxRtt)
        assertEquals(247.seconds, params.exchangeLifetime)
        assertEquals(145.seconds, params.nonLifetime)
        assertTrue(params.toString().contains("NSTART=1"))
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(ackRandomFactor = 0.9) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(ackTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(nstart = 0) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(maxRetransmit = -1) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(probingRate = 0) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(defaultLeisure = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(maxLatency = (-1).seconds) }
    }
}
