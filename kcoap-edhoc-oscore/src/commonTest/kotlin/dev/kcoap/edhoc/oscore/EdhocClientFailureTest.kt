package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapUri
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The client's last step, deriving the OSCORE context of the session (RFC 9528 Appendix A.1), fails with an
 * `EdhocException` when the application's sequence number store or the crypto backend throws, as the server's
 * does: `run` and `runCombined` list only `KcoapException` and `CancellationException` in `@Throws`, and on Apple
 * platforms an exception of another type would end the Swift process.
 */
class EdhocClientFailureTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.7", 5683)
    private val server = CoapUri.parse("coap://192.0.2.7")

    /** A durable store whose first write for a new context fails, as a full disk does. */
    private class FailingStore(private val failure: Exception) : SequenceNumberStore {
        override suspend fun load(contextName: String): Long? = null

        override suspend fun store(contextName: String, limit: Long) = throw failure
    }

    private suspend fun handshake(sequenceStore: SequenceNumberStore): EdhocClientHandshake {
        val peers = Peers.create(crypto)
        val edhoc = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        return EdhocClientHandshake(peers.initiatorConfig(), crypto, server, sequenceStore = sequenceStore) {
            edhoc.handle(it.payload, peer)
        }
    }

    private fun assertClientFailure(cause: Throwable, error: EdhocException) {
        assertSame(cause, error.cause)
        assertEquals(EdhocError.UNSPECIFIED_ERROR, error.errorCode)
        assertNull(error.errorMessage, "the client answers no message of the server's here")
        assertTrue(cause.message.orEmpty() in error.message.orEmpty(), error.message)
    }

    @Test
    fun aSequenceStoreThatFailsAfterMessage3FailsRunWithAnEdhocException() = runTest {
        val diskFull = IllegalStateException("No space left on device")
        val error = assertFailsWith<EdhocException> { handshake(FailingStore(diskFull)).run() }
        assertClientFailure(diskFull, error)
    }

    @Test
    fun aSequenceStoreThatFailsBeforeTheCombinedRequestFailsRunCombinedWithAnEdhocException() = runTest {
        val diskFull = IllegalStateException("No space left on device")
        val error = assertFailsWith<EdhocException> { handshake(FailingStore(diskFull)).runCombined() }
        assertClientFailure(diskFull, error)
    }

    /**
     * A store whose own `withTimeout` expires failed, as the caller is still active: a `CancellationException` would
     * end a coroutine launched for the handshake without a word.
     */
    @Test
    fun aSequenceStoreThatTimesOutOnItsOwnFailsTheRunWithAnEdhocException() = runTest {
        val slow = object : SequenceNumberStore {
            override suspend fun load(contextName: String): Long? = withTimeout(1.milliseconds) { awaitCancellation() }

            override suspend fun store(contextName: String, limit: Long) = Unit
        }
        val error = assertFailsWith<EdhocException> { handshake(slow).run() }
        assertIs<TimeoutCancellationException>(error.cause)
    }

    /** The caller's own cancellation while the context is derived stays a cancellation. */
    @Test
    fun theCallersCancellationWhileTheContextIsDerivedPassesUnchanged() = runTest {
        val loading = CompletableDeferred<Unit>()
        val hanging = object : SequenceNumberStore {
            override suspend fun load(contextName: String): Long? {
                loading.complete(Unit)
                awaitCancellation()
            }

            override suspend fun store(contextName: String, limit: Long) = Unit
        }
        val handshake = handshake(hanging)
        var failure: Throwable? = null
        val run = launch {
            try {
                handshake.run()
            } catch (e: Throwable) {
                failure = e
                throw e
            }
        }
        loading.await()
        run.cancelAndJoin()
        assertIs<CancellationException>(failure, "not an EdhocException")
    }
}
