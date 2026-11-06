package dev.kcoap.dtls

import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey

/**
 * DER material for [DtlsLocalCredential] tests: SubjectPublicKeyInfos (RFC 5480, RFC 8410) and a
 * certificate with placeholder signature, around the public keys below. Private keys are bare handles, as
 * a key store would hand out; nothing here signs.
 */
internal object TestCredentials {
    /** The P-256 base point of SEC 2 v2 §2.4.2, uncompressed. */
    const val P256_POINT: String = "04" +
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"

    /** Another point on P-256: 2G. */
    const val P256_OTHER_POINT: String = "04" +
        "7cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
        "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"

    /** A P-256 key handle of a key store. */
    fun p256Key(): PrivateKey = object : PrivateKey(KeyType.P256) {}

    /** `SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING point }` (RFC 5480 §2). */
    fun p256Spki(point: String = P256_POINT): ByteArray =
        "3059301306072a8648ce3d020106082a8648ce3d030107034200".hexToBytes() + point.hexToBytes()

    /** An X25519 SubjectPublicKeyInfo (RFC 8410 §4). */
    fun x25519Spki(): ByteArray = "302a300506032b656e032100".hexToBytes() + ByteArray(32) { 9 }

    /** A certificate for [spki], signed (with a placeholder) by "Test CA". */
    fun certificate(spki: ByteArray): ByteArray {
        val ecdsaWithSha256 = sequence(tlv(0x06, "2a8648ce3d040302".hexToBytes()))
        val validity = sequence(tlv(0x17, "260101000000Z".encodeToByteArray()), tlv(0x17, "360101000000Z".encodeToByteArray()))
        val tbs = sequence(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, byteArrayOf(1)),
            ecdsaWithSha256,
            name("Test CA"),
            validity,
            name("device"),
            spki,
        )
        return sequence(tbs, ecdsaWithSha256, tlv(0x03, ByteArray(9)))
    }

    private fun name(commonName: String): ByteArray =
        sequence(tlv(0x31, sequence(tlv(0x06, "550403".hexToBytes()), tlv(0x0C, commonName.encodeToByteArray()))))

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { acc, part -> acc + part })

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val length = when {
            content.size < 0x80 -> byteArrayOf(content.size.toByte())
            content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (content.size ushr 8).toByte(), content.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
