package dev.kcoap.dtls

import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionEndReason
import dev.kcoap.transport.SessionId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
