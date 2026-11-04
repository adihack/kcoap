package com.example.kcoap.edhoc

import kotlin.test.Test
import kotlin.test.assertEquals

class CipherSuiteTest {
    /**
     * The "Array" column of RFC 9528 §10.2 as COSE ids:
     * [EDHOC AEAD, EDHOC hash, MAC length, key exchange, signature, app AEAD, app hash].
     */
    private val registry = mapOf(
        0 to listOf(10, -16, 8, 4, -8, 10, -16),
        1 to listOf(30, -16, 16, 4, -8, 10, -16),
        2 to listOf(10, -16, 8, 1, -7, 10, -16),
        3 to listOf(30, -16, 16, 1, -7, 10, -16),
        4 to listOf(24, -16, 16, 4, -8, 24, -16),
        5 to listOf(24, -16, 16, 1, -7, 24, -16),
        6 to listOf(1, -16, 16, 4, -7, 1, -16),
        24 to listOf(3, -43, 16, 2, -35, 3, -43),
        25 to listOf(24, -45, 16, 5, -8, 24, -45),
    )

    @Test
    fun suitesMatchIanaRegistry() {
        assertEquals(registry.keys, CipherSuite.entries.map { it.value }.toSet())
        for (suite in CipherSuite.entries) {
            val actual = listOf(
                suite.edhocAead.coseId,
                suite.edhocHash?.coseId ?: SHAKE256,
                suite.macLength,
                suite.keyExchange.coseId,
                suite.signature.coseId,
                suite.appAead.coseId,
                suite.appHash?.coseId ?: SHAKE256,
            )
            assertEquals(registry.getValue(suite.value), actual, suite.name)
        }
    }

    @Test
    fun mandatorySuitesAreTwoAndThree() {
        assertEquals(setOf(2, 3), CipherSuite.entries.filter { it.isMandatoryToImplement }.map { it.value }.toSet())
    }

    private companion object {
        const val SHAKE256 = -45
    }
}
