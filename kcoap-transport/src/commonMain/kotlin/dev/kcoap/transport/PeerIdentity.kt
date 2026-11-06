package dev.kcoap.transport

/**
 * Who the peer is according to channel security (DTLS or TLS), already authenticated by that layer.
 * OSCORE and EDHOC identities are separate; the application can authorize on either or both.
 *
 * Byte arrays are copied in and out, so an identity cannot change after it was checked.
 */
public sealed interface PeerIdentity {
    public val mode: SecurityMode

    /** No channel security. */
    public data object None : PeerIdentity {
        override val mode: SecurityMode get() = SecurityMode.NO_SEC
    }

    /** The PSK identity the client presented (RFC 4279 §2). */
    public class PreSharedKey(identity: ByteArray) : PeerIdentity {
        private val bytes = identity.copyOf()

        public val identity: ByteArray get() = bytes.copyOf()

        override val mode: SecurityMode get() = SecurityMode.PRE_SHARED_KEY

        override fun equals(other: Any?): Boolean = other is PreSharedKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "PreSharedKey(${bytes.size} bytes)"
    }

    /**
     * The peer's raw public key (RFC 7250) as a DER `SubjectPublicKeyInfo`. RFC 7252 §9.1.3.2.1 derives
     * the CoAP identifier from it with RFC 6920 (`sha-256-120` at least); that lives in kcoap-dtls.
     */
    public class RawPublicKey(subjectPublicKeyInfo: ByteArray) : PeerIdentity {
        private val bytes = subjectPublicKeyInfo.copyOf()

        public val subjectPublicKeyInfo: ByteArray get() = bytes.copyOf()

        override val mode: SecurityMode get() = SecurityMode.RAW_PUBLIC_KEY

        override fun equals(other: Any?): Boolean = other is RawPublicKey && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "RawPublicKey(${bytes.size} bytes)"
    }

    /** A validated X.509 chain, DER encoded, end-entity certificate first. */
    public class Certificate(chain: List<ByteArray>) : PeerIdentity {
        private val certificates = chain.map { it.copyOf() }

        init {
            require(certificates.isNotEmpty()) { "Certificate chain is empty" }
        }

        public val chain: List<ByteArray> get() = certificates.map { it.copyOf() }

        override val mode: SecurityMode get() = SecurityMode.CERTIFICATE

        override fun equals(other: Any?): Boolean =
            other is Certificate &&
                certificates.size == other.certificates.size &&
                certificates.indices.all { certificates[it].contentEquals(other.certificates[it]) }

        override fun hashCode(): Int = certificates.fold(1) { acc, cert -> 31 * acc + cert.contentHashCode() }

        override fun toString(): String = "Certificate(${certificates.size} certificates)"
    }
}
