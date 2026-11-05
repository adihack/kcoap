package dev.kcoap.testkit

import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionEndedException
import dev.kcoap.transport.SessionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A stand-in for a DTLS layer on an [InMemoryTransport], for tests of what a CoAP endpoint does with secure
 * sessions: every peer appears as an [Endpoint.Secure], one session per address, authenticated as [identity],
 * and nothing is encrypted. [endAll] ends every session at once, as a DTLS layer reports sessions that closed
 * ([DatagramEvent.SessionEnded], RFC 7252 §9.1.1); a send to an ended session throws [SessionEndedException],
 * and the next datagram to or from the address opens a new session. Safe to use from any coroutine.
 */
public class InMemorySessions(
    private val transport: InMemoryTransport,
    private val identity: PeerIdentity = PeerIdentity.None,
) : DatagramTransport {
    private val lock = Mutex()
    private val sessions = HashMap<Endpoint, Endpoint.Secure>()
    private var nextSession = 1L

    /** The address this side is attached at. */
    public val localEndpoint: Endpoint.Udp get() = transport.localEndpoint

    override val maxDatagramSize: Int get() = transport.maxDatagramSize

    /** The session to [address], opened if there is none. */
    public suspend fun sessionTo(address: Endpoint.Udp): Endpoint.Secure = lock.withLock { open(address) }

    /** @throws SessionEndedException when [to] is a session that ended. */
    override suspend fun send(to: Endpoint, datagram: ByteArray) {
        require(to is Endpoint.Secure) { "$to is not reached through a session of $this" }
        val current = lock.withLock { sessions[to.via] }
        if (current != to) throw SessionEndedException(to, SessionEndReason.CLOSED_LOCALLY)
        transport.send(to.via, datagram)
    }

    override suspend fun receive(): DatagramEvent = when (val event = transport.receive()) {
        is DatagramEvent.Received -> DatagramEvent.Received(lock.withLock { open(event.from) }, event.datagram)
        is DatagramEvent.SessionEnded -> event
    }

    /** Ends every session for [reason]; the receiver hears of each one as a [DatagramEvent.SessionEnded]. */
    public suspend fun endAll(reason: SessionEndReason = SessionEndReason.CLOSED_BY_PEER) {
        val ended = lock.withLock { sessions.values.toList().also { sessions.clear() } }
        ended.forEach { transport.inject(DatagramEvent.SessionEnded(it, reason)) }
    }

    override suspend fun close() {
        transport.close()
    }

    /** Under [lock]. */
    private fun open(address: Endpoint): Endpoint.Secure =
        sessions.getOrPut(address) { Endpoint.Secure(SessionId(nextSession++), 0, address, identity) }

    override fun toString(): String = "InMemorySessions($localEndpoint)"
}
