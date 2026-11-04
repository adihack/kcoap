package dev.kcoap.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PeerIdentityTest {
    /** RFC 7252 §9: NoSec and RawPublicKey are mandatory to implement. */
    @Test
    fun mandatoryModes() {
        assertEquals(
            setOf(SecurityMode.NO_SEC, SecurityMode.RAW_PUBLIC_KEY),
            SecurityMode.entries.filter { it.isMandatoryToImplement }.toSet(),
        )
    }

    @Test
    fun identitiesReportTheirMode() {
        assertEquals(SecurityMode.NO_SEC, PeerIdentity.None.mode)
        assertEquals(SecurityMode.PRE_SHARED_KEY, PeerIdentity.PreSharedKey(byteArrayOf(1)).mode)
        assertEquals(SecurityMode.RAW_PUBLIC_KEY, PeerIdentity.RawPublicKey(byteArrayOf(1)).mode)
        assertEquals(SecurityMode.CERTIFICATE, PeerIdentity.Certificate(listOf(byteArrayOf(1))).mode)
    }

    @Test
    fun equalityComparesContent() {
        assertEquals(PeerIdentity.PreSharedKey(byteArrayOf(1, 2)), PeerIdentity.PreSharedKey(byteArrayOf(1, 2)))
        assertNotEquals(PeerIdentity.PreSharedKey(byteArrayOf(1, 2)), PeerIdentity.PreSharedKey(byteArrayOf(1, 3)))
        assertEquals(PeerIdentity.RawPublicKey(byteArrayOf(9)), PeerIdentity.RawPublicKey(byteArrayOf(9)))
        assertEquals(
            PeerIdentity.Certificate(listOf(byteArrayOf(1), byteArrayOf(2))),
            PeerIdentity.Certificate(listOf(byteArrayOf(1), byteArrayOf(2))),
        )
        assertNotEquals(
            PeerIdentity.Certificate(listOf(byteArrayOf(1), byteArrayOf(2))),
            PeerIdentity.Certificate(listOf(byteArrayOf(1))),
        )
    }

    /** An authenticated identity must not change after the check, through either reference. */
    @Test
    fun bytesAreCopied() {
        val input = byteArrayOf(1, 2, 3)
        val identity = PeerIdentity.PreSharedKey(input)
        input[0] = 0
        identity.identity[1] = 0
        assertContentEquals(byteArrayOf(1, 2, 3), identity.identity)

        val certificate = byteArrayOf(4, 5)
        val chain = PeerIdentity.Certificate(listOf(certificate))
        certificate[0] = 0
        chain.chain[0][1] = 0
        assertContentEquals(byteArrayOf(4, 5), chain.chain[0])
    }

    @Test
    fun emptyChainIsRejected() {
        assertFailsWith<IllegalArgumentException> { PeerIdentity.Certificate(emptyList()) }
    }
}
