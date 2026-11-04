package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.OptionFormat
import dev.kcoap.coap.SignalingOptionDefinition
import dev.kcoap.coap.SignalingOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SignalTest {
    private fun message(code: CoapCode, vararg options: CoapOption, payload: String = "") = CoapMessage.build {
        this.code = code
        options { options.forEach { add(it) } }
        payload(payload)
    }

    private fun known(message: CoapMessage): Signal = assertIs<Signal.Parsed.Known>(Signal.parse(message)).signal

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

    @Test
    fun signalsRoundTripThroughMessages() {
        val csm = assertIs<Signal.Csm>(known(Signal.Csm(4096, true).toMessage()))
        assertEquals(4096, csm.maxMessageSize)
        assertTrue(csm.blockWiseTransfer)
        assertTrue(assertIs<Signal.Ping>(known(Signal.Ping(custody = true).toMessage())).custody)
        assertFalse(assertIs<Signal.Pong>(known(Signal.Pong(custody = false).toMessage())).custody)
        val release = assertIs<Signal.Release>(known(Signal.Release(listOf("a:1", "b"), 30.seconds, "bye").toMessage()))
        assertEquals(listOf("a:1", "b"), release.alternativeAddresses)
        assertEquals(30.seconds, release.holdOff)
        assertEquals("bye", release.diagnostic)
        val abort = assertIs<Signal.Abort>(known(Signal.Abort(2, "bad").toMessage()))
        assertEquals(2, abort.badCsmOption)
        assertEquals("bad", abort.diagnostic)
        val bare = assertIs<Signal.Abort>(known(Signal.Abort().toMessage()))
        assertNull(bare.badCsmOption)
        assertNull(bare.diagnostic)
    }

    /** RFC 7252 §5.4.5 (by RFC 8323 §5.2): occurrences of a non-repeatable option after the first are ignored. */
    @Test
    fun repeatedNonRepeatableOptionsKeepTheFirst() {
        val csm = assertIs<Signal.Csm>(known(message(CoapCode.CSM, CoapOption.uint(2, 2000), CoapOption.uint(2, 3000))))
        assertEquals(2000, csm.maxMessageSize)
    }

    /** RFC 7252 §5.4.3 (by RFC 8323 §5.2): an elective option of the wrong length is ignored outside a CSM. */
    @Test
    fun wrongLengthsAreIgnoredOutsideACsm() {
        val release = assertIs<Signal.Release>(known(message(CoapCode.RELEASE, CoapOption.uint(4, 0x01000000), CoapOption.string(2, ""))))
        assertNull(release.holdOff, "Hold-Off has at most 3 bytes")
        assertTrue(release.alternativeAddresses.isEmpty(), "Alternative-Address has at least 1 byte")
        assertFalse(assertIs<Signal.Ping>(known(message(CoapCode.PING, CoapOption(2, byteArrayOf(1))))).custody)
        val invalid = assertIs<Signal.Parsed.Invalid>(Signal.parse(message(CoapCode.CSM, CoapOption(4, byteArrayOf(1)))))
        assertEquals(4, invalid.badCsmOption)
    }

    @Test
    fun unknownCriticalOptionsAreInvalidAndUnknownCodesUnknown() {
        val ping = assertIs<Signal.Parsed.Invalid>(Signal.parse(message(CoapCode.PING, CoapOption.empty(5))))
        assertNull(ping.badCsmOption, "Bad-CSM-Option names CSM options only")
        assertEquals("Unknown critical option 5 in 7.02 Ping", ping.reason)
        assertSame(Signal.Parsed.UnknownCode, Signal.parse(message(CoapCode.of(7, 0))))
        assertSame(Signal.Parsed.UnknownCode, Signal.parse(message(CoapCode.of(7, 31))))
    }

    @Test
    fun signalingOptionsPrintByTheirCodesRegistry() {
        assertEquals("7.01 CSM [Max-Message-Size: 1152, Block-Wise-Transfer]", Signal.Csm(1152, true).toMessage().toString())
        assertEquals("7.04 Release [6: 0a]", message(CoapCode.RELEASE, CoapOption(6, byteArrayOf(10))).toString())
        val long = message(CoapCode.ABORT, CoapOption(2, ByteArray(8) { 1 }))
        assertEquals("7.05 Abort [Bad-CSM-Option: 0101010101010101]", long.toString(), "too long for a number: hex")
        assertEquals("7.04 Release [Alternative-Address: h:1]", message(CoapCode.RELEASE, CoapOption.string(2, "h:1")).toString())
    }
}
