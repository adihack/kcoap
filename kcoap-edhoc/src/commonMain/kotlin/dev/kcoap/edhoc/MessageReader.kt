package dev.kcoap.edhoc

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborReader
import dev.kcoap.cbor.CborType
import dev.kcoap.cbor.CborValue

/**
 * Reads an EDHOC message or plaintext as a CBOR sequence, one data item at a time.
 *
 * Every item must be in preferred serialization: RFC 9528 §3.1 defines EDHOC messages as deterministically
 * encoded CBOR, and §9.8 recommends aborting otherwise. That rejects the non-shortest encodings of
 * RFC 9529 §4.3.1; indefinite lengths (§4.3.2) are already refused by [CborReader]. Map key order is not
 * checked, because ID_CRED_x maps need not be sorted (RFC 9528 §3.5.3).
 *
 * Every failure is an [EdhocException] naming [what] was malformed.
 */
internal class MessageReader(private val bytes: ByteArray, private val what: String) {
    private val reader = CborReader(bytes)

    val isAtEnd: Boolean get() = reader.isAtEnd

    /** The next item as received, after checking it is deterministically encoded. */
    fun readEncoded(): ByteArray = readItem().first

    /** The next item, decoded, after checking it is deterministically encoded. */
    fun readValue(): CborValue = readItem().second

    private fun readItem(): Pair<ByteArray, CborValue> = parse {
        val encoded = reader.readEncodedItem()
        val value = Cbor.decode(encoded)
        if (!value.encode().contentEquals(encoded)) throw malformed("not deterministically encoded")
        encoded to value
    }

    /** The next item, which must be an integer in the Int range. */
    fun readInt(): Int {
        val value = readValue()
        val number = (value as? CborInt)?.value ?: throw malformed("expected an integer")
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) throw malformed("integer out of range")
        return number.toInt()
    }

    fun readBytes(): ByteArray = (readValue() as? CborBytes)?.bytes ?: throw malformed("expected a byte string")

    /** The major type of the next item, or null at the end. */
    fun peekType(): CborType? = if (reader.isAtEnd) null else parse { reader.peekType() }

    fun expectEnd() {
        if (!reader.isAtEnd) throw malformed("${reader.remaining} unexpected trailing bytes")
    }

    fun malformed(reason: String): EdhocException = EdhocException.malformed("$what: $reason")

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: CborException) {
        throw EdhocException.malformed(what, e)
    }

    companion object {
        /**
         * Whether [bytes] starts with an integer, which in reply to message_1, message_2 or message_3 means
         * an error message: message_2, message_3 and message_4 start with a byte string (RFC 9528 §5).
         */
        fun startsWithInteger(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val majorType = (bytes[0].toInt() and 0xFF) ushr 5
            return majorType == 0 || majorType == 1
        }
    }
}
