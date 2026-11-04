# RFC compliance checklist

"100% RFC" here means every MUST and SHOULD of the published RFCs below, verified by the RFC's own test
vectors where they exist and by interop runs against independent implementations. Status values:
`todo`, `partial`, `done`. Drafts are listed separately and are out of scope until they become RFCs.

## Foundations

| Spec | What | Status | Verified by |
|---|---|---|---|
| RFC 3610 | AES-CCM | done | RFC 3610 §8 vectors 1–24; BouncyCastle differential (all tag/nonce lengths, 6-byte AAD length) |
| RFC 5869 | HKDF | done | RFC 5869 A.1–A.3 |
| RFC 8949 §4.2 | Deterministic CBOR | todo | RFC 8949 Appendix A examples |
| RFC 8742 | CBOR sequences (EDHOC messages) | done | RFC 9529 traces |
| RFC 9052 / 9053 | COSE structures and algorithms | todo | RFC 8613 C, RFC 9529 |
| RFC 8392 | CWT / CCS credentials | partial (CCS done, CWT todo) | RFC 9529 trace 2 |
| RFC 9360 | x5chain / x5t | done (as EDHOC ID_CRED_x) | RFC 9529 trace 1 |

## CoAP

| Spec | What | Status |
|---|---|---|
| RFC 7252 | Messages, reliability, deduplication, options, caching rules, proxying | partial (option registry) |
| RFC 6690 | Link format, `/.well-known/core` | todo |
| RFC 7641 | Observe | todo |
| RFC 7959 | Block-wise | todo |
| RFC 8132 | FETCH, PATCH, iPATCH | todo |
| RFC 7967 | No-Response | todo |
| RFC 9175 | Echo, Request-Tag, token processing (needed by OSCORE Appendix B.1.2) | todo |
| RFC 8768 | Hop-Limit | todo |
| RFC 8323 | CoAP over TCP/TLS/WebSockets: framing, CSM, Ping/Pong, Release/Abort, BERT | todo |
| RFC 9177 | Q-Block | optional |

Hop-Limit and OSCORE: RFC 8768 gives it no OSCORE class, so under the published RFCs it is Class E
(RFC 8613 §4.1). draft-ietf-core-oscore-capable-proxies updates RFC 8768 to Class U, and libcoap already
behaves that way. kcoap follows the published RFCs for now; the difference only shows through proxies.

## DTLS and TLS

RFC 7252 §9 makes NoSec and RawPublicKey mandatory to implement, so DTLS is part of RFC 7252
compliance: kcoap cannot claim full RFC 7252 compliance until the DTLS feature lands. It is
implemented last; the interfaces and rules around it are in place now (`architecture.md`, "Delivery").

| Spec | What | Status |
|---|---|---|
| RFC 7252 §9 | Security modes, NoSec + RawPublicKey mandatory | partial (`SecurityMode`, `PeerIdentity`) |
| RFC 7252 §9.1.1–9.1.2 | Matching by DTLS session and epoch; no retransmission across epochs | partial (`Endpoint.Secure` equality, `SessionEnded`; messaging layer todo) |
| RFC 7252 §9.1.3.1 | PreSharedKey: `TLS_PSK_WITH_AES_128_CCM_8` (RFC 6655) | registry done; protocol todo |
| RFC 7252 §9.1.3.2 | RawPublicKey (RFC 7250): `TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8` (RFC 7251), secp256r1, RFC 6920 `sha-256-120` identifiers | registry done; protocol todo |
| RFC 7252 §9.1.3.3 | Certificate: same suite, authority vs SubjectAltName URI / CN, no wildcards | todo (`CertificateValidator` interface) |
| RFC 6347 | DTLS 1.2: record layer, cookie exchange, fragmentation, retransmission, replay window | todo (record overhead computed) |
| RFC 7925 | DTLS 1.2 IoT profile: resumption, SNI and `max_fragment_length` (RFC 6066), `cached_info` (RFC 7924), no renegotiation, 9 s timer | partial (`DtlsConfig` defaults) |
| RFC 7627 | Extended master secret (SHOULD in RFC 7925) | todo |
| RFC 9146 | DTLS 1.2 Connection ID | partial (endpoint semantics, record overhead) |
| RFC 8323 §9 | TLS for `coaps+tcp` / `coaps+ws`, ALPN `coap`; same three modes via RFC 7925 | partial (`StreamSecurity`, `CoapScheme.ALPN`) |
| RFC 9147 | DTLS 1.3 | later |

## OSCORE (RFC 8613)

| Section | What | Status | Verified by |
|---|---|---|---|
| §3 | Security context derivation | done | C.1–C.3 (keys, IVs, `info`, nonces) |
| §4 | Option classes E/U, Proxy-Uri decomposition | done | C.4–C.6, unit tests |
| §5–§8 | COSE object, AAD, nonce, protect/verify, error responses | done | C.4–C.8 byte for byte, both sides |
| §7.4 | Replay window | done | unit + end-to-end tests |
| §4.1.3.5, §8.3.1, §8.4.2 | Observe with OSCORE | done | end-to-end tests |
| §8.2.1, §8.4.1 | Inner Block-wise (by the stack, above OSCORE) | with the stack | stack tests |
| App. B.1.1 | Sequence-number persistence | done | unit tests |
| App. B.1.2 | Echo-based replay-window recovery (server side; client repeats via the stack) | done | unit tests, libcoap `coap-client` answering kcoap's challenge, kcoap answering libcoap's |
| App. B.2 | Context re-derivation (ID Context) | todo | libcoap |
| §11 | OSCORE over HTTP | todo | |
| — | Interop | live, both directions | libcoap v4.3.5b over UDP: kcoap client ↔ `coap-server` (incl. its B.1.2 Echo challenge), `coap-client` ↔ kcoap responder; `oscore_testcases.sh` todo |

