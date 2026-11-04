package dev.kcoap.edhoc

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborArray
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.CborWriter
import dev.kcoap.common.toHex
import dev.kcoap.cose.CoseHeader
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm

/**
 * ID_CRED_I or ID_CRED_R (RFC 9528 §3.5.3): a COSE header map that identifies a credential by reference
 * or carries it by value.
 *
 * [encoded] is the header map exactly as sent or received; it is what enters context_2/context_3 and the
 * COSE_Sign1 protected header. In PLAINTEXT_2 and PLAINTEXT_3 a map holding only a 'kid' is replaced by
 * the compact encoding of RFC 9528 §3.5.3.2 ([encodeForPlaintext]).
 */
public sealed class CredentialIdentifier(encoded: ByteArray) {
    private val encodedBytes = encoded.copyOf()

    /** The header map encoding. */
    public val encoded: ByteArray get() = encodedBytes.copyOf()

    /** The header map. */
    public val header: CborMap get() = Cbor.decode(encodedBytes) as CborMap

    /** Whether the credential travels inside the identifier ('kccs', 'x5chain') rather than by reference. */
    public abstract val isByValue: Boolean

    /** The representation in PLAINTEXT_2 / PLAINTEXT_3 (RFC 9528 §3.5.3.2). */
    public open fun encodeForPlaintext(): ByteArray = encodedBytes.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CredentialIdentifier && encodedBytes.contentEquals(other.encodedBytes)

    override fun hashCode(): Int = encodedBytes.contentHashCode()

    /** `{ 4 : kid }`, the credential identifier every implementation must support (RFC 9528 §8). */
    public class KeyId(kid: ByteArray) : CredentialIdentifier(map(CoseHeader.KID, CborBytes(kid))) {
        private val kidBytes = kid.copyOf()

        public val kid: ByteArray get() = kidBytes.copyOf()

        override val isByValue: Boolean get() = false

        /** The kid alone, as an integer when it is one byte that encodes one (RFC 9528 §3.5.3.2). */
        override fun encodeForPlaintext(): ByteArray = ByteStringIdentifier.encode(kidBytes)

        override fun toString(): String = "KeyId(${kidBytes.toHex()})"
    }

    /**
     * `{ 34 : [ alg, hash ] }`, an X.509 certificate by thumbprint (RFC 9360 §2). [algorithm] is the COSE
     * hash algorithm, for example -15 for SHA-256/64 as in RFC 9529 §2.
     */
    public class X509Thumbprint(public val algorithm: Int, hash: ByteArray) : CredentialIdentifier(
        map(CoseHeader.X5T, CborArray(CborInt(algorithm.toLong()), CborBytes(hash))),
    ) {
        private val hashBytes = hash.copyOf()

        public val hash: ByteArray get() = hashBytes.copyOf()

        override val isByValue: Boolean get() = false

        /** Whether this thumbprint matches the DER certificate [der]; false for an unsupported algorithm. */
        public suspend fun matches(backend: CryptoBackend, der: ByteArray): Boolean =
            thumbprint(backend, algorithm, der)?.contentEquals(hashBytes) == true

        override fun toString(): String = "X509Thumbprint($algorithm, ${hashBytes.toHex()})"
    }

    /**
     * `{ 33 : COSE_X509 }`, an X.509 chain by value, end-entity certificate first (RFC 9360 §2). One
     * certificate is encoded as a byte string, several as an array.
     */
    public class X509Chain(certificates: List<ByteArray>) :
        CredentialIdentifier(map(CoseHeader.X5CHAIN, chainValue(certificates))) {
        private val chain = certificates.map { it.copyOf() }

        /** DER certificates, end entity first. */
        public val certificates: List<ByteArray> get() = chain.map { it.copyOf() }

        override val isByValue: Boolean get() = true

        /** The end-entity certificate as a credential; CRED_x is the end-entity certificate only (RFC 9528 §3.5.2). */
        public fun endEntityCredential(): AuthenticationCredential = AuthenticationCredential.x509(chain[0])

        override fun toString(): String = "X509Chain(${chain.size} certificates)"
    }

    /** `{ 14 : CCS }`, a CWT Claims Set by value (RFC 9528 §3.5.3.1), as used for raw public keys. */
    public class Kccs(claimsSet: CwtClaimsSet) : CredentialIdentifier(map(CoseHeader.KCCS, claimsSet.encoded)) {
        public val claimsSet: CwtClaimsSet = claimsSet

        override val isByValue: Boolean get() = true

        /** The carried credential; CRED_x is the CCS. */
        public fun credential(): AuthenticationCredential = AuthenticationCredential.ccs(claimsSet)

        override fun toString(): String = "Kccs($claimsSet)"
    }

