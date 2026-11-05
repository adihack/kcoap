package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.codec.MessageCodec
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration

/**
 * What one message to a peer may be: at most [maxMessageSize] bytes in [codec]'s framing. That is the
 * transport's datagram size for UDP (architecture rule 3), the peer's Max-Message-Size on a connection
 * (RFC 8323 §5.3.1).
 */
internal class MessageLimits(
    val maxMessageSize: Int,
    private val codec: MessageCodec,
    /** RFC 8323 §6: both CSMs indicated BERT, so one message may carry several 1024-byte blocks. */
    val allowsBert: Boolean,
    /** On a reliable transport SZX 7 means BERT; on datagrams it is reserved (RFC 7959 §2.2). */
    val isReliable: Boolean,
) {
    /** The encoded size of [message]. */
    fun size(message: CoapMessage): Int = codec.encode(message).size

    /** Whether [message] fits with [reserve] bytes to spare for message security. */
    fun fits(message: CoapMessage, reserve: Int): Boolean = size(message) + reserve <= maxMessageSize

    /**
     * Payload bytes that fit next to [header] (its payload is ignored) and [reserve]: the header, token,
     * options and payload marker come off, and on a stream the 4 bytes the length field can grow by.
     */
    fun payloadRoom(header: CoapMessage, reserve: Int): Int {
        val bare = size(header.toBuilder().apply { payload = ByteArray(0) }.build())
        return maxMessageSize - reserve - bare - 1 - (if (isReliable) LENGTH_GROWTH else 0)
    }

    override fun toString(): String = "MessageLimits(maxMessageSize=$maxMessageSize, bert=$allowsBert)"

    private companion object {
        /** RFC 8323 §3.2: the extended length takes at most 4 bytes. */
        const val LENGTH_GROWTH = 4
    }
}

/**
 * How a body is cut into blocks: [chunk] payload bytes per message, numbered in units of [unit] bytes.
 * Plain blocks are 16 to 1024 bytes (RFC 7959 §2.2); a BERT block (SZX 7, RFC 8323 §6) is a multiple of
 * 1024 bytes numbered in 1024-byte units.
 */
internal class BlockSizing private constructor(val chunk: Int, val sizeExponent: Int) {
    val isBert: Boolean get() = sizeExponent == BERT_EXPONENT

    val unit: Int get() = if (isBert) Blockwise.MAX_BLOCK_SIZE else chunk

    /** The Block option for the block at [offset]. */
    fun option(offset: Long, more: Boolean): BlockOption = BlockOption(offset / unit, more, sizeExponent)

    /**
     * How many bytes the block at [offset] of a [total]-byte body carries. RFC 8323 §6 lets a final BERT
     * block hold several 1024-byte blocks plus a partial one; libcoap 4.3.5 rejects such a block with 4.08,
     * so when the rest of the body fits one BERT block but exceeds 1024 bytes, this block stops at the last
     * 1024-byte boundary before the end and the final block carries at most 1024 bytes. Both are valid BERT
     * blocks; it costs one message at most.
     */
    fun length(offset: Long, total: Int): Int {
        val remaining = total - offset
        if (!isBert || remaining <= unit || remaining > chunk) return chunk
        return ((remaining - 1) / unit * unit).toInt()
    }

    override fun toString(): String = if (isBert) "BERT($chunk)" else "$chunk"

    companion object {
        private const val BERT_EXPONENT = 7

        fun of(size: Int): BlockSizing = BlockSizing(size, BlockOption.sizeExponentFor(size))

        fun bert(chunk: Int): BlockSizing {
            require(chunk > 0 && chunk % Blockwise.MAX_BLOCK_SIZE == 0) { "BERT blocks are multiples of 1024 bytes: $chunk" }
            return BlockSizing(chunk, BERT_EXPONENT)
        }
    }
}

/** Block-wise transfer helpers shared by client and server (RFC 7959, RFC 8323 §6). */
internal object Blockwise {
    const val MIN_BLOCK_SIZE: Int = 16
    const val MAX_BLOCK_SIZE: Int = 1024

