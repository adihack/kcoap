package dev.kcoap.coap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalingOptionsTest {
    /** RFC 8323 §5.2, §11.2: option numbers are per signaling code, apart from the request option registry. */
    @Test
    fun rfc8323Section11_2OptionNumbersArePerCode() {
        assertEquals("Max-Message-Size", SignalingOptions.byNumber(CoapCode.CSM, 2)?.name)
        assertEquals("Custody", SignalingOptions.byNumber(CoapCode.PING, 2)?.name)
        assertEquals("Custody", SignalingOptions.byNumber(CoapCode.PONG, 2)?.name)
        assertEquals("Alternative-Address", SignalingOptions.byNumber(CoapCode.RELEASE, 2)?.name)
        assertEquals("Bad-CSM-Option", SignalingOptions.byNumber(CoapCode.ABORT, 2)?.name)
        assertEquals("Block-Wise-Transfer", SignalingOptions.byNumber(CoapCode.CSM, 4)?.name)
        assertEquals("Hold-Off", SignalingOptions.byNumber(CoapCode.RELEASE, 4)?.name)
        assertNull(SignalingOptions.byNumber(CoapCode.PING, 4))
        assertTrue(SignalingOptions.all.none { it.isCritical }, "every registered signaling option is elective")
        assertEquals("Max-Message-Size(2)", SignalingOptions.MAX_MESSAGE_SIZE.toString())
        assertEquals(1152, SignalingOptions.BASE_MAX_MESSAGE_SIZE)
        assertFailsWith<IllegalArgumentException> {
            SignalingOptionDefinition(setOf(CoapCode.GET), 2, "x", OptionFormat.EMPTY, 0, 0, false)
        }
        assertTrue(SignalingOptionDefinition(setOf(CoapCode.CSM), 7, "x", OptionFormat.OPAQUE, 0, 1, false).isCritical)
    }

    /** RFC 8323 §5.2: a signaling message prints its options by its code's registry, not by RFC 7252's. */
    @Test
    fun signalingMessagesPrintTheirOptionsByTheirCodesRegistry() {
        assertEquals("7.02 Ping [Custody]", signal(CoapCode.PING, CoapOption.empty(2)).toString())
        assertEquals("7.04 Release [Hold-Off: 30]", signal(CoapCode.RELEASE, CoapOption.uint(4, 30)).toString())
        assertEquals("7.04 Release [Alternative-Address: h:1]", signal(CoapCode.RELEASE, CoapOption.string(2, "h:1")).toString())
        assertEquals("7.04 Release [6: 0a]", signal(CoapCode.RELEASE, CoapOption(6, byteArrayOf(10))).toString(), "unregistered: hex")
        val long = signal(CoapCode.ABORT, CoapOption(2, ByteArray(8) { 1 }))
        assertEquals("7.05 Abort [Bad-CSM-Option: 0101010101010101]", long.toString(), "too long for a number: hex")
    }

    private fun signal(code: CoapCode, option: CoapOption) = CoapMessage.build {
        this.code = code
        options { add(option) }
    }
}
