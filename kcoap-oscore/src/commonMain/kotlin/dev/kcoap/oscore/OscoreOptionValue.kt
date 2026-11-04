package dev.kcoap.oscore

import dev.kcoap.common.ByteArrayBuilder
import dev.kcoap.common.toHex

/**
 * The value of the OSCORE option: the compressed COSE header (RFC 8613 §6.1).
 *
 * ```
 *  0 1 2 3 4 5 6 7
 * |0 0 0|h|k|  n  | Partial IV (n bytes) | s | kid context (s bytes) | kid (rest) |
 * ```
 *
 * A field that is null is absent; an empty [kid] is present but empty, as in RFC 8613 Appendix C.4.
 */
public class OscoreOptionValue(
    partialIv: ByteArray? = null,
    kid: ByteArray? = null,
    kidContext: ByteArray? = null,
) {
    private val piv = partialIv?.copyOf()
    private val kidBytes = kid?.copyOf()
    private val kidContextBytes = kidContext?.copyOf()

    init {
        require(piv == null || piv.size in 1..5) { "Partial IV must be 1 to 5 bytes" }
        require(kidContextBytes == null || kidContextBytes.size <= 255) { "kid context longer than 255 bytes" }
    }

    public val partialIv: ByteArray? get() = piv?.copyOf()
    public val kid: ByteArray? get() = kidBytes?.copyOf()
    public val kidContext: ByteArray? get() = kidContextBytes?.copyOf()

    /** The option value; empty when nothing is present (§6.1: flag byte 0x00 is never sent). */
    public fun encode(): ByteArray {
        if (piv == null && kidBytes == null && kidContextBytes == null) return ByteArray(0)
        val flags = (piv?.size ?: 0) or
            (if (kidBytes != null) KID_FLAG else 0) or
            (if (kidContextBytes != null) KID_CONTEXT_FLAG else 0)
        val out = ByteArrayBuilder(16).append(flags)
        piv?.let { out.append(it) }
        kidContextBytes?.let { out.append(it.size).append(it) }
        kidBytes?.let { out.append(it) }
        return out.toByteArray()
    }

    override fun toString(): String = buildString {
        append("OscoreOptionValue(")
        append(listOfNotNull(
            piv?.let { "piv=${it.toHex()}" },
            kidBytes?.let { "kid=${it.toHex()}" },
            kidContextBytes?.let { "kidContext=${it.toHex()}" },
        ).joinToString())
        append(')')
    }

    public companion object {
        private const val KID_FLAG = 0x08
        private const val KID_CONTEXT_FLAG = 0x10
        private const val RESERVED_FLAGS = 0xE0

        /** @throws OscoreException with [OscoreError.DECODE_FAILED] for malformed values. */
        public fun decode(value: ByteArray): OscoreOptionValue {
            if (value.isEmpty()) return OscoreOptionValue()
            val flags = value[0].toInt() and 0xFF
            if (flags and RESERVED_FLAGS != 0) throw OscoreException(OscoreError.DECODE_FAILED, "reserved flag bits set")
            val pivLength = flags and 0x07
            if (pivLength > 5) throw OscoreException(OscoreError.DECODE_FAILED, "reserved Partial IV length $pivLength")

            var offset = 1
            if (offset + pivLength > value.size) throw OscoreException(OscoreError.DECODE_FAILED, "truncated Partial IV")
            val piv = if (pivLength > 0) value.copyOfRange(offset, offset + pivLength) else null
            offset += pivLength

            var kidContext: ByteArray? = null
            if (flags and KID_CONTEXT_FLAG != 0) {
                if (offset >= value.size) throw OscoreException(OscoreError.DECODE_FAILED, "missing kid context length")
                val length = value[offset].toInt() and 0xFF
                offset++
                if (offset + length > value.size) throw OscoreException(OscoreError.DECODE_FAILED, "truncated kid context")
                kidContext = value.copyOfRange(offset, offset + length)
                offset += length
            }

            val kid = if (flags and KID_FLAG != 0) value.copyOfRange(offset, value.size) else null
            if (kid == null && offset != value.size) throw OscoreException(OscoreError.DECODE_FAILED, "trailing bytes without the kid flag")
            return OscoreOptionValue(piv, kid, kidContext)
        }
    }
}
