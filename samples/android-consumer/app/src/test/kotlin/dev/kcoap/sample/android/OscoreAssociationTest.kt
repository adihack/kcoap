package dev.kcoap.sample.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The OSCORE contexts are the process's: one derivation, whatever runs the tour, so that two Activities at
 * once never hold two live contexts under one context name, which would reuse nonces (RFC 8613
 * Appendix B.1.1). Plain JVM tests, without Android.
 */
class OscoreAssociationTest {
    private val directory = Files.createTempDirectory("kcoap-oscore").toFile()

    @AfterTest
    fun deleteTheStore() {
        directory.deleteRecursively()
    }

    @Test
    fun everyCallerGetsTheContextsOfOneDerivationAlsoWhenTheyRace() = runBlocking {
        val association = OscoreAssociation(cryptoBackend(), directory)

        val contexts = List(8) { async(Dispatchers.Default) { association.contexts() } }.awaitAll()

        contexts.forEach { assertSame(contexts.first(), it) }
        // One store file per context name, written by that one derivation.
        assertEquals(setOf("sid=,rid=01.seq", "sid=01,rid=.seq"), directory.list()!!.toSet())
    }

    @Test
    fun toursRunningAtOnceShareTheContextsAndEachGetsItsAnswerUnderOscore() = runBlocking {
        val association = OscoreAssociation(cryptoBackend(), directory)
        val crypto = cryptoBackend()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The first protected request proves its freshness to the new server context (RFC 8613
            // Appendix B.1.2), which then starts its replay window there. Two such proofs at once would
            // leave the slower request below that window, a replay.
            val first = KcoapTour(crypto, CRYPTO_SETUP, association).run(scope)
            val client = association.contexts().client
            val next = client.peekSequenceNumber()

            val reports = List(2) {
                async(Dispatchers.Default) { KcoapTour(crypto, CRYPTO_SETUP, association).run(scope) }
            }.awaitAll()

            (reports + first).forEach {
                assertContains(it, "Hello under OSCORE")
                // The plain GET reaches the public resource of a server that has OSCORE (ADR 0008).
                assertContains(it, "2.05 Content Hello\n")
            }
            // The two protected requests took their Partial IVs from the one client context.
            assertEquals(next + 2, client.peekSequenceNumber())
        } finally {
            scope.cancel()
        }
    }
}
