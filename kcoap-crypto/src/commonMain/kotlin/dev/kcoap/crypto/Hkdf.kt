package dev.kcoap.crypto

/**
 * HKDF (RFC 5869) built on [CryptoBackend.hmac].
 *
 * Extract and Expand are exposed separately: OSCORE (RFC 8613 §3.2.1) uses both, while EDHOC_KDF
 * (RFC 9528 §4.1.2) calls Expand directly on PRKs that come from its own Extract steps.
 */
public class Hkdf(private val crypto: CryptoBackend, public val algorithm: HashAlgorithm) {

    /** HKDF-Extract. An empty salt means a string of HashLen zero bytes (RFC 5869 §2.2). */
    public suspend fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(algorithm.outputLength) else salt
        return crypto.hmac(algorithm, effectiveSalt, ikm)
    }

    /** HKDF-Expand to [length] bytes, at most 255 * HashLen. */
    public suspend fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLength = algorithm.outputLength
        require(length in 0..255 * hashLength) { "HKDF-Expand length $length exceeds 255 * $hashLength" }
        val okm = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = crypto.hmac(algorithm, prk, previous + info + byteArrayOf(counter.toByte()))
            val chunk = minOf(hashLength, length - offset)
            previous.copyInto(okm, destinationOffset = offset, endIndex = chunk)
            offset += chunk
            counter++
        }
        return okm
    }

    public suspend fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
