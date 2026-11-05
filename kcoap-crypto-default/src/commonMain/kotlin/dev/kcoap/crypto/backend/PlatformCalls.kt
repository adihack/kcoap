package dev.kcoap.crypto.backend

import dev.kcoap.crypto.UnsupportedAlgorithmException
import kotlin.coroutines.cancellation.CancellationException

// How failures of the platform's cryptography leave the backend, the same way on every platform.
//
// Protocol code catches exceptions from a backend and turns them into protocol errors (an invalid peer
// key aborts EDHOC, RFC 9528 §9.8), and it asks a backend what it can do through
// UnsupportedAlgorithmException (see dev.kcoap.crypto.supports). Platforms do not report failures
// that way: JCA, CommonCrypto and OpenSSL throw their own exception types, and a WebCrypto rejection is
// not a Kotlin exception at all (a DOMException on JS, a bare Throwable on Wasm). These helpers map
// both, without knowing which platform they run on.

/**
 * Runs [block], a call into the platform whose only input is the algorithm: generating a key pair, or
 * importing a symmetric key whose length the backend has checked. Any byte string of the right length
 * is a valid symmetric key, so such a call can only fail because the platform lacks the algorithm or
 * key size (Chromium has no 192-bit AES, WebCrypto no ChaCha20/Poly1305), and any failure is reported
 * as [UnsupportedAlgorithmException].
 */
internal inline fun <T> capabilityCall(algorithm: String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    if (e is UnsupportedAlgorithmException || !isPlatformFailure(e)) throw e
    throw UnsupportedAlgorithmException(algorithm)
}

/**
 * Runs [block], a call into the platform on caller or peer input, so that every failure leaves as an
 * [Exception]: the platform's own exceptions pass through, anything else becomes an
 * [IllegalArgumentException], since the platform rejected what it was given.
 */
internal inline fun <T> platformCall(algorithm: String, block: () -> T): T = try {
    block()
} catch (e: Throwable) {
    throw asException(e, algorithm)
}

/** [error] if it is an [Exception] or must not be caught, otherwise an [IllegalArgumentException] about it. */
internal fun asException(error: Throwable, algorithm: String): Throwable =
    if (error is Exception || !isPlatformFailure(error)) {
        error
    } else {
        IllegalArgumentException("$algorithm: the platform rejected the input ($error)", error)
    }

/**
 * Whether [error] is the platform reporting that an operation failed, as opposed to cancellation or a
 * fatal error. Not every such report is an [Exception].
 */
internal fun isPlatformFailure(error: Throwable): Boolean = error !is CancellationException && error !is Error
