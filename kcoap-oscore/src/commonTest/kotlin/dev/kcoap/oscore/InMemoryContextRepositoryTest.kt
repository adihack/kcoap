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
    fun removeDropsThatContext() = runTest {
        val repository: MutableSecurityContextRepository = InMemoryContextRepository()
        val removed = context(0x01)
        val kept = context(0x02)
        repository.add(removed)
        repository.add(kept)
        assertTrue(repository.remove(removed))
        assertNull(repository.find(byteArrayOf(0x01), null))
        assertSame(kept, repository.find(byteArrayOf(0x02), null))
        assertFalse(repository.remove(removed), "removed already")
        assertFalse(repository.remove(context(0x03)), "never added")
        assertSame(kept, repository.find(byteArrayOf(0x02), null), "removing an absent context changes nothing")
    }

    /**
     * A context replaced or evicted already is not the one held under its pair any more: removing it late, as an
     * expiry timer or a second revocation would, leaves the context that took its Recipient ID since.
     */
    @Test
    fun removingAReplacedContextLeavesItsSuccessor() = runTest {
        val repository: MutableSecurityContextRepository = InMemoryContextRepository()
        val old = context(0x01)
        val successor = context(0x01, senderId = 0x71)
        repository.add(old)
        repository.add(successor)
        assertFalse(repository.remove(old))
        assertSame(successor, repository.find(byteArrayOf(0x01), null))
        assertFalse(repository.remove(context(0x01, senderId = 0x71)), "the same parameters derived again are another context")
        assertSame(successor, repository.find(byteArrayOf(0x01), null))
        assertTrue(repository.remove(successor))
        assertNull(repository.find(byteArrayOf(0x01), null))
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
