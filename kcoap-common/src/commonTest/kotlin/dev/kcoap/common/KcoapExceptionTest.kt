package dev.kcoap.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class KcoapExceptionTest {
    private class ProtocolFailure(message: String, cause: Throwable? = null) : KcoapException(message, cause)

    @Test
    fun aSubtypeKeepsItsMessageAndCause() {
        val cause = IllegalStateException("below")
        val failure = ProtocolFailure("the peer did not answer", cause)

        assertEquals("the peer did not answer", failure.message)
        assertSame(cause, failure.cause)
        assertIs<Exception>(failure)
    }

    @Test
    fun theCauseIsOptional() {
        assertNull(ProtocolFailure("no cause").cause)
    }

    @Test
    fun oneCatchHandlesEverySubtype() {
        val caught = try {
            throw ProtocolFailure("any protocol")
        } catch (e: KcoapException) {
            e
        }
        assertIs<ProtocolFailure>(caught)
    }
}
