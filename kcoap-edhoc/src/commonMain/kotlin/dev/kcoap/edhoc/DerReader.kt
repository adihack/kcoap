package dev.kcoap.edhoc

/**
 * A minimal reader for DER (ITU-T X.690), enough to walk an X.509 certificate down to its
 * SubjectPublicKeyInfo and subject name. Single-byte tags and definite lengths only, as DER requires.
 * Every failure is an [IllegalArgumentException].
 */
internal class DerReader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {

    /** One TLV: its [tag] and the bounds of its content in [data]. */
    class Element(val tag: Int, private val data: ByteArray, private val start: Int, private val end: Int) {
        fun content(): ByteArray = data.copyOfRange(start, end)

        /** A reader over the content, for constructed types such as SEQUENCE. */
        fun reader(): DerReader = DerReader(data, start, end)
    }

    val isAtEnd: Boolean get() = position >= end

    fun peekTag(): Int {
        require(position < end) { "Unexpected end of DER data" }
        return data[position].toInt() and 0xFF
    }

    fun read(): Element {
        val tag = peekTag()
        require(tag and 0x1F != 0x1F) { "Multi-byte DER tags are not supported" }
        position++
        val length = readLength()
        require(length <= end - position) { "DER length $length exceeds the input" }
        val element = Element(tag, data, position, position + length)
        position += length
        return element
    }

    fun read(expectedTag: Int): Element {
        val element = read()
        require(element.tag == expectedTag) { "Expected DER tag $expectedTag, found ${element.tag}" }
        return element
    }

    /** X.690 §8.1.3 with the DER restrictions of §10.1: definite, minimal length encodings. */
    private fun readLength(): Int {
        require(position < end) { "Unexpected end of DER data" }
        val first = data[position++].toInt() and 0xFF
        if (first < 0x80) return first
        val count = first and 0x7F
        require(count in 1..3) { "Unsupported DER length encoding" }
        require(end - position >= count) { "Unexpected end of DER data" }
        var length = 0
        repeat(count) { length = (length shl 8) or (data[position++].toInt() and 0xFF) }
        require(length >= 0x80 && length ushr (8 * (count - 1)) != 0) { "Non-minimal DER length" }
        return length
    }

    companion object {
        const val INTEGER = 0x02
        const val BIT_STRING = 0x03
        const val OBJECT_IDENTIFIER = 0x06
        const val UTF8_STRING = 0x0C
        const val PRINTABLE_STRING = 0x13
        const val IA5_STRING = 0x16
        const val SEQUENCE = 0x30
        const val SET = 0x31
        const val CONTEXT_0 = 0xA0
    }
}
