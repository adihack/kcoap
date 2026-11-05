# kcoap-crypto

The seam between kcoap's protocols and cryptography, and the building blocks they share. Protocol code
(OSCORE, EDHOC, COSE, DTLS) never calls a platform API: it calls a `CryptoBackend`, and what every
backend would otherwise implement again is common code here. Pure Kotlin with no platform dependencies,
so it builds for every target, browsers included; the backends live in `kcoap-crypto-default` (the
platform's cryptography) and `kcoap-crypto-bc` (post-quantum on Bouncy Castle).

| Type | What it is |
|---|---|
| `CryptoBackend` | The SPI, all `suspend` (WebCrypto is asynchronous): digest, HMAC, the AES block, AEADs, key pairs, key agreement, signatures, KEMs, and the `supports` members that say what runs ([ADR 0001](../docs/adr/0001-crypto-capabilities.md)) |
| `PrivateKey`, `SoftwarePrivateKey`, `KeyPair`, `KemEncapsulation` | Opaque private-key handles with `destroy()`, a software key in memory, raw public keys, a KEM result whose secret leaves only through `sharedSecret()` |
| `AesCcm`, `Hkdf`, `Shake256`, `Kmac256` | AES-CCM on the backend's AES block (RFC 3610), HKDF on its HMAC (RFC 5869), SHAKE256 and KMAC256 on Keccak (FIPS 202, NIST SP 800-185) |
| `Sec1` | Compressed P-256 and P-384 points expanded to `04 \|\| x \|\| y` (SEC 1 v2 §2.3.4) |
| `EcdsaSignatureCodec` | ECDSA signatures between raw `r \|\| s` (COSE) and DER (X.509, TLS) |
| `x509.X509Certificate`, `x509.SubjectPublicKeyInfo`, `x509.CertificateFormatException` | The parts of a certificate and of a SubjectPublicKeyInfo that EDHOC and DTLS need; one exception for whatever cannot be used |
| `CertificateValidator` | The SPI for chain validation, shared by EDHOC and DTLS; no implementation ships yet (below) |

## The contract at the seam

Public keys cross the boundary in the raw encoding of their `KeyType`, and the same input behaves the
same way with every backend on every platform:

- **P-256 and P-384 keys are uncompressed SEC1 points** `04 || x || y`. Protocol code expands compressed
  ones with `Sec1` before any backend sees them: EDHOC's x-only ephemeral keys (RFC 9528 Appendix B),
  compressed keys in certificates (RFC 5480 §2.2), COSE_Key sign bits (RFC 9053 §7.1.1). Apple's SecKey
  and browsers' WebCrypto do not reliably take compressed points, and a secure element may not at all, so
  a backend need not.
- **ECDSA signatures are raw `r || s`**, as COSE uses them. DTLS and X.509 convert with
  `EcdsaSignatureCodec`, and a backend on a platform that signs in DER converts the other way.
- **SHAKE256 is a digest every backend answers.** One without a native SHAKE256 returns
  `Shake256.digest(data, 64)`. KMAC256 is common code on Keccak and never reaches the backend, as HKDF is
  common code on the backend's HMAC.
- **A missing algorithm is `UnsupportedAlgorithmException`** and nothing else, and `supports(x)` is false
  exactly where an operation on `x` would throw it.

## Keys in a key store

Android KeyStore, the Secure Enclave, a PKCS#11 token or a non-extractable WebCrypto key hold private keys
that never leave them. Such a store plugs in as a decorator of a software backend that overrides only the
private-key operations, and the same decorator then serves EDHOC (`LocalCredential`) and DTLS
(`DtlsLocalCredential`):

```kotlin
class KeyStoreKey(type: KeyType, val alias: String) : PrivateKey(type) {
    override fun destroy() { /* release the handle; delete the entry if the key was ephemeral */ }
}

class KeyStoreBackend(
    private val software: CryptoBackend,
    private val store: PlatformKeyStore,              // the application's wrapper around the platform API
) : CryptoBackend by software {
    override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray): ByteArray =
        if (privateKey !is KeyStoreKey) software.sign(algorithm, privateKey, data)
        // The store signs in DER, as JCA and SecKey do; COSE and the SPI want raw r || s.
        else EcdsaSignatureCodec.fromDer(algorithm, store.sign(privateKey.alias, data)) ?: error("Malformed signature")
    // The same for keyAgreement and decapsulate. Override supports(...) with static answers: a probe
    // could create a persistent key or prompt for a biometric.
}
```

Protocol code calls `PrivateKey.destroy()` on the keys it owns, such as EDHOC's ephemeral keys (RFC 9528
§9.8), whatever kind of handle they are; a software key overwrites its bytes and fails closed afterwards.

## Certificates

`X509Certificate.parse` walks the DER of a certificate (RFC 5280 §4.1) down to its subject public key and
the subject's common name; `SubjectPublicKeyInfo.decode` does the same for a raw public key (RFC 7250 §3).
They recognize Ed25519, X25519, X448, P-256, P-384 and ML-DSA-44/65/87 keys, and hand out P-256 and
P-384 keys uncompressed. Whatever the bytes, they return or throw `CertificateFormatException`, so DER from
a peer never surfaces as an `IllegalArgumentException`.

Parsing is not validation. kcoap ships no `CertificateValidator` yet: until it does, trusting an
`x5chain` means pinning its end-entity certificate (kcoap-edhoc's `TrustedCredentials`) or a validator
the application supplies. The planned default is a common-code validator for the RFC 7925 profile that
verifies signatures through `CryptoBackend.verify` and reads time from an injected clock, with the
operating system's trust stores as optional adapters later (X.509 item of the [roadmap](../docs/roadmap.md)).

## Verified by

| Test | What |
|---|---|
| `Sec1Test` | the SEC 2 base points, their doubles and triples, and the P-256 points of RFC 9529 §3, with y of both parities; x ≥ p, x off the curve, wrong lengths and prefixes |
| `PrimeFieldTest` | the field arithmetic under `Sec1` on a small prime, and the moduli it refuses |
| `EcdsaSignatureCodecTest` | minimal positive INTEGERs (X.690 §8.3.2), ES256 and ES384, strict DER only, and 4 000 mutated signatures that decode or give null |
| `X509CertificateTest`, `SubjectPublicKeyInfoTest` | the Ed25519 certificates of RFC 9529 §2, every recognized key algorithm, compressed keys, malformed and unsupported input, and 4 000 mutated certificates and keys each that parse or throw `CertificateFormatException` |
| `KeccakTest`, `BackendDefaultsTest`, `ProbeTest` | FIPS 202 and SP 800-185 known answers; a backend with only the symmetric primitives; the default `supports` probes; key handles and KEM results |
| `Sec1DifferentialTest` (JVM) | 128 random points and 400 random x-coordinates against Bouncy Castle |
| `EcdsaSignatureCodecJcaTest` (JVM) | the JDK's DER and IEEE P1363 ECDSA signatures, each converted and verified as the other, on P-256 and P-384 |
| `KeccakDifferentialTest` (JVM) | SHAKE256 and KMAC256 against Bouncy Castle |

All but the JVM ones run on every target: JVM, Linux native, Apple native (on macOS), and Node.js as
JavaScript and as Wasm. The AES-CCM and HKDF vectors run through the backends, in `kcoap-crypto-default`.
