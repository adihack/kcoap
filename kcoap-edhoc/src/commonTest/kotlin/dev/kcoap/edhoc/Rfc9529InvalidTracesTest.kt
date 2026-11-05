package dev.kcoap.edhoc

import dev.kcoap.cbor.CborWriter
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.vectors.Rfc9529InvalidTraces
import dev.kcoap.edhoc.vectors.Rfc9529Trace2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** RFC 9529 §4: every invalid message is rejected, and none crashes the state machine. */
class Rfc9529InvalidTracesTest {
    private val backend = DefaultCryptoBackend()

    /** A method-3 Responder for [suite], with a static DH credential on its curve. */
    private suspend fun responder(
        suite: CipherSuite,
        keys: EphemeralKeySource = EphemeralKeySource.Random,
    ): EdhocResponder {
        val keyType = Fixtures.keyTypeFor(AuthenticationKind.STATIC_DH, suite)
        val credential = Fixtures.ccsCredential(backend, AuthenticationKind.STATIC_DH, keyType, byteArrayOf(0x32))
        return EdhocResponder(
            EdhocResponderConfig(
                setOf(EdhocMethod.STATIC_DH_STATIC_DH), listOf(suite), listOf(credential),
                TrustedCredentials(backend, emptyList()),
                ephemeralKeys = keys,
            ),
            backend,
        )
    }

    private suspend fun assertMalformedMessage1(hex: String, suite: CipherSuite = CipherSuite.SUITE_2): EdhocException {
        val responder = responder(suite)
        val failure = assertFailsWith<EdhocException>(hex) { responder.processMessage1(hex.hex()) }
        assertEquals(EdhocError.UNSPECIFIED_ERROR, failure.errorCode)
        assertEquals(EdhocPhase.ABORTED, responder.phase)
        return failure
    }

    /** §4.1.1–§4.1.4 and §4.3: encoding errors in message_1. */
    @Test
    fun encodingErrorsInMessage1() = runTest {
        for (
            (hex, reason) in listOf(
                Rfc9529InvalidTraces.SURPLUS_ARRAY_ENCODING_OF_A_MESSAGE to "expected an integer",
                Rfc9529InvalidTraces.SURPLUS_BSTR_ENCODING_OF_THE_CONNECTION_IDENTIFIER to "must be sent as an integer",
                Rfc9529InvalidTraces.SURPLUS_ARRAY_ENCODING_OF_THE_CIPHERSUITE to "invalid SUITES_I",
                Rfc9529InvalidTraces.TEXT_STRING_ENCODING_OF_THE_EPHEMERAL_KEY to "expected a byte string",
                Rfc9529InvalidTraces.UNNECESSARY_LONG_ENCODING to "not deterministically encoded",
                Rfc9529InvalidTraces.INDEFINITE_LENGTH_ARRAY_ENCODING to "Malformed message_1",
            )
        ) {
            val diagnostic = assertMalformedMessage1(hex).error.diagnostic!!
            assertTrue(diagnostic.startsWith("Malformed message_1") && reason in diagnostic, diagnostic)
        }
    }

    /** §4.2.1 (P-384 suite 24 with a 32-byte G_X) and §4.2.6 (31-byte P-256 x-coordinate). */
    @Test
    fun wrongEphemeralKeyLengths() = runTest {
        val p384 =
            assertMalformedMessage1(Rfc9529InvalidTraces.ERROR_IN_THE_LENGTH_OF_THE_EPHEMERAL_KEY, CipherSuite.SUITE_24)
        assertEquals("G_X has the wrong length for cipher suite 24", p384.error.diagnostic)
        assertEquals(
            "G_X has the wrong length for cipher suite 2",
            assertMalformedMessage1(Rfc9529InvalidTraces.ERROR_IN_THE_ELLIPTIC_CURVE_ENCODING).error.diagnostic,
        )
    }

