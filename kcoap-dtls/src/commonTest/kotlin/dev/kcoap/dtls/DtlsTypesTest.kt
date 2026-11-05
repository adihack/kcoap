package dev.kcoap.dtls

import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DtlsTypesTest {
    @Test
    fun pskCredentialsAreCopied() {
        val identity = "client".encodeToByteArray()
        val key = ByteArray(16) { 7 }
        val credential = PskCredential(identity, key)
        identity[0] = 0
        key[0] = 0
        assertContentEquals("client".encodeToByteArray(), credential.identity())
        assertContentEquals(ByteArray(16) { 7 }, credential.key())
        credential.key()[1] = 0
        assertContentEquals(ByteArray(16) { 7 }, credential.key())
        assertEquals("PskCredential(6-byte identity)", credential.toString())
    }

    /** RFC 7250 §3 and RFC 7925 §4.3, §4.4: a P-256 key handle, its SubjectPublicKeyInfo and, optionally, its chain. */
    @Test
    fun localCredentialsHoldAP256KeyWithItsOwnCopies() {
        val spki = TestCredentials.p256Spki()
        val certificate = TestCredentials.certificate(spki)
        val key = TestCredentials.p256Key()
        val credential = DtlsLocalCredential(key, spki, listOf(certificate))
        spki.fill(0)
        certificate.fill(0)
        credential.subjectPublicKeyInfo.fill(0)
        credential.certificateChain[0].fill(0)
        assertContentEquals(TestCredentials.p256Spki(), credential.subjectPublicKeyInfo)
        assertContentEquals(TestCredentials.certificate(TestCredentials.p256Spki()), credential.certificateChain.single())
        assertSame(key, credential.privateKey)
        assertEquals("DtlsLocalCredential(P256, 1 certificates)", credential.toString())
        assertEquals(emptyList(), DtlsLocalCredential(key, TestCredentials.p256Spki()).certificateChain)
    }

    @Test
    fun localCredentialsRejectKeysThatDoNotBelongTogether() {
        val key = TestCredentials.p256Key()
        val spki = TestCredentials.p256Spki()
        val ed25519 = object : PrivateKey(KeyType.ED25519) {}
        assertFailsWith<IllegalArgumentException> { DtlsLocalCredential(ed25519, spki) }
        assertFailsWith<IllegalArgumentException> { DtlsLocalCredential(key, TestCredentials.x25519Spki()) }
        val malformed = assertFailsWith<IllegalArgumentException> { DtlsLocalCredential(key, spki.copyOf(spki.size - 1)) }
        assertTrue(malformed.cause is CertificateFormatException)
        val otherKey = TestCredentials.certificate(TestCredentials.p256Spki(TestCredentials.P256_OTHER_POINT))
        assertFailsWith<IllegalArgumentException> { DtlsLocalCredential(key, spki, listOf(otherKey)) }
        val notACertificate = assertFailsWith<IllegalArgumentException> { DtlsLocalCredential(key, spki, listOf(ByteArray(300))) }
        assertTrue(notACertificate.cause is CertificateFormatException)
    }

    @Test
    fun suiteParameters() {
        val suite = DtlsCipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8
        assertEquals(16, suite.keyLength)
        assertEquals(4, suite.fixedIvLength)
        assertEquals(HashAlgorithm.SHA_256, suite.prfHash)
        assertEquals(KeyExchange.ECDHE_ECDSA, suite.keyExchange)
        assertEquals("RFC 7251", suite.reference)
    }

    @Test
    fun effectsCarryTheirData() {
        val udp = Endpoint.Udp("192.0.2.1", 5684)
        val secure = Endpoint.Secure(SessionId(1), 1, udp, PeerIdentity.None)
        val bytes = byteArrayOf(1)
        val transmit = DtlsEffect.Transmit(udp, bytes)
        assertSame(udp, transmit.to)
        assertSame(bytes, transmit.datagram)
        assertSame(secure, DtlsEffect.Deliver(secure, bytes).from)
        assertEquals(9.seconds, DtlsEffect.ScheduleTimer(9.seconds).at)
        assertSame(secure, DtlsEffect.SessionEstablished(secure).endpoint)
        assertEquals(SessionEndReason.ALERT, DtlsEffect.SessionEnded(secure, SessionEndReason.ALERT).reason)
        val failure = IllegalStateException("handshake failed")
        val failed = DtlsEffect.SendFailed(udp, bytes, failure)
        assertSame(failure, failed.cause)
        assertSame(bytes, failed.data)
    }
}