    /**
     * The largest block size (16..1024, RFC 7959 §2.2) whose payload fits a message within [limits] next
     * to [header], the message without payload, plus [reserve] bytes for message security. Null if not
     * even 16 bytes fit. Architecture rule 3: sizes come from the transport, never from a constant.
     */
    fun fittingBlockSize(limits: MessageLimits, header: CoapMessage, reserve: Int, limit: Int = MAX_BLOCK_SIZE): Int? {
        val available = limits.payloadRoom(header, reserve)
        var size = limit
        while (size >= MIN_BLOCK_SIZE) {
            if (size <= available) return size
            size /= 2
        }
        return null
    }

    /**
     * BERT blocks for a peer that indicated BERT (RFC 8323 §5.3.2, §6): as many 1024-byte blocks per
     * message as its Max-Message-Size takes next to [header], or null when BERT is not possible.
     */
    fun bertSizing(limits: MessageLimits, header: CoapMessage, reserve: Int): BlockSizing? {
        if (!limits.allowsBert) return null
        val blocks = limits.payloadRoom(header, reserve) / MAX_BLOCK_SIZE
        return if (blocks >= 1) BlockSizing.bert(blocks * MAX_BLOCK_SIZE) else null
    }

    /** Whether [message] fits a message within [limits] with [reserve] bytes to spare. */
    fun fits(message: CoapMessage, limits: MessageLimits, reserve: Int): Boolean = limits.fits(message, reserve)

    /** The [length] bytes of [body] from [offset], and whether more follow. Null if beyond the end. */
    fun slice(body: ByteArray, offset: Long, length: Int): Pair<ByteArray, Boolean>? {
        if (offset > body.size || (offset == body.size.toLong() && offset > 0)) return null
        val end = minOf(body.size.toLong(), offset + length).toInt()
        return body.copyOfRange(offset.toInt(), end) to (end < body.size)
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
     * The options that identify a block-wise operation: all but Block1, Block2, Q-Block1, Q-Block2, Observe
     * and the elective NoCacheKey options such as Size1, Size2 and Echo (RFC 7959 §2.4, RFC 9175 §3.3,
     * RFC 9177 §4.3). Request-Tag is among them, so operations with different tags stay apart.
     */
    fun operationOptions(options: OptionSet): List<CoapOption> = options.filter { option ->
        val number = option.number
        val electiveNoCacheKey = number % 2 == 0 && (number and 0x1e) == 0x1c
        number !in BLOCK_OPTIONS && number != CoapOptions.OBSERVE.number && !electiveNoCacheKey
    }

    private val BLOCK_OPTIONS = setOf(
        CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number, CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number,
    )
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
 *
 * [put] and [get] give an entry the expiry `now + lifetime` and move it to the end; [peek] changes
 * neither. With one lifetime for all entries and `now` never going back, the entries are therefore in
 * order of expiry, so the expired ones are always the first and pruning them costs only their number.
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

    /** Like [get], but leaves the entry's expiry and its place in the eviction order as they are. */
    fun peek(key: K, now: Duration): V? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt > now) return entry.value
        entries.remove(key)
        return null
    }

    /** Stores [value]; returns the key evicted to make room for it, or null when there was room. */
    fun put(key: K, value: V, now: Duration): K? {
        entries.remove(key)
        prune(now)
        var evicted: K? = null
        if (entries.size >= capacity) evicted = entries.keys.first().also { entries.remove(it) }
        entries[key] = Entry(value, now + lifetime)
        return evicted
    }

    fun remove(key: K): V? = entries.remove(key)?.value

    /** Removes the entries whose key matches [predicate] and returns their values, so the caller can release them. */
    fun removeAll(predicate: (K) -> Boolean): List<V> {
        val removed = ArrayList<V>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                removed += entry.value.value
                iterator.remove()
            }
        }
        return removed
    }

    val size: Int get() = entries.size

    /** Every entry, expired or not, from the one used longest ago to the latest; nothing changes. */
    val entryList: List<Pair<K, V>> get() = entries.map { (key, entry) -> key to entry.value }

    /** When the first entry expires, the earliest of all, or null when the cache is empty. */
    val earliestExpiry: Duration? get() = entries.values.firstOrNull()?.expiresAt

    /** Drops the expired entries, which are the first ones, and returns their values. */
    fun prune(now: Duration): List<V> {
        val removed = ArrayList<V>()
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.expiresAt > now) break
            removed += entry.value
            iterator.remove()
        }
        return removed
    }

    /** The keys from the first entry to the last, for tests. */
    val keys: List<K> get() = entries.keys.toList()
}
