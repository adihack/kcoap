package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.ReliableFixture
import dev.kcoap.coap.StackFixture
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * The PeerLink seam (ADR 0005) on its own: what each link reports about itself, the checks a datagram link
 * makes, and the notification policies. The exchanges over both links run in
 * [dev.kcoap.coap.ExchangeConformanceTest].
 */
class PeerLinkTest {
    private fun get(): CoapMessage = CoapMessage.request(CoapCode.GET) { options { uriPath("x") } }

    @Test
    fun aDatagramLinkReportsTheTransportsLimitsAndRfc7252Capabilities() = runTest {
        val fixture = StackFixture(this)
        val endpoint = fixture.endpoint(maxDatagramSize = 512)
        val peer = Endpoint.Udp("127.0.0.1", 9)
        val session = Endpoint.Secure(SessionId(1), 0, peer, PeerIdentity.PreSharedKey(byteArrayOf(1)))

        val link = endpoint.linkFor(peer)

        assertEquals(512, link.limits.maxMessageSize)
        assertFalse(link.limits.allowsBert)
        assertSame(LinkCapabilities.DATAGRAM, link.capabilities)
        assertTrue(link.capabilities.hasReset && link.capabilities.hasMessageTypes)
        assertFalse(link.capabilities.isOrdered || link.capabilities.verifiesAddress)
        assertEquals("datagram", link.capabilities.toString())
        assertEquals(PeerIdentity.None, link.peerIdentity)
        assertEquals(PeerIdentity.PreSharedKey(byteArrayOf(1)), endpoint.datagramLinkTo(session).peerIdentity)
        assertEquals(endpoint.datagramLinkTo(peer), link, "one peer, one link")
        assertEquals(link.hashCode(), endpoint.datagramLinkTo(peer).hashCode())
        assertNotEquals(endpoint.datagramLinkTo(Endpoint.Udp("127.0.0.1", 10)), link)
        assertNotEquals(fixture.endpoint().datagramLinkTo(peer), link, "another endpoint's transport")
        assertNotEquals<Any>(peer, link)
        assertTrue("$peer" in link.toString())
        assertIs<NotificationPolicy.Interspersed>(link.notificationPolicy(Duration.ZERO, isAuthenticated = false, 24.seconds))
    }

    @Test
    fun aDatagramLinkChecksABodyLeaseBeforeAnythingIsRegistered() = runTest {
        val fixture = StackFixture(this)
        val endpoint = fixture.endpoint()
        val peer = Endpoint.Udp("127.0.0.1", 9)
        val link = endpoint.linkFor(peer)
        val lease = endpoint.leaseBody(peer, 100)

        link.checkPacing(confirmable = true, Pacing.PerMessage)
        link.checkPacing(confirmable = false, Pacing.Body(lease))
        assertFailsWith<IllegalArgumentException> { link.checkPacing(confirmable = true, Pacing.Body(lease)) }
        assertFailsWith<IllegalArgumentException> { endpoint.linkFor(Endpoint.Udp("127.0.0.1", 10)).checkPacing(false, Pacing.Body(lease)) }

        // A delivery of another link is nothing this one settles or supersedes.
        link.settle(Delivery.Written)
        assertFalse(link.supersede(Delivery.Written, get()))
        link.exchangeClosed("00")
        endpoint.releaseBody(lease)
    }

