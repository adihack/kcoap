package dev.kcoap.coap

import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.common.readUnsigned
import dev.kcoap.common.toHex

/**
 * One option in a message: a number and its raw value (RFC 7252 §5.4). The meaning of the number,
 * if registered, is in [definition].
 */
public class CoapOption(public val number: Int, value: ByteArray) {
    private val bytes = value.copyOf()

    init {
        require(number in 0..65535) { "Option number out of range: $number" }
        require(bytes.size <= MAX_LENGTH) { "Option value of ${bytes.size} bytes exceeds $MAX_LENGTH" }
    }

    public val value: ByteArray get() = bytes.copyOf()

    public val length: Int get() = bytes.size

    public val definition: OptionDefinition? get() = CoapOptions.byNumber(number)

    /** The value as UTF-8 text, for string-format options. */
    public fun stringValue(): String = bytes.decodeToString()

    /**
     * The value as an unsigned integer (RFC 7252 §3.2: big-endian, shortest form, zero is empty).
     *
     * @throws IllegalStateException for values longer than 7 bytes; no registered uint option is.
     */
    public fun uintValue(): Long {
        check(bytes.size <= 7) { "Option $number value of ${bytes.size} bytes is not a supported unsigned integer" }
        return bytes.readUnsigned()
    }

    override fun equals(other: Any?): Boolean = other is CoapOption && number == other.number && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * number + bytes.contentHashCode()

    /** `Uri-Path: tv1`, `Observe: 0`, or `65000: 0a0b` for unregistered numbers. */
    override fun toString(): String {
        val definition = definition ?: return "$number: ${bytes.toHex()}"
        val rendered = when (definition.format) {
            OptionFormat.EMPTY -> ""
            OptionFormat.STRING -> stringValue()
            OptionFormat.UINT -> uintValue().toString()
            OptionFormat.OPAQUE -> bytes.toHex()
        }
        return if (rendered.isEmpty()) definition.name else "${definition.name}: $rendered"
    }

    public companion object {
        /** RFC 7252 §3.1 allows values up to 65804 bytes; kcoap accepts the full range. */
        public const val MAX_LENGTH: Int = 65804

        public fun string(number: Int, value: String): CoapOption = CoapOption(number, value.encodeToByteArray())

        public fun uint(number: Int, value: Long): CoapOption = CoapOption(number, minimalUnsignedBytes(value))

        public fun empty(number: Int): CoapOption = CoapOption(number, ByteArray(0))
    }
}

/**
 * Content-Format identifiers (IANA "CoAP Content-Formats", RFC 7252 §12.3) used across kcoap.
 */
public object ContentFormat {
    public const val TEXT_PLAIN: Int = 0
    public const val LINK_FORMAT: Int = 40
    public const val XML: Int = 41
    public const val OCTET_STREAM: Int = 42
    public const val EXI: Int = 47
    public const val JSON: Int = 50
    public const val CBOR: Int = 60

    /** EDHOC messages as a CBOR sequence (RFC 9528 §10.9). */
    public const val EDHOC_CBOR_SEQ: Int = 64

    /** EDHOC messages prefixed with a connection identifier (RFC 9528 §10.9, Appendix A.2). */
    public const val CID_EDHOC_CBOR_SEQ: Int = 65

    /** OSCORE messages carried over HTTP (RFC 8613 §11). */
    public const val OSCORE: Int = 10001
}

/**
 * The value of a Block1 or Block2 option (RFC 7959 §2.2): block number, more flag and size exponent.
 * Size exponent 7 is BERT, defined only for reliable transports (RFC 8323 §6).
 */
public class BlockOption(public val number: Long, public val more: Boolean, public val sizeExponent: Int) {
    init {
        require(number in 0..MAX_NUMBER) { "Block number out of range: $number" }
        require(sizeExponent in 0..7) { "Size exponent out of range: $sizeExponent" }
    }

    /** Block size in bytes: 16 to 1024; BERT blocks are counted in units of 1024 bytes. */
    public val size: Int get() = if (isBert) 1024 else 1 shl (sizeExponent + 4)

    public val isBert: Boolean get() = sizeExponent == 7

    /** Byte offset of this block in the whole body. */
    public val offset: Long get() = number * size

    /** The option value as an unsigned integer. */
    public fun toUint(): Long = (number shl 4) or (if (more) 0x8L else 0L) or sizeExponent.toLong()

    override fun equals(other: Any?): Boolean =
        other is BlockOption && number == other.number && more == other.more && sizeExponent == other.sizeExponent

    override fun hashCode(): Int = toUint().hashCode()

    override fun toString(): String = "$number/${if (more) 1 else 0}/$size"

    public companion object {
        /** The largest block number a 3-byte option value can carry. */
        public const val MAX_NUMBER: Long = 0xFFFFF

        public fun fromUint(value: Long): BlockOption {
            require(value in 0..0xFFFFFF) { "Block option value out of range: $value" }
            return BlockOption(number = value ushr 4, more = (value and 0x8L) != 0L, sizeExponent = (value and 0x7L).toInt())
        }

        /** The size exponent for a block size of 16..1024 bytes. */
        public fun sizeExponentFor(size: Int): Int {
            require(size in 16..1024 && size and (size - 1) == 0) { "Block size must be a power of two in 16..1024: $size" }
            return size.countTrailingZeroBits() - 4
        }
    }
}
