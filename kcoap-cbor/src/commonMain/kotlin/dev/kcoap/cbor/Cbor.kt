package dev.kcoap.cbor

/** Entry points for whole-buffer encoding and decoding. */
public object Cbor {
    public fun encode(value: CborValue): ByteArray = value.encode()

    /** Decodes exactly one data item; trailing bytes are an error. */
    public fun decode(bytes: ByteArray): CborValue {
        val reader = CborReader(bytes)
        val value = reader.readValue()
        if (!reader.isAtEnd) throw CborException("${reader.remaining} trailing bytes after the data item", reader.position)
        return value
    }

    /** Decodes a CBOR sequence (RFC 8742): zero or more items back to back. */
    public fun decodeSequence(bytes: ByteArray): List<CborValue> {
        val reader = CborReader(bytes)
        val items = mutableListOf<CborValue>()
        while (!reader.isAtEnd) items += reader.readValue()
        return items
    }

    /** Encodes [items] as a CBOR sequence: their encodings concatenated. */
    public fun encodeSequence(items: List<CborValue>): ByteArray {
        val writer = CborWriter()
        items.forEach { writer.write(it) }
        return writer.toByteArray()
    }
}
