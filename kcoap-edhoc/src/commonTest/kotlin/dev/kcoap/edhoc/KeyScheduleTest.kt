package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.cose.CoseStructures
import dev.kcoap.crypto.Kmac256
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.vectors.Rfc9529Trace1
import dev.kcoap.edhoc.vectors.Rfc9529Trace2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The building blocks of RFC 9528 §4 and §5 against the inputs RFC 9529 prints, one by one: info
 * encodings, context_2/3, the COSE_Sign1 and COSE_Encrypt0 inputs, and the transcript hash inputs.
 */
class KeyScheduleTest {
    private val backend = DefaultCryptoBackend()

    @Test
    fun infoEncodingsOfTrace1() = runTest {
        val t = Rfc9529Trace1
        assertHex(t.INFO_FOR_MAC_2, KeySchedule.info(KeySchedule.MAC_2, t.CONTEXT_2.hex(), 32))
        assertHex(t.INFO_FOR_KEYSTREAM_2, KeySchedule.info(KeySchedule.KEYSTREAM_2, t.TH_2.hex(), 82))
        assertHex(t.INFO_FOR_MAC_3, KeySchedule.info(KeySchedule.MAC_3, t.CONTEXT_3.hex(), 32))
        assertHex(t.INFO_FOR_K_3, KeySchedule.info(KeySchedule.K_3, t.TH_3.hex(), 16))
        assertHex(t.INFO_FOR_IV_3, KeySchedule.info(KeySchedule.IV_3, t.TH_3.hex(), 13))
        assertHex(t.INFO_FOR_K_4, KeySchedule.info(KeySchedule.K_4, t.TH_4.hex(), 16))
        assertHex(t.INFO_FOR_IV_4, KeySchedule.info(KeySchedule.IV_4, t.TH_4.hex(), 13))
        assertHex(t.INFO_FOR_PRK_OUT, KeySchedule.info(KeySchedule.PRK_OUT, t.TH_4.hex(), 32))
        assertHex(t.INFO_FOR_PRK_EXPORTER, KeySchedule.info(KeySchedule.PRK_EXPORTER, ByteArray(0), 32))
        val empty = ByteArray(0)
        val secretLabel = EdhocSession.EXPORTER_OSCORE_MASTER_SECRET
        val saltLabel = EdhocSession.EXPORTER_OSCORE_MASTER_SALT
        assertHex(t.INFO_FOR_OSCORE_MASTER_SECRET, KeySchedule.info(secretLabel, empty, 16))
        assertHex(t.INFO_FOR_OSCORE_MASTER_SALT, KeySchedule.info(saltLabel, empty, 8))
        assertHex(t.INFO_FOR_KEYUPDATE, KeySchedule.info(KeySchedule.KEY_UPDATE, t.CONTEXT_FOR_KEYUPDATE.hex(), 32))
        assertHex(t.CONTEXT_2_BSTR, dev.kcoap.cbor.CborWriter().writeBytes(t.CONTEXT_2.hex()).toByteArray())
    }

    @Test
    fun infoEncodingsOfTrace2() = runTest {
        val t = Rfc9529Trace2
        assertHex(t.INFO_FOR_SALT_3E2M, KeySchedule.info(KeySchedule.SALT_3E2M, t.TH_2.hex(), 32))
        assertHex(t.INFO_FOR_SALT_4E3M, KeySchedule.info(KeySchedule.SALT_4E3M, t.TH_3.hex(), 32))
        assertHex(t.INFO_FOR_MAC_2, KeySchedule.info(KeySchedule.MAC_2, t.CONTEXT_2.hex(), 8))
        assertHex(t.INFO_FOR_MAC_3, KeySchedule.info(KeySchedule.MAC_3, t.CONTEXT_3.hex(), 8))
        assertHex(t.INFO_FOR_KEYSTREAM_2, KeySchedule.info(KeySchedule.KEYSTREAM_2, t.TH_2.hex(), 11))
    }

