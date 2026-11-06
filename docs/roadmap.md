# Roadmap

kcoap is built bottom-up: each layer is complete and tested against RFC vectors before the next one
depends on it. Interop with libcoap and libedhoc runs as soon as a layer can talk to them. The
[checklist](checklist.md) has every requirement with its status; [target-architecture.md](target-architecture.md)
has the refactoring work packages (WP-…) and the roadmap features E1 to E11 named below. The numbers of the
milestones are identifiers, so the ones still open (12, 13) keep theirs.

## Done

| # | Milestone | Modules | Verified by | State |
|---|---|---|---|---|
| 1 | Foundations: logging, hex, coverage rule, `KcoapException` | `kcoap-common`, build | unit tests | done |
| 2 | Crypto seam: async SPI with capability questions, AES-CCM, HKDF, SHAKE256/KMAC256, SEC1, ECDSA DER, X.509 parsing; X25519, Ed25519, P-256/384 | `kcoap-crypto`, `kcoap-crypto-default`, `kcoap-crypto-testsuite` | RFC 3610, 5869, 7748, 8032, 8613 C, FIPS 202, SP 800-185, Bouncy Castle; the conformance suite on every target | done; no X.509 path validator (13) |
| 3 | CBOR and COSE | `kcoap-cbor`, `kcoap-cose` | RFC 8949 A, RFC 8613 C.4, RFC 9529 | done for what OSCORE and EDHOC need; strict decoding open (checklist foundations-1) |
| 4 | Post-quantum backend: ML-KEM, ML-DSA | `kcoap-crypto-bc` | FIPS 203/204 vectors (NIST ACVP) through the conformance suite | done (JVM, Android) |
| 5 | CoAP messages and codecs (UDP and RFC 8323 framing), options, URIs, link format | `kcoap-coap-message` | RFC 7252, 8323, RFC 8613 C, RFC 6690 §5; fuzzing | done |
| 6 | EDHOC: methods 0–3, suites 0–6 and 24, the post-quantum suite -24 (KEM and ML-DSA), EAD, exporter | `kcoap-edhoc` | RFC 9529 traces (both roles, §4 invalid messages), libedhoc live (both roles, suites 0, 2, 4, 24, -24) | done; suite 25 and CWT credentials open |
| 7 | OSCORE: context, protect/verify, replay window, Observe, B.1.1, B.1.2 | `kcoap-oscore` | RFC 8613 C, libcoap live | done; Outer Block-wise and B.2 open |
| 8 | CoAP endpoint: messaging, exchanges, client and server API, block-wise, Observe, UDP transport | `kcoap-coap`, `kcoap-transport-ktor`, `kcoap-testkit` | unit tests on a simulated network, libcoap both ways | done; caching, proxying and multicast open |
| 9 | EDHOC over CoAP and the RFC 9668 combined request | `kcoap-edhoc-oscore` | RFC 9668 Figure 4, full-stack tests, the browser topology through libcoap's proxy | done for the forward flow; the reverse flow and the combined request's fallbacks open |
| 10 | Interop harness: libcoap and libedhoc in containers | `interop/` | CI | live harnesses done (libcoap CoAP, OSCORE, Q-Block, TLS clients; libedhoc EDHOC incl. post-quantum); byte-exact oracles planned |
| 11 | TCP and WebSocket transports (RFC 8323), the browser targets (`js`, `wasmJs`) | `kcoap-coap`, `kcoap-transport-ktor`, `kcoap-transport-ktor-websocket(-server)`, build | RFC 8323 figures, in-memory and loopback tests, libcoap both ways over TCP and WebSockets; the common tests on Node.js; a headless browser | done |
| 14 | Q-Block (RFC 9177), off by default, with Observe and under OSCORE | `kcoap-coap`, `kcoap-oscore` | every figure of RFC 9177 on virtual time, soaks at 10 % and 30 % loss, libcoap 4.3.5b both ways | done (QB-0 to QB-7) |
| 15 | Fail-closed message security and server address verification | `kcoap-coap`, `kcoap-oscore`, `kcoap-edhoc-oscore` | `AccessPolicyTest`, `UnverifiedResponseTest`, `AmplificationTest`, libcoap | done ([ADR 0008](adr/0008-fail-closed-message-security.md), SRV-1) |
| 16 | Universal by design: the refactoring plan's API-breaking work packages (A1–A5, B1–B3, C1–C3, D1, D2, W1) | every module | ABI dumps, `verifyCommonOnly`, the conformance suite, `ExchangeConformanceTest`, the Swift smoke test | done: the [release freeze](releasing.md#release-freeze) list is complete |
| 17 | TLS clients: `coaps+tcp` on the JVM, `coaps+ws` on the WebSocket engine's TLS | `kcoap-transport-ktor`, `kcoap-transport-ktor-websocket` | libcoap 4.3.5b on OpenSSL, JSSE | done (WP-T1); servers terminate TLS at a proxy ([ADR 0010](adr/0010-server-side-tls.md)) |
| 18 | Platform checks: Android API level 26, Swift from a framework, one version through a BOM | build, `samples/android-consumer`, `kcoap-swift-smoke`, `kcoap-bom` | `checkAndroidApi`, the CI `android` and `apple` jobs, `verifyBomConstraints` | done (PL-1, D2, C3); no emulator or device run yet |

