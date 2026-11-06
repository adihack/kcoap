package dev.kcoap.edhoc.oscore

import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.SequenceNumberStore

/** An ephemeral store that records the context names it serves. */
internal class RecordingStore : SequenceNumberStore {
    private val delegate = EphemeralSequenceNumberStore()
    val names = mutableSetOf<String>()

    override suspend fun load(contextName: String): Long? = delegate.load(contextName).also { names += contextName }

    override suspend fun store(contextName: String, limit: Long) = delegate.store(contextName, limit)
}
