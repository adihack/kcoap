package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class InMemoryNetworkTest {
    private suspend fun InMemoryTransport.receiveBytes(): ByteArray = (receive() as DatagramEvent.Received).datagram

    @Test
    fun datagramsArriveWithTheSenderAddressAsACopy() = runTest {
        val network = InMemoryNetwork()
        val a = network.attach()
        val b = network.attach(Endpoint.Udp("10.0.0.2", 5683), maxDatagramSize = 64)
        assertNotEquals(a.localEndpoint, b.localEndpoint)
        assertEquals(64, b.maxDatagramSize)

        val bytes = byteArrayOf(1, 2, 3)
        a.send(b.localEndpoint, bytes)
        bytes[0] = 9
        val event = assertIs<DatagramEvent.Received>(b.receive())
        assertEquals(a.localEndpoint, event.from)
        assertContentEquals(byteArrayOf(1, 2, 3), event.datagram)
        assertTrue(a.toString().contains(a.localEndpoint.toString()))
    }

    @Test
    fun theTapSeesEveryFate() = runTest {
        val network = InMemoryNetwork(random = Random(1))
        val fates = mutableListOf<DatagramFate>()
        network.tap = { datagram, fate ->
            assertTrue(datagram.toString().contains("bytes"))
            fates += fate
        }
        val a = network.attach()
        val b = network.attach()

        a.send(Endpoint.Udp("10.9.9.9", 1), byteArrayOf(0))
        network.filter = { it.bytes[0] != 1.toByte() }
        a.send(b.localEndpoint, byteArrayOf(1))
        network.filter = { true }
        network.conditions = LinkConditions(lossRate = 1.0)
        a.send(b.localEndpoint, byteArrayOf(2))
        network.conditions = LinkConditions(duplicationRate = 1.0)
        a.send(b.localEndpoint, byteArrayOf(3))
        network.conditions = LinkConditions.Perfect
        a.send(b.localEndpoint, byteArrayOf(4))

        assertEquals(
            listOf(DatagramFate.UNREACHABLE, DatagramFate.FILTERED, DatagramFate.LOST, DatagramFate.DUPLICATED, DatagramFate.DELIVERED),
            fates,
        )
        assertEquals(listOf<Byte>(3, 3, 4), List(3) { b.receiveBytes()[0] })
    }

    @Test
    fun latencyAndJitterRunOnVirtualTimeAndReorder() = runTest {
        val network = InMemoryNetwork(backgroundScope, Random(3))
        val a = network.attach()
        val b = network.attach()
        network.conditions = LinkConditions(latency = 100.milliseconds, jitter = 100.milliseconds)

        repeat(20) { a.send(b.localEndpoint, byteArrayOf(it.toByte())) }
        val first = async { b.receiveBytes() }
        advanceTimeBy(99.milliseconds)
        runCurrent()
        assertFalse(first.isCompleted, "nothing arrives before the latency")
        advanceTimeBy(102.milliseconds)
        runCurrent()
        val order = listOf(first.await()[0].toInt()) + List(19) { b.receiveBytes()[0].toInt() }
        assertEquals((0 until 20).toSet(), order.toSet())
        assertNotEquals((0 until 20).toList(), order, "jitter reorders")
    }

    @Test
    fun latencyNeedsAScope() {
        assertFailsWith<IllegalArgumentException> { InMemoryNetwork().conditions = LinkConditions(latency = 1.milliseconds) }
    }

    @Test
    fun conditionsAreValidated() {
        assertFailsWith<IllegalArgumentException> { LinkConditions(lossRate = 1.5) }
        assertFailsWith<IllegalArgumentException> { LinkConditions(duplicationRate = -0.1) }
        assertFailsWith<IllegalArgumentException> { LinkConditions(latency = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { LinkConditions(jitter = (-1).milliseconds) }
        assertTrue(LinkConditions.Perfect.isImmediate)
        assertTrue(LinkConditions(lossRate = 0.5).toString().contains("loss=0.5"))
    }

    @Test
    fun addressesAndSizesAreChecked() = runTest {
        val network = InMemoryNetwork()
        val a = network.attach(Endpoint.Udp("127.0.0.1", 1))
        assertFailsWith<IllegalArgumentException> { network.attach(Endpoint.Udp("127.0.0.1", 1)) }
        assertFailsWith<IllegalArgumentException> { network.attach(maxDatagramSize = 0) }
        assertFailsWith<IllegalArgumentException> { a.send(Endpoint.Udp("127.0.0.1", 2), ByteArray(2000)) }
    }

    @Test
    fun closingDetachesAndEndsReceiving() = runTest {
        val network = InMemoryNetwork()
        val a = network.attach()
        val b = network.attach()
        b.close()
        b.close()
        assertTrue(b.isClosed)
        assertFailsWith<IllegalStateException> { b.send(a.localEndpoint, byteArrayOf(1)) }
        assertFailsWith<ClosedReceiveChannelException> { b.receive() }
        // The address is free again.
        network.attach(b.localEndpoint)
    }

    @Test
    fun injectedEventsSimulateASecurityLayer() = runTest {
        val network = InMemoryNetwork()
        val a = network.attach()
        val session = Endpoint.Secure(SessionId(1), 0, a.localEndpoint, PeerIdentity.None)
        a.inject(DatagramEvent.SessionEnded(session, SessionEndReason.ALERT))
        assertEquals(session, assertIs<DatagramEvent.SessionEnded>(a.receive()).endpoint)
    }
}
