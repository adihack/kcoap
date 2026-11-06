package dev.kcoap.cose

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.asMap
import dev.kcoap.cbor.asText
import dev.kcoap.cbor.toCbor

/**
 * A CWT Claims Set (RFC 8392) carrying a confirmation key (RFC 8747), the CCS credential type of
 * EDHOC (RFC 9528 §3.5.2): `{ 2: subject, 8: { 1: COSE_Key } }`.
 *
 * EDHOC hashes credentials as received, so [encoded] keeps the exact bytes a credential was parsed
 * from; [toCbor] is only used for credentials created locally.
 */
public class CwtClaimsSet private constructor(
    public val subject: String?,
    public val confirmationKey: CoseKey,
    private val originalEncoding: ByteArray?,
) {
    public constructor(subject: String?, confirmationKey: CoseKey) : this(subject, confirmationKey, null)

    public fun toCbor(): CborMap {
        val entries = mutableListOf<Pair<CborValue, CborValue>>()
        subject?.let { entries += CLAIM_SUBJECT.toCbor() to it.toCbor() }
        entries += CLAIM_CNF.toCbor() to CborMap(CNF_COSE_KEY.toCbor() to confirmationKey.toCbor())
        return CborMap(entries)
    }

    /** The bytes this claims set was decoded from, or its own encoding when created locally. */
    public val encoded: ByteArray get() = originalEncoding?.copyOf() ?: toCbor().encode()

    override fun toString(): String = "CwtClaimsSet(subject=$subject, $confirmationKey)"

    public companion object {
        public const val CLAIM_SUBJECT: Int = 2
        public const val CLAIM_CNF: Int = 8
        public const val CNF_COSE_KEY: Int = 1

        public fun decode(bytes: ByteArray): CwtClaimsSet = fromCbor(Cbor.decode(bytes).asMap(), bytes)

        private fun fromCbor(map: CborMap, encoding: ByteArray): CwtClaimsSet {
            val cnf = map[CLAIM_CNF]?.asMap() ?: throw CborException("CWT Claims Set without a cnf claim")
            val key = cnf[CNF_COSE_KEY]?.asMap() ?: throw CborException("cnf claim without a COSE_Key")
            return CwtClaimsSet(map[CLAIM_SUBJECT]?.asText(), CoseKey.fromCbor(key), encoding.copyOf())
        }
    }
}
