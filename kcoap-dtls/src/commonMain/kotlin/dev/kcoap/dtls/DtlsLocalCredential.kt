package dev.kcoap.dtls

import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.x509.CertificateFormatException
import dev.kcoap.crypto.x509.SubjectPublicKeyInfo
import dev.kcoap.crypto.x509.X509Certificate

/**
 * This endpoint's own authentication material for RawPublicKey and Certificate mode: the handle of its
 * private key, the DER SubjectPublicKeyInfo it presents as a raw public key (RFC 7250 §3), and in
 * Certificate mode its certificate chain.
 *
 * The private key is a [PrivateKey] handle, as EDHOC's `LocalCredential` takes, so it can stay in the
 * Android KeyStore, the Secure Enclave or an HSM behind one [dev.kcoap.crypto.CryptoBackend] decorator
 * that serves both protocols. The engine signs the handshake with `CryptoBackend.sign(ES256, ...)` and
 * converts the raw `r || s` to the DER that TLS 1.2 sends (RFC 8422 §5.4) with
 * [dev.kcoap.crypto.EcdsaSignatureCodec.toDer].
 *
 * The key is a P-256 key: the ECDHE_ECDSA suites of the RFC 7925 profile sign with ECDSA on secp256r1 and
 * SHA-256 (RFC 7925 §4.3 for raw public keys, §4.4 for certificates).
 *
 * @param privateKey the private key, P-256; it never leaves its backend.
 * @param subjectPublicKeyInfo the DER SubjectPublicKeyInfo of the matching public key.
 * @param certificateChain DER certificates, end entity first, whose end entity holds that public key;
 *   empty for RawPublicKey mode only.
 * @throws IllegalArgumentException when the key is not P-256, the SubjectPublicKeyInfo or end-entity
 *   certificate does not decode ([CertificateFormatException] is the cause), or they hold another key.
 */
public class DtlsLocalCredential(
    public val privateKey: PrivateKey,
    subjectPublicKeyInfo: ByteArray,
    certificateChain: List<ByteArray> = emptyList(),
) {
    private val spki = subjectPublicKeyInfo.copyOf()
    private val chain = certificateChain.map { it.copyOf() }

    init {
        require(privateKey.type == KeyType.P256) {
            "DTLS signs with ECDSA on P-256 (RFC 7925 §4.3, §4.4), the private key is ${privateKey.type}"
        }
        val publicKey = decoded("SubjectPublicKeyInfo") { SubjectPublicKeyInfo.decode(spki) }
        require(publicKey.keyType == privateKey.type) {
            "The SubjectPublicKeyInfo holds a ${publicKey.keyType} key, the private key is ${privateKey.type}"
        }
        chain.firstOrNull()?.let { endEntity ->
            val certificate = decoded("end-entity certificate") { X509Certificate.parse(endEntity) }
            require(certificate.keyType == publicKey.keyType && certificate.publicKey.contentEquals(publicKey.publicKey)) {
                "The end-entity certificate holds another key than the SubjectPublicKeyInfo"
            }
        }
    }

    /** The DER SubjectPublicKeyInfo, which RawPublicKey mode sends as the certificate (RFC 7250 §3). */
    public val subjectPublicKeyInfo: ByteArray get() = spki.copyOf()

    /** DER certificates, end entity first; empty in RawPublicKey mode. */
    public val certificateChain: List<ByteArray> get() = chain.map { it.copyOf() }

    override fun toString(): String = "DtlsLocalCredential(${privateKey.type}, ${chain.size} certificates)"

    /** The result of [decode], with its [CertificateFormatException] as the cause of a caller mistake. */
    private inline fun <T> decoded(what: String, decode: () -> T): T = try {
        decode()
    } catch (e: CertificateFormatException) {
        throw IllegalArgumentException("Invalid $what: ${e.message}", e)
    }
}
