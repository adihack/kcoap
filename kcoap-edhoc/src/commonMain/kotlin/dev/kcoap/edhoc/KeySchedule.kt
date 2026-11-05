package dev.kcoap.edhoc

import dev.kcoap.cbor.CborWriter
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.Hkdf
import dev.kcoap.crypto.Kmac256

/**
 * The EDHOC key schedule of RFC 9528 §4 for one cipher suite: EDHOC_Extract, EDHOC_Expand, EDHOC_KDF, the
 * transcript hashes, and the derivations of Figure 6.
 *
 * EDHOC_Extract and EDHOC_Expand are HKDF for SHA-2 suites and KMAC256 with an empty customization string
 * for SHAKE256 suites (RFC 9528 §4.1.1, §4.1.2):
 *
 * ```
 * EDHOC_Extract(salt, IKM)       = KMAC256(salt, IKM, 512, "")
 * EDHOC_Expand(PRK, info, length) = KMAC256(PRK, info, 8 * length, "")
 * ```
 */
internal class KeySchedule(val suite: CipherSuite, private val crypto: CryptoBackend) {
    private val hash = suite.edhocHash
    private val hkdf = Hkdf(crypto, hash)

    val hashLength: Int get() = hash.outputLength

    suspend fun hash(data: ByteArray): ByteArray = crypto.digest(hash, data)

    /** EDHOC_Extract (RFC 9528 §4.1.1). */
    suspend fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        if (hash.isXof) Kmac256.compute(key = salt, data = ikm, outputLength = hashLength) else hkdf.extract(salt, ikm)

    /** EDHOC_Expand (RFC 9528 §4.1.2). */
    suspend fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray =
        if (hash.isXof) Kmac256.compute(key = prk, data = info, outputLength = length) else
            hkdf.expand(prk, info, length)

    /** `EDHOC_KDF(PRK, info_label, context, length) = EDHOC_Expand(PRK, info, length)` (RFC 9528 §4.1.2). */
    suspend fun kdf(prk: ByteArray, label: Int, context: ByteArray, length: Int): ByteArray =
        expand(prk, info(label, context, length), length)

    /** TH_2 = H( G_Y, H(message_1) ) (RFC 9528 §5.3.2). */
    suspend fun th2(gY: ByteArray, hashMessage1: ByteArray): ByteArray =
        hash(CborWriter().writeBytes(gY).writeBytes(hashMessage1).toByteArray())

    /** TH_3 = H( TH_2, PLAINTEXT_2, CRED_R ) and TH_4 = H( TH_3, PLAINTEXT_3, CRED_I ) (RFC 9528 §5.4.2). */
    suspend fun nextTranscriptHash(previous: ByteArray, plaintext: ByteArray, credential: ByteArray): ByteArray =
        hash(CborWriter().writeBytes(previous).writeEncoded(plaintext).writeEncoded(credential).toByteArray())

    /**
     * KEYSTREAM_2 for a PLAINTEXT_2 of [length] bytes. With SHA-2, HKDF-Expand stops at 255 * hash_length
     * bytes, so longer plaintexts use the keystream of RFC 9528 Appendix G:
     * `OKM(i) = EDHOC_KDF( PRK_2e, -i, TH_2, length(P(i)) )`, which equals Figure 6 for short plaintexts.
     */
    suspend fun keystream2(prk2e: ByteArray, th2: ByteArray, length: Int): ByteArray {
        val partLength = 255 * hashLength
        if (hash.isXof || length <= partLength) return kdf(prk2e, KEYSTREAM_2, th2, length)
        val keystream = ByteArray(length)
        var offset = 0
        var part = 0
        while (offset < length) {
            val size = minOf(partLength, length - offset)
            kdf(prk2e, -part, th2, size).copyInto(keystream, offset)
            offset += size
            part++
        }
        return keystream
    }

    /** PRK_2e = EDHOC_Extract( TH_2, G_XY ) (RFC 9528 §4.1.1.1). */
    suspend fun prk2e(th2: ByteArray, gXY: ByteArray): ByteArray = extract(th2, gXY)

    /** PRK_3e2m = EDHOC_Extract( SALT_3e2m, G_RX ) (RFC 9528 §4.1.1.2). */
    suspend fun salt3e2m(prk2e: ByteArray, th2: ByteArray): ByteArray = kdf(prk2e, SALT_3E2M, th2, hashLength)

    /** PRK_4e3m = EDHOC_Extract( SALT_4e3m, G_IY ) (RFC 9528 §4.1.1.3). */
    suspend fun salt4e3m(prk3e2m: ByteArray, th3: ByteArray): ByteArray = kdf(prk3e2m, SALT_4E3M, th3, hashLength)

    suspend fun k3(prk3e2m: ByteArray, th3: ByteArray): ByteArray = kdf(prk3e2m, K_3, th3, suite.edhocAead.keyLength)

    suspend fun iv3(prk3e2m: ByteArray, th3: ByteArray): ByteArray =
        kdf(prk3e2m, IV_3, th3, suite.edhocAead.nonceLength)

    suspend fun k4(prk4e3m: ByteArray, th4: ByteArray): ByteArray = kdf(prk4e3m, K_4, th4, suite.edhocAead.keyLength)

    suspend fun iv4(prk4e3m: ByteArray, th4: ByteArray): ByteArray =
        kdf(prk4e3m, IV_4, th4, suite.edhocAead.nonceLength)

    suspend fun prkOut(prk4e3m: ByteArray, th4: ByteArray): ByteArray = kdf(prk4e3m, PRK_OUT, th4, hashLength)

    /** PRK_exporter = EDHOC_KDF( PRK_out, 10, h'', hash_length ) (RFC 9528 §4.1.3). */
    suspend fun prkExporter(prkOut: ByteArray): ByteArray = kdf(prkOut, PRK_EXPORTER, ByteArray(0), hashLength)

    /** new PRK_out = EDHOC_KDF( old PRK_out, 11, context, hash_length ) (RFC 9528 Appendix H). */
    suspend fun keyUpdate(prkOut: ByteArray, context: ByteArray): ByteArray =
        kdf(prkOut, KEY_UPDATE, context, hashLength)

    internal companion object {
        // info_label values of RFC 9528 Figure 6 and Appendix H.
        const val KEYSTREAM_2 = 0
        const val SALT_3E2M = 1
        const val MAC_2 = 2
        const val K_3 = 3
        const val IV_3 = 4
        const val SALT_4E3M = 5
        const val MAC_3 = 6
        const val PRK_OUT = 7
        const val K_4 = 8
        const val IV_4 = 9
        const val PRK_EXPORTER = 10
        const val KEY_UPDATE = 11

        /** `info = ( info_label : int, context : bstr, length : uint )` (RFC 9528 §4.1.2). */
        fun info(label: Int, context: ByteArray, length: Int): ByteArray =
            CborWriter().writeInt(label).writeBytes(context).writeInt(length).toByteArray()
    }
}
