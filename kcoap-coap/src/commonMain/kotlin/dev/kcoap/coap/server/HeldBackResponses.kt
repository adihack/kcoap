package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * The responses a server held back from requests that must not run twice, each kept for the repeat of
 * its own request with Echo (RFC 9175 §2.4 item 3). A response is found by the client's address and the
 * request: its method, its options without Echo and its payload, so every request of one client keeps
 * its own response, however many are challenged before the first repeat. Identical requests keep one
 * response each and get them in the order they were kept.
 *
 * At most [capacity] responses are kept, each for [lifetime]; the oldest gives way to a new one. Every
 * operation takes constant time. Not thread safe.
 */
internal class HeldBackResponses(private val capacity: Int, private val lifetime: Duration) {
    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
    }

    /** What a repeat must match: the address it comes from, its method, options without Echo and payload. */
    class Key(val peer: Endpoint, request: CoapMessage) {
        private val code: CoapCode = request.code
        private val options: List<CoapOption> = request.options.filter { it.number != CoapOptions.ECHO.number }
        private val payload: ByteArray = request.payload
        private val hash = ((peer.hashCode() * 31 + code.hashCode()) * 31 + options.hashCode()) * 31 + payload.contentHashCode()

        override fun equals(other: Any?): Boolean = other is Key && hash == other.hash && peer == other.peer &&
            code == other.code && options == other.options && payload.contentEquals(other.payload)

        override fun hashCode(): Int = hash
    }

    private class Entry(val key: Key, val response: CoapResponse, val expiresAt: Duration)

    /** Every entry by its serial number, oldest first; one lifetime for all, so also in order of expiry. */
    private val entries = LinkedHashMap<Long, Entry>()

    /** The serial numbers of the entries kept for each request, oldest first. */
    private val serials = HashMap<Key, ArrayDeque<Long>>()
    private var nextSerial = 0L

    val size: Int get() = entries.size

    /** Keeps [response] for the repeat of [key]'s request; returns the address whose response gave way to it. */
    fun put(key: Key, response: CoapResponse, now: Duration): Endpoint? {
        prune(now)
        val evicted = if (entries.size >= capacity) removeOldest().peer else null
        val serial = nextSerial++
        entries[serial] = Entry(key, response, now + lifetime)
        serials.getOrPut(key) { ArrayDeque() }.addLast(serial)
        return evicted
    }

    /** The response kept longest for [key]'s request, which is handed out only once. */
    fun take(key: Key, now: Duration): CoapResponse? {
        prune(now)
        val queue = serials[key] ?: return null
        val serial = queue.removeFirst()
        if (queue.isEmpty()) serials.remove(key)
        return entries.remove(serial)?.response
    }

    private fun prune(now: Duration) {
        while (entries.isNotEmpty() && entries.values.first().expiresAt <= now) removeOldest()
    }

    /** Removes the oldest entry, which is also the oldest kept for its request; returns its key. */
    private fun removeOldest(): Key {
        val serial = entries.keys.first()
        val key = entries.getValue(serial).key
        entries.remove(serial)
        val queue = serials.getValue(key)
        queue.removeFirst()
        if (queue.isEmpty()) serials.remove(key)
        return key
    }
}