## Next

In order, as far as the order is known:

1. **The first release, 0.1.0.** The freeze list has merged; what [releasing.md](releasing.md#release-freeze) still
   asks before the tag: the deprecated constructors of `CoapEndpointConfig` and `CoapClientConfig` removed, a
   CHANGELOG entry for the version, the `dev.kcoap` namespace verified and the signing secrets set (the owner's
   steps), and the consumer samples of WP-P1 (a JVM consumer on the BOM, a js consumer with a bundle-size budget).
2. **The rest of the CoAP work packages.** SRV-2 (server hardening: 4.29 Too Many Requests, sequence tokens on TLS
   connections, the remaining bounds) and WP-B4 (the rest of the error and observability findings: a
   `ResponseRejected` event, reason enums for rejected and malformed messages, `stats()`, `CoapResponseException`).
3. **Platforms.** Android emulator runs with a capability report per API level (PL-2); a JS facade and npm package
   (PL-4); the Apple facade and XCFramework for Swift-only apps (E4, kcoap-apple); the mobile lifecycle helpers
   (E9: re-observe, keepalive policy, transport rebind); a Network.framework adapter (E10); `linuxArm64` (E5).
4. **Crypto.**

   | # | Item | Modules | Verified by | State |
   |---|---|---|---|---|
   | 13 | X.509 path validation (E2): a common-code `CertificateValidator` for the RFC 7925 §4.4.2 profile (explicit trust anchors, ECDSA P-256/P-384, Ed25519 and ML-DSA signatures, validity on an injected clock, basicConstraints and keyUsage, no RSA, no revocation), then optional platform adapters (JVM `CertPathValidator`, Apple `SecTrust`) | `kcoap-crypto`, next to the `x509` parser, shared by EDHOC `x5chain` and DTLS | an x509-limbo subset, the RFC 9529 trace 1 chain | todo; until then only pinned certificates are trusted unless the application supplies a validator |

   Then post-quantum on every target (E3, kcoap-crypto-pq: pure-Kotlin ML-KEM and ML-DSA, ML-DSA verification
   first), key-store adapters as `CryptoBackend` decorators (E7: Android KeyStore, Secure Enclave, non-extractable
   WebCrypto keys, PKCS#11), and message-level AES-CCM on WebCrypto only if a browser benchmark asks for it (E8).
5. **The rest of the RFCs.** The open MUSTs the [checklist](checklist.md#biggest-gaps) lists in order: caching,
   proxying and multicast (RFC 7252), the companions' gaps, OSCORE's Outer Block-wise and Appendix B.2, EDHOC's
   reverse flow, suite 25 and CWT credentials, the RFC 9668 fallbacks; each lands with its libcoap or libedhoc
   cross-test.
6. **Large bodies and proxies.** Streamed bodies beside the `ByteArray` API (E6); a kcoap cross-proxy for JVM
   servers that front browsers, if WP-I1's results ask for it (E11).

## Last: DTLS

| # | Milestone | Modules | Verified by | State |
|---|---|---|---|---|
| 12 | DTLS 1.2 (E1): a sans-IO engine in common Kotlin on `CryptoBackend` (record layer, handshake, cookies, PSK, raw public keys, certificates, Connection ID), a `DatagramSecurity` that drives it, the `coaps` scheme lifted for a transport that runs it; then PSK and raw public keys over TCP in the same engine | `kcoap-dtls` | libcoap with five DTLS backends, Bouncy Castle `bctls` as a differential oracle, DTLS-Fuzzer | last, by decision; the endpoint already follows the rules DTLS needs ([architecture](architecture.md#dtls)), and `coaps` is refused until then |

DTLS 1.3 (RFC 9147) follows only when deployed devices need it; the engine interface does not change.

## Post-quantum

libedhoc implements the KEM-based ephemeral exchange of draft-ietf-lake-pqsuites with ML-KEM-512 and ML-DSA-44 as its
private suite -24. kcoap models the ephemeral exchange as a strategy (Diffie-Hellman or KEM), so the same Initiator
and Responder run both; `CipherSuite.SUITE_PQC_1` mirrors libedhoc's suite. It runs on real ML-KEM and ML-DSA from
`kcoap-crypto-bc` on the JVM and Android and interoperates with libedhoc's liboqs-based suite -24 in both roles. On
other targets EDHOC leaves suite -24 out, because their backends answer `supports` with no. Platform APIs may add the
algorithms through the same capability mechanism (JDK 24's JCA, OpenSSL 3.5, CryptoKit, WebCrypto), but CryptoKit
has no ML-KEM-512 or ML-DSA-44, the parameter sets of suite -24, so Apple targets need the common-code
implementation of E3 even then.
