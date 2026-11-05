package dev.kcoap.coap.stack

import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether the peers of one [CoapEndpoint] support Q-Block (RFC 9177 §4.1), as its clients found out, per peer
 * and per protection (with or without message security). The endpoint keeps one, shared by all its clients,
 * and forgets a peer here when the peer's DTLS session ends or its connection closes, so a client registers
 * nothing with the endpoint and leaves nothing behind when it is dropped without being closed.
 *
 * Each entry lasts until the time it was recorded with. Entries are bounded by [maxEntries]; the oldest goes
 * first. Safe to use from any coroutine.
 */
internal class QBlockCapabilities(private val maxEntries: Int = MAX_ENTRIES) {
    init {
        require(maxEntries >= 1) { "At least one entry must fit: $maxEntries" }
    }

    /** What is known about a peer. */
    enum class Capability {
        /** The peer takes Q-Block. */
        SUPPORTED,

        /** The peer rejected Q-Block, or answered the probe without it. */
        UNSUPPORTED,

        /** The probe got no answer; requests use RFC 7959 until it is repeated. */
        UNKNOWN,
    }

    private data class Key(val peer: Endpoint, val isProtected: Boolean)

    private class Entry(val capability: Capability, val until: Duration)

    /** Guards [entries]; never held across a suspension. */
    private val lock = Mutex()
    private val entries = LinkedHashMap<Key, Entry>()

    /** What is known about [peer] at [now], or null; an expired entry is removed. */
    suspend fun get(peer: Endpoint, isProtected: Boolean, now: Duration): Capability? = lock.withLock {
        val key = Key(peer, isProtected)
        val entry = entries[key] ?: return null
        if (entry.until > now) return entry.capability
        entries.remove(key)
        null
    }

    /** Records [capability] for [peer] until [until], dropping expired entries and, beyond [maxEntries], the oldest. */
    suspend fun put(peer: Endpoint, isProtected: Boolean, capability: Capability, until: Duration, now: Duration) {
        lock.withLock {
            val key = Key(peer, isProtected)
            entries.remove(key)
            entries.values.removeAll { it.until <= now }
            while (entries.size >= maxEntries) entries.remove(entries.keys.first())
            entries[key] = Entry(capability, until)
        }
    }

    /** Forgets [peer], with and without protection; true if anything was known. */
    suspend fun invalidate(peer: Endpoint): Boolean = lock.withLock { entries.keys.removeAll { it.peer == peer } }

    /** How many entries there are, expired ones included; for tests. */
    suspend fun size(): Int = lock.withLock { entries.size }

    override fun toString(): String = "QBlockCapabilities(max=$maxEntries)"

    companion object {
        /** How many peers are remembered at most. */
        const val MAX_ENTRIES: Int = 1024
    }
}
