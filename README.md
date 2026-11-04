# kcoap

Kotlin Multiplatform CoAP + DTLS + OSCORE + EDHOC, aiming at full compliance with the published RFCs,
for mobile apps, browsers and servers alike. libcoap (CoAP, DTLS, OSCORE) and libedhoc (EDHOC) are the
reference oracles: byte-exact differential tests plus live interop, described in `interop/README.md`.

Status: **skeleton**. The build, module layout, crypto seam, AES-CCM and HKDF are in place and tested
against RFC vectors. Everything else is the plan below; transports, streams and DTLS are designed in
`docs/architecture.md`.

## Why from scratch

No Kotlin implementation of OSCORE or EDHOC exists. Eclipse Californium (Java) has OSCORE but no
EDHOC upstream, is JVM-only, and is effectively maintained by one person (22 commits in 2026, no release
since Aug 2025). The only CoAP piece worth reusing is the JuulLabs KoAP codec, which may be vendored into
`kcoap-coap` later. See `docs/rfc-compliance.md` for scope and `interop/README.md` for test partners.

## Modules

| Module | Scope |
|---|---|
| `kcoap-crypto` | `CryptoBackend` seam, algorithm registries, pure-Kotlin **AES-CCM** and **HKDF** |
| `kcoap-crypto-default` | Backend on cryptography-kotlin: JCA on JVM/Android, CryptoKit + CommonCrypto on Apple |
| `kcoap-cbor` | Deterministic CBOR and CBOR sequences (empty) |
| `kcoap-cose` | COSE_Encrypt0, Sign1, Enc/Sig structures, COSE_Key, CCS/CWT, x5chain/x5t (empty) |
| `kcoap-coap` | Option registry with OSCORE classes; codec and exchange layer to come |
| `kcoap-transport` | Datagram, byte-stream and message-stream interfaces; endpoints, peer identities, URI schemes |
| `kcoap-dtls` | DTLS 1.2 placeholders: cipher suites, config, credentials, engine interface. Engine comes last |
| `kcoap-oscore` | RFC 8613 (empty) |
| `kcoap-edhoc` | Cipher-suite registry; RFC 9528 state machines to come |
| `kcoap-edhoc-oscore` | RFC 9668 combined request, EDHOC→OSCORE mapping (empty) |

DTLS is written in common Kotlin because no platform stack offers the cipher suites RFC 7252 requires.
It is implemented last, as a separate feature; the interfaces and rules it needs are in place so that
adding it changes no other module. Transport adapters for Ktor, Netty and Network.framework are
planned. See `docs/architecture.md`.

Design rules:

- **No I/O inside protocol code.** State machines take bytes and time and return bytes and timers;
  transports (ktor-network, Netty, Network.framework, browser WebSocket) are thin adapters. This keeps
  every module testable with fixed vectors and embeddable in any server event loop.
- **Every random value and ephemeral key is injectable**, otherwise RFC 9529 traces cannot be replayed.
- **All crypto goes through `CryptoBackend`**, so a secure element or Android KeyStore / Secure Enclave
  keys can replace the default backend.

## Targets

`jvm` (also consumed by Android), `iosArm64`, `iosSimulatorArm64`, `macosArm64`. Apple targets only
build on macOS with Xcode; on Linux they are skipped (`kotlin.native.ignoreDisabledTargets`). `js` and
`wasmJs` come once `CryptoBackend` is `suspend` (WebCrypto has no blocking API); browsers get
WebSocket and HTTP only, through a proxy.

Crypto availability per platform (from cryptography-kotlin's support tables) drives two decisions:

- AES-CCM, the default AEAD of OSCORE and EDHOC suites 0–3, exists in no Apple framework and in neither
  the stock JDK nor Android providers, so it lives in common code on top of the platform AES block.
- EDHOC suite 25 (X448, Ed448, SHAKE256) is not available through CryptoKit and SHAKE256 is not exposed
  anywhere, so it is out of scope for now. RFC 9528 does not require it.

## Build

Needs a JDK 17+ (a JRE is not enough).

```bash
./gradlew jvmTest
```

On macOS, also:

```bash
./gradlew macosArm64Test iosSimulatorArm64Test
```

Test vectors in `kcoap-crypto-default/src/commonTest/.../vectors` are generated from the RFC text:

```bash
tools/regen-vectors.sh
```

## Before the first publish

- Replace the placeholder coordinates (`kcoap.group` in `gradle.properties`) and the
  `com.example.kcoap` package.
- Pick a license.
