# Roadmap

kcoap is built bottom-up: each layer is complete and tested against RFC vectors before the next one
depends on it. Interop with libcoap and libedhoc runs as soon as a layer can talk to them.

| # | Milestone | Modules | Verified by | State |
|---|---|---|---|---|
| 1 | Foundations: logging, hex, coverage rule | `kcoap-common`, build | unit tests | done |
| 2 | Crypto seam: async SPI, AES-CCM, HKDF, SHAKE256/KMAC256, X25519, Ed25519, P-256/384 | `kcoap-crypto`, `kcoap-crypto-default` | RFC 3610, 5869, 7748, 8032, 8613 C, FIPS 202, SP 800-185, Bouncy Castle | done |
| 3 | CBOR and COSE | `kcoap-cbor`, `kcoap-cose` | RFC 8949 A, RFC 8613 C.4, RFC 9529 | done |
| 4 | Post-quantum backend: ML-KEM, ML-DSA | `kcoap-crypto-bc` | FIPS 203/204 vectors (NIST ACVP) | done |
| 5 | CoAP messages and codec (UDP and RFC 8323 framing), options, URIs | `kcoap-coap` | RFC 7252, 8323, RFC 8613 C | done |
| 6 | EDHOC: methods 0–3, suites 0–6 and 24, PQ suite (KEM + ML-DSA), EAD, exporter | `kcoap-edhoc` | RFC 9529 traces (both roles, §4 invalid messages), libedhoc live (both roles, suites 0, 2, 4, 24, -24) | done |
| 7 | OSCORE: context, protect/verify, replay window, Observe, B.1.1, B.1.2 (B.2 pending) | `kcoap-oscore` | RFC 8613 C, libcoap live | done |
| 8 | CoAP endpoint: messaging, exchanges, client and server API, UDP transport | `kcoap-coap`, `kcoap-transport-ktor`, `kcoap-testkit` | unit tests on a simulated network, libcoap both ways | done (UDP; DTLS with 12) |
| 9 | EDHOC over CoAP and RFC 9668 combined request | `kcoap-edhoc-oscore` | RFC 9668 Figure 4, full-stack tests | done; combined flow live interop pending |
| 10 | Interop harness: libcoap and libedhoc in containers, byte-exact oracles | `interop/` | CI | live harnesses done (libcoap CoAP + OSCORE, libedhoc EDHOC incl. PQ, in CI); byte-exact oracles planned |
| 11 | TCP and WebSocket transports (RFC 8323), browser target (JS/Wasm) | `kcoap-coap`, `kcoap-transport-ktor`, `kcoap-transport-ktor-websocket(-server)`, `kcoap-testkit`, build | RFC 8323 figures, in-memory and loopback tests, libcoap both ways over TCP and WebSockets; the common tests on Node.js | RFC 8323 done except `coaps+tcp` (no TLS connector yet); `js` and `wasmJs` targets done (every protocol module, WebCrypto backend with capability checks and common-code fallbacks) |
| 12 | DTLS 1.2 | `kcoap-dtls` | libcoap with five DTLS backends | last |
| 13 | X.509 path validation: a common-code `CertificateValidator` for the RFC 7925 §4.4.2 profile (explicit trust anchors, ECDSA P-256/P-384, Ed25519 and ML-DSA signatures, validity on an injected clock, basicConstraints and keyUsage, no RSA, no revocation), then optional platform adapters (JVM `CertPathValidator`, Apple `SecTrust`) | `kcoap-crypto`, next to the `x509` parser, shared by EDHOC `x5chain` and DTLS | an x509-limbo subset, the RFC 9529 trace 1 chain | todo; until then only pinned certificates are trusted unless the application supplies a validator |

Post-quantum: libedhoc implements the KEM-based ephemeral exchange of draft-ietf-lake-pqsuites with
ML-KEM-512 and ML-DSA-44 as its private suite -24. kcoap models the ephemeral exchange as a strategy
(Diffie-Hellman or KEM) so the same Initiator and Responder run both; `CipherSuite.SUITE_PQC_1` mirrors
libedhoc's suite. It runs on real ML-KEM and ML-DSA from `kcoap-crypto-bc` and interoperates with
libedhoc's liboqs-based suite -24 in both roles.
