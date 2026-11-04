package com.example.kcoap.coap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoapOptionsTest {
    @Test
    fun numbersAreUnique() {
        assertEquals(CoapOptions.all.size, CoapOptions.all.map { it.number }.toSet().size)
    }

    /** Critical / Unsafe / NoCacheKey columns from RFC 7252 Table 4, RFC 9668 Table 1 and RFC 9175. */
    @Test
    fun derivedPropertiesMatchRegistryTables() {
        assertTrue(CoapOptions.URI_HOST.isCritical && CoapOptions.URI_HOST.isUnsafe)
        assertTrue(CoapOptions.SIZE1.isNoCacheKey && !CoapOptions.SIZE1.isCritical)
        assertTrue(CoapOptions.EDHOC.isCritical)
        assertFalse(CoapOptions.EDHOC.isUnsafe)
        assertFalse(CoapOptions.EDHOC.isNoCacheKey)
        assertTrue(CoapOptions.ECHO.isNoCacheKey && !CoapOptions.ECHO.isCritical)
        // RFC 9175 §3.2.1: "elective, safe to forward, repeatable, and part of the cache-key".
        assertFalse(CoapOptions.REQUEST_TAG.isCritical || CoapOptions.REQUEST_TAG.isUnsafe)
        assertFalse(CoapOptions.REQUEST_TAG.isNoCacheKey)
        assertTrue(CoapOptions.Q_BLOCK1.isCritical && CoapOptions.Q_BLOCK1.isUnsafe)
        assertTrue(CoapOptions.PROXY_URI.isCritical && CoapOptions.PROXY_URI.isUnsafe)
    }

    @Test
    fun unknownOptionsAreClassE() {
        assertEquals(setOf(OscoreClass.E), CoapOptions.oscoreClasses(65000))
        assertEquals(setOf(OscoreClass.U), CoapOptions.oscoreClasses(CoapOptions.OSCORE.number))
    }
}
