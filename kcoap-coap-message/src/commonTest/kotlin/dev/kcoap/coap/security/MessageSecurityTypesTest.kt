package dev.kcoap.coap.security

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The values of the message-security SPI on their own. How the stack acts on them is tested in kcoap-coap
 * (`MessageSecurityTest`, `AccessPolicyTest`, `UnverifiedResponseTest`).
 */
class MessageSecurityTypesTest {
    @Test
    fun rfc8613Section8_2AFailedVerificationIsAnUnprotectedBadRequestByDefault() {
        val failure = MessageSecurityException("Decryption failed")
        assertEquals("Decryption failed", failure.message)
        assertEquals(CoapCode.BAD_REQUEST, failure.responseCode)
        assertNull(failure.diagnostic)
        assertNull(failure.errorPayload)
        assertNull(failure.contentFormat)
        assertNull(failure.cause)
    }

    @Test
    fun aStructuredErrorBodyIsCopiedInAndOut() {
        val cause = IllegalStateException("no context")
        val body = byteArrayOf(1, 2)
        val failure = MessageSecurityException("refused", CoapCode.UNAUTHORIZED, "Security context not found", cause, body, 64)
        body[0] = 9
        assertContentEquals(byteArrayOf(1, 2), failure.errorPayload, "a later change of the caller's array does not reach it")
        failure.errorPayload!![1] = 9
        assertContentEquals(byteArrayOf(1, 2), failure.errorPayload, "a change of a returned array does not reach it")
        assertEquals(CoapCode.UNAUTHORIZED, failure.responseCode)
        assertEquals("Security context not found", failure.diagnostic)
        assertEquals(64, failure.contentFormat)
        assertSame(cause, failure.cause)
    }

    @Test
    fun anUnprotectedRequestRequiresNoChallengeUnlessItSaysSo() {
        val request = object : UnprotectedRequest {
            override val message = CoapMessage.request(CoapCode.GET)
            override val identity = object : SecurityIdentity {
                override val description = "test peer"
            }

            override suspend fun protectResponse(response: CoapMessage) = response
        }
        assertNull(request.challenge)
    }

    @Test
    fun responseVerificationsShowTheCodeAndTheReasonButNoPayload() {
        val content = CoapMessage.build {
            code = CoapCode.CONTENT
            payload("secret")
        }
        val verified = ResponseVerification.Verified(content)
        assertSame(content, verified.message)
        assertEquals("Verified(2.05 Content)", verified.toString())

        val unauthorized = CoapMessage.build { code = CoapCode.UNAUTHORIZED }
        val unverified = ResponseVerification.Unverified(unauthorized, "unprotected error")
        assertSame(unauthorized, unverified.message)
        assertEquals("unprotected error", unverified.reason)
        assertEquals("Unverified(4.01 Unauthorized: unprotected error)", unverified.toString())
    }
}
