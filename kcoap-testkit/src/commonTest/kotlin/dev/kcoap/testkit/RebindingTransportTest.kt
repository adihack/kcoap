package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RebindingTransportTest {
    private suspend fun InMemoryTransport.receiveFrom(): DatagramEvent.Received = assertIs<DatagramEvent.Received>(receive())

    @Test
    fun theSourceAddressChangesAtTheChosenDatagram() = runTest {
        val network = InMemoryNetwork()
        val peer = network.attach()
        val rebinding = RebindingTransport(network.attach(), network.attach(), switchAt = 2)
        val before = rebinding.localEndpoint
        assertFalse(rebinding.isRebound)

        repeat(4) { rebinding.send(peer.localEndpoint, byteArrayOf(it.toByte())) }

        val sources = List(4) { peer.receiveFrom() }.map { it.from }
        assertEquals(listOf(before, before, rebinding.second.localEndpoint, rebinding.second.localEndpoint), sources)
        assertEquals(rebinding.first.localEndpoint, before)
        assertTrue(rebinding.isRebound)
        assertEquals(4, rebinding.sent)
        assertEquals(rebinding.second.localEndpoint, rebinding.localEndpoint)
        assertTrue(rebinding.toString().contains("at 2"))
    }

    @Test
    fun datagramsArriveOnBothAddresses() = runTest {
        val network = InMemoryNetwork()
        val peer = network.attach()
        val rebinding = RebindingTransport(network.attach(), network.attach(), switchAt = 0)
        assertTrue(rebinding.isRebound, "switching at 0 sends every datagram from the second address")

        peer.send(rebinding.first.localEndpoint, byteArrayOf(1))
        peer.send(rebinding.second.localEndpoint, byteArrayOf(2))

        val received = List(2) { assertIs<DatagramEvent.Received>(rebinding.receive()).datagram[0] }
        assertEquals(setOf<Byte>(1, 2), received.toSet())
    }

    @Test
    fun anExpiredOldBindingLosesWhatArrivesThereAfterTheSwitch() = runTest {
        val network = InMemoryNetwork()
        val peer = network.attach()
        val rebinding = RebindingTransport(network.attach(), network.attach(), switchAt = 1, oldAddressReceives = false)
        peer.send(rebinding.first.localEndpoint, byteArrayOf(1))
        assertContentEquals(byteArrayOf(1), assertIs<DatagramEvent.Received>(rebinding.receive()).datagram, "before the switch")

        rebinding.send(peer.localEndpoint, byteArrayOf(0))
        peer.send(rebinding.first.localEndpoint, byteArrayOf(2))
        peer.send(rebinding.second.localEndpoint, byteArrayOf(3))

        assertContentEquals(byteArrayOf(3), assertIs<DatagramEvent.Received>(rebinding.receive()).datagram)
    }

    @Test
    fun closingClosesBothTransports() = runTest {
        val network = InMemoryNetwork()
        val rebinding = RebindingTransport(network.attach(), network.attach(maxDatagramSize = 100), switchAt = 3)
        assertEquals(100, rebinding.maxDatagramSize)
        val waiting = async { runCatching { rebinding.receive() } }
        runCurrent()

        rebinding.close()

        assertTrue(rebinding.first.isClosed && rebinding.second.isClosed)
        assertIs<ClosedReceiveChannelException>(waiting.await().exceptionOrNull())
    }

    @Test
    fun itNeedsTwoAddressesAndAValidSwitch() = runTest {
        val network = InMemoryNetwork()
        val a = network.attach()
        assertFailsWith<IllegalArgumentException> { RebindingTransport(a, a, switchAt = 1) }
        assertFailsWith<IllegalArgumentException> { RebindingTransport(a, network.attach(), switchAt = -1) }
    }
}
