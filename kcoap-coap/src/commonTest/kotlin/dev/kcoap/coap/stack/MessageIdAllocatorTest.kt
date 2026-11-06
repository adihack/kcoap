package dev.kcoap.coap.stack

import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** RFC 7252 §4.4: no Message ID is reused towards a peer within its lifetime, and the reservations are bounded. */
class MessageIdAllocatorTest {
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val other = Endpoint.Udp("192.0.2.2", 5683)
    private val lifetime = 247.seconds

    @Test
    fun rfc7252Section4_4EveryIdOfThePeersSpaceIsHandedOutOnceThenNoneIsLeft() {
        val ids = MessageIdAllocator(initial = 0xFFFE)
        val handedOut = List(MessageIdAllocator.MESSAGE_ID_SPACE) { ids.allocate(peer, Duration.ZERO, lifetime) }
        assertEquals(listOf(0xFFFE, 0xFFFF, 0, 1), handedOut.take(4), "one counter for all peers, wrapping at 16 bits")
        assertEquals(MessageIdAllocator.MESSAGE_ID_SPACE, handedOut.toSet().size)
        assertEquals(Limit.MESSAGE_IDS_PER_PEER, ids.refusal(peer, 1.seconds), "the whole space is the bound per peer")
        val error = assertFailsWith<CoapException> { ids.allocate(peer, 1.seconds, lifetime) }
        assertEquals(CoapException.Reason.IDENTIFIERS_EXHAUSTED, error.reason)
        assertNull(ids.refusal(other, 1.seconds), "another peer has a space of its own")
        assertEquals(0xFFFE, ids.allocate(other, 1.seconds, lifetime))
    }

    @Test
    fun reservationsAreCountedPerPeerAndInAllUntilTheyExpire() {
        val ids = MessageIdAllocator(initial = 7, maxReserved = 3, maxReservedPerPeer = 2)
        val first = ids.allocate(peer, Duration.ZERO, lifetime)
        ids.allocate(peer, Duration.ZERO, lifetime)
        assertEquals(Limit.MESSAGE_IDS_PER_PEER, ids.refusal(peer, Duration.ZERO))
        ids.allocate(other, Duration.ZERO, lifetime)
        assertEquals(Limit.MESSAGE_IDS, ids.refusal(Endpoint.Udp("192.0.2.3", 5683), Duration.ZERO))

        // Extending a reservation that was just sent counts it once.
        ids.extend(peer, first, lifetime + 1.seconds)
        assertEquals(2, ids.reservedCount(peer))
        assertEquals(3, ids.reservedCount)

        // At EXCHANGE_LIFETIME the others expire; the extended one a second later.
        assertNull(ids.refusal(Endpoint.Udp("192.0.2.3", 5683), lifetime))
        assertEquals(1, ids.reservedCount(peer))
        assertEquals(0, ids.reservedCount(other))
        assertEquals(lifetime + 1.seconds, ids.nextExpiry())
        ids.prune(lifetime + 1.seconds)
        assertEquals(0, ids.reservedCount)
        assertNull(ids.nextExpiry())
    }

    @Test
    fun rfc7252Section11_3DroppableMessagesLeaveAQuarterOfEachBoundToTheOthers() {
        val ids = MessageIdAllocator(initial = 0, maxReserved = 8, maxReservedPerPeer = 4)
        repeat(3) { ids.allocate(peer, Duration.ZERO, lifetime) }
        val perPeer = ids.refusal(peer, Duration.ZERO, droppable = true)
        assertEquals(Limit.MESSAGE_IDS_PER_PEER, perPeer, "three of four towards one peer")
        assertNull(ids.refusal(peer, Duration.ZERO), "the last quarter is for messages that are not droppable")
        ids.allocate(peer, Duration.ZERO, lifetime)

        val third = Endpoint.Udp("192.0.2.3", 5683)
        repeat(2) { ids.allocate(other, Duration.ZERO, lifetime) }
        assertEquals(Limit.MESSAGE_IDS, ids.refusal(third, Duration.ZERO, droppable = true), "six of eight in all")
        assertNull(ids.refusal(third, Duration.ZERO))
    }

    @Test
    fun rfc7252Section9_1_1AnEndedSessionGivesItsReservationsBack() {
        val ids = MessageIdAllocator(initial = 0, maxReserved = 2)
        ids.allocate(peer, Duration.ZERO, lifetime)
        ids.allocate(other, Duration.ZERO, lifetime)
        assertEquals(Limit.MESSAGE_IDS, ids.refusal(peer, Duration.ZERO))
        ids.forget(peer)
        assertEquals(0, ids.reservedCount(peer))
        assertNull(ids.refusal(peer, Duration.ZERO))
        // Its queued expiry no longer counts anything down when it comes.
        ids.prune(lifetime)
        assertEquals(0, ids.reservedCount)
    }
}