    /**
     * Any other header map, such as several parameters or a type kcoap does not interpret ('kcwt', 'c5t',
     * 'x5u'). A resolver may still understand it through [header].
     */
    public class Other internal constructor(encoded: ByteArray) : CredentialIdentifier(encoded) {
        public constructor(header: CborMap) : this(header.encode())

        override val isByValue: Boolean get() = false

        override fun toString(): String = "CredentialIdentifier($header)"
    }

    public companion object {
        /** COSE algorithm SHA-256/64, the RECOMMENDED x5t hash with SHA-256 suites (RFC 9528 §9.3). */
        public const val SHA_256_64: Int = -15

        /** `{ 34 : [ algorithm, hash ] }` for the DER certificate [der]. */
        public suspend fun x509Thumbprint(
            backend: CryptoBackend,
            der: ByteArray,
            algorithm: Int = SHA_256_64,
        ): X509Thumbprint {
            val hash = thumbprint(backend, algorithm, der)
                ?: throw IllegalArgumentException("Unsupported thumbprint algorithm $algorithm")
            return X509Thumbprint(algorithm, hash)
        }

        /**
         * Reads ID_CRED_x from PLAINTEXT_2 or PLAINTEXT_3: a header map, or a compact kid. A map holding only
         * a 'kid' must have been sent compactly (RFC 9528 §3.5.3.2, RFC 9529 §4.1.6).
         */
        internal fun decodeFromPlaintext(encoded: ByteArray, what: String): CredentialIdentifier =
            when (val item = Cbor.decode(encoded)) {
                is CborMap -> fromHeader(item, encoded, what)
                else -> KeyId(ByteStringIdentifier.decode(item, what))
            }

        private fun fromHeader(header: CborMap, encoded: ByteArray, what: String): CredentialIdentifier {
            if (header.size != 1) return Other(encoded)
            val (label, value) = header.entries.single()
            fun malformed(): Nothing = throw EdhocException.malformed("$what: invalid ID_CRED $label")
            return when ((label as? CborInt)?.value) {
                CoseHeader.KID -> throw EdhocException.malformed("$what: a lone 'kid' must use the compact encoding")
                CoseHeader.X5T -> {
                    val pair = value as? CborArray
                    val algorithm = (pair?.items?.getOrNull(0) as? CborInt)?.value
                    val hash = pair?.items?.getOrNull(1) as? CborBytes
                    if (pair?.size != 2 || algorithm == null || hash == null) malformed()
                    if (algorithm !in Int.MIN_VALUE..Int.MAX_VALUE) malformed()
                    X509Thumbprint(algorithm.toInt(), hash.bytes)
                }
                CoseHeader.X5CHAIN -> X509Chain(
                    when (value) {
                        is CborBytes -> listOf(value.bytes)
                        is CborArray -> if (value.size < 2) {
                            malformed()
                        } else {
                            value.items.map { (it as? CborBytes)?.bytes ?: malformed() }
                        }
                        else -> malformed()
                    },
                )
                CoseHeader.KCCS -> if (value is CborMap) {
                    try {
                        Kccs(CwtClaimsSet.decode(value.encode()))
                    } catch (e: Exception) {
                        throw EdhocException.malformed("$what: invalid CCS in 'kccs'", e)
                    }
                } else {
                    malformed()
                }
                else -> Other(encoded)
            }
        }

        private fun map(label: Long, value: CborValue): ByteArray = map(label, value.encode())

        /** A one-entry header map around an already encoded value, which keeps its exact bytes. */
        private fun map(label: Long, encodedValue: ByteArray): ByteArray =
            CborWriter().writeMapHeader(1).writeInt(label).writeEncoded(encodedValue).toByteArray()

        private fun chainValue(certificates: List<ByteArray>): CborValue {
            require(certificates.isNotEmpty()) { "x5chain needs at least one certificate" }
            return if (certificates.size == 1) CborBytes(certificates[0]) else CborArray(certificates.map(::CborBytes))
        }

        /** COSE_CertHash over a DER certificate (RFC 9360 §2); null for an unsupported algorithm. */
        internal suspend fun thumbprint(backend: CryptoBackend, algorithm: Int, der: ByteArray): ByteArray? =
            when (algorithm) {
                SHA_256_64 -> backend.digest(HashAlgorithm.SHA_256, der).copyOf(8)
                HashAlgorithm.SHA_256.coseId -> backend.digest(HashAlgorithm.SHA_256, der)
                HashAlgorithm.SHA_384.coseId -> backend.digest(HashAlgorithm.SHA_384, der)
                HashAlgorithm.SHAKE256.coseId -> backend.digest(HashAlgorithm.SHAKE256, der)
                else -> null
            }
    }
}
