package dev.kcoap.crypto.testsuite

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScriptedRandomTest {
    @Test
    fun theScriptIsHandedOutInOrderAcrossItsParts() {
        val random = ScriptedRandom(byteArrayOf(1, 2), byteArrayOf(3))

        assertEquals(3, random.remaining)
        assertContentEquals(byteArrayOf(1), random.next(1))
        assertContentEquals(byteArrayOf(2, 3), random.next(2))
        assertContentEquals(ByteArray(0), random.next(0))
        assertEquals(0, random.remaining)
    }

    /** A backend that draws more than a vector holds draws more than the standard prescribes. */
    @Test
    fun drawingMoreThanIsLeftFailsAndDrawsNothing() {
        val random = ScriptedRandom(byteArrayOf(1, 2))

        assertFailsWith<IllegalStateException> { random.next(3) }
        assertEquals(2, random.remaining)
        assertFailsWith<IllegalArgumentException> { random.next(-1) }
    }

    @Test
    fun theScriptIsCopied() {
        val part = byteArrayOf(1)
        val random = ScriptedRandom(part)

        part[0] = 9

        assertContentEquals(byteArrayOf(1), random.next(1))
    }
}
