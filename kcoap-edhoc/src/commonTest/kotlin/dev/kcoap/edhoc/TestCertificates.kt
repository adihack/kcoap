package dev.kcoap.edhoc

/**
 * Builds structurally valid DER certificates for public key algorithms the RFC 9529 traces do not cover.
 * The signatures are placeholders: only the parser's view of the certificate matters here.
 */
internal object TestCertificates {
    const val ML_DSA_44 = "608648016503040311"
    const val ML_DSA_87 = "608648016503040313"
    const val X25519 = "2b656e"
    const val EC_PUBLIC_KEY = "2a8648ce3d0201"
    const val P_256 = "2a8648ce3d030107"
    const val P_384 = "2b81040022"

    fun certificate(
        algorithmIdentifier: ByteArray,
        publicKey: ByteArray,
        commonName: String,
        version: Boolean = true,
        subject: ByteArray = name(commonName),
    ): ByteArray {
        val validity = sequence(utcTime("260101000000Z"), utcTime("360101000000Z"))
        val tbs = sequence(
            if (version) tlv(0xA0, tlv(0x02, byteArrayOf(2))) else ByteArray(0),
            tlv(0x02, byteArrayOf(1)),
            sequence(oid(ML_DSA_44)),
            name("Test CA"),
            validity,
            subject,
            sequence(algorithmIdentifier, bitString(publicKey)),
        )
        return sequence(tbs, sequence(oid(ML_DSA_44)), bitString(ByteArray(4)))
    }

    private fun utcTime(time: String): ByteArray = tlv(0x17, time.encodeToByteArray())

    fun algorithm(oidHex: String, parameterOidHex: String? = null): ByteArray =
        sequence(oid(oidHex), parameterOidHex?.let(::oid) ?: ByteArray(0))

    fun name(commonName: String): ByteArray =
        sequence(tlv(0x31, sequence(oid("550403"), tlv(0x0C, commonName.encodeToByteArray()))))

    fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    fun oid(hex: String): ByteArray = tlv(0x06, hex.hex())

    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

    fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        size < 0x10000 -> byteArrayOf(0x82.toByte(), (size ushr 8).toByte(), size.toByte())
        else -> byteArrayOf(0x83.toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte())
    }
}
