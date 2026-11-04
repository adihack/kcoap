package dev.kcoap.cbor

/**
 * Writes CBOR in preferred serialization (RFC 8949 §4.1): shortest argument encoding and definite
 * lengths only, so the output is deterministic. Calls chain:
 *
 * ```
 * val info = CborWriter()
 *     .writeArrayHeader(3)
 *     .writeBytes(senderId).writeInt(10).writeText("Key")
 *     .toByteArray()
 * ```
 *
 * The writer does not check that headers are followed by the right number of items; [CborReader]
 * round-trips in the tests guard the protocol code that uses it.
 */
public class CborWriter {
    private var buffer = ByteArray(64)
    private var length = 0

    public val size: Int get() = length

    public fun writeInt(value: Long): CborWriter = apply {
        if (value >= 0) {
            writeHead(MAJOR_UNSIGNED, value.toULong())
        } else {
            // Major type 1 carries -1 - n, which is the bitwise complement of n.
            writeHead(MAJOR_NEGATIVE, value.inv().toULong())
        }
    }

    public fun writeInt(value: Int): CborWriter = writeInt(value.toLong())

    public fun writeBytes(bytes: ByteArray): CborWriter = apply {
        writeHead(MAJOR_BYTES, bytes.size.toULong())
        append(bytes)
    }

    public fun writeText(text: String): CborWriter = apply {
        val utf8 = text.encodeToByteArray()
        writeHead(MAJOR_TEXT, utf8.size.toULong())
        append(utf8)
    }

    public fun writeArrayHeader(count: Int): CborWriter = apply {
        require(count >= 0) { "Negative array size" }
        writeHead(MAJOR_ARRAY, count.toULong())
    }

    public fun writeMapHeader(count: Int): CborWriter = apply {
        require(count >= 0) { "Negative map size" }
        writeHead(MAJOR_MAP, count.toULong())
    }

    public fun writeTag(tag: Long): CborWriter = apply {
        require(tag >= 0) { "Negative tag" }
        writeHead(MAJOR_TAG, tag.toULong())
    }

    public fun writeBool(value: Boolean): CborWriter = apply { appendByte(if (value) SIMPLE_TRUE else SIMPLE_FALSE) }

    public fun writeNull(): CborWriter = apply { appendByte(SIMPLE_NULL) }

    public fun writeUndefined(): CborWriter = apply { appendByte(SIMPLE_UNDEFINED) }

    public fun writeSimple(value: Int): CborWriter = apply {
        require(value in 0..19 || value in 32..255) { "Not an unassigned simple value: $value" }
        writeHead(MAJOR_SIMPLE, value.toULong())
    }

    /** Appends bytes that already are one or more encoded CBOR items, such as a credential `CRED_x`. */
    public fun writeEncoded(encoded: ByteArray): CborWriter = apply { append(encoded) }

    /** Appends [value] and, recursively, its content. */
    public fun write(value: CborValue): CborWriter = apply {
        when (value) {
            is CborInt -> writeInt(value.value)
            is CborBytes -> writeBytes(value.bytes)
            is CborText -> writeText(value.value)
            is CborArray -> {
                writeArrayHeader(value.size)
                value.items.forEach { write(it) }
            }
            is CborMap -> {
                writeMapHeader(value.size)
                value.entries.forEach { (key, item) -> write(key).write(item) }
            }
            is CborTag -> writeTag(value.tag).write(value.value)
            is CborBool -> writeBool(value.value)
            CborNull -> writeNull()
            CborUndefined -> writeUndefined()
            is CborSimple -> writeSimple(value.value)
        }
    }

    public fun toByteArray(): ByteArray = buffer.copyOf(length)

    /** The initial byte and argument of a data item, in the shortest form (RFC 8949 §3). */
    private fun writeHead(major: Int, argument: ULong) {
        val type = major shl 5
        when {
            argument < 24u -> appendByte(type or argument.toInt())
            argument <= 0xFFu -> {
                appendByte(type or 24)
                appendByte(argument.toInt())
            }
            argument <= 0xFFFFu -> {
                appendByte(type or 25)
                appendBigEndian(argument, 2)
            }
            argument <= 0xFFFF_FFFFu -> {
                appendByte(type or 26)
                appendBigEndian(argument, 4)
            }
            else -> {
                appendByte(type or 27)
                appendBigEndian(argument, 8)
            }
        }
    }

    private fun appendBigEndian(value: ULong, byteCount: Int) {
        for (shift in (byteCount - 1) downTo 0) appendByte((value shr (8 * shift)).toInt() and 0xFF)
    }

    private fun appendByte(value: Int) {
        ensureCapacity(1)
        buffer[length++] = value.toByte()
    }

    private fun append(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, destinationOffset = length)
        length += bytes.size
    }

    private fun ensureCapacity(extra: Int) {
        if (length + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < length + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    internal companion object {
        const val MAJOR_UNSIGNED = 0
        const val MAJOR_NEGATIVE = 1
        const val MAJOR_BYTES = 2
        const val MAJOR_TEXT = 3
        const val MAJOR_ARRAY = 4
        const val MAJOR_MAP = 5
        const val MAJOR_TAG = 6
        const val MAJOR_SIMPLE = 7

        const val SIMPLE_FALSE = 0xF4
        const val SIMPLE_TRUE = 0xF5
        const val SIMPLE_NULL = 0xF6
        const val SIMPLE_UNDEFINED = 0xF7
    }
}
