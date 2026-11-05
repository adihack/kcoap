package dev.kcoap.crypto.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The capability answers of one backend instance, each found out once (docs/adr/0001-crypto-capabilities.md).
 *
 * A question is any value with equality, such as an `AeadAlgorithm`. The first caller runs the probe and
 * the others wait for its answer, so a probe runs once however many callers race for it. The [Mutex]
 * guards the maps only and is never held across a probe. A probe that fails or is cancelled leaves no
 * answer, and the next caller probes again.
 *
 * A probe must not ask this memo the question it is answering: it would wait for itself.
 */
internal class CapabilityMemo {
    private val lock = Mutex()
    private val answers = HashMap<Any, Boolean>()
    private val probing = HashMap<Any, CompletableDeferred<Unit>>()

    suspend fun answer(question: Any, probe: suspend () -> Boolean): Boolean {
        while (true) {
            val mine = CompletableDeferred<Unit>()
            val running = lock.withLock {
                answers[question]?.let { return it }
                probing[question] ?: run {
                    probing[question] = mine
                    null
                }
            }
            if (running != null) {
                running.await()
                continue
            }
            val answer = try {
                probe()
            } catch (e: Throwable) {
                settle(question, null, mine)
                throw e
            }
            settle(question, answer, mine)
            return answer
        }
    }

    /** Records [answer], if there is one, and releases the callers waiting for [probe]. */
    private suspend fun settle(question: Any, answer: Boolean?, probe: CompletableDeferred<Unit>) {
        withContext(NonCancellable) {
            lock.withLock {
                if (answer != null) answers[question] = answer
                probing.remove(question)
            }
        }
        probe.complete(Unit)
    }
}
