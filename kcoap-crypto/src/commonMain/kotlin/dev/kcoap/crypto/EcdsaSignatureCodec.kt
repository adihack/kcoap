package dev.kcoap.crypto

import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.crypto.x509.DerReader

/**
 * Converts ECDSA signatures between the raw `r || s` of [CryptoBackend.sign] and [CryptoBackend.verify]
 * (COSE, RFC 9053 §2.1) and the DER `ECDSA-Sig-Value ::= SEQUENCE { r INTEGER, s INTEGER }` of X.509 and
 * TLS (RFC 5480 §2.2, RFC 8422 §5.4).
 *
 * DTLS signs its handshake through [CryptoBackend.sign] and converts the result with [toDer], and turns a
 * peer's DER signature into the raw form with [fromDer] before it verifies. A backend on a platform that
 * signs in DER, such as JCA, the Android KeyStore or Apple's SecKey, converts the other way to implement
 * [CryptoBackend.sign] for [SignatureAlgorithm.ES256] and [SignatureAlgorithm.ES384].
 */
public object EcdsaSignatureCodec {
    /**
     * The DER encoding of the raw signature [signature], `r || s` with each half as long as a coordinate of
     * the curve of [algorithm].
     *
     * @throws IllegalArgumentException when [algorithm] is not ECDSA, [signature] has the wrong length for
     *   it, or r or s is zero, which no ECDSA signature has.
     */
    public fun toDer(algorithm: SignatureAlgorithm, signature: ByteArray): ByteArray {
        val length = componentLength(algorithm)
        require(signature.size == 2 * length) {
            "A raw $algorithm signature has ${2 * length} bytes, got ${signature.size}"
        }
        val r = integer(signature.copyOfRange(0, length))
        val s = integer(signature.copyOfRange(length, signature.size))
        // At most 2 * (2 + 1 + 48) bytes of content, so the SEQUENCE length always fits one byte.
        return byteArrayOf(DerReader.SEQUENCE.toByte(), (r.size + s.size).toByte()) + r + s
    }

    /**
     * The raw `r || s` of the DER signature [der], each half as long as a coordinate of the curve of
     * [algorithm], or null when [der] is not exactly one DER `ECDSA-Sig-Value` whose r and s are positive
     * and fit that length. Peer input never makes it throw: a malformed signature is one that does not
     * verify.
     *
     * @throws IllegalArgumentException when [algorithm] is not ECDSA.
     */
    public fun fromDer(algorithm: SignatureAlgorithm, der: ByteArray): ByteArray? {
        val length = componentLength(algorithm)
        return try {
            val outer = DerReader(der)
            val sequence = outer.read(DerReader.SEQUENCE).reader()
            outer.expectEnd("the signature")
            val r = component(sequence.read(DerReader.INTEGER).content(), length)
            val s = component(sequence.read(DerReader.INTEGER).content(), length)
            sequence.expectEnd("s")
            if (r == null || s == null) null else r + s
        } catch (_: CertificateFormatException) {
            null
        }
    }

    private fun componentLength(algorithm: SignatureAlgorithm): Int = when (algorithm) {
        SignatureAlgorithm.ES256, SignatureAlgorithm.ES384 -> algorithm.signatureLength / 2
        else -> throw IllegalArgumentException("$algorithm is not ECDSA")
    }

    /** A positive INTEGER TLV for the unsigned big-endian [magnitude]: no leading zeros but a sign byte. */
    private fun integer(magnitude: ByteArray): ByteArray {
        val first = magnitude.indexOfFirst { it.toInt() != 0 }
        require(first >= 0) { "r and s of an ECDSA signature are never zero" }
        val needsSignByte = magnitude[first].toInt() and 0x80 != 0
        val content = (if (needsSignByte) byteArrayOf(0) else ByteArray(0)) + magnitude.copyOfRange(first, magnitude.size)
        return byteArrayOf(DerReader.INTEGER.toByte(), content.size.toByte()) + content
    }

    /**
     * The content of a DER INTEGER as [length] unsigned big-endian bytes, or null when it is not a minimal
     * encoding (X.690 §8.3.2) of a positive number below `2^(8 * length)`.
     */
    private fun component(content: ByteArray, length: Int): ByteArray? {
        if (content.isEmpty() || content[0].toInt() and 0x80 != 0) return null // empty or negative
        val magnitude = if (content[0].toInt() == 0) {
            // A leading zero is allowed only as the sign byte of a number whose top bit is set.
            if (content.size == 1 || content[1].toInt() and 0x80 == 0) return null
            content.copyOfRange(1, content.size)
        } else {
            content
        }
        if (magnitude.size > length) return null
        return ByteArray(length - magnitude.size) + magnitude
    }
}
