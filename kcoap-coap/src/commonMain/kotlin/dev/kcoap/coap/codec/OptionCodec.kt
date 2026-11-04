package dev.kcoap.coap.codec

import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.OptionSet
import dev.kcoap.common.ByteArrayBuilder

/**
 * The option and payload part shared by every CoAP framing (RFC 7252 §3.1, RFC 8323 §3.2): delta-encoded
 * options, then an optional 0xFF marker followed by a non-empty payload.
 */
internal object OptionCodec {
    private const val PAYLOAD_MARKER = 0xFF

    fun write(options: OptionSet, payload: ByteArray, out: ByteArrayBuilder) {
        var previous = 0
        for (option in options) {
            val delta = option.number - previous
            val value = option.value
            val deltaField = Field.of(delta)
            val lengthField = Field.of(value.size)
            out.append((deltaField.nibble shl 4) or lengthField.nibble)
            deltaField.writeExtension(out)
            lengthField.writeExtension(out)
            out.append(value)
            previous = option.number
        }
        if (payload.isNotEmpty()) {
            out.append(PAYLOAD_MARKER)
            out.append(payload)
        }
    }

    /** Parsed options and the payload that followed them. */
    class Parsed(val options: OptionSet, val payload: ByteArray)

    /** Reads options and payload from `bytes[start, end)`; [fail] builds the exception to throw. */
    fun read(bytes: ByteArray, start: Int, end: Int, fail: (String) -> CoapFormatException): Parsed {
        val options = OptionSet.Builder()
        var number = 0
        var offset = start
        while (offset < end) {
            val initial = bytes[offset++].toInt() and 0xFF
            if (initial == PAYLOAD_MARKER) {
                // RFC 7252 §3: a marker followed by a zero-length payload is a format error.
                if (offset == end) throw fail("Payload marker without payload")
                return Parsed(options.build(), bytes.copyOfRange(offset, end))
            }
            val delta = readExtended(initial ushr 4, bytes, offset, end, fail)
            offset += delta.extensionLength
            val length = readExtended(initial and 0x0F, bytes, offset, end, fail)
            offset += length.extensionLength
            number += delta.value
            if (number > 65535) throw fail("Option number $number exceeds 65535")
            if (length.value > end - offset) throw fail("Option $number value runs past the message end")
            options.add(CoapOption(number, bytes.copyOfRange(offset, offset + length.value)))
            offset += length.value
        }
        return Parsed(options.build(), ByteArray(0))
    }

    private class Extended(val value: Int, val extensionLength: Int)

    private fun readExtended(nibble: Int, bytes: ByteArray, offset: Int, end: Int, fail: (String) -> CoapFormatException): Extended =
        when (nibble) {
            in 0..12 -> Extended(nibble, 0)
            13 -> {
                if (offset + 1 > end) throw fail("Truncated option header")
                Extended((bytes[offset].toInt() and 0xFF) + 13, 1)
            }
            14 -> {
                if (offset + 2 > end) throw fail("Truncated option header")
                Extended((((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)) + 269, 2)
            }
            else -> throw fail("Reserved option nibble 15")
        }

    /** A delta or length in its 4-bit field plus 0, 1 or 2 extension bytes. */
    private class Field private constructor(val nibble: Int, private val extension: Int, private val extensionLength: Int) {
        fun writeExtension(out: ByteArrayBuilder) {
            out.appendBigEndian(extension.toLong(), extensionLength)
        }

        companion object {
            fun of(value: Int): Field = when {
                value < 13 -> Field(value, 0, 0)
                value < 269 -> Field(13, value - 13, 1)
                else -> Field(14, value - 269, 2)
            }
        }
    }
}
