package dev.kcoap.coap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RFC 8516: 4.29 Too Many Requests, with Max-Age as the time after which a similar request may come again. */
class TooManyRequestsTest {
    @Test
    fun rfc8516Section3TheServerSetsMaxAgeToTheRetryAfterInWholeSeconds() {
        val response = CoapResponse.tooManyRequests(30.seconds, "slow down")
        assertEquals(CoapCode.TOO_MANY_REQUESTS, response.code)
        assertEquals("4.29 Too Many Requests", response.code.toString())
        assertEquals(30L, response.options.maxAge)
        assertEquals("slow down", response.payloadAsText(), "the diagnostic payload (RFC 7252 §5.5.2)")
        assertEquals(0, CoapResponse.tooManyRequests(Duration.ZERO).payloadSize)
        assertEquals(0L, CoapResponse.tooManyRequests(Duration.ZERO).options.maxAge)
    }

    @Test
    fun rfc8516Section4AFractionOfASecondIsRoundedUpSoTheClientNeverComesBackEarly() {
        assertEquals(2L, CoapResponse.tooManyRequests(1500.milliseconds).options.maxAge)
        assertEquals(1L, CoapResponse.tooManyRequests(1.milliseconds).options.maxAge)
    }

    @Test
    fun rfc7252Section5_10_5MaxAgeIsAtMostFourBytes() {
        assertEquals(0xFFFF_FFFFL, CoapResponse.tooManyRequests(Duration.INFINITE).options.maxAge)
        assertEquals(0xFFFF_FFFFL, CoapResponse.tooManyRequests(0x1_0000_0000L.seconds).options.maxAge)
        assertEquals(0xFFFF_FFFFL, CoapResponse.tooManyRequests((0xFFFF_FFFFL.seconds - 1.milliseconds)).options.maxAge)
    }

    @Test
    fun aNegativeRetryAfterIsRefused() {
        assertFailsWith<IllegalArgumentException> { CoapResponse.tooManyRequests((-1).seconds) }
    }

    @Test
    fun rfc8516Section4TheRetryAfterIsMaxAgeOrSixtySeconds() {
        assertEquals(30.seconds, CoapResponse.tooManyRequests(30.seconds).retryAfter)
        assertEquals(60.seconds, CoapResponse(CoapCode.TOO_MANY_REQUESTS).retryAfter, "Max-Age's default (RFC 7252 §5.10.5)")
        val unavailable = CoapResponse(CoapCode.SERVICE_UNAVAILABLE, OptionSet.build { maxAge(2) })
        assertEquals(2.seconds, unavailable.retryAfter, "5.03 with Max-Age (RFC 7252 §5.9.3.4)")
        assertNull(CoapResponse.content("x").retryAfter)
        assertNull(CoapResponse(CoapCode.NOT_FOUND).retryAfter)
    }
}