    /** context_2, context_3, the messages to be signed, and A_3 / A_4 of RFC 9529 §2. */
    @Test
    fun coseInputsOfTrace1() = runTest {
        val t = Rfc9529Trace1
        val credR = AuthenticationCredential.x509(t.CRED_R.hex())
        val credI = AuthenticationCredential.x509(t.CRED_I.hex())
        val input2 = AuthenticationInput(
            KeySchedule.MAC_2, cid(t.C_R), CredentialIdentifier.decodeFromPlaintext(t.ID_CRED_R.hex(), "test"),
            t.TH_2.hex(), credR, emptyList(),
        )
        assertHex(t.CONTEXT_2, input2.context())
        val toBeSigned2 = CoseStructures.sigStructure(t.ID_CRED_R.hex(), input2.externalAad(), t.MAC_2.hex())
        assertHex(t.MESSAGE_TO_BE_SIGNED_IN_MESSAGE_2, toBeSigned2)
        val input3 = AuthenticationInput(
            KeySchedule.MAC_3, null, CredentialIdentifier.decodeFromPlaintext(t.ID_CRED_I.hex(), "test"), t.TH_3.hex(),
            credI, emptyList(),
        )
        assertHex(t.CONTEXT_3, input3.context())
        val toBeSigned3 = CoseStructures.sigStructure(t.ID_CRED_I.hex(), input3.externalAad(), t.MAC_3.hex())
        assertHex(t.MESSAGE_TO_BE_SIGNED_IN_MESSAGE_3, toBeSigned3)
        assertHex(t.A_3, Handshake.encrypt0Aad(t.TH_3.hex()))
        assertHex(t.A_4, Handshake.encrypt0Aad(t.TH_4.hex()))

        val keySchedule = KeySchedule(CipherSuite.SUITE_0, backend)
        val authenticator = SignatureOrMac(keySchedule, backend)
        assertHex(t.MAC_2, authenticator.mac(t.PRK_3E2M.hex(), input2, AuthenticationKind.SIGNATURE))
        assertHex(t.MAC_3, authenticator.mac(t.PRK_4E3M.hex(), input3, AuthenticationKind.SIGNATURE))
    }

    @Test
    fun coseInputsOfTrace2() = runTest {
        val t = Rfc9529Trace2
        val input2 = AuthenticationInput(
            KeySchedule.MAC_2, cid(t.C_R), CredentialIdentifier.KeyId("32".hex()), t.TH_2.hex(),
            AuthenticationCredential.ccs(t.CRED_R.hex()), emptyList(),
        )
        assertHex(t.CONTEXT_2, input2.context())
        val input3 = AuthenticationInput(
            KeySchedule.MAC_3, null, CredentialIdentifier.KeyId("2b".hex()), t.TH_3.hex(),
            AuthenticationCredential.ccs(t.CRED_I.hex()), emptyList(),
        )
        assertHex(t.CONTEXT_3, input3.context())
        assertHex(t.A_3, Handshake.encrypt0Aad(t.TH_3.hex()))
        assertHex(t.A_4, Handshake.encrypt0Aad(t.TH_4.hex()))
        val authenticator = SignatureOrMac(KeySchedule(CipherSuite.SUITE_2, backend), backend)
        assertEquals(8, authenticator.macLength(AuthenticationKind.STATIC_DH))
        assertEquals(32, authenticator.macLength(AuthenticationKind.SIGNATURE))
        assertEquals(64, authenticator.expectedLength(AuthenticationKind.SIGNATURE))
        assertHex(t.MAC_3, authenticator.mac(t.PRK_4E3M.hex(), input3, AuthenticationKind.STATIC_DH))
    }

