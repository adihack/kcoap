package dev.kcoap.cbor

import dev.kcoap.common.KcoapException

/**
 * Thrown for malformed or unsupported CBOR (RFC 8949), with the byte offset where reading stopped, and for COSE
 * structures that are not what RFC 9052 requires. A [KcoapException].
 */
public class CborException(message: String, public val offset: Int = -1) :
    KcoapException(if (offset >= 0) "$message (at byte $offset)" else message, null)

/** The eight CBOR major types (RFC 8949 §3.1). */
public enum class CborType { UNSIGNED, NEGATIVE, BYTES, TEXT, ARRAY, MAP, TAG, SIMPLE }

/**
 * Reads CBOR data items one at a time from [data], for protocol code that parses a fixed structure or a
 * CBOR sequence (RFC 8742), as EDHOC messages are:
 *
 * ```
 * val reader = CborReader(message1)
 * val method = reader.readInt()
 * val suites = reader.readValue()
 * ```
 *
 * Accepted: definite-length items of every major type except floating point. Rejected with
 * [CborException]: indefinite lengths, floats, reserved encodings, integers outside the Long range,
 * invalid UTF-8, truncated input, and nesting deeper than [maxDepth].
 *
 * Encodings that are valid but not the shortest are accepted, so peers with less strict encoders still
 * interoperate. Use [readEncodedItem] when the exact received bytes matter, as they do for EDHOC
 * transcript hashes.
 */
