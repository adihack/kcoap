package dev.kcoap.common

/** A growable byte buffer for encoders. Methods chain; [toByteArray] returns a copy of what was written. */
@InternalKcoapApi
public class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buffer = ByteArray(maxOf(initialCapacity, 1))

    public var size: Int = 0
        private set

    public fun append(byte: Int): ByteArrayBuilder = apply {
        ensureCapacity(1)
        buffer[size++] = byte.toByte()
    }

    public fun append(bytes: ByteArray): ByteArrayBuilder = apply {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, destinationOffset = size)
        size += bytes.size
    }

    /** Appends the low [byteCount] bytes of [value], most significant first. */
    public fun appendBigEndian(value: Long, byteCount: Int): ByteArrayBuilder = apply {
        require(byteCount in 0..8) { "Invalid byte count $byteCount" }
        for (shift in (byteCount - 1) downTo 0) append(((value ushr (8 * shift)) and 0xFF).toInt())
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensureCapacity(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}
