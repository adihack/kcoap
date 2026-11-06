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
| RFC 9053 §7.1.1 | EC2 COSE_Key with y as a sign bit | done | RFC 9529 §3.4 PK_R, the SEC 2 P-384 base point |
| SEC 1 v2 §2.3.4 | P-256 / P-384 point decompression (`Sec1`), in common code before any backend | done | SEC 2 base points and their multiples, RFC 9529 §3 points; Bouncy Castle differential; EDHOC on a backend that refuses compressed points (`UncompressedPointsTest`) |
| RFC 5280 §4.1, RFC 5480, RFC 8410, RFC 7250 §3 | Certificate and SubjectPublicKeyInfo parsing (`x509`): subject public key and common name | partial (parsing only; path validation todo) | RFC 9529 §2 certificates; fuzzing |
| RFC 3279 §2.2.3, RFC 8422 §5.4 | ECDSA signatures between raw `r \|\| s` and DER (`EcdsaSignatureCodec`) | done | JDK differential (DER and IEEE P1363), fuzzing |

## CoAP

| Spec | What | Status | Verified by |
|---|---|---|---|
| RFC 7252 §3–4 | Message format, CON/NON/ACK/RST, retransmission, deduplication, Message IDs, NSTART, PROBING_RATE, ping | done | `UdpMessagingTest` (sans-IO, per section), end-to-end on a lossy simulated network, libcoap |
| RFC 7252 §5 | Tokens, piggybacked / separate / NON responses, matching, critical options (4.02 for requests; a Reset for a separate response, sent after verification), Accept (4.06), Proxy-Uri (5.05), conditional requests (helper), methods | done; deviation from §5.4.1: a request the resource's access policy refuses gets 4.01 or 4.03 even when it carries an unrecognized critical option, and a refused Non-confirmable one gets that response instead of a Reset, since the policy is asked before the option check ([ADR 0008](adr/0008-fail-closed-message-security.md)) | `RequestResponseTest`, `ResponseAcknowledgementTest`, `AccessPolicyTest`, `EndpointMatchingTest` (§5.3.2: a response matches by the address and port the request went to, hosts compared as §6.6 compares them), libcoap |
| RFC 7252 §5.6–5.7, §8 | Caching, proxying, multicast | todo: kcoap has no proxy, no cache and no multicast. As client and origin server it works through a forward-proxy (§5.7.2), the target named by Proxy-Scheme and Uri-Host (§5.10.2); the client has no proxy setting, and the tests' `ProxyRoute` leaves out the Uri-Port that §6.4 step 7 calls for, which libcoap 4.3.5b would repeat | through a proxy: libcoap 4.3.5b's `coap-server -P`, `coap+ws` in and `coap` out, kcoap at both ends (`BrowserTopologyInteropTest`, [ADR 0009](adr/0009-browser-topology-interop.md)) |
| RFC 6690 | Link format, `/.well-known/core`, query filtering | done | RFC 6690 §5 examples, libcoap discovery both ways |
| RFC 7641 | Observe: server (sequence numbers, NSTART, superseding, 24 h CON, removal rules) and client (aggregation, §3.4 ordering, deregistration) | done; client re-registration on Max-Age (MAY) todo | `ObserveTest`, libcoap `/time` and `coap-client -s` |
| RFC 7252 §11.3, RFC 7641 §7 | Bounds on what unauthenticated peers can make an endpoint keep: deduplication entries, concurrent handlers (piggybacked 5.03), exchanges per peer, observers (global, per peer, per resource; served without Observe), pending and established connections; each reported as `EndpointEvent.LimitReached`. Confirmable notifications interspersed for unauthenticated observers. Amplification mitigation by address verification with Echo (RFC 9175 §2.4 item 3, §2.6): Q-Block2 bursts by default, every response larger than 3× its request with `AmplificationPolicy.ALL_RESPONSES` | done; amplification mitigation opt-in (`ALL_RESPONSES`): the default checks only Q-Block2 bursts, which the server sends from QB-3 on; the default for single responses is decided in SRV-2 | `ResourceBoundsTest`, `ObserveTest`, `ConcurrencyStressTest`, `AmplificationTest`, libcoap with `ALL_RESPONSES` |
| RFC 7959 | Block1 / Block2, negotiation, 2.31 / 4.08 / 4.13, Size1 / Size2, with Observe | done (inner; outer for proxies todo) | `BlockwiseTest`, libcoap `-b` both ways |
| RFC 8132 | FETCH, PATCH, iPATCH, observable FETCH | done (methods; patch formats are the application's) | `RequestResponseTest`, `ObserveTest` |
| RFC 7967 | No-Response | done (server) | `RequestResponseTest`, `EdgeCaseTest` |
| RFC 9175 | Echo, Request-Tag, token processing (needed by OSCORE Appendix B.1.2) | partial: client Echo retry (under message security only for a verified 4.01, ADR 0008), Request-Tag for concurrent Block1 and server keying, DTLS token sequences, server address verification against amplification (§2.4 item 3, §2.6); server freshness checks (§2.3) todo | `MessageSecurityTest`, `BlockwiseTest`, `EdgeCaseTest`, `AmplificationTest`, `AddressVerificationTest`, `HeldBackResponsesTest`, libcoap |
| RFC 8768 | Hop-Limit | todo | |
| RFC 8323 §3.2, §4.2 | Framing: Len/TKL with extended length over TCP, one binary frame with Len 0 over WebSockets, fragmented frames reassembled | done | §3.2 Figure 5 and §5.7 Figures 11/12 byte for byte, `ReliableMessageCodecTest`, libcoap |
| RFC 8323 §3.3, §4.3, §5.3 | CSM first from both sides, without waiting for the peer's; peer's CSM required first (Empty excepted, §3.4), within a timeout, else Abort; Max-Message-Size (base 1152) and Block-Wise-Transfer, cumulative; clients wait for the CSM before the first request | done | `ReliableMessagingTest`, `ReliableTransportTest`, libcoap both ways |
| RFC 8323 §5.2 | Signaling option numbers per code, own registry (`SignalingOptions`); unknown critical options abort, elective ones ignored | done | `SignalingOptionsTest`, `SignalTest`, `ReliableMessagingTest` |
| RFC 8323 §5.3.1 | Max-Message-Size enforced on receipt (Abort, before buffering) and on sending (`MESSAGE_TOO_LARGE`); block sizes from the peer's value | done | unit and end-to-end tests, libcoap `-X` |
| RFC 8323 §5.4 | Ping/Pong with the Ping's token, Custody: the Pong waits until requests before the Ping are answered; `CoapEndpoint.ping(peer, custody)`; keep-alive pings | done | unit tests, libcoap (its Pongs carry no token; matched to the oldest Ping) |
| RFC 8323 §5.5 | Release: sent on idle client connections and at shutdown; received, no new requests, drain of requests before it and of own requests, then close; Hold-Off honoured; Alternative-Address reported, not followed (§10.1) | done | `ReliableMessagingTest`, `ReliableTransportTest` |
| RFC 8323 §5.6 | Abort with diagnostic and Bad-CSM-Option: missing or invalid CSM, format errors, oversize messages, unknown critical signaling options; received, the connection closes | done | unit and end-to-end tests |
| RFC 8323 §3.3, §3.4, §7.4 | Requests both ways on a connection, 5.01 without a server; per-connection tokens; connection loss fails exchanges and removes observers | done | `ReliableTransportTest` |
| RFC 8323 §6 | BERT: SZX 7 in both directions, Block1 and Block2, numbering in 1024-byte units, only when both CSMs indicate it; plain SZX in answer switches to plain blocks | done | §6 Figures 13/14 shapes in `ReliableTransportTest`, libcoap both ways over TCP |
| RFC 8323 §7 | Observe: no message types, Observe values ignored on receipt (§7.1), registration response before any notification, explicit deregistration (§7.4) | done | `ReliableTransportTest`, libcoap `/time` and `coap-client -s` |
| RFC 8323 §4.1, §8 | WebSocket subprotocol `coap` at `/.well-known/coap`, required both ways; `coap+tcp`, `coaps+tcp`, `coap+ws`, `coaps+ws` URIs and default ports; a connection per peer as §8 names it: TLS and plain peers apart on one host and port, WebSocket URIs compared in RFC 7252 §6.6 normal form | done (`coaps+tcp` and `coaps+ws` over TLS: see §9 below) | `KtorWebSocketTransportTest`, `EndpointMatchingTest`, libcoap, `KcoapClientAgainstLibcoapTlsTest` |
| RFC 9177 | Q-Block | done, off by default on both sides ([ADR 0006](adr/0006-q-block.md)); not applicable: §4.8 (multicast, which kcoap does not have, coap-core-8) and §8–9 (proxies, coap-core-7). Server (`CoapServerConfig.qBlock`): Q-Block1 uploads with a 2.31 per set and 4.08 lists (Content-Format 272, §5) at once and on the NON_RECEIVE_TIMEOUT ladder, Q-Block2 bodies for requests with Q-Block1 or Q-Block2 (sets, Continues, missing blocks from a cache, one ETag per body, Size2), bounded per peer and in all, the address checked with Echo before bursts (RFC 9175 §2.4) and the §11 warning at start. Client (`CoapClientConfig.qBlock`): uploads, downloads and an upload answered with a download over NON (§7.2), CON (§7.1, Appendix A) and reliable transports (Appendix B), the `/.well-known/core` probe (§4.1) with the answer kept by the endpoint, one NSTART slot per Non-confirmable body (§7.2). Observe (§4.5): notification bodies with the Observe value on every block, one outstanding per observation, each emitted once. Under OSCORE opt-in on both sides (`withMessageSecurity`): the responses of a body, notification bodies included, each with a Partial IV new in a replay window of its request (RFC 8613 §7.4, §7.4.1), freshness per body (§4.1.3.5.2), and only the unprotected errors that end a body (§11). While Q-Block is off a Confirmable request with Q-Block gets 4.02 and a Non-confirmable one a Reset (§4.1) | Every figure of §10 and Appendices A–B as a script on virtual time (`QBlockFiguresTest`) and end to end (`QBlockObserveTest`, Figures 7–15); the parts (`QBlockPartsTest`, `QBlock1SenderTest`, `QBlock1ReceiverTest`, `QBlock2SenderTest`, `QBlock2ReceiverTest`, `ExchangeModesTest`, `UdpMessagingTest`); the server (`QBlockServerTest`, `QBlockReliableTest`), the client (`QBlockClientTest`, `QBlockSupportTest`, `QBlockClientReliableTest`, `QBlockClientSoakTest`) and both together (`QBlockEndToEndTest`, `QBlockTest`: 20 seeds at 10 % and 30 % loss), on threads (`QBlockStressTest`); `QBlockFallbackTest`; under OSCORE `OscoreQBlockTest` and `OscoreQBlockEndToEndTest` (bodies and notification bodies at 10 % loss and reordering, replayed and stale bodies); against libcoap 4.3.5b both ways, K1–K12 and L1–L12 with the L9 and K soaks (20 runs with `-Pkcoap.soak=true`, not yet scheduled in CI; [testing.md](testing.md#nightly-soak)), its deviations pinned as `upstreamLibcoap…` tests ([interop/README.md](../interop/README.md)) |

Hop-Limit and OSCORE: RFC 8768 gives it no OSCORE class, so under the published RFCs it is Class E
(RFC 8613 §4.1). draft-ietf-core-oscore-capable-proxies updates RFC 8768 to Class U, and libcoap already
behaves that way. kcoap follows the published RFCs for now; the difference only shows through proxies, and
did not through libcoap's forward-proxy (WP-I1): kcoap's client sends no Hop-Limit and the proxy inserts none.

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
| RFC 8323 §9 | TLS for `coaps+tcp` / `coaps+ws`, ALPN `coap`; same three modes via RFC 7925 | partial (WP-T1): the client sends a `coaps+tcp` or `coaps+ws` request only where a connector or listener runs TLS for the scheme, and only to a peer reached over TLS (`SecureSchemeTest`). `coaps+tcp` client on the JVM, `KtorTlsConnector` on ktor-network-tls: Certificate mode only, the server's chain validated against the platform store or given anchors, its name checked by RFC 9525 §6, a pinning hook; TLS 1.2 with ECDHE and AES-GCM only; no ALPN, so ports other than 5684 only by opting in (deviation from §8.2's MUST, which ktor-network-tls cannot meet); none on Linux native or Apple, where ktor-network-tls has no TLS. `coaps+ws` client through the ktor engine's TLS (`wss`). Both cross-tested with libcoap 4.3.5b on OpenSSL (`KcoapClientAgainstLibcoapTlsTest`), `KtorTlsConnectorTest` against JSSE. No PreSharedKey or RawPublicKey mode, no client certificate, no TLS server: servers terminate TLS at a proxy ([ADR 0010](adr/0010-server-side-tls.md), [platforms.md](platforms.md#tls-support-per-runtime)) |
| RFC 9147 | DTLS 1.3 | later |

## OSCORE (RFC 8613)

| Section | What | Status | Verified by |
|---|---|---|---|
| §3 | Security context derivation | done | C.1–C.3 (keys, IVs, `info`, nonces) |
| §4 | Option classes E/U, Proxy-Uri decomposition | done | C.4–C.6, unit tests |
| §5–§8 | COSE object, AAD, nonce, protect/verify, error responses | done | C.4–C.8 byte for byte, both sides |
| §7.4 | Replay window; one response per request, except notifications and the responses of a Q-Block body (RFC 9177, opt-in), each with a Partial IV new in a window of its request; §7.4.1 for notifications: strictly above the Notification Number, or for a registration with Inner Q-Block new in its window (every verified Partial IV recorded, a stale one too) and of the freshest body | done | unit + end-to-end tests (`OscoreQBlockTest`, `OscoreQBlockEndToEndTest`) |
| §4.1.3.5, §8.3.1, §8.4.2 | Observe with OSCORE: a new Partial IV in every notification after the first, the one without a Partial IV the oldest, ordering by Partial IV, also of Q-Block2 notification bodies taken in any order (RFC 9177 §4.5) | done | end-to-end tests, `OscoreQBlockTest`, `OscoreQBlockEndToEndTest` |
| §8.2.1, §8.4.1 | Inner Block-wise (by the stack, above OSCORE) | with the stack | stack tests |
| §8.2, §8.4 | Unprotected error responses on the client: handed to the stack as unverified (`ResponseVerification.Unverified`), never acted on (no Echo retry, no block-wise or Q-Block step, no end of an observation), a request without a verified answer fails with `UNVERIFIED_RESPONSE`; other unprotected responses dropped ([ADR 0008](adr/0008-fail-closed-message-security.md)) | done | `UnverifiedResponseTest` (CON, NON, Block1, Q-Block, Observe), `OscoreQBlockTest`, `OscoreQBlockEndToEndTest` (a forged 4.01 with Echo leaves a protected POST run once, CON and NON) |
| §8.2 (server) | Requests without OSCORE on a server with OSCORE: refused by the default access policy before any processing, 4.01 (ADR 0008) | done | `AccessPolicyTest`, `MessageSecurityTest`, `FullStackTest` |
| §9 | Web linking: `osc` on the resources reachable with OSCORE only | done: `/.well-known/core` adds it to `MessageSecurityRequired` resources (`LinkFormat.OSCORE`) | `AccessPolicyTest`, `FullStackTest` |
| App. B.1.1 | Sequence-number persistence | done | unit tests |
| App. B.1.2 | Echo-based replay-window recovery (server side; client repeats via the stack) | done | unit tests, libcoap `coap-client` answering kcoap's challenge, kcoap answering libcoap's |
| App. B.2 | Context re-derivation (ID Context) | deferred: an optional example protocol; needs the stack to resend on the security layer's request | libcoap (`rfc8613_b_2`) when done |
| §10 | CoAP-to-CoAP forwarding proxy: kcoap's endpoints through an OSCORE-unaware forward-proxy, the target in Class U options outside (Proxy-Scheme, Uri-Host, Uri-Port), the path inside, Outer Observe (§4.1.3.5) | done as endpoints (kcoap has no proxy); Outer Block options for a fragmenting proxy todo (checklist oscore-2) | libcoap 4.3.5b's `coap-server -P` from `coap+ws` to `coap`: B.1.2 challenge, inner Block1 and Block2, an observation whose registration and notifications carry Observe outside and verify, authorization on the `OscoreIdentity` (`BrowserTopologyInteropTest`, [ADR 0009](adr/0009-browser-topology-interop.md)); pinned: libcoap drops a request with the outer Proxy-Uri of §4.1.3.3, and forwards a deregistration on a new token, so the device keeps notifying |
| §11 | OSCORE over HTTP | todo | |
| — | Interop | live, both directions | libcoap v4.3.5b over UDP, raw and through the CoAP stack: B.1.2 challenges both ways, Block1/Block2 and Observe inside OSCORE; kcoap to kcoap through libcoap's forward-proxy; `oscore_testcases.sh` todo |

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
| RFC 9528 App. A.2.1, RFC 9668 §3 through a proxy | The forward flow, an EDHOC error and the combined request (its EDHOC option Safe-to-Forward) from a WebSocket client through a forward-proxy to a UDP server | done, kcoap at both ends through libcoap 4.3.5b's `coap-server -P` (`BrowserTopologyInteropTest`); post-quantum suites wait for libcoap, whose proxy corrupts request bodies of several blocks ([ADR 0009](adr/0009-browser-topology-interop.md)) |
| RFC 9668 §4 | Connection identifier selection for OSCORE | done (server side) |
| RFC 9668 §7 | Access control on the rebuilt request knows the authenticated EDHOC peer | partial: `EdhocIdentity` (peer credential and identifier) is the `securityIdentity` of every request a context from EDHOC verifies; EAD not exposed to handlers (checklist edhoc-oscore-x1) |
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
