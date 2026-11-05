package dev.kcoap.edhoc

import dev.kcoap.crypto.CryptoBackend
import kotlin.concurrent.Volatile

/**
 * The [RunnableSuites] of one configuration per method, as the crypto backend it was last used with
 * answered them (docs/adr/0001-crypto-capabilities.md).
 *
 * Both state machines filter the configured suites in every session, and the Responder does so before it
 * has checked anything of a message_1 that any peer can send. A backend that keeps the probing defaults of
 * the `supports` members of [CryptoBackend] answers each question by generating key pairs, signing and
 * agreeing on a secret, and a hardware backend that forgot to override them may create a persistent key.
 * Asking per session would let unauthenticated peers trigger that work at will; with this memo, a
 * configuration asks once per method and backend, whether or not the backend memoizes.
 *
 * It holds the answers of one backend, by identity: used with another backend, the configuration asks that
 * one and forgets the first, so the memo never keeps more than one backend alive. Sessions that ask at the
 * same time may each ask the backend, and one of them may forget what another recorded; either way the
 * answers are the same, because a backend's answers do not change during its lifetime.
 */
internal class RunnableSuitesMemo {
    private class Answers(val crypto: CryptoBackend, val byMethod: Map<EdhocMethod, RunnableSuites>)

    @Volatile
    private var answers: Answers? = null

    /** The remembered answer of [crypto] for [method], or else the result of [ask], which is remembered. */
    suspend fun answer(crypto: CryptoBackend, method: EdhocMethod, ask: suspend () -> RunnableSuites): RunnableSuites {
        known(crypto)[method]?.let { return it }
        return ask().also { remember(crypto, mapOf(method to it)) }
    }

    /** Remembers [answers] of [crypto], with what is known of it already. */
    fun remember(crypto: CryptoBackend, answers: Map<EdhocMethod, RunnableSuites>) {
        this.answers = Answers(crypto, known(crypto) + answers)
    }

    private fun known(crypto: CryptoBackend): Map<EdhocMethod, RunnableSuites> =
        answers?.takeIf { it.crypto === crypto }?.byMethod.orEmpty()
}
