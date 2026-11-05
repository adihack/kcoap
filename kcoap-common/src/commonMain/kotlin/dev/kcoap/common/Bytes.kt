package dev.kcoap.common

/**
 * Compares two arrays in time that depends only on their lengths, never on where they differ. Use it for
 * every comparison of a MAC, tag or other secret-derived value.
 */
public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
    return difference == 0
}

/** Overwrites the array with zeros, for key material that is no longer needed. */
public fun ByteArray.wipe() {
    fill(0)
}

/** Big-endian unsigned encoding of [value] in the fewest bytes; zero encodes as an empty array. */
@InternalKcoapApi
public fun minimalUnsignedBytes(value: Long): ByteArray {
    require(value >= 0) { "Negative value: $value" }
    var length = 0
    var rest = value
    while (rest != 0L) {
        length++
        rest = rest ushr 8
    }
    return ByteArray(length) { i -> (value ushr (8 * (length - 1 - i))).toByte() }
}

/** Reads [length] bytes at [offset] as a big-endian unsigned number. At most 7 bytes, so it fits a Long. */
@InternalKcoapApi
public fun ByteArray.readUnsigned(offset: Int = 0, length: Int = size - offset): Long {
    require(length in 0..7) { "Cannot read $length bytes into a Long" }
    require(offset >= 0 && offset + length <= size) { "Range $offset+$length outside $size bytes" }
    var value = 0L
    for (i in offset until offset + length) value = (value shl 8) or (this[i].toLong() and 0xFF)
    return value
}
