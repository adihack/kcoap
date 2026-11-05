# kcoap-dtls

DTLS 1.2 for `coaps` in common Kotlin: RFC 6347 with the RFC 7925 IoT profile, PSK, raw public keys
(RFC 7250), X.509, Connection ID (RFC 9146), and a TLS 1.2 stream mode for PSK and raw public keys
over TCP (RFC 8323).

**State: placeholders. The engine is a separate feature, implemented last.** Until it lands the module
is built and tested like the others but not published (`kcoap.unpublished`), so no release ships SPIs
without an implementation. What exists:

- `DtlsCipherSuite`: `TLS_PSK_WITH_AES_128_CCM_8` and `TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8`, their
  CCM-8 parameters and the record overhead (with and without CID).
- `DtlsConfig`: RFC 7925 defaults (9 s initial retransmission, 60 s cap), validated per mode.
- `DtlsCredentials`: `PskStore`, `DtlsLocalCredential` (a P-256 `PrivateKey` handle, its
  SubjectPublicKeyInfo and certificate chain, checked to belong together), `RawPublicKeyTrust`, and the
  shared `CertificateValidator` from kcoap-crypto. The private key is the same kind of handle EDHOC's
  `LocalCredential` takes, so one `CryptoBackend` decorator for a key store serves both protocols; the
  engine will sign with `CryptoBackend.sign(ES256, ...)` and `EcdsaSignatureCodec.toDer`.
- `DtlsEngine` / `DtlsEffect`: the sans-IO engine interface.

The feature adds: the engine (handshake, record layer, cookies, fragmentation, retransmission,
replay window, resumption, CID), a `DatagramSecurity` that drives it (which then loses its
`@ExperimentalKcoapApi` marker), the stream mode for a `coaps+tcp` connector, RFC 6920 identifiers, the
RFC 7252 §9.1.3.3 name check and the TLS 1.2 PRF. Then the module is published. Certificate and raw
public key parsing (`X509Certificate`, `SubjectPublicKeyInfo`) and DER↔raw ECDSA conversion
(`EcdsaSignatureCodec`) are in kcoap-crypto already, shared with EDHOC.

Interop: libcoap with TinyDTLS, GnuTLS, wolfSSL, OpenSSL and Mbed TLS; Bouncy Castle `bctls` as a
byte-exact differential on the JVM; DTLS-Fuzzer and TLS-Attacker. See `interop/README.md`.