    @Test
    fun aConnectionLinkReportsThePeersSettingsAndRfc8323Capabilities() = runTest {
        val fixture = ReliableFixture(this)
        fixture.server { resource("x") { get { dev.kcoap.coap.CoapResponse.content("x") } } }
        val endpoint = fixture.endpoint()
        val peer = Endpoint.Tcp("127.0.0.1", 5683)

        val link = endpoint.linkFor(peer)

        assertIs<ConnectionLink>(link)
        assertSame(link, endpoint.linkFor(peer), "one connection per peer")
        assertEquals(ReliableTransportConfig.DEFAULT_MAX_MESSAGE_SIZE.toInt(), link.limits.maxMessageSize)
        assertTrue(link.limits.allowsBert, "both CSMs indicated BERT")
        assertSame(LinkCapabilities.CONNECTION, link.capabilities)
        assertFalse(link.capabilities.hasReset || link.capabilities.hasMessageTypes)
        assertTrue(link.capabilities.isOrdered && link.capabilities.verifiesAddress)
        assertEquals("connection", link.capabilities.toString())
        assertEquals(PeerIdentity.None, link.peerIdentity)
        assertTrue("$peer" in link.toString())

        // RFC 8323 §2 has no NSTART: nothing to pace, settle or supersede, and no message types to choose.
        link.checkPacing(confirmable = true, Pacing.Body(BodyLease.unpaced(peer, Duration.ZERO)))
        val lease = link.leaseBody(3000, 2.seconds)
        assertFalse(lease.isPaced)
        assertEquals(BodyLease.State.HELD, lease.state)
        assertEquals(2.seconds, lease.nonTimeoutRandom)
        assertEquals(Duration.ZERO, link.leaseBody(3000, null).nonTimeoutRandom)
        link.settle(Delivery.Written)
        assertFalse(link.supersede(Delivery.Written, get()))
        val policy = link.notificationPolicy(Duration.ZERO, isAuthenticated = false, 24.seconds)
        assertSame(NotificationPolicy.Untyped, policy)
        assertTrue(policy.nextIsConfirmable(requested = true, Duration.ZERO))
        assertFalse(policy.nextIsConfirmable(requested = false, 1000.seconds), "nothing interspersed")
        policy.sent(confirmable = false, Duration.ZERO)

        endpoint.close()
        assertEquals(CoapException.Reason.CLOSED, assertFailsWith<CoapException> { link.leaseBody(100, null) }.reason)
    }

    /** RFC 7641 §4.5 and §7: Confirmable notifications interspersed by time and, unauthenticated, by count. */
    @Test
    fun rfc7641Section7TheDatagramPolicyIntersperseConfirmableNotifications() {
        val authenticated = NotificationPolicy.Interspersed(Duration.ZERO, isAuthenticated = true, confirmableInterval = 24.seconds)
        assertTrue(authenticated.nextIsConfirmable(requested = true, 1.seconds), "the resource asks for it")
        repeat(20) {
            assertFalse(authenticated.nextIsConfirmable(requested = false, 1.seconds))
            authenticated.sent(confirmable = false, 1.seconds)
        }
        assertTrue(authenticated.nextIsConfirmable(requested = false, 24.seconds), "once per interval")
        authenticated.sent(confirmable = true, 24.seconds)
        assertFalse(authenticated.nextIsConfirmable(requested = false, 25.seconds))

        val unauthenticated = NotificationPolicy.Interspersed(Duration.ZERO, isAuthenticated = false, confirmableInterval = 24.seconds)
        val types = List(10) {
            unauthenticated.nextIsConfirmable(requested = false, 1.seconds).also { unauthenticated.sent(it, 1.seconds) }
        }
        assertEquals(List(8) { false } + true + false, types, "every ninth is Confirmable")
    }

    @Test
    fun deliveriesAndTheirOutcomesSayWhatTheyAre() {
        val written = Delivery.Written
        assertFalse(written.isOutstanding || written.isConfirmable || written.isSuperseded)
        assertTrue(written.isDone)
        assertEquals("Delivered(piggybacked=null)", DeliveryOutcome.Delivered(null).toString())
        assertEquals("Delivered(piggybacked=2.05 Content)", DeliveryOutcome.Delivered(CoapMessage.response(get(), CoapCode.CONTENT)).toString())
        assertEquals("Failed(CLOSED)", DeliveryOutcome.Failed(CoapException("closed", CoapException.Reason.CLOSED)).toString())
        assertNull((DeliveryOutcome.Released as? DeliveryOutcome.Delivered)?.piggybacked)
    }
}
