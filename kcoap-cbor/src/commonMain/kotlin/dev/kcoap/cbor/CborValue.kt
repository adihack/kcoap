package dev.kcoap.cbor

import dev.kcoap.common.toHex

/**
 * A decoded CBOR data item (RFC 8949 §3). Values are immutable and compare by content.
 *
 * Floating-point values are not modelled: none of CoAP, OSCORE, EDHOC or COSE as used here carries
 * them, and rejecting them keeps encodings deterministic.
 */
public sealed class CborValue {
    /** The preferred (deterministic) encoding of this value. Map entries keep their order. */
    public fun encode(): ByteArray = CborWriter().write(this).toByteArray()
}

/** Major types 0 and 1. The range is that of [Long]; larger CBOR integers are rejected on decode. */
public data class CborInt(public val value: Long) : CborValue() {
    override fun toString(): String = value.toString()
}

/** Major type 2. */
public class CborBytes(bytes: ByteArray) : CborValue() {
    private val content = bytes.copyOf()

    public val bytes: ByteArray get() = content.copyOf()

    public val size: Int get() = content.size

    override fun equals(other: Any?): Boolean = other is CborBytes && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "h'${content.toHex()}'"
}

/** Major type 3, always valid UTF-8. */
public data class CborText(public val value: String) : CborValue() {
    override fun toString(): String = "\"$value\""
}

/** Major type 4. */
public data class CborArray(public val items: List<CborValue>) : CborValue() {
    public constructor(vararg items: CborValue) : this(items.toList())

    public val size: Int get() = items.size

    public operator fun get(index: Int): CborValue = items[index]

    override fun toString(): String = items.joinToString(prefix = "[", postfix = "]")
}

/**
 * Major type 5. Entries keep the order they were given or decoded in, because COSE and EDHOC structures
 * are compared byte for byte. Use [sortedDeterministically] for RFC 8949 §4.2.1 key order.
 */
public data class CborMap(public val entries: List<Pair<CborValue, CborValue>>) : CborValue() {
    public constructor(vararg entries: Pair<CborValue, CborValue>) : this(entries.toList())

    public val size: Int get() = entries.size

    public operator fun get(key: CborValue): CborValue? = entries.firstOrNull { it.first == key }?.second

    /** Lookup by integer label, the common case for COSE. */
    public operator fun get(label: Long): CborValue? = get(CborInt(label))

    public operator fun get(label: Int): CborValue? = get(CborInt(label.toLong()))

    public operator fun get(key: String): CborValue? = get(CborText(key))

    /** Keys sorted by the bytewise order of their encodings (RFC 8949 §4.2.1). */
    public fun sortedDeterministically(): CborMap =
        CborMap(entries.sortedWith { a, b -> compareEncodings(a.first.encode(), b.first.encode()) })

    override fun toString(): String = entries.joinToString(prefix = "{", postfix = "}") { "${it.first}: ${it.second}" }

    private companion object {
        fun compareEncodings(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }
}

/** Major type 6. */
public data class CborTag(public val tag: Long, public val value: CborValue) : CborValue() {
    override fun toString(): String = "$tag($value)"
}

/** Simple values `false` and `true`. */
public data class CborBool(public val value: Boolean) : CborValue() {
    override fun toString(): String = value.toString()
}

public data object CborNull : CborValue() {
    override fun toString(): String = "null"
}

public data object CborUndefined : CborValue() {
    override fun toString(): String = "undefined"
}

/** Other simple values (major type 7, 0..19 and 32..255). */
public data class CborSimple(public val value: Int) : CborValue() {
    init {
        require(value in 0..19 || value in 32..255) { "Not an unassigned simple value: $value" }
    }

    override fun toString(): String = "simple($value)"
}

// Small builders that keep protocol code close to the CDDL it implements.

public fun Int.toCbor(): CborInt = CborInt(toLong())

public fun Long.toCbor(): CborInt = CborInt(this)

public fun String.toCbor(): CborText = CborText(this)

public fun ByteArray.toCbor(): CborBytes = CborBytes(this)

public fun Boolean.toCbor(): CborBool = CborBool(this)

/** The value as a Long, or an exception naming what was found instead. */
public fun CborValue.asLong(): Long = (this as? CborInt)?.value ?: throw CborException("Expected an integer, found $this")

public fun CborValue.asInt(): Int {
    val value = asLong()
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw CborException("Integer $value does not fit an Int")
    return value.toInt()
}

public fun CborValue.asBytes(): ByteArray = (this as? CborBytes)?.bytes ?: throw CborException("Expected a byte string, found $this")

public fun CborValue.asText(): String = (this as? CborText)?.value ?: throw CborException("Expected a text string, found $this")

public fun CborValue.asArray(): CborArray = this as? CborArray ?: throw CborException("Expected an array, found $this")

public fun CborValue.asMap(): CborMap = this as? CborMap ?: throw CborException("Expected a map, found $this")
