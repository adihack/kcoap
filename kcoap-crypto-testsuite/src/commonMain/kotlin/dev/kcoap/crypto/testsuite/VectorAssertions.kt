package dev.kcoap.crypto.testsuite

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// Assertions the known-answer suites share.

/** A vector's hex value as bytes; whitespace, as in the generated multi-line strings, is ignored. */
internal fun String.hex(): ByteArray = hexToBytes()

/** Compares as lower-case hex, so a failure shows a readable diff against a vector. */
internal fun assertHexEquals(expected: String, actual: ByteArray, message: String) {
    assertEquals(expected.hexToBytes().toHex(), actual.toHex(), message)
}

/**
 * A software key holds one of the two encodings of its type (`KeyType.seedLength`, `expandedPrivateKeyLength`):
 * [seed], when the key was made from it and the backend keeps it, or [expanded]. Keys of other kinds, such as
 * key-store handles, hold nothing this test can read.
 */
internal fun assertHoldsEncoding(key: PrivateKey, seed: ByteArray?, expanded: ByteArray, message: String) {
    val encoded = (key as? SoftwarePrivateKey ?: return).encoded()
    val isSeed = seed != null && encoded.contentEquals(seed)
    val isExpanded = encoded.contentEquals(expanded)
    assertTrue(isSeed || isExpanded, "$message: the private key is neither the seed nor the expanded key")
}

/**
 * Runs [block], which must reject its input as FIPS 203 and 204 require: with an exception, but not with
 * `UnsupportedAlgorithmException`, since the parameter set is there and the input is what is wrong.
 */
internal inline fun assertRejects(message: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnsupportedAlgorithmException) {
        fail("$message: rejected as an unsupported algorithm, not as bad input ($e)")
    } catch (_: Exception) {
        return
    }
    fail("$message: accepted")
}
