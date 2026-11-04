package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/** Block-wise transfer helpers shared by client and server (RFC 7959). */
internal object Blockwise {
    const val MIN_BLOCK_SIZE: Int = 16
    const val MAX_BLOCK_SIZE: Int = 1024

    /**
     * The largest block size (16..1024, RFC 7959 §2.2) whose payload fits a datagram of [maxDatagramSize]
     * next to [header], the message without payload, plus [reserve] bytes for message security. Null if
     * not even 16 bytes fit. Architecture rule 3: sizes come from the transport, never from a constant.
     */
    fun fittingBlockSize(maxDatagramSize: Int, header: CoapMessage, reserve: Int, limit: Int = MAX_BLOCK_SIZE): Int? {
        val available = maxDatagramSize - payloadOffset(header) - reserve
        var size = limit
        while (size >= MIN_BLOCK_SIZE) {
            if (size <= available) return size
            size /= 2
        }
        return null
    }

    /** Whether [message] fits a datagram of [maxDatagramSize] with [reserve] bytes to spare. */
    fun fits(message: CoapMessage, maxDatagramSize: Int, reserve: Int): Boolean =
        UdpMessageCodec.encode(message).size + reserve <= maxDatagramSize

    /** Bytes before the payload: header, token, options and the payload marker. */
    private fun payloadOffset(header: CoapMessage): Int =
        UdpMessageCodec.encode(header.toBuilder().apply { payload = ByteArray(0) }.build()).size + 1

    /** The block [number] of [body] in blocks of [size], and whether more follow. Null if beyond the end. */
    fun slice(body: ByteArray, number: Long, size: Int): Pair<ByteArray, Boolean>? {
        val start = number * size
        if (start > body.size || (start == body.size.toLong() && number > 0)) return null
        val end = minOf(body.size.toLong(), start + size).toInt()
        return body.copyOfRange(start.toInt(), end) to (end < body.size)
    }

    fun block(number: Long, more: Boolean, size: Int): BlockOption =
        BlockOption(number, more, BlockOption.sizeExponentFor(size))

    /**
     * A 64-bit FNV-1a hash of [body] as an ETag for a representation sent block-wise without one, so the
     * client can tell blocks of different representations apart (RFC 7959 §2.4, RFC 9175 §3.8).
     */
    fun etagOf(body: ByteArray): ByteArray {
        var hash = FNV_OFFSET
        for (byte in body) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_PRIME
        }
        return ByteArray(8) { i -> (hash ushr (56 - 8 * i)).toByte() }
    }

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L

    /**
     * The options that identify a block-wise operation: all but Block1, Block2, Observe and the elective
     * NoCacheKey options such as Size1, Size2 and Echo (RFC 7959 §2.4, RFC 9175 §3.3). Request-Tag is
     * among them, so operations with different tags stay apart.
     */
    fun operationOptions(options: OptionSet): List<CoapOption> = options.filter { option ->
        val number = option.number
        val electiveNoCacheKey = number % 2 == 0 && (number and 0x1e) == 0x1c
        number != CoapOptions.BLOCK1.number && number != CoapOptions.BLOCK2.number &&
            number != CoapOptions.OBSERVE.number && !electiveNoCacheKey
    }
}

/** Identifies one block-wise operation of one peer (and message-security identity). */
internal data class OperationKey(
    val peer: Endpoint,
    val identity: Any?,
    val method: Int,
    val options: List<CoapOption>,
)

/**
 * A bounded map whose entries expire [lifetime] after their last use, evicting the least recently used
 * entry when full. Not thread safe.
 */
internal class ExpiringCache<K, V>(private val capacity: Int, private val lifetime: Duration) {
    private class Entry<V>(val value: V, var expiresAt: Duration)

    private val entries = LinkedHashMap<K, Entry<V>>()

    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
    }

    fun get(key: K, now: Duration): V? {
        val entry = entries.remove(key) ?: return null
        if (entry.expiresAt <= now) return null
        entry.expiresAt = now + lifetime
        entries[key] = entry
        return entry.value
    }

    fun put(key: K, value: V, now: Duration) {
        entries.remove(key)
        prune(now)
        while (entries.size >= capacity) entries.remove(entries.keys.first())
        entries[key] = Entry(value, now + lifetime)
    }

    fun remove(key: K): V? = entries.remove(key)?.value

    fun removeAll(predicate: (K) -> Boolean) {
        entries.keys.removeAll(predicate)
    }

    val size: Int get() = entries.size

    private fun prune(now: Duration) {
        entries.entries.removeAll { it.value.expiresAt <= now }
    }
}
