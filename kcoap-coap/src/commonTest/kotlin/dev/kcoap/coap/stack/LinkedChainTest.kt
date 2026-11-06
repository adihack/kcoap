package dev.kcoap.coap.stack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The list behind the bounded caches: the oldest at the head, any element removed in constant time. */
class LinkedChainTest {
    @Test
    fun elementsLeaveFromTheHeadTheMiddleAndTheTail() {
        val chain = LinkedChain<String>()
        assertNull(chain.first())
        assertNull(chain.removeFirst())
        val (a, b, c, d) = listOf("a", "b", "c", "d").map(chain::addLast)
        assertEquals(listOf("a", "b", "c", "d"), chain.toList())

        assertTrue(chain.remove(b))
        assertFalse(b.isLinked)
        assertFalse(chain.remove(b), "only once")
        assertTrue(chain.remove(d))
        assertEquals(listOf("a", "c"), chain.toList())
        assertEquals("a", chain.removeFirst())
        assertFalse(a.isLinked)
        assertEquals("c", chain.first())
        assertTrue(c.isLinked)
        assertTrue(chain.remove(c))
        assertEquals(0, chain.size)
        assertEquals(emptyList(), chain.toList())

        chain.addLast("e")
        assertEquals(listOf("e"), chain.toList(), "an emptied chain takes new elements")
    }

    @Test
    fun aNodeOfAnotherChainIsNotRemoved() {
        val one = LinkedChain<Int>()
        val other = LinkedChain<Int>()
        val node = one.addLast(1)
        other.addLast(2)
        assertFalse(other.remove(node))
        assertEquals(1, one.size)
        assertEquals(1, other.size)
    }
}
