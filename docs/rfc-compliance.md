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
| RFC 8742 | CBOR sequences (EDHOC messages) | todo | RFC 9529 traces |
| RFC 9052 / 9053 | COSE structures and algorithms | todo | RFC 8613 C, RFC 9529 |
| RFC 8392 | CWT / CCS credentials | todo | RFC 9529 trace 2 |
| RFC 9360 | x5chain / x5t | todo | RFC 9529 trace 1 |

## CoAP

| Spec | What | Status | Verified by |
|---|---|---|---|
| RFC 7252 §3–4 | Message format, CON/NON/ACK/RST, retransmission, deduplication, Message IDs, NSTART, PROBING_RATE, ping | done | `UdpMessagingTest` (sans-IO, per section), end-to-end on a lossy simulated network, libcoap |
| RFC 7252 §5 | Tokens, piggybacked / separate / NON responses, matching, critical options (4.02), Accept (4.06), Proxy-Uri (5.05), conditional requests (helper), methods | done | `RequestResponseTest`, libcoap |
| RFC 7252 §5.6–5.7, §8 | Caching, proxying, multicast | todo | |
| RFC 6690 | Link format, `/.well-known/core`, query filtering | done | RFC 6690 §5 examples, libcoap discovery both ways |
| RFC 7641 | Observe: server (sequence numbers, NSTART, superseding, 24 h CON, removal rules) and client (aggregation, §3.4 ordering, deregistration) | done; client re-registration on Max-Age (MAY) todo | `ObserveTest`, libcoap `/time` and `coap-client -s` |
| RFC 7959 | Block1 / Block2, negotiation, 2.31 / 4.08 / 4.13, Size1 / Size2, with Observe | done (inner; outer for proxies todo) | `BlockwiseTest`, libcoap `-b` both ways |
| RFC 8132 | FETCH, PATCH, iPATCH, observable FETCH | done (methods; patch formats are the application's) | `RequestResponseTest`, `ObserveTest` |
| RFC 7967 | No-Response | done (server) | `RequestResponseTest`, `EdgeCaseTest` |
| RFC 9175 | Echo, Request-Tag, token processing (needed by OSCORE Appendix B.1.2) | partial: client Echo retry, Request-Tag for concurrent Block1 and server keying, DTLS token sequences; server freshness checks and amplification mitigation todo | `MessageSecurityTest`, `BlockwiseTest`, `EdgeCaseTest` |
| RFC 8768 | Hop-Limit | todo | |
| RFC 8323 | CoAP over TCP/TLS/WebSockets: framing, CSM, Ping/Pong, Release/Abort, BERT | partial (framing codec) | codec tests |
| RFC 9177 | Q-Block | optional (Q-Block options get 4.02, so peers fall back to RFC 7959) | |

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
| RFC 7252 §9.1.1–9.1.2 | Matching by DTLS session and epoch; no retransmission across epochs | done in the endpoint (`Endpoint.Secure` equality, `SessionEnded` fails instead of retransmitting); DTLS itself todo |
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
| §3 | Security context derivation | partial (HKDF on given `info`) | C.1–C.3 |
| §4 | Option classes E/U, Proxy-Uri decomposition | partial (registry) | C.4–C.7 |
| §5–§8 | COSE object, AAD, nonce, protect/verify | todo | C.4–C.7 (AEAD part done) |
| §7.4 | Replay window | todo | interop + unit |
| §8.3–8.4, §9 | Observe and Block with OSCORE | todo | interop |
| App. B.1 | Sequence-number persistence, Echo-based replay-window recovery | todo | interop |
| App. B.2 | Context re-derivation (ID Context) | todo | libcoap |

## EDHOC (RFC 9528)

§8 compliance requirements:

| Requirement | Status |
|---|---|
| Cipher suites 2 **and** 3 (MUST) | registry done; protocol todo |
| `kid` credential identifiers (MUST) | todo |
| EDHOC_Exporter (MUST) | todo |
| EAD processing (MUST) | todo |
| Error codes 1 and 2 (MUST) | todo |
| message_4 (MAY, but MUST where no app message flows Responder→Initiator) | todo |

Full scope: methods 0–3, both roles, suites 0–6 and 24, credentials CCS/CWT/X.509 (by reference and by
value), connection identifier encoding, deduplication (§7), error handling (§6), key update
(Appendix H). Verified by RFC 9529 traces and the interop matrix.

## EDHOC with CoAP and OSCORE

| Spec | What | Status |
|---|---|---|
| RFC 9528 App. A.1 | EDHOC connection identifiers ↔ OSCORE Sender/Recipient IDs | todo |
| RFC 9528 App. A.2 | EDHOC over CoAP (`/.well-known/edhoc`, forward and reverse flows) | todo |
| RFC 9668 | EDHOC option, combined EDHOC+OSCORE request, application-profile parameters | registry done |

## Drafts (out of scope until published)

Group OSCORE (draft-ietf-core-oscore-groupcomm), KUDOS key update (draft-ietf-core-oscore-key-update),
EDHOC-PSK (draft-ietf-lake-edhoc-psk; libedhoc already implements it as method 4), OSCORE-capable
proxies (draft-ietf-core-oscore-capable-proxies), TLS/DTLS 1.3 IoT profile
(draft-ietf-uta-tls13-iot-profile).
