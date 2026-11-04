package dev.kcoap.cose

import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.asBytes
import dev.kcoap.cbor.asInt
import dev.kcoap.cbor.toCbor
import dev.kcoap.common.toHex
import dev.kcoap.crypto.KeyType

/** COSE key types (RFC 9053 §7, RFC 9052 §7). */
public enum class CoseKeyType(public val value: Int) {
    /** Octet key pair: X25519, X448, Ed25519, Ed448. */
    OKP(1),

    /** Elliptic curve with x and y: P-256, P-384, P-521. */
    EC2(2),

    /** Algorithm Key Pair, used for ML-DSA keys (draft-ietf-cose-dilithium). */
    AKP(7),
    ;

    public companion object {
        public fun fromValue(value: Int): CoseKeyType? = entries.firstOrNull { it.value == value }
    }
}

/** COSE elliptic curves (RFC 9053 §7.1). */
public enum class CoseCurve(public val value: Int, public val keyType: KeyType) {
    P_256(1, KeyType.P256),
    P_384(2, KeyType.P384),
    X25519(4, KeyType.X25519),
    X448(5, KeyType.X448),
    ED25519(6, KeyType.ED25519),
    ;

    public companion object {
        public fun fromValue(value: Int): CoseCurve? = entries.firstOrNull { it.value == value }
    }
}

/**
 * A public COSE_Key (RFC 9052 §7). Private parts are never modelled: private keys live in a
 * [dev.kcoap.crypto.CryptoBackend].
 *
 * Map labels: kty 1, kid 2, alg 3, crv -1, x -2, y -3. AKP keys (draft-ietf-cose-dilithium) have no
 * curve and carry their public key as `pub` under label -1 instead. The order of [encode] follows the
 * examples in RFC 9529 (kty, kid, crv, x, y), so credentials built from this class match the traces byte
 * for byte.
 */
public class CoseKey(
    public val keyType: CoseKeyType,
    public val curve: CoseCurve?,
    x: ByteArray,
    y: ByteArray? = null,
    kid: ByteArray? = null,
    /** COSE algorithm id, when the key is restricted to one. */
    public val algorithm: Int? = null,
) {
    private val xBytes = x.copyOf()
    private val yBytes = y?.copyOf()
    private val kidBytes = kid?.copyOf()

    init {
        when (keyType) {
            CoseKeyType.EC2 -> require(curve != null && yBytes != null) { "EC2 keys need a curve and y" }
            CoseKeyType.OKP -> require(curve != null && yBytes == null) { "OKP keys have a curve and no y" }
            CoseKeyType.AKP -> require(curve == null && algorithm != null) { "AKP keys have an algorithm and no curve" }
        }
    }

    /** x for EC2 and OKP; the public key `pub` for AKP. */
    public val x: ByteArray get() = xBytes.copyOf()
    public val y: ByteArray? get() = yBytes?.copyOf()
    public val kid: ByteArray? get() = kidBytes?.copyOf()

    /** The public key in the raw encoding [dev.kcoap.crypto.CryptoBackend] expects. */
    public fun rawPublicKey(): ByteArray = when (keyType) {
        CoseKeyType.EC2 -> byteArrayOf(0x04) + xBytes + yBytes!!
        CoseKeyType.OKP, CoseKeyType.AKP -> xBytes.copyOf()
    }

    public fun toCbor(): CborMap {
        val entries = mutableListOf<Pair<CborValue, CborValue>>(LABEL_KTY.toCbor() to keyType.value.toCbor())
        kidBytes?.let { entries += LABEL_KID.toCbor() to it.toCbor() }
        algorithm?.let { entries += LABEL_ALG.toCbor() to it.toCbor() }
        curve?.let { entries += LABEL_CRV.toCbor() to it.value.toCbor() }
        entries += (if (keyType == CoseKeyType.AKP) LABEL_AKP_PUB else LABEL_X).toCbor() to xBytes.toCbor()
        yBytes?.let { entries += LABEL_Y.toCbor() to it.toCbor() }
        return CborMap(entries)
    }

    public fun encode(): ByteArray = toCbor().encode()

    override fun equals(other: Any?): Boolean =
        other is CoseKey && keyType == other.keyType && curve == other.curve && algorithm == other.algorithm &&
            xBytes.contentEquals(other.xBytes) && yBytes.contentEquals(other.yBytes) && kidBytes.contentEquals(other.kidBytes)

    override fun hashCode(): Int = 31 * keyType.hashCode() + xBytes.contentHashCode()

    override fun toString(): String =
        "CoseKey($keyType${curve?.let { ", $it" } ?: ""}${kidBytes?.let { ", kid=${it.toHex()}" } ?: ""})"

    public companion object {
        public const val LABEL_KTY: Int = 1
        public const val LABEL_KID: Int = 2
        public const val LABEL_ALG: Int = 3
        public const val LABEL_CRV: Int = -1
        public const val LABEL_X: Int = -2
        public const val LABEL_Y: Int = -3

        /** The public key of an AKP key; it shares label -1 with crv of the other key types. */
        public const val LABEL_AKP_PUB: Int = -1

        /** Parses a COSE_Key map. Unknown parameters are ignored, as RFC 9052 §7 allows. */
        public fun fromCbor(map: CborMap): CoseKey {
            val type = map[LABEL_KTY]?.asInt()?.let(CoseKeyType::fromValue)
                ?: throw CborException("COSE_Key has a missing or unsupported kty")
            if (type == CoseKeyType.AKP) {
                val pub = map[LABEL_AKP_PUB]?.asBytes() ?: throw CborException("AKP COSE_Key without pub")
                val algorithm = (map[LABEL_ALG] as? CborInt)?.value?.toInt() ?: throw CborException("AKP COSE_Key without alg")
                return CoseKey(type, curve = null, x = pub, kid = map[LABEL_KID]?.asBytes(), algorithm = algorithm)
            }
            val curve = map[LABEL_CRV]?.let { value ->
                CoseCurve.fromValue(value.asInt()) ?: throw CborException("Unsupported COSE curve $value")
            }
            val x = map[LABEL_X]?.asBytes() ?: throw CborException("COSE_Key without x")
            val y = when (val value = map[LABEL_Y]) {
                null -> null
                is CborBytes -> value.bytes
                // RFC 9053 §7.1.1: y may be a boolean sign bit for compressed points; not supported.
                else -> throw CborException("COSE_Key y must be a byte string")
            }
            val algorithm = (map[LABEL_ALG] as? CborInt)?.value?.toInt()
            return CoseKey(type, curve, x, y, map[LABEL_KID]?.asBytes(), algorithm)
        }
    }
}
