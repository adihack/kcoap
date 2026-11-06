package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class EndpointTest {
    private val device = Endpoint.Udp("192.0.2.1", 5684)
    private val rebound = Endpoint.Udp("198.51.100.7", 40123)
    private val psk = PeerIdentity.PreSharedKey("client-1".encodeToByteArray())

    private fun secure(session: Long, epoch: Int, via: Endpoint = device) =
        Endpoint.Secure(SessionId(session), epoch, via, psk)

    @Test
    fun sameSessionAndEpochMatch() {
        assertEquals(secure(1, 1), secure(1, 1))
        assertEquals(secure(1, 1).hashCode(), secure(1, 1).hashCode())
    }

    /** RFC 9146: with a Connection ID the address may change while the session stays the same. */
    @Test
    fun addressChangeKeepsTheMatch() {
        assertEquals(secure(1, 1, via = device), secure(1, 1, via = rebound))
        assertEquals(secure(1, 1, via = device).hashCode(), secure(1, 1, via = rebound).hashCode())
    }

    /** RFC 7252 §9.1.2: the epoch MUST be the same. */
    @Test
    fun differentEpochDoesNotMatch() {
        assertNotEquals(secure(1, 1), secure(1, 2))
    }

    /** RFC 7252 §9.1.2: the DTLS session MUST be the same. */
    @Test
    fun differentSessionDoesNotMatch() {
        assertNotEquals(secure(1, 1), secure(2, 1))
    }

    /** RFC 7252 §9.1.2: a NoSec response to a DTLS request does not match. */
    @Test
    fun plaintextNeverMatchesSecure() {
        assertNotEquals<Endpoint>(device, secure(1, 1))
        assertNotEquals<Endpoint>(secure(1, 1), device)
    }

    @Test
    fun rejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> { Endpoint.Udp("192.0.2.1", 65536) }
        assertFailsWith<IllegalArgumentException> { Endpoint.Tcp("192.0.2.1", -1) }
        assertFailsWith<IllegalArgumentException> { secure(1, 0x10000) }
        assertFailsWith<IllegalArgumentException> { secure(1, 1, via = secure(2, 1)) }
    }
}
