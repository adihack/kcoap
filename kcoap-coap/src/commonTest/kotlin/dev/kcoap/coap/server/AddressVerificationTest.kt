package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * The address-verification state of RFC 9175 §2.4 item 3 on its own: which responses need a verified
 * address, Echo values bound to one address, lifetimes, bounds and the responses kept for a repeat.
 */
class AddressVerificationTest {
    private val a = Endpoint.Udp("192.0.2.1", 5683)
    private val b = Endpoint.Udp("192.0.2.2", 5683)
    private val events = mutableListOf<EndpointEvent>()

    /** Echo values 1, 2, 3, ... so a test can tell them apart. */
    private var counter = 0
    private val counting: (Int) -> ByteArray = { length -> ByteArray(length) { (++counter).toByte() } }

    private fun verification(
        config: CoapServerConfig = CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES),
        challengeLifetime: Duration = 247.seconds,
        random: ((Int) -> ByteArray)? = counting,
    ) = if (random == null) {
        AddressVerification(config, challengeLifetime, { events += it })
    } else {
        AddressVerification(config, challengeLifetime, { events += it }, random)
    }

    private fun limits(limit: Limit): List<Endpoint> = events.filterIsInstance<EndpointEvent.LimitReached>().filter { it.limit == limit }.map { it.peer }

    private fun post(payload: String, echo: ByteArray? = null) = CoapMessage.request(CoapCode.POST) {
        options {
            uriPath("r")
            echo?.let { echo(it) }
        }
        payload(payload)
    }

    @Test
    fun rfc9175Section2_4ResponsesBeyondTheFactorNeedAVerifiedAddressUnderAllResponses() {
        val all = verification()
        assertFalse(all.exceedsLimit(requestSize = 10, responseSize = 30, isBurst = false), "3x is allowed")
        assertTrue(all.exceedsLimit(requestSize = 10, responseSize = 31, isBurst = false))
        assertTrue(all.exceedsLimit(requestSize = 10, responseSize = 31, isBurst = true))
        assertTrue(all.appliesTo(isBurst = false))

        val factor5 = verification(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, amplificationFactor = 5))
        assertFalse(factor5.exceedsLimit(requestSize = 10, responseSize = 50, isBurst = false))
        assertTrue(factor5.exceedsLimit(requestSize = 10, responseSize = 51, isBurst = false))
    }

    @Test
    fun theDefaultPolicyCoversOnlyQBlockBursts() {
        val default = verification(CoapServerConfig())
        assertEquals(AmplificationPolicy.Q_BLOCK_BURSTS, CoapServerConfig().amplification)
        assertEquals(3, CoapServerConfig().amplificationFactor)
        assertFalse(default.appliesTo(isBurst = false))
        assertTrue(default.appliesTo(isBurst = true))
        assertFalse(default.exceedsLimit(requestSize = 10, responseSize = 10_000, isBurst = false), "single responses go out as before")
        assertTrue(default.exceedsLimit(requestSize = 10, responseSize = 31, isBurst = true))
        assertFalse(default.exceedsLimit(requestSize = 30, responseSize = 90, isBurst = true), "RFC 9177: the probe's one block stays below 3x")
    }

    @Test
    fun offNeverNeedsAVerifiedAddress() {
        val off = verification(CoapServerConfig(amplification = AmplificationPolicy.OFF))
        assertFalse(off.appliesTo(isBurst = true))
        assertFalse(off.exceedsLimit(requestSize = 1, responseSize = 10_000, isBurst = true))
        assertFalse(off.exceedsLimit(requestSize = 1, responseSize = 10_000, isBurst = false))
    }

    @Test
    fun rfc9175Section2_3AnEchoValueVerifiesOnlyTheAddressItWasSentTo() = runTest {
        val verification = verification()
        val echo = verification.challenge(a, 0.seconds)
        assertEquals(AddressVerification.ECHO_LENGTH, echo.size)

        assertFalse(verification.verify(b, echo, 1.seconds), "another address cannot use it")
        assertFalse(verification.verify(a, null, 1.seconds), "no Echo, no proof")
        assertFalse(verification.verify(a, echo.copyOf().also { it[0] = 99 }, 1.seconds), "a value the server did not send")
        assertFalse(verification.verify(a, echo.copyOf(4), 1.seconds), "a truncated value")
        assertTrue(verification.verify(a, echo, 1.seconds))
        assertTrue(verification.verify(a, null, 2.seconds), "the address stays verified")
        assertFalse(verification.verify(b, null, 2.seconds))
        assertEquals(1 to 0, verification.sizes(), "the challenge is used up")
    }

    @Test
    fun rfc9175Section2_3ChallengesToOneAddressRepeatTheOutstandingValue() = runTest {
        val verification = verification()
        val first = verification.challenge(a, 0.seconds)
        val second = verification.challenge(a, 1.seconds)
        val other = verification.challenge(b, 1.seconds)

        assertContentEquals(first, second, "concurrent requests of one client all succeed with the first answer")
        assertFalse(first.contentEquals(other))
        assertEquals(listOf(a, a, b), limits(Limit.AMPLIFICATION_FACTOR), "every held-back response is reported")
    }

    @Test
    fun anOutstandingChallengeExpiresAfterItsLifetime() = runTest {
        val verification = verification(challengeLifetime = 10.seconds)
        val stale = verification.challenge(a, 0.seconds)

        assertFalse(verification.verify(a, stale, 10.seconds))
        val fresh = verification.challenge(a, 10.seconds)
        assertFalse(stale.contentEquals(fresh), "a new value once the old one expired")
        assertTrue(verification.verify(a, fresh, 11.seconds))
    }

    @Test
    fun aVerifiedAddressExpiresAfterItsLifetimeHoweverOftenItIsUsed() = runTest {
        val verification = verification(
            CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, verifiedAddressLifetime = 10.seconds),
        )
        assertTrue(verification.verify(a, verification.challenge(a, 0.seconds), 0.seconds))

        assertTrue(verification.verify(a, null, 5.seconds))
        assertTrue(verification.verify(a, null, 9.seconds), "using it does not extend it")
        assertFalse(verification.verify(a, null, 10.seconds), "a NAT may have given the address to another host")
        assertEquals(0 to 0, verification.sizes())
    }

    @Test
    fun aFullChallengeListDropsTheOldestChallengeAndReportsIt() = runTest {
        val verification = verification(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, maxVerifiedAddresses = 1))
        val echoA = verification.challenge(a, 0.seconds)
        verification.challenge(b, 1.seconds)

        assertEquals(listOf(a), limits(Limit.ADDRESS_CHALLENGES))
        assertFalse(verification.verify(a, echoA, 2.seconds), "the dropped value no longer verifies the address")
        assertEquals(0 to 1, verification.sizes())
    }

    @Test
    fun aFullVerifiedListForgetsTheAddressVerifiedLongestAgoAndReportsIt() = runTest {
        val verification = verification(CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, maxVerifiedAddresses = 1))
        assertTrue(verification.verify(a, verification.challenge(a, 0.seconds), 0.seconds))
        assertTrue(verification.verify(b, verification.challenge(b, 1.seconds), 1.seconds))

        assertEquals(listOf(a), limits(Limit.VERIFIED_ADDRESSES))
        assertFalse(verification.verify(a, null, 2.seconds), "challenged again when it next needs to be verified")
        assertTrue(verification.verify(b, null, 2.seconds))
    }

    @Test
    fun aHeldBackResponseAnswersOnlyTheRepeatOfItsRequestAndOnlyOnce() = runTest {
        val verification = verification()
        val response = CoapResponse.content("kept")
        val echo = verification.challenge(a, 0.seconds, AddressVerification.Withheld(post("body"), response))

        assertNull(verification.takeWithheld(b, post("body", echo), 1.seconds), "another address")
        assertNull(verification.takeWithheld(a, post("other", echo), 1.seconds), "another payload")
        assertNull(verification.takeWithheld(a, CoapMessage.request(CoapCode.PUT) { options { uriPath("r") } }, 1.seconds), "another method")
        assertEquals(response, verification.takeWithheld(a, post("body", echo), 1.seconds))
        assertNull(verification.takeWithheld(a, post("body", echo), 2.seconds), "handed out once")
    }

    @Test
    fun rfc9175Section2_4EveryRequestOfOneAddressKeepsItsOwnHeldBackResponse() = runTest {
        val verification = verification()
        val echo = verification.challenge(a, 0.seconds, AddressVerification.Withheld(post("first"), CoapResponse.content("1")))
        verification.challenge(a, 1.seconds, AddressVerification.Withheld(post("second"), CoapResponse.content("2")))

        assertEquals("2", verification.takeWithheld(a, post("second", echo), 2.seconds)?.payloadAsText())
        assertEquals("1", verification.takeWithheld(a, post("first", echo), 2.seconds)?.payloadAsText(), "the second did not replace it")
        assertTrue(limits(Limit.HELD_BACK_RESPONSES).isEmpty())
    }

    @Test
    fun heldBackResponsesExpireAndAreBoundedByMaxConcurrentTransfers() = runTest {
        val verification = verification(
            CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES, maxConcurrentTransfers = 1),
            challengeLifetime = 10.seconds,
        )
        verification.challenge(a, 0.seconds, AddressVerification.Withheld(post("a"), CoapResponse.content("a")))
        verification.challenge(b, 0.seconds, AddressVerification.Withheld(post("b"), CoapResponse.content("b")))
        assertNull(verification.takeWithheld(a, post("a"), 1.seconds), "made room for b")
        assertEquals(listOf(a), limits(Limit.HELD_BACK_RESPONSES), "the eviction is reported with the address that lost its response")

        verification.challenge(a, 20.seconds, AddressVerification.Withheld(post("a"), CoapResponse.content("a")))
        assertNull(verification.takeWithheld(a, post("a"), 30.seconds), "expired with its challenge")
        assertEquals(listOf(a), limits(Limit.HELD_BACK_RESPONSES), "an expiry is not an eviction")
    }

    @Test
    fun onlyPostAndPatchKeepTheirResponseForTheRepeat() {
        assertTrue(AddressVerification.isRepeatSensitive(CoapCode.POST))
        assertTrue(AddressVerification.isRepeatSensitive(CoapCode.PATCH))
        for (code in listOf(CoapCode.GET, CoapCode.FETCH, CoapCode.PUT, CoapCode.DELETE, CoapCode.IPATCH)) {
            assertFalse(AddressVerification.isRepeatSensitive(code), "$code may run again")
        }
    }

    @Test
    fun rfc9175Section5EchoValuesAre64BitsFromTheCsprng() = runTest {
        val verification = verification(random = null)
        val values = List(50) { verification.challenge(Endpoint.Udp("192.0.2.1", 1000 + it), 0.seconds) }

        assertTrue(values.all { it.size == 8 })
        assertEquals(50, values.map { it.toList() }.toSet().size, "no value repeats")
    }

    @Test
    fun badAmplificationSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(amplificationFactor = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(maxVerifiedAddresses = 0) }
        assertFailsWith<IllegalArgumentException> { CoapServerConfig(verifiedAddressLifetime = Duration.ZERO) }
        assertTrue("amplification=ALL_RESPONSES" in CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES).toString())
    }
}