    /** "Input to calculate TH_x" of both traces, and the hashes over them. */
    @Test
    fun transcriptHashes() = runTest {
        val traces = listOf(
            with(Rfc9529Trace1) { Trace(INPUT_TO_CALCULATE_TH_2, TH_2, G_Y, H_MESSAGE_1) },
            with(Rfc9529Trace2) { Trace(INPUT_TO_CALCULATE_TH_2, TH_2, G_Y, H_MESSAGE_1) },
        )
        for (t in traces) {
            val keySchedule = KeySchedule(CipherSuite.SUITE_0, backend)
            assertHex(t.th, keySchedule.th2(t.gY.hex(), t.hashMessage1.hex()))
            assertHex(t.th, keySchedule.hash(t.input.hex()))
        }
        val keySchedule = KeySchedule(CipherSuite.SUITE_2, backend)
        val t = Rfc9529Trace2
        assertHex(t.TH_3, keySchedule.nextTranscriptHash(t.TH_2.hex(), t.PLAINTEXT_2.hex(), t.CRED_R.hex()))
        assertHex(t.TH_4, keySchedule.nextTranscriptHash(t.TH_3.hex(), t.PLAINTEXT_3.hex(), t.CRED_I.hex()))
        assertHex(t.TH_3, keySchedule.hash(t.INPUT_TO_CALCULATE_TH_3.hex()))
        assertHex(t.TH_4, keySchedule.hash(t.INPUT_TO_CALCULATE_TH_4.hex()))
        assertHex(t.SALT_3E2M, keySchedule.salt3e2m(t.PRK_2E.hex(), t.TH_2.hex()))
        assertHex(t.SALT_4E3M, keySchedule.salt4e3m(t.PRK_3E2M.hex(), t.TH_3.hex()))
        assertHex(t.PRK_3E2M, keySchedule.extract(t.SALT_3E2M.hex(), t.G_RX.hex()))
        assertHex(t.KEYSTREAM_2, keySchedule.keystream2(t.PRK_2E.hex(), t.TH_2.hex(), 11))
        assertHex(t.K_4, keySchedule.k4(t.PRK_4E3M.hex(), t.TH_4.hex()))
        assertHex(t.IV_4, keySchedule.iv4(t.PRK_4E3M.hex(), t.TH_4.hex()))
    }

    private class Trace(val input: String, val th: String, val gY: String, val hashMessage1: String)

    /** RFC 9528 Appendix G: past 255 * hash_length, KEYSTREAM_2 continues with info labels 0, -1, -2, ... */
    @Test
    fun longKeystreamUsesAppendixG() = runTest {
        val keySchedule = KeySchedule(CipherSuite.SUITE_2, backend)
        val prk = ByteArray(32) { 1 }
        val th2 = ByteArray(32) { 2 }
        val part = 255 * 32
        val keystream = keySchedule.keystream2(prk, th2, 2 * part + 10)
        assertContentEquals(keySchedule.kdf(prk, 0, th2, part), keystream.copyOfRange(0, part))
        assertContentEquals(keySchedule.kdf(prk, -1, th2, part), keystream.copyOfRange(part, 2 * part))
        assertContentEquals(keySchedule.kdf(prk, -2, th2, 10), keystream.copyOfRange(2 * part, 2 * part + 10))
        assertContentEquals(keySchedule.kdf(prk, 0, th2, part), keySchedule.keystream2(prk, th2, part))
    }

    /** RFC 9528 §4.1: SHAKE256 suites use KMAC256 for Extract and Expand, with no output limit. */
    @Test
    fun shake256SuitesUseKmac256() = runTest {
        val keySchedule = KeySchedule(CipherSuite.SUITE_PQC_1, backend)
        val salt = ByteArray(64) { 3 }
        val ikm = ByteArray(32) { 4 }
        assertEquals(64, keySchedule.hashLength)
        assertContentEquals(Kmac256.compute(salt, ikm, 64), keySchedule.extract(salt, ikm))
        val info = KeySchedule.info(0, salt, 20_000)
        val long = keySchedule.keystream2(ikm, salt, 20_000)
        assertContentEquals(Kmac256.compute(ikm, info, 20_000), long)
        assertEquals(64, keySchedule.hash(ByteArray(0)).size)
        // FIPS 202 SHAKE256 of the empty string, first 32 of the 64 bytes.
        val emptyHash = "46b9dd2b0ba88d13233b3feb743eeb243fcd52ea62b81b82b50c27646ed5762f"
        assertEquals(emptyHash, keySchedule.hash(ByteArray(0)).toHex().take(64))
    }
}
