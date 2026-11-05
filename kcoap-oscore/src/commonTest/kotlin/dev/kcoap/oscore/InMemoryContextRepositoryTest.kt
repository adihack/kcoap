package dev.kcoap.oscore

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [InMemoryContextRepository] as a [MutableSecurityContextRepository]: contexts held under their Recipient ID and
 * ID Context (RFC 8613 §3.3), found by 'kid' and 'kid context' (§8.2 step 2).
 */
class InMemoryContextRepositoryTest {
    private val crypto = DefaultCryptoBackend()
    private val secret = ByteArray(16) { it.toByte() }

    private suspend fun context(recipientId: Byte, idContext: ByteArray? = null, senderId: Byte = 0x70): SecurityContext =
        ephemeralContext(SecurityContextParameters(secret, byteArrayOf(senderId), byteArrayOf(recipientId), idContext = idContext), crypto)

    @Test
    fun addReplacesTheContextOfTheSameRecipientIdAndIdContext() = runTest {
        val repository: MutableSecurityContextRepository = InMemoryContextRepository()
        val first = context(0x01)
        val second = context(0x01, senderId = 0x71)
        repository.add(first)
        assertSame(first, repository.find(byteArrayOf(0x01), null))
        repository.add(second)
        assertSame(second, repository.find(byteArrayOf(0x01), null))
    }

    /** RFC 9668 §4.1.2: a writer that must not replace another's context adds only to a free pair. */
    @Test
    fun addIfAbsentNeverReplaces() = runTest {
        val repository: MutableSecurityContextRepository = InMemoryContextRepository()
        val first = context(0x01)
        assertTrue(repository.addIfAbsent(first))
        assertFalse(repository.addIfAbsent(context(0x01, senderId = 0x71)))
        assertSame(first, repository.find(byteArrayOf(0x01), null))
        assertTrue(repository.addIfAbsent(context(0x01, idContext = byteArrayOf(0x37))), "another ID Context is another pair")
    }

    @Test
    fun removeDropsTheContextOfItsPair() = runTest {
        val repository: MutableSecurityContextRepository = InMemoryContextRepository()
        val kept = context(0x02)
        repository.add(context(0x01))
        repository.add(kept)
        repository.remove(context(0x01, senderId = 0x71))
        assertNull(repository.find(byteArrayOf(0x01), null), "removed by Recipient ID and ID Context")
        assertSame(kept, repository.find(byteArrayOf(0x02), null))
        repository.remove(context(0x03))
        assertSame(kept, repository.find(byteArrayOf(0x02), null), "removing an absent pair changes nothing")
    }

    /** RFC 8613 §8.2 step 2: without 'kid context', a Recipient ID that only one context has finds it. */
    @Test
    fun aRequestWithoutKidContextFindsTheOnlyContextOfItsRecipientId() = runTest {
        val repository = InMemoryContextRepository()
        val withContext = context(0x01, idContext = byteArrayOf(0x37))
        repository.add(withContext)
        assertSame(withContext, repository.find(byteArrayOf(0x01), null))
        assertSame(withContext, repository.find(byteArrayOf(0x01), byteArrayOf(0x37)))
        assertNull(repository.find(byteArrayOf(0x01), byteArrayOf(0x38)), "another kid context")

        repository.add(context(0x01, idContext = byteArrayOf(0x38)))
        assertNull(repository.find(byteArrayOf(0x01), null), "ambiguous without kid context")
        assertEquals(byteArrayOf(0x38).toList(), repository.find(byteArrayOf(0x01), byteArrayOf(0x38))?.idContext?.toList())
    }
}
