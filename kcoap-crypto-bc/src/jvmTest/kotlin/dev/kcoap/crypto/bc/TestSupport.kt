package dev.kcoap.crypto.bc

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlin.test.assertEquals

// Short names for the vector-heavy tests in this module.
internal fun String.hex(): ByteArray = hexToBytes()

internal fun ByteArray.hex(): String = toHex()

/** Compares as lower-case hex without whitespace, so a failure shows a readable diff against a vector. */
internal fun assertHexEquals(expected: String, actual: ByteArray, message: String) {
    assertEquals(expected.hex().hex(), actual.hex(), message)
}

/** A copy with bit 0 of byte [index] flipped. */
internal fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

/** The raw material of a key made by a software backend. */
internal val PrivateKey.encoded: ByteArray get() = (this as SoftwarePrivateKey).encoded()

/**
 * The default backend with scripted randomness: [randomBytes] hands out [script] in order, so ML-KEM and ML-DSA
 * consume NIST's `d || z`, `m`, `ξ` and `rnd`. Running out is a test failure, and [remaining] shows whether an
 * operation drew exactly what FIPS 203/204 prescribe.
 */
internal class ScriptedRandomBackend(vararg script: ByteArray) : CryptoBackend by DefaultCryptoBackend() {
    private val bytes = script.fold(ByteArray(0)) { all, next -> all + next }
    private var offset = 0

    val remaining: Int get() = bytes.size - offset

    override suspend fun randomBytes(size: Int): ByteArray {
        check(size <= remaining) { "$size random bytes requested, $remaining scripted" }
        return bytes.copyOfRange(offset, offset + size).also { offset += size }
    }
}
