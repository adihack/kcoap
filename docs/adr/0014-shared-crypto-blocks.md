# 0014. Shared crypto building blocks live in kcoap-crypto

Date: 2026-11-06. Status: accepted. Settled by WP-A4 of [target-architecture.md](../target-architecture.md)
(§2.4, §2.5, §3.9; findings MB-7, U3, U5, U10); written down by WP-C3.

## Context

X.509 and SubjectPublicKeyInfo parsing lived in kcoap-edhoc, although DTLS certificate mode needs the same parsing.
SEC1 point decompression lived in kcoap-crypto-default, so every other backend (a key store, a secure element, a
future pure-Kotlin one) would have had to repeat it, and Apple's SecKey and browsers do not reliably take compressed
points at all. DTLS had a key-store abstraction of its own (`DtlsSigner`), separate from the `PrivateKey` handles
EDHOC uses. The review proposed a separate kcoap-x509 module.

## Decision

- **kcoap-crypto holds what more than one protocol or backend needs**, as pure common Kotlin:
  - the `x509` package: `X509Certificate`, `SubjectPublicKeyInfo`, an internal DER reader, and
    `CertificateFormatException`, the only exception they throw, whatever a peer sends;
  - `Sec1`, which expands compressed P-256 and P-384 points before any backend sees them, so the SPI takes
    uncompressed points only;
  - `EcdsaSignatureCodec`, between COSE's raw `r || s` and the DER of X.509 and TLS;
  - `PrivateKey.destroy()` and `KemEncapsulation.sharedSecret()`, so protocol code releases any kind of key handle.
  No kcoap-x509 module: the code is small, pure Kotlin, and belongs next to `CertificateValidator`, its SPI.
- **One credential shape for EDHOC and DTLS.** `DtlsLocalCredential(privateKey, subjectPublicKeyInfo,
  certificateChain)` replaces `DtlsSigner`; both protocols sign through `CryptoBackend.sign`, so one key-store
  decorator, overriding `sign`, `keyAgreement` and `decapsulate` for its own key subclass, serves both (the
  kcoap-crypto README has the recipe).
- **Parsing is not validation.** kcoap ships no `CertificateValidator` yet. The planned default is a common-code
  validator for the RFC 7925 profile, with an injected clock, verifying signatures through `CryptoBackend.verify`
  (roadmap item 13, E2); validators on the operating system's trust stores may follow as optional adapters.

## Consequences

- A backend implements only primitives; encodings are settled once, above the SPI, and behave the same on every
  platform.
- The `x509` package moved without a typealias, a breaking change made while the version is a snapshot.
- Until the validator exists, an `x5chain` is trusted by pinning (`TrustedCredentials`) or by a validator the
  application supplies; docs/architecture.md says what is trusted with and without one.
