# RFC compliance checklist

"100% RFC" here means every MUST and SHOULD of the published RFCs below, verified by the RFC's own test
vectors where they exist and by interop runs against independent implementations. Status values:
`todo`, `partial`, `done`. Drafts are listed separately and are out of scope until they become RFCs.
[checklist.md](checklist.md) tracks every project requirement, these included, with evidence and next steps.

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

| Spec | What | Status | Verified by |
|---|---|---|---|
| RFC 7252 §3–4 | Message format, CON/NON/ACK/RST, retransmission, deduplication, Message IDs, NSTART, PROBING_RATE, ping | done | `UdpMessagingTest` (sans-IO, per section), end-to-end on a lossy simulated network, libcoap |
| RFC 7252 §5 | Tokens, piggybacked / separate / NON responses, matching, critical options (4.02 for requests; a Reset for a separate response, sent after verification), Accept (4.06), Proxy-Uri (5.05), conditional requests (helper), methods | done | `RequestResponseTest`, `ResponseAcknowledgementTest`, libcoap |
| RFC 7252 §5.6–5.7, §8 | Caching, proxying, multicast | todo | |
| RFC 6690 | Link format, `/.well-known/core`, query filtering | done | RFC 6690 §5 examples, libcoap discovery both ways |
| RFC 7641 | Observe: server (sequence numbers, NSTART, superseding, 24 h CON, removal rules) and client (aggregation, §3.4 ordering, deregistration) | done; client re-registration on Max-Age (MAY) todo | `ObserveTest`, libcoap `/time` and `coap-client -s` |
| RFC 7252 §11.3, RFC 7641 §7 | Bounds on what unauthenticated peers can make an endpoint keep: deduplication entries, concurrent handlers (piggybacked 5.03), exchanges per peer, observers (global, per peer, per resource; served without Observe), pending and established connections; each reported as `EndpointEvent.LimitReached`. Confirmable notifications interspersed for unauthenticated observers. Amplification mitigation by address verification with Echo (RFC 9175 §2.4 item 3, §2.6): Q-Block2 bursts by default, every response larger than 3× its request with `AmplificationPolicy.ALL_RESPONSES` | done; amplification mitigation opt-in (`ALL_RESPONSES`): the default checks only Q-Block2 bursts, which the server sends from QB-3 on; the default for single responses is decided in SRV-2 | `ResourceBoundsTest`, `ObserveTest`, `ConcurrencyStressTest`, `AmplificationTest`, libcoap with `ALL_RESPONSES` |
| RFC 7959 | Block1 / Block2, negotiation, 2.31 / 4.08 / 4.13, Size1 / Size2, with Observe | done (inner; outer for proxies todo) | `BlockwiseTest`, libcoap `-b` both ways |
| RFC 8132 | FETCH, PATCH, iPATCH, observable FETCH | done (methods; patch formats are the application's) | `RequestResponseTest`, `ObserveTest` |
| RFC 7967 | No-Response | done (server) | `RequestResponseTest`, `EdgeCaseTest` |
| RFC 9175 | Echo, Request-Tag, token processing (needed by OSCORE Appendix B.1.2) | partial: client Echo retry, Request-Tag for concurrent Block1 and server keying, DTLS token sequences, server address verification against amplification (§2.4 item 3, §2.6); server freshness checks (§2.3) todo | `MessageSecurityTest`, `BlockwiseTest`, `EdgeCaseTest`, `AmplificationTest`, `AddressVerificationTest`, `HeldBackResponsesTest`, libcoap |
| RFC 8768 | Hop-Limit | todo | |
| RFC 8323 §3.2, §4.2 | Framing: Len/TKL with extended length over TCP, one binary frame with Len 0 over WebSockets, fragmented frames reassembled | done | §3.2 Figure 5 and §5.7 Figures 11/12 byte for byte, `ReliableMessageCodecTest`, libcoap |
| RFC 8323 §3.3, §4.3, §5.3 | CSM first from both sides, without waiting for the peer's; peer's CSM required first (Empty excepted, §3.4), within a timeout, else Abort; Max-Message-Size (base 1152) and Block-Wise-Transfer, cumulative; clients wait for the CSM before the first request | done | `ReliableMessagingTest`, `ReliableTransportTest`, libcoap both ways |
| RFC 8323 §5.2 | Signaling option numbers per code, own registry (`SignalingOptions`); unknown critical options abort, elective ones ignored | done | `SignalTest`, `ReliableMessagingTest` |
| RFC 8323 §5.3.1 | Max-Message-Size enforced on receipt (Abort, before buffering) and on sending (`MESSAGE_TOO_LARGE`); block sizes from the peer's value | done | unit and end-to-end tests, libcoap `-X` |
| RFC 8323 §5.4 | Ping/Pong with the Ping's token, Custody: the Pong waits until requests before the Ping are answered; `CoapEndpoint.ping(peer, custody)`; keep-alive pings | done | unit tests, libcoap (its Pongs carry no token; matched to the oldest Ping) |
| RFC 8323 §5.5 | Release: sent on idle client connections and at shutdown; received, no new requests, drain of requests before it and of own requests, then close; Hold-Off honoured; Alternative-Address reported, not followed (§10.1) | done | `ReliableMessagingTest`, `ReliableTransportTest` |
| RFC 8323 §5.6 | Abort with diagnostic and Bad-CSM-Option: missing or invalid CSM, format errors, oversize messages, unknown critical signaling options; received, the connection closes | done | unit and end-to-end tests |
| RFC 8323 §3.3, §3.4, §7.4 | Requests both ways on a connection, 5.01 without a server; per-connection tokens; connection loss fails exchanges and removes observers | done | `ReliableTransportTest` |
| RFC 8323 §6 | BERT: SZX 7 in both directions, Block1 and Block2, numbering in 1024-byte units, only when both CSMs indicate it; plain SZX in answer switches to plain blocks | done | §6 Figures 13/14 shapes in `ReliableTransportTest`, libcoap both ways over TCP |
| RFC 8323 §7 | Observe: no message types, Observe values ignored on receipt (§7.1), registration response before any notification, explicit deregistration (§7.4) | done | `ReliableTransportTest`, libcoap `/time` and `coap-client -s` |
| RFC 8323 §4.1, §8 | WebSocket subprotocol `coap` at `/.well-known/coap`, required both ways; `coap+tcp`, `coaps+tcp`, `coap+ws`, `coaps+ws` URIs and default ports | done (`coaps+ws` on the ktor engine's TLS, not cross-tested) | `KtorWebSocketTransportTest`, libcoap |
| RFC 9177 | Q-Block | partial ([plan](plan-qblock-and-platforms.md), [ADR 0006](adr/0006-q-block.md)): the sans-IO core is done, not yet wired into client and server: Q-Block1 and Q-Block2 senders and receivers for NON (§7.2 sets, 2.31, Continue, missing-block lists and requests, the NON_RECEIVE_TIMEOUT ladder), CON (§7.1, Appendix A) and reliable transports (Appendix B); the Content-Format 272 codec (§5); the structural checks of §4.1–4.6; the §7.2 parameters (Tables 3 and 4). The endpoint seams are in place: several responses per token into a per-body inbox, and one NSTART slot per Non-confirmable body, released at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT) (§7.2). Until client and server use them, and later while it is off, a Confirmable request with Q-Block1 or Q-Block2 gets 4.02 and a Non-confirmable one a Reset (§4.1), so peers fall back to RFC 7959 | `QBlockFiguresTest` (Figures 2–20 as scripts on virtual time), `QBlockPartsTest`, `QBlock1SenderTest`, `QBlock1ReceiverTest`, `QBlock2SenderTest`, `QBlock2ReceiverTest`; `ExchangeModesTest` and `UdpMessagingTest` for the endpoint seams; `QBlockFallbackTest`, libcoap `coap-client -L 7` probe and fallback |

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
| RFC 8323 §9 | TLS for `coaps+tcp` / `coaps+ws`, ALPN `coap`; same three modes via RFC 7925 | partial: `coaps+ws` through the ktor client engine's TLS (`wss`); `coaps+tcp` has its seam (`Endpoint.Tcp(usesTls)`, `Connector`, `CoapScheme.ALPN`; TLS lives in the connector, ADR 0003) but no TLS connector yet |
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
| App. B.2 | Context re-derivation (ID Context) | deferred: an optional example protocol; needs the stack to resend on the security layer's request | libcoap (`rfc8613_b_2`) when done |
| §11 | OSCORE over HTTP | todo | |
| — | Interop | live, both directions | libcoap v4.3.5b over UDP, raw and through the CoAP stack: B.1.2 challenges both ways, Block1/Block2 and Observe inside OSCORE; `oscore_testcases.sh` todo |

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
| §6 | Error messages, cipher suite negotiation (§6.3) | done; every peer-authentication failure (untrusted or unfitting credential, rejected chain, Signature_or_MAC that does not verify) sends the same code 1 "Authentication failed", the reason stays local (`ErrorHandlingTest.everyPeerAuthenticationFailureSendsTheSameError`) |
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
| RFC 9528 App. A.1 | EDHOC connection identifiers ↔ OSCORE Sender/Recipient IDs, exporter-derived context | done |
| RFC 9528 App. A.2.1 | EDHOC over CoAP, forward flow (`/.well-known/edhoc`, prefixes, Content-Formats, A.2.3 errors) | done (stack-independent; CoAP resource wiring with the stack) |
| RFC 9528 App. A.2.2 | Reverse message flow | todo |
| RFC 9668 §3 | Combined EDHOC + OSCORE request, client and server, error handling | done; Figure 4 byte for byte |
| RFC 9668 §4 | Connection identifier selection for OSCORE | done (server side) |
| RFC 9668 §4.1.2 | C_R unique among pending sessions and Recipient IDs, never C_I: reserved in the locked step that checks it, before message_2, and held until the session's OSCORE context is in the repository, so concurrent handshakes of one server never share one; the context is added only if its Recipient ID is still free, so a second server on the same repository (RFC 9668 §5) never replaces one | done (`EdhocServerSessionsTest`: gated races, a stress test on real threads, two servers on one repository) |
| RFC 9528 §9.7 | Pending server sessions: bound to the peer that sent message_1 (a continuation from another peer is answered as for an unknown C_R and costs no cryptography), bounded in total and per peer (new ones refused with `EdhocServerEvent.LimitReached`, except that a full server evicts the oldest session waiting longer than a grace period, `SessionEvicted`), expired after MAX_TRANSMIT_WAIT and wiped. Echo before message_2 against spoofed floods | done (`EdhocServerSessionsTest`); Echo todo |

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
