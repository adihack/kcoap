package dev.kcoap.edhoc

import dev.kcoap.cbor.CborType
import dev.kcoap.cbor.CborWriter

/**
 * One External Authorization Data item, `ead = ( ead_label : int, ? ead_value : bstr )` (RFC 9528 §3.8).
 *
 * A negative label marks the item critical: a receiver that does not recognize or cannot process it
 * must abort. Label 0 is padding (RFC 9528 §3.8.1), which receivers discard.
 */
public class EadItem(public val label: Int, value: ByteArray? = null) {
    private val content = value?.copyOf()

    /** ead_value, or null when the item has none. */
    public val value: ByteArray? get() = content?.copyOf()

    public val isCritical: Boolean get() = label < 0

    public val isPadding: Boolean get() = label == 0

    internal fun writeTo(writer: CborWriter) {
        writer.writeInt(label)
        content?.let(writer::writeBytes)
    }

    override fun equals(other: Any?): Boolean =
        other is EadItem && label == other.label && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * label + (content?.contentHashCode() ?: 0)

    override fun toString(): String = "EadItem($label${content?.let { ", ${it.size} bytes" } ?: ""})"

    public companion object {
        /**
         * Padding (RFC 9528 §3.8.1). [value] should be pseudorandom; `null` gives the one-byte padding
         * `0x00`, an empty array the two-byte `0x0040`.
         */
        public fun padding(value: ByteArray? = null): EadItem = EadItem(0, value)

        /** EAD_x as the CBOR sequence that follows the other fields of a message. */
        internal fun encode(items: List<EadItem>): ByteArray {
            val writer = CborWriter()
            items.forEach { it.writeTo(writer) }
            return writer.toByteArray()
        }

        /** Reads EAD items until the end of [reader]. */
        internal fun readAll(reader: MessageReader): List<EadItem> {
            val items = mutableListOf<EadItem>()
            while (!reader.isAtEnd) {
                val label = reader.readInt()
                val value = if (reader.peekType() == CborType.BYTES) reader.readBytes() else null
                items += EadItem(label, value)
            }
            return items
        }
    }
}

/** The four EDHOC messages that carry EAD fields (RFC 9528 §3.8). */
public enum class EdhocMessage {
    MESSAGE_1,
    MESSAGE_2,
    MESSAGE_3,
    MESSAGE_4,
}

/**
 * Produces and consumes External Authorization Data for a security application (RFC 9528 §3.8,
 * Appendix E). Both methods have defaults, so a handler overrides only what it uses.
 *
 * Received items arrive before the message that carries them is verified, as RFC 9528 §5.3.3 and §5.4.3
 * order it: treat them as unauthenticated until the processing call returns. Padding never reaches the
 * handler. Throw an [EdhocException] to abort the session with a specific error.
 */
public interface EadHandler {
    /** The items to send in EAD_x of [message]; empty for none. */
    public suspend fun produce(message: EdhocMessage): List<EadItem> = emptyList()

    /**
     * Processes one received item and returns whether it was recognized and processed. An unprocessed
     * critical item aborts the session (RFC 9528 §3.8); an unprocessed non-critical one is ignored.
     */
    public suspend fun consume(message: EdhocMessage, item: EadItem): Boolean = false

    public companion object {
        /** Sends nothing and recognizes nothing. */
        public val None: EadHandler = object : EadHandler {}
    }
}

/** Hands received items to the handler and enforces the criticality rule of RFC 9528 §3.8. */
internal suspend fun EadHandler.receive(message: EdhocMessage, items: List<EadItem>) {
    for (item in items) {
        if (item.isPadding) continue
        if (!consume(message, item) && item.isCritical) {
            throw EdhocException.unspecified("Unsupported critical EAD item ${item.label}")
        }
    }
}

/** For logs: labels only, never values. */
internal fun List<EadItem>.describe(): String =
    if (isEmpty()) "none" else joinToString(prefix = "[", postfix = "]") { it.label.toString() }
