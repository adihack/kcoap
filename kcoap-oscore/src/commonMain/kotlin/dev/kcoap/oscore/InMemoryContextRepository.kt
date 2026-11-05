package dev.kcoap.oscore

import dev.kcoap.common.toHex
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [MutableSecurityContextRepository] in memory, keyed by Recipient ID and ID Context. It keeps every context
 * until it is removed, without a bound: right for provisioned contexts and tests, while a server that runs
 * EDHOC for many clients supplies a bounded repository instead ([MutableSecurityContextRepository]).
 */
public class InMemoryContextRepository : MutableSecurityContextRepository {
    private val lock = Mutex()
    private val contexts = mutableMapOf<String, SecurityContext>()

    override suspend fun add(context: SecurityContext) {
        lock.withLock { contexts[key(context.recipientId, context.idContext)] = context }
    }

    override suspend fun addIfAbsent(context: SecurityContext): Boolean = lock.withLock {
        val key = key(context.recipientId, context.idContext)
        if (key in contexts) return@withLock false
        contexts[key] = context
        true
    }

    override suspend fun remove(context: SecurityContext) {
        lock.withLock { contexts.remove(key(context.recipientId, context.idContext)) }
    }

    /**
     * Without a 'kid context' in the request, a context is found by Recipient ID alone when exactly one
     * context has it (RFC 8613 §3.3 recommends unique Recipient IDs).
     */
    override suspend fun find(recipientId: ByteArray, idContext: ByteArray?): SecurityContext? = lock.withLock {
        if (idContext != null) return@withLock contexts[key(recipientId, idContext)]
        contexts[key(recipientId, null)]
            ?: contexts.values.singleOrNull { it.recipientId.contentEquals(recipientId) }
    }

    private fun key(recipientId: ByteArray, idContext: ByteArray?): String = "${recipientId.toHex()}/${idContext?.toHex() ?: "-"}"
}
