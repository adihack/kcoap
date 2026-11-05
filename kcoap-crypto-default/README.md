# kcoap-crypto-default

The default `CryptoBackend`, on [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin)
0.6: one common-code implementation over whatever provider the platform has.

| Target | Provider |
|---|---|
| `jvm` (servers, Android) | JCA (the JDK's own providers) |
| iOS, macOS | CryptoKit and CommonCrypto |
| `linuxX64` | OpenSSL 3 (prebuilt, linked in) |
| `js`, `wasmJs` (browsers, Node.js) | WebCrypto |

```kotlin
val crypto = DefaultCryptoBackend()
// A durable SequenceNumberStore for pre-shared keys: see kcoap-oscore/README.md.
val context = SecurityContext.derive(parameters, crypto, sequenceStore)
if (!crypto.supports(AeadAlgorithm.CHACHA20_POLY1305)) { /* offer another AEAD */ }
```

## What it implements

SHA-256, SHA-384, SHAKE256 (common code), HMAC, AES-CCM (common code on the provider's AES block),
AES-GCM, ChaCha20/Poly1305, X25519, X448, Ed25519, and ECDH and ECDSA on P-256 and P-384, with raw key
encodings as `KeyType` documents them. Post-quantum algorithms are in `kcoap-crypto-bc`.

## Capabilities and fallbacks

The code is the same on every target; providers differ in what they offer, and the backend adapts by
asking the provider, never by checking the platform:

| Need | Provider has it | Provider lacks it |
|---|---|---|
| AES block for CCM | AES-ECB on single blocks | AES-CTR: a zero block encrypted with the input as the counter is `AES_K(input)` (WebCrypto) |
| ECDH or ECDSA from a compressed point | — | the contract asks for uncompressed points, which protocol code makes with `Sec1` (kcoap-crypto); this backend also takes compressed ones and expands them with `Sec1` before the provider sees them |
| An AEAD, curve or key size | used | `UnsupportedAlgorithmException`: ChaCha20/Poly1305 on WebCrypto, 192-bit AES in Chromium, X25519 or Ed25519 in browsers that predate them |

The `supports` members of `CryptoBackend` ask in advance, the same way for every platform and every
backend ([ADR 0001](../docs/adr/0001-crypto-capabilities.md)). This backend probes each algorithm once
per instance, by running it on throwaway keys, and memoizes the answer, so later questions cost a lookup
however many callers ask at once; that also absorbs the differences between browsers.
`CipherSuite.isSupportedBy(crypto, method)` in `kcoap-edhoc` builds on them, and EDHOC offers and accepts
only the suites they allow. Every other failure of the platform leaves as an exception, also on
WebCrypto, whose rejections are not Kotlin exceptions, so protocol code turns them into protocol errors.

ChaCha20/Poly1305, X25519 and Ed25519 have no common-code fallback: they process secret keys, and
constant-time arithmetic is not something Kotlin/JS can promise. Point decompression only sees public
values.

## Verified by

| Test | What |
|---|---|
| `AesCcmTest` | all 24 RFC 3610 packets, the RFC 8613 Appendix C AEAD vectors, tampering |
| `AesBlockTest` | FIPS 197 Appendix C through both AES-block constructions, ECB and CTR agree where ECB exists, RFC 3610 through CTR |
| `HkdfTest` | RFC 5869, RFC 8613 Appendix C key derivation |
| `AsymmetricTest` | RFC 7748 (X25519), RFC 8032 (Ed25519), ECDH and ECDSA on fresh keys, compressed points, fresh platform keys of both parities through `Sec1` (whose vectors are in kcoap-crypto), unusable peer keys |
| `PlatformAeadTest`, `CapabilitiesTest` | every AEAD round-trips or is reported unsupported; the mandatory algorithms are supported everywhere |
| `SupportsMemoTest` | a provider that counts lookups is reached only by the first question about an algorithm, also when callers race on real threads |
| `BouncyCastleDifferentialTest` (JVM) | CCM for every tag and nonce length, GCM and ChaCha20/Poly1305 against Bouncy Castle |
| `ConsumerRulesTest` (JVM) | the jar's R8 rules cover every Bouncy Castle class cryptography-kotlin's JDK provider refers to (below) |

All but the JVM ones run on every target: JVM, Linux native, Apple native (on macOS), and Node.js as
JavaScript and as Wasm.

## Android and R8

The jvm jar carries R8 and ProGuard rules (`META-INF/proguard/kcoap-crypto-default.pro`), which the
Android Gradle plugin applies to an app's release build without any rule of the app's own.
cryptography-kotlin's JDK provider refers to Bouncy Castle and uses it only when `Class.forName` finds it
at runtime; the rules tell R8 that the classes may be missing, which they are in an app that does not use
`kcoap-crypto-bc`. Without them R8 fails such an app's release build. The classical flavor of
[samples/android-consumer](../samples/android-consumer/README.md) shrinks with them in CI.

