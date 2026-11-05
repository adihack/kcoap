package dev.kcoap.edhoc

import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.ByteMutator
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Hostile EDHOC messages: mutations of real message_1, message_2 and message_3 are rejected with
 * [EdhocException], never with another exception, and never complete a session with changed content.
 */
class EdhocFuzzTest {
    private val backend = DefaultCryptoBackend()

    @Test
    fun mutatedMessagesFailCleanly() = runTest {
        val random = Random(0xED0)
        for (suite in listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2)) {
            val configs = Fixtures.configs(backend, EdhocMethod.SIGNATURE_SIGNATURE, suite)
            val valid = Fixtures.handshake(backend, configs).messages
            repeat(ITERATIONS) { round ->
                when (round % 3) {
                    0 -> expectRejection { EdhocResponder(configs.second, backend).processMessage1(ByteMutator.mutate(valid[0], random)) }
                    1 -> {
                        val initiator = EdhocInitiator(configs.first, backend)
                        val responder = EdhocResponder(configs.second, backend)
                        responder.processMessage1(initiator.createMessage1())
                        val message2 = responder.createMessage2()
                        expectRejection { initiator.processMessage2(ByteMutator.mutate(message2, random)) }
                    }
                    else -> {
                        val initiator = EdhocInitiator(configs.first, backend)
                        val responder = EdhocResponder(configs.second, backend)
                        responder.processMessage1(initiator.createMessage1())
                        initiator.processMessage2(responder.createMessage2())
                        val message3 = initiator.createMessage3().bytes
                        val mutated = ByteMutator.mutate(message3, random)
                        expectRejection { responder.processMessage3(mutated) }
                        if (responder.session != null && !mutated.contentEquals(message3)) {
                            fail("A mutated message_3 completed the session")
                        }
                    }
                }
            }
        }
    }

    private suspend fun expectRejection(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: EdhocException) {
            // expected for tampered or malformed messages
        } catch (e: Throwable) {
            fail("Unexpected ${e::class.simpleName}: $e")
        }
    }

    private companion object {
        const val ITERATIONS = 150
    }
}
