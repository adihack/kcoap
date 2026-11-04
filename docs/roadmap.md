# Roadmap

kcoap is built bottom-up: each layer is complete and tested against RFC vectors before the next one
depends on it. Interop with libcoap and libedhoc runs as soon as a layer can talk to them.

| # | Milestone | Modules | Verified by | State |
|---|---|---|---|---|
| 1 | Foundations: logging, hex, coverage rule | `kcoap-common`, build | unit tests | done |
| 2 | Crypto seam: async SPI, AES-CCM, HKDF, SHAKE256/KMAC256, X25519, Ed25519, P-256/384 | `kcoap-crypto`, `kcoap-crypto-default` | RFC 3610, 5869, 7748, 8032, 8613 C, FIPS 202, SP 800-185, Bouncy Castle | done |
| 3 | CBOR and COSE | `kcoap-cbor`, `kcoap-cose` | RFC 8949 A, RFC 8613 C.4, RFC 9529 | done |
| 4 | Post-quantum backend: ML-KEM, ML-DSA | `kcoap-crypto-bc` | FIPS 203/204 vectors | in progress |
| 5 | CoAP messages and codec (UDP and RFC 8323 framing), options, Block, Observe | `kcoap-coap` | RFC 7252, 7959, 7641, 8323, RFC 8613 C | in progress |
| 6 | EDHOC: methods 0–3, suites 0–6 and 24, PQ suite (KEM + ML-DSA), EAD, exporter | `kcoap-edhoc` | RFC 9529 traces (both roles, §4 invalid messages), libedhoc | done; libedhoc interop and real ML-KEM pending |
| 7 | OSCORE: context, protect/verify, replay window, Observe/Block, Appendix B | `kcoap-oscore` | RFC 8613 C, libcoap | planned |
| 8 | CoAP endpoint: messaging, exchanges, client and server API, UDP transport | `kcoap-coap`, `kcoap-transport-*` | unit tests, libcoap | planned |
| 9 | EDHOC over CoAP and RFC 9668 combined request | `kcoap-edhoc-oscore` | libcoap + libedhoc application | planned |
| 10 | Interop harness: libcoap and libedhoc in containers, byte-exact oracles | `interop/` | CI | planned |
| 11 | TCP and WebSocket transports, browser target (JS/Wasm) | `kcoap-transport-*` | libcoap | planned |
| 12 | DTLS 1.2 | `kcoap-dtls` | libcoap with five DTLS backends | last |

Post-quantum: libedhoc implements the KEM-based ephemeral exchange of draft-ietf-lake-pqsuites with
ML-KEM-512 and ML-DSA-44 as its private suite -24. kcoap models the ephemeral exchange as a strategy
(Diffie-Hellman or KEM) so the same Initiator and Responder run both; `CipherSuite.SUITE_PQC_1` mirrors
libedhoc's suite. It is tested with a size-faithful test double today, and cross-tested against
libedhoc once `kcoap-crypto-bc` provides ML-KEM and ML-DSA.