## EDHOC (RFC 9528)

§8 compliance requirements:

| Requirement | Status | Verified by |
|---|---|---|
| Cipher suites 2 **and** 3 (MUST) | done | RFC 9529 §3 (suite 2), libedhoc interop (suite 2), round trips (suite 3; libedhoc lacks it) |
| `kid` credential identifiers (MUST) | done, incl. compact encoding and non-unique kids | RFC 9529 §3, §4.1.6–4.1.7, libedhoc interop (integer and byte-string kids) |
| EDHOC_Exporter (MUST) | done | RFC 9529 §2.6, §3.8, libedhoc interop (exporter, OSCORE secret and salt, before and after key update) |
| EAD processing (MUST) | done: `EadHandler`, critical labels, padding | round trips, negative tests, libedhoc interop (padding, non-critical items) |
| Error codes 1 and 2 (MUST) | done, plus 0 and 3 | RFC 9529 §3.2, negotiation tests, libedhoc interop (code 2 negotiation and code 1 in both directions) |
| message_4 (MAY, but MUST where no app message flows Responder→Initiator) | done | RFC 9529 §2.4, §3.6, libedhoc interop |

| Section | What | Status |
|---|---|---|
| §3.2, §5 | Methods 0–3, Initiator and Responder, sans-IO state machines | done |
| §3.3.2 | Connection identifier encoding, enforced on receipt | done |
| §3.5 | Credentials: CCS by `kid` / `kccs`, X.509 by `x5t` / `x5chain`; CWT and C509 | partial (no CWT, C509) |
| §3.6, §10.2 | Suites 0–6 and 24; 25 registered but not implemented (Ed448) | done |
| §3.8 | EAD, criticality, padding | done |
| §4 | Key schedule: HKDF (SHA-2), KMAC256 (SHAKE256), transcript hashes | done |
| §6 | Error messages, cipher suite negotiation (§6.3) | done |
| §7 | Deduplication | left to the transport (RFC 9528 §3.4) |
| §9.2, §9.8 | Public key validation, all-zero check, field lengths, deterministic CBOR on receipt | done |
| App. G | Long PLAINTEXT_2 | done |
| App. H | EDHOC_KeyUpdate | done (RFC 9529 §2.7, §3.9) |
| — | Interop with libedhoc 0363bd3: suites 0, 2, 4, 24 × methods 0–3 and -24 × method 0, kid/CCS and x5chain, negotiation, tampering (`interop/README.md`) | live, both roles |

Verified by RFC 9529 traces 1 and 2 (every printed value, both roles), the invalid messages of RFC 9529
§4, round trips over every method and suite, and live sessions against libedhoc in both roles for every
suite and method both implement (`LibedhocInteropTest`). Suites 1, 3, 5 and 6 have no independent peer
yet: libedhoc lacks them. Two libedhoc deviations from RFC 9528 found on the way (its Responder supports
only its last configured suite and skips the §5.2.3 prior-suite check; without an EAD callback it accepts
unknown critical EAD items) are pinned by `upstream…` tests and listed in `interop/README.md`.

## EDHOC with CoAP and OSCORE

| Spec | What | Status |
|---|---|---|
| RFC 9528 App. A.1 | EDHOC connection identifiers ↔ OSCORE Sender/Recipient IDs | partial (Master Secret/Salt from `EdhocSession`; context derivation todo) |
| RFC 9528 App. A.2 | EDHOC over CoAP (`/.well-known/edhoc`, forward and reverse flows) | todo |
| RFC 9668 | EDHOC option, combined EDHOC+OSCORE request, application-profile parameters | registry done |

## Drafts (out of scope until published)

Group OSCORE (draft-ietf-core-oscore-groupcomm), KUDOS key update (draft-ietf-core-oscore-key-update),
EDHOC-PSK (draft-ietf-lake-edhoc-psk; libedhoc already implements it as method 4), OSCORE-capable
proxies (draft-ietf-core-oscore-capable-proxies), TLS/DTLS 1.3 IoT profile
(draft-ietf-uta-tls13-iot-profile).

One exception: the KEM-based key exchange of draft-ietf-lake-pqsuites-02 §3 is implemented, because
RFC 9528 §9.4 already allows a KEM in method 0 and libedhoc ships it as the private suite -24
(`CipherSuite.SUITE_PQC_1`: ML-KEM-512, ML-DSA-44, AES-CCM-16-128-128, SHAKE256). The draft's suites
have no code points yet (TBD1–TBD3); they can be declared as private `CipherSuite`s. Verified with real
ML-KEM and ML-DSA (`kcoap-crypto-bc`) in round trips for suite -24 and a private ML-KEM-1024/ML-DSA-87
suite, and live against libedhoc (liboqs) for suite -24 in both roles, with CCS (AKP COSE_Key) and
ML-DSA-44 X.509 credentials.