public class CborReader(
    private val data: ByteArray,
    start: Int = 0,
    private val end: Int = data.size,
    private val maxDepth: Int = 32,
) {
    init {
        require(start in 0..end && end <= data.size) { "Invalid range $start..$end of ${data.size} bytes" }
    }

    /** Offset of the next unread byte in [data]. */
    public var position: Int = start
        private set

    public val isAtEnd: Boolean get() = position >= end

    public val remaining: Int get() = end - position

    /** The major type of the next item, without consuming it. */
    public fun peekType(): CborType {
        ensureAvailable(1)
        return CborType.entries[(data[position].toInt() and 0xFF) ushr 5]
    }

    /** Whether the next item is `null`, without consuming it. */
    public fun peekIsNull(): Boolean = !isAtEnd && (data[position].toInt() and 0xFF) == CborWriter.SIMPLE_NULL

    public fun readInt(): Long {
        val start = position
        val head = readHead()
        if (head.argument > Long.MAX_VALUE.toULong()) throw CborException("Integer exceeds the Long range", start)
        return when (head.major) {
            CborWriter.MAJOR_UNSIGNED -> head.argument.toLong()
            CborWriter.MAJOR_NEGATIVE -> head.argument.toLong().inv()
            else -> throw CborException("Expected an integer, found ${CborType.entries[head.major]}", start)
        }
    }

    public fun readBytes(): ByteArray = readString(CborWriter.MAJOR_BYTES)

    public fun readText(): String {
        val start = position
        val utf8 = readString(CborWriter.MAJOR_TEXT)
        return try {
            utf8.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw CborException("Text string is not valid UTF-8", start)
        }
    }

    /** Reads an array header and returns the number of items that follow. */
    public fun readArrayHeader(): Int = readCount(CborWriter.MAJOR_ARRAY)

    /** Reads a map header and returns the number of key/value pairs that follow. */
    public fun readMapHeader(): Int = readCount(CborWriter.MAJOR_MAP)

    public fun readTag(): Long {
        val start = position
        val head = readHead()
        if (head.major != CborWriter.MAJOR_TAG) throw CborException("Expected a tag", start)
        if (head.argument > Long.MAX_VALUE.toULong()) throw CborException("Tag exceeds the Long range", start)
        return head.argument.toLong()
    }

    public fun readBool(): Boolean = when (val value = readValue()) {
        is CborBool -> value.value
        else -> throw CborException("Expected a boolean, found $value", position)
    }

    public fun readNull() {
        val value = readValue()
        if (value != CborNull) throw CborException("Expected null, found $value", position)
    }

    /** Reads the next complete data item. */
    public fun readValue(): CborValue = readValue(depth = 0)

    /** The exact bytes of the next complete data item, as received. */
    public fun readEncodedItem(): ByteArray {
        val start = position
        readValue()
        return data.copyOfRange(start, position)
    }

    public fun skip() {
        readValue()
    }

    private fun readValue(depth: Int): CborValue {
        if (depth > maxDepth) throw CborException("Nesting deeper than $maxDepth", position)
        val start = position
        val head = readHead()
        return when (head.major) {
            CborWriter.MAJOR_UNSIGNED, CborWriter.MAJOR_NEGATIVE -> {
                position = start
                CborInt(readInt())
            }
            CborWriter.MAJOR_BYTES -> CborBytes(take(lengthOf(head, start)))
            CborWriter.MAJOR_TEXT -> {
                position = start
                CborText(readText())
            }
            CborWriter.MAJOR_ARRAY -> {
                val count = countOf(head, start)
                CborArray(List(count) { readValue(depth + 1) })
            }
            CborWriter.MAJOR_MAP -> {
                val count = countOf(head, start)
                CborMap(List(count) { readValue(depth + 1) to readValue(depth + 1) })
            }
            CborWriter.MAJOR_TAG -> {
                if (head.argument > Long.MAX_VALUE.toULong()) throw CborException("Tag exceeds the Long range", start)
                CborTag(head.argument.toLong(), readValue(depth + 1))
            }
            else -> simpleValue(head, start)
        }
    }

    private fun simpleValue(head: Head, start: Int): CborValue {
        if (head.additionalInfo in 25..27) throw CborException("Floating-point values are not supported", start)
        // RFC 8949 §3.3: the one-byte extension must not encode values below 32.
        if (head.additionalInfo == 24 && head.argument < 32u) throw CborException("Invalid simple value encoding", start)
        return when (val value = head.argument.toInt()) {
            20 -> CborBool(false)
            21 -> CborBool(true)
            22 -> CborNull
            23 -> CborUndefined
            else -> CborSimple(value)
        }
    }

    private class Head(val major: Int, val additionalInfo: Int, val argument: ULong)

    private fun readHead(): Head {
        val start = position
        ensureAvailable(1)
        val initial = data[position++].toInt() and 0xFF
        val major = initial ushr 5
        val info = initial and 0x1F
        val argument: ULong = when {
            info < 24 -> info.toULong()
            info == 24 -> readBigEndian(1)
            info == 25 -> readBigEndian(2)
            info == 26 -> readBigEndian(4)
            info == 27 -> readBigEndian(8)
            info == 31 -> throw CborException("Indefinite-length items are not supported", start)
            else -> throw CborException("Reserved additional information $info", start)
        }
        return Head(major, info, argument)
    }

    private fun readString(major: Int): ByteArray {
        val start = position
        val head = readHead()
        if (head.major != major) {
            throw CborException("Expected ${CborType.entries[major]}, found ${CborType.entries[head.major]}", start)
        }
        return take(lengthOf(head, start))
    }

    private fun readCount(major: Int): Int {
        val start = position
        val head = readHead()
        if (head.major != major) {
            throw CborException("Expected ${CborType.entries[major]}, found ${CborType.entries[head.major]}", start)
        }
        return countOf(head, start)
    }

    /** A byte or text string length, which must fit the remaining input. */
    private fun lengthOf(head: Head, start: Int): Int {
        if (head.argument > remaining.toULong()) throw CborException("Length ${head.argument} exceeds the input", start)
        return head.argument.toInt()
    }

    /** An item count; each item takes at least one byte, which bounds allocation on hostile input. */
    private fun countOf(head: Head, start: Int): Int {
        if (head.argument > remaining.toULong()) throw CborException("Count ${head.argument} exceeds the input", start)
        return head.argument.toInt()
    }

    private fun take(count: Int): ByteArray {
        ensureAvailable(count)
        val bytes = data.copyOfRange(position, position + count)
        position += count
        return bytes
    }

    private fun readBigEndian(count: Int): ULong {
        ensureAvailable(count)
        var value = 0uL
        repeat(count) { value = (value shl 8) or (data[position++].toInt() and 0xFF).toULong() }
        return value
    }

    private fun ensureAvailable(count: Int) {
        if (remaining < count) throw CborException("Unexpected end of input", position)
    }
}
