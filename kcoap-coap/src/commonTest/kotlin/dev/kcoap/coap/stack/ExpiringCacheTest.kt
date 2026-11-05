package dev.kcoap.coap.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The bounded, expiring map behind the block-wise caches and address verification: its entries stay in
 * order of expiry, so pruning only looks at the expired ones at its head.
 */
class ExpiringCacheTest {
    @Test
    fun getMovesAnEntryToTheEndWithANewExpiryAndPeekLeavesItWhereItIs() {
        val cache = ExpiringCache<String, Int>(capacity = 8, lifetime = 10.seconds)
        cache.put("a", 1, 0.seconds)
        cache.put("b", 2, 1.seconds)
        cache.put("c", 3, 2.seconds)

        assertEquals(2, cache.peek("b", 3.seconds))
        assertEquals(listOf("a", "b", "c"), cache.keys, "peek moves nothing")
        assertEquals(1, cache.get("a", 4.seconds))
        assertEquals(listOf("b", "c", "a"), cache.keys, "get moves the entry to the end, expiring at 14 s")

        // b expired at 11 s and c at 12 s; a, read at 4 s, lives until 14 s.
        cache.put("d", 4, 12.seconds)
        assertEquals(listOf("a", "d"), cache.keys, "pruning stopped at the first live entry")
        assertEquals(1, cache.peek("a", 13.seconds))
        assertNull(cache.peek("a", 14.seconds))
        assertEquals(listOf("d"), cache.keys)
    }

    @Test
    fun aFullCacheEvictsTheLeastRecentlyUsedEntryAndNamesIt() {
        val cache = ExpiringCache<String, Int>(capacity = 2, lifetime = 10.seconds)
        assertNull(cache.put("a", 1, 0.seconds))
        assertNull(cache.put("b", 2, 1.seconds))
        cache.get("a", 2.seconds)

        assertEquals("b", cache.put("c", 3, 3.seconds))
        assertEquals(listOf("a", "c"), cache.keys)
        assertNull(cache.put("d", 4, 12.seconds), "a expired at 12 s, so there was room without evicting")
        assertEquals(listOf("c", "d"), cache.keys)
    }

    @Test
    fun aStaleEntryIsNeverReturned() {
        val cache = ExpiringCache<String, Int>(capacity = 2, lifetime = 10.seconds)
        cache.put("a", 1, 0.seconds)

        assertNull(cache.get("a", 10.seconds))
        assertEquals(0, cache.size)
    }
}