    /**
     * §4.2.2 (x ≥ p), §4.2.3 (x not on P-256) and §4.2.4 (low-order X25519 point): message_1 parses, and the
     * public key validation of RFC 9528 §9.2 fails when message_2 is composed, before anything is sent. The
     * P-256 shares fail in common code ([dev.kcoap.crypto.Sec1]) before Y is generated, the same way on
     * every platform; the X25519 one fails after, and Y is destroyed.
     */
    @Test
    fun invalidPublicKeysAreRejected() = runTest {
        for (
            (hex, suite, spendsY) in listOf(
                Triple(Rfc9529InvalidTraces.ERROR_IN_ELLIPTIC_CURVE_REPRESENTATION, CipherSuite.SUITE_2, false),
                Triple(Rfc9529InvalidTraces.ERROR_IN_THE_ELLIPTIC_CURVE_POINT, CipherSuite.SUITE_2, false),
                Triple(Rfc9529InvalidTraces.CURVE_POINT_OF_THE_LOW_ORDER, CipherSuite.SUITE_0, true),
            )
        ) {
            val keys = mutableListOf<PrivateKey>()
            val recording = EphemeralKeySource { crypto, type -> crypto.generateKeyPair(type).also { keys += it.privateKey } }
            val responder = responder(suite, recording)
            responder.processMessage1(hex.hex())
            val failure = assertFailsWith<EdhocException>(hex) { responder.createMessage2() }
            assertTrue(failure.error.diagnostic!!.startsWith("Invalid public key"), failure.error.diagnostic)
            assertEquals(EdhocPhase.ABORTED, responder.phase)
            assertEquals(if (spendsY) 1 else 0, keys.size, hex)
            for (y in keys) assertFailsWith<IllegalStateException>("Y is destroyed") { (y as SoftwarePrivateKey).encoded() }
        }
    }

    /** §4.1.5: message_2 with two CBOR items. */
    @Test
    fun message2WithTooManyItems() = runTest {
        val initiator = trace2Initiator()
        initiator.createMessage1()
        val failure = assertFailsWith<EdhocException> {
            initiator.processMessage2(Rfc9529InvalidTraces.WRONG_NUMBER_OF_CBOR_SEQUENCE_ELEMENTS.hex())
        }
        assertTrue(failure.error.diagnostic!!.startsWith("Malformed message_2"), failure.error.diagnostic)
    }

    /**
     * §4.1.6, §4.1.7 and §4.2.5: invalid PLAINTEXT_2s, encrypted with the trace's KEYSTREAM_2 derivation so
     * that they reach the Initiator's plaintext checks inside a genuine message_2.
     */
    @Test
    fun invalidPlaintext2() = runTest {
        for (
            (plaintext, reason) in listOf(
                Rfc9529InvalidTraces.SURPLUS_MAP_ENCODING_OF_THE_ID_CRED_FIELD to "must use the compact encoding",
                Rfc9529InvalidTraces.SURPLUS_BSTR_ENCODING_OF_THE_ID_CRED_FIELD to "must be sent as an integer",
                Rfc9529InvalidTraces.ERROR_IN_THE_LENGTH_OF_THE_MAC to "Signature_or_MAC_2 has the wrong length",
            )
        ) {
            val initiator = trace2Initiator()
            initiator.createMessage1()
            val keySchedule = KeySchedule(CipherSuite.SUITE_2, backend)
            val bytes = plaintext.hex()
            val keystream = keySchedule.keystream2(Rfc9529Trace2.PRK_2E.hex(), Rfc9529Trace2.TH_2.hex(), bytes.size)
            val gYCiphertext2 = Rfc9529Trace2.G_Y.hex() + Handshake.xor(bytes, keystream)
            val message2 = CborWriter().writeBytes(gYCiphertext2).toByteArray()
            val failure = assertFailsWith<EdhocException>(plaintext) { initiator.processMessage2(message2) }
            assertTrue(reason in failure.error.diagnostic!!, failure.error.diagnostic)
        }
    }

    /** The Initiator of RFC 9529 §3.3, which produces the trace's message_1 and so its TH_2 and PRK_2e. */
    private suspend fun trace2Initiator(): EdhocInitiator {
        val t = Rfc9529Trace2
        val credential = LocalCredential.staticDiffieHellman(
            AuthenticationCredential.ccs(t.CRED_I.hex()),
            CredentialIdentifier.KeyId(byteArrayOf(0x2b)),
            backend.importKeyPair(KeyType.P256, t.SK_I.hex(), ("04" + t.PK_I_X + t.PK_I_Y).hex()).privateKey,
        )
        return EdhocInitiator(
            EdhocInitiatorConfig(
                EdhocMethod.STATIC_DH_STATIC_DH, listOf(CipherSuite.SUITE_6, CipherSuite.SUITE_2), listOf(credential),
                TrustedCredentials(backend, listOf(AuthenticationCredential.ccs(t.CRED_R.hex()))),
                connectionIdentifiers = ConnectionIdentifierProvider.fixed(cid(t.C_I)),
                ephemeralKeys = sequenceOfKeys(fixedKey(KeyType.P256, t.X, "04" + t.G_X + t.G_X_Y_COORDINATE)),
                initialCipherSuite = CipherSuite.SUITE_2,
            ),
            backend,
        )
    }
}
