package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborException
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.codec.CoapFormatException
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.common.KcoapException
import dev.kcoap.crypto.AeadAuthenticationException
import dev.kcoap.crypto.CertificateValidationException
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.oscore.OscoreError
import dev.kcoap.oscore.OscoreException
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionEndedException
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The error model of docs/target-architecture.md §3.6: the exception of every protocol facade is a [KcoapException],
 * the one type that `@Throws` lists for Swift, and the signals of the SPIs between the layers are not, since each
 * layer translates them at its boundary. This module sees every module of the table, so the whole table is here.
 */
class ErrorModelTest {
    private val failure = IllegalStateException("below")

    /** One instance of every row of the §3.6 table, the URI row excepted (it throws IllegalArgumentException). */
    private val protocolExceptions: Map<String, Exception> = mapOf(
        "CoapException" to CoapException("No response", CoapException.Reason.RESPONSE_TIMEOUT, failure),
        "OscoreException" to OscoreException(OscoreError.REPLAY, "seen", failure),
        "EdhocException" to EdhocException(EdhocError.unspecified("Malformed message_2"), cause = failure),
        "EdhocPeerErrorException" to EdhocPeerErrorException(EdhocError.unspecified("no"), retryWith = CipherSuite.SUITE_2),
        "EdhocTransferException" to EdhocTransferException("4.04 Not Found"),
        "CborException" to CborException("Truncated", offset = 3),
        "CertificateFormatException" to CertificateFormatException("Not DER", failure),
    )

    /** The SPI signals of the §3.6 translation table. */
    private val spiSignals: Map<String, Exception> = mapOf(
        "MessageSecurityException" to MessageSecurityException("No OSCORE context"),
        "CoapFormatException" to CoapFormatException("Truncated header"),
        "SessionEndedException" to SessionEndedException(
            Endpoint.Secure(SessionId(1), 0, Endpoint.Udp("127.0.0.1", 9), PeerIdentity.None),
            SessionEndReason.CLOSED_BY_PEER,
        ),
        "AeadAuthenticationException" to AeadAuthenticationException(),
        "UnsupportedAlgorithmException" to UnsupportedAlgorithmException("ChaCha20/Poly1305"),
        "CertificateValidationException" to CertificateValidationException("Untrusted root"),
    )

    @Test
    fun everyProtocolExceptionOfTheErrorModelIsAKcoapException() {
        for ((name, exception) in protocolExceptions) {
            assertIs<KcoapException>(exception, name)
        }
    }

    @Test
    fun theSignalsBetweenTheLayersStayOutsideTheRoot() {
        for ((name, exception) in spiSignals) {
            assertFalse(exception is KcoapException, "$name is translated at its layer's boundary, not a KcoapException")
        }
    }

    @Test
    fun reparentingKeepsMessagesCausesAndProtocolFields() {
        val coap = protocolExceptions.getValue("CoapException") as CoapException
        assertEquals("No response", coap.message)
        assertEquals(failure, coap.cause)
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, coap.reason)

        val oscore = protocolExceptions.getValue("OscoreException") as OscoreException
        assertEquals("Replay detected: seen", oscore.message)
        assertEquals(failure, oscore.cause)
        assertEquals(OscoreError.REPLAY, oscore.error)

        val edhoc = protocolExceptions.getValue("EdhocException") as EdhocException
        assertEquals("Malformed message_2", edhoc.message)
        assertEquals(failure, edhoc.cause)
        assertEquals(EdhocError.UNSPECIFIED_ERROR, edhoc.errorCode)

        val cbor = protocolExceptions.getValue("CborException") as CborException
        assertEquals("Truncated (at byte 3)", cbor.message)
        assertEquals(3, cbor.offset)

        val certificate = protocolExceptions.getValue("CertificateFormatException")
        assertEquals("Not DER", certificate.message)
        assertEquals(failure, certificate.cause)
    }

    @Test
    fun oneCatchHandlesTheFailureOfAnyProtocol() {
        for ((name, exception) in protocolExceptions) {
            val caught = try {
                throw exception
            } catch (e: KcoapException) {
                e
            }
            assertTrue(caught === exception, name)
        }
    }
}
