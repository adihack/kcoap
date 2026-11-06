package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IpLiteralTest {
    @Test
    fun rfc5952Section4CanonicalIpv6Text() {
        assertEquals("::1", IpLiteral.canonical("0:0:0:0:0:0:0:1"))
        assertEquals("2001:db8::1", IpLiteral.canonical("2001:0DB8:0000:0000:0000:0000:0000:0001"))
        // §4.2.2: a single zero group is not compressed; §4.2.3: the longest run, the first on a tie.
        assertEquals("2001:db8:0:1:1:1:1:1", IpLiteral.canonical("2001:db8::1:1:1:1:1"))
        assertEquals("2001:0:0:1::1", IpLiteral.canonical("2001:0:0:1:0:0:0:1"))
        assertEquals("2001:db8::1:0:0:1", IpLiteral.canonical("2001:db8:0:0:1:0:0:1"))
        assertEquals("::", IpLiteral.canonical("0::0"))
        assertEquals("fe80::", IpLiteral.canonical("fe80::"))
        assertEquals("1:2:3:4:5:6:7:8", IpLiteral.canonical("1:2:3:4:5:6:7:8"))
        assertEquals("::102:304", IpLiteral.canonical("::1.2.3.4"))
        // IPv4-mapped addresses are written as IPv4, as dual-stack sockets report them.
        assertEquals("192.0.2.1", IpLiteral.canonical("::ffff:192.0.2.1"))
        assertEquals("192.0.2.1", IpLiteral.canonical("192.0.2.1"))
    }

    @Test
    fun invalidLiteralsAreRejected() {
        for (text in listOf("", "1.2.3", "1.2.3.256", "1.2.3.x", "1.2.3.4444", "1::2::3", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9",
            "12345::", "g::", ":::", "1:2:3:4:5:6:7:8::", "::1.2.3", "example.com")) {
            assertNull(IpLiteral.canonical(text), text)
        }
        assertFailsWith<IllegalArgumentException> { IpLiteral.format(ByteArray(5)) }
    }

    /**
     * RFC 3986 §3.2.2: a dec-octet has no leading zero and an h16 is hex digits only, so such text is a name. RFC 3986
     * §7.4: resolvers may read `010.0.0.1` as octal, an address other than `10.0.0.1`.
     */
    @Test
    fun rfc3986Section3_2_2LiteralsHaveNoSignsOrLeadingZeros() {
        for (text in listOf("::-1", "::+1", "+1::", "-0::1", "1:2:3:4:5:6:7:+8", "010.0.0.1", "10.0.0.01", "1.2.3.00",
            "::ffff:010.0.0.1", "::1.02.3.4")) {
            assertNull(IpLiteral.parse(text), text)
            assertNull(IpLiteral.canonical(text), text)
        }
        assertEquals("0.0.0.0", IpLiteral.canonical("0.0.0.0"))
        assertEquals("10.0.0.100", IpLiteral.canonical("10.0.0.100"))
        assertEquals("2001:db8::1", IpLiteral.canonical("2001:0db8::0001"), "an h16 may have leading zeros")
        assertEquals("::abcd", IpLiteral.canonical("::ABCD"))
    }
}
