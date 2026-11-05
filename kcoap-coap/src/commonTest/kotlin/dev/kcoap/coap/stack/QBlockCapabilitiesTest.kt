package dev.kcoap.coap.stack

import dev.kcoap.coap.stack.QBlockCapabilities.Capability
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** What an endpoint keeps about its peers' Q-Block support (RFC 9177 §4.1), on its own. */
class QBlockCapabilitiesTest {
    private fun peer(port: Int) = Endpoint.Udp("192.0.2.1", port)

    @Test
    fun anEntryLastsUntilItsTimePerPeerAndProtection() = runTest {
        val capabilities = QBlockCapabilities()
        capabilities.put(peer(1), isProtected = false, Capability.SUPPORTED, until = 10.seconds, now = 0.seconds)

        assertEquals(Capability.SUPPORTED, capabilities.get(peer(1), isProtected = false, now = 9.seconds))
        assertNull(capabilities.get(peer(1), isProtected = true, now = 9.seconds), "kept per protection")
        assertNull(capabilities.get(peer(1), isProtected = false, now = 10.seconds), "expired")
        assertEquals(0, capabilities.size(), "an expired entry is removed when it is looked up")
    }

    @Test
    fun theOldestEntriesGoBeyondTheBoundAndExpiredOnesOnEveryRecord() = runTest {
        val capabilities = QBlockCapabilities(maxEntries = 2)
        capabilities.put(peer(1), false, Capability.SUPPORTED, until = 100.seconds, now = 0.seconds)
        capabilities.put(peer(2), false, Capability.UNSUPPORTED, until = 5.seconds, now = 0.seconds)
        capabilities.put(peer(3), false, Capability.UNKNOWN, until = 100.seconds, now = 1.seconds)

        assertNull(capabilities.get(peer(1), false, now = 1.seconds), "the oldest went")
        assertEquals(Capability.UNKNOWN, capabilities.get(peer(3), false, now = 1.seconds))
        capabilities.put(peer(4), false, Capability.SUPPORTED, until = 100.seconds, now = 6.seconds)
        assertEquals(2, capabilities.size(), "peer 2 expired, so nothing else had to go")
        assertEquals(Capability.UNKNOWN, capabilities.get(peer(3), false, now = 6.seconds))

        assertTrue(capabilities.invalidate(peer(3)))
        assertFalse(capabilities.invalidate(peer(3)))
        assertFailsWith<IllegalArgumentException> { QBlockCapabilities(maxEntries = 0) }
    }
}
