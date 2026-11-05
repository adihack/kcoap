# Project checklist

This is the master checklist for kcoap's goals: the full published RFCs for CoAP, OSCORE and EDHOC;
Kotlin Multiplatform for Android, iPhone, JVM servers and browsers; tests near 100% line and branch
coverage; and cross-tests in both roles (client and server, Initiator and Responder) against at least
libcoap and libedhoc. [rfc-compliance.md](rfc-compliance.md) has the RFC detail section by section;
this file tracks every requirement, including platforms, tests, CI and documentation.

## How to read it

- One section per area, one row per requirement. Plain IDs (`coap-core-12`) are the audited
  requirements. IDs with an `x` (`coap-core-x3`) are requirements the auditors found missing from the
  list.
- Status: **Done** means met and verified. **Partial** means some of it is met; the last column says
  what is not. **To do** means not started. **Deferred** means postponed on purpose; the row says why.
- Evidence names the key code and tests. Gap / next step says what is missing and what to do first.
- [Biggest gaps](#biggest-gaps) at the end orders the work by the project's goals.

## How it was produced

Written on 2026-11-05 at commit `05aa66c` on `feat/protocol-core`. One agent audited each area against
the sources, tests and RFC texts; a second agent re-checked every status, and where they disagreed the
second agent's status stands. Some audits ran before the latest commits, so those rows were checked
again against the tree and the git history:

- `3e28315` merged the `js` and `wasmJs` targets with capability-based crypto.
- `1d95636` added [scripts/test](../scripts/test), a rewritten [ci.yml](../.github/workflows/ci.yml),
  [release.yml](../.github/workflows/release.yml) and [releasing.md](releasing.md).
- `a5f0123` fixed the Ed25519 vector tests for Apple's hedged CryptoKit signatures.
- `f0dc39d` merged CoAP over TCP and WebSockets (RFC 8323) with 17 libcoap cross-tests.
- `05aa66c` added documents only: [target-architecture.md](target-architecture.md), the ADRs,
  [CHANGELOG.md](../CHANGELOG.md) and the release freeze. The code is the same as in `f0dc39d`.

WP-A1 (OSCORE safe persistence) later updated the rows it changed: oscore-8, kmp-x3 and
architecture-x6. kmp-x3 and architecture-x6 moved from To do to Partial; the summary counts include
that, and the run figures below are still those of `f0dc39d`.

WP-C1 (build governance) later updated architecture-x1 (from To do to Done), kmp-2 and architecture-2;
the summary counts include that too.

WP-A2 (capability-aware EDHOC negotiation) later updated foundations-3, foundations-5, edhoc-5 and kmp-2;
no status changed.

No build or test ran for this document. The evidence is the source tree, the JUnit results under
`*/build/test-results`, the Kover report `build/reports/kover/report.xml` (`scripts/coverage-summary`),
and GitHub Actions run 41949553396 for `f0dc39d`, which passed every job:

| CI job | Test task | Tests | Failed | Skipped |
|---|---|---:|---:|---:|
| Unit | `jvmTest` | 543 | 0 | 0 |
| Unit | `linuxX64Test`, `jsNodeTest`, `wasmJsNodeTest` | 490 each | 0 | 0 |
| Apple | `macosArm64Test`, `iosSimulatorArm64Test` | 490 each | 0 | 0 |
| Interop | `jvmTest` against libcoap and libedhoc | 48 | 0 | 0 |

The 48 interop tests are 16 against libcoap over UDP, 17 against libcoap over TCP and WebSockets, 4
OSCORE tests against libcoap and 11 against libedhoc; each group runs kcoap in both roles. The API
reference job (`dokkaGenerate`) passed too. The local JUnit results have the same counts, and the local
Kover report (JVM unit tests) shows 98.4% line and 86.8% branch coverage.

The re-check changed two of the 76 audited rows: oscore-11 from Partial to Done, and kmp-7 from To do to
Partial. The rows are explained below.

| Audited rows | Done | Partial | To do | Deferred |
|---|---:|---:|---:|---:|
| Before the re-check | 18 | 43 | 12 | 3 |
| After the re-check | 19 | 43 | 11 | 3 |

## Summary

All rows, including the 48 `x` rows:

| Area | Rows | Done | Partial | To do | Deferred |
|---|---:|---:|---:|---:|---:|
| [CBOR, COSE, crypto](#cbor-cose-crypto) | 11 | 0 | 5 | 6 | 0 |
| [CoAP core and companions](#coap-core-and-companions) | 30 | 6 | 12 | 10 | 2 |
| [OSCORE](#oscore) | 16 | 6 | 4 | 5 | 1 |
| [EDHOC](#edhoc) | 12 | 5 | 3 | 3 | 1 |
| [EDHOC with CoAP and OSCORE](#edhoc-with-coap-and-oscore) | 12 | 0 | 6 | 6 | 0 |
| [Kotlin Multiplatform](#kotlin-multiplatform) | 16 | 1 | 8 | 5 | 2 |
| [Architecture, code, logging, docs](#architecture-code-logging-docs) | 12 | 1 | 9 | 2 | 0 |
| [Tests, cross-tests, CI/CD](#tests-cross-tests-cicd) | 15 | 3 | 7 | 5 | 0 |
| **Total** | **124** | **22** | **54** | **42** | **6** |

## CBOR, COSE, crypto

Modules: [kcoap-cbor](../kcoap-cbor), [kcoap-cose](../kcoap-cose), [kcoap-crypto](../kcoap-crypto),
[kcoap-crypto-default](../kcoap-crypto-default), [kcoap-crypto-bc](../kcoap-crypto-bc).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| foundations-1 | CBOR (RFC 8949): deterministic encoding, strict decoding, Appendix A; CBOR sequences (RFC 8742) | Partial | `CborWriter.kt` writes shortest heads and definite lengths; `CborReader.kt` rejects malformed input. `CborTest` (Appendix A subset, sequences) and `CborFuzzTest` pass on every target. | Decoding accepts non-shortest heads, duplicate and unsorted map keys and invalid tag content (§5.3.2); `sortedDeterministically()` does not recurse; no integers beyond `Long`; floats, bignums and indefinite lengths are unsupported. Next: a strict decode mode for COSE and OSCORE, the rest of Appendix A as accept or reject tests, and a recorded decision on floats. |
| foundations-2 | COSE (RFC 9052/9053) structures, COSE_Key (OKP, EC2, AKP), CWT Claims Set (RFC 8392) with cnf (RFC 8747), x5chain/x5t (RFC 9360) | Partial | `CoseStructures.kt` matches RFC 8613 C.4 (`CoseTest.encStructureMatchesOscoreAad`) and RFC 9529 trace 1; `CoseKey.kt` and `CwtClaimsSet.kt` are tested in `CoseTest`. | Not enforced: duplicate labels (RFC 9052 §9 MUST NOT), kty/crv consistency, coordinate lengths, alg and key_ops checks (RFC 9053 §7.1, §2); cnf only as COSE_Key, the single-key MUST unchecked. Next: these checks with negative tests, on the strict CBOR mode. |
| foundations-3 | Crypto SPI: async, opaque private keys, AEADs, HKDF, HMAC, SHA-2, SHAKE256, KMAC256, X25519, X448, Ed25519, Ed448, P-256/P-384, ML-KEM, ML-DSA | Partial | `CryptoBackend.kt` (all `suspend`), `Keys.kt` (opaque `PrivateKey`), five `supports` members whose defaults probe (`Capabilities.kt`, `ProbeTest`) and whose answers `DefaultCryptoBackend` memoizes per instance (`SupportsMemoTest`, WP-A2); `DefaultCryptoBackend` on cryptography-kotlin; `BouncyCastleCryptoBackend` adds ML-KEM and ML-DSA on the JVM. | Ed448 is missing (blocks EDHOC suite 25); X448 has no test; post-quantum only on the JVM (kmp-8); no SHA-512. Next: Ed448 with the RFC 8032 §7.4 vectors, X448 with RFC 7748 §6.2. |
| foundations-4 | Crypto vectors: RFC 3610, 5869, 7748, 8032, FIPS 202, SP 800-185, NIST ACVP ML-KEM/ML-DSA, Bouncy Castle differential | Partial | Complete: RFC 3610 (24 packets, `AesCcmTest`, every target), RFC 5869 A.1–A.3 (`HkdfTest`), ACVP subsets (`MlKemKnownAnswerTest`, `MlDsaKnownAnswerTest`). | One case each for RFC 7748 (§6.1), RFC 8032 (TEST 1) and FIPS 202; KMAC256 Samples #5 and #6 missing; the differential runs on the JVM only, with none for X25519, ECDH or ECDSA. Next: generate the missing vectors with `tools/regen-vectors.sh` into commonTest. |
| foundations-5 | Hardware key storage (Android Keystore, Secure Enclave, HSM) through `PrivateKey` handles | Partial | Only the SPI shape: `PrivateKey` is an abstract handle and protocol code never reads key material; `BouncyCastleCryptoBackend` shows the decorator a hardware backend would use. A destroyed `SoftwarePrivateKey` or `EdhocSession` throws instead of handing out zeroed key material (WP-SEC1). | No hardware backend and no opaque-handle test backend; symmetric keys are raw `ByteArray`; no `destroy` for non-software keys. Such a backend overrides the `supports` members with static answers instead of being probed (WP-A2, ADR 0001). Next: `destroy` in the SPI, an opaque-handle backend running EDHOC and OSCORE in tests, then one real backend. |
| foundations-x1 | Full COSE messages: COSE_Sign1, COSE_Encrypt0, COSE_Mac0, header buckets, `crit` (RFC 9052) | To do | Only the structures OSCORE and EDHOC need exist. | Needed for CWT and for full RFC 9052; test with the cose-wg Examples. |
| foundations-x2 | CWT (RFC 8392): tag 61, signed or MACed CWTs, §7.2 validation, claims iss, aud, exp, nbf, iat, cti | To do | Only the unsigned CCS (sub, cnf) exists; `kcwt` is just a header label (edhoc-3). | A `Cwt` type verified through `CryptoBackend`, tested with RFC 8392 Appendix A. |
| foundations-x3 | RFC 9360 x5bag and x5u, and x5t with a text-string algorithm | To do | Not parsed; a text-string x5t algorithm is rejected as malformed. | Parse them; x5u needs a pluggable fetcher. |
| foundations-x4 | X.509 path validation: a built-in validator or platform `CertificateValidator`s (JVM PKIX, Apple SecTrust, Android, OpenSSL) | To do | Only the interface `CertificateValidator.kt`; tests pass lambdas. EDHOC x5chain and DTLS certificates depend on it. Its KDoc, `TrustedCredentials` and architecture.md "Extension points" say what is trusted with and without a validator, and that a validator authenticates without authorizing (WP-SEC1); [roadmap](roadmap.md) item 13. | Planned as E2 (`X509PathValidator`) and E7 in [target-architecture.md](target-architecture.md); test with the RFC 9529 trace 1 chain. |
| foundations-x5 | COSE P-521 and the SHA-512 family (ES512, x5t -44, SHA-512/256) | To do | Absent; an x5t with -44 never matches. | Add with full COSE. |
| foundations-x6 | Known-answer tests beyond the list: AES-GCM (with A192GCM), ChaCha20-Poly1305 (RFC 8439), SHA-384, HMAC and HKDF-SHA-384, P-256/P-384 ECDH and ECDSA, Wycheproof edge cases | To do | These are checked only by round trips, the JVM differential and libedhoc. | Generate them into commonTest; the crypto conformance suite (WP-C2) is the place. |

## CoAP core and companions

Modules: [kcoap-coap](../kcoap-coap), [kcoap-transport](../kcoap-transport),
[kcoap-transport-ktor](../kcoap-transport-ktor), [kcoap-transport-ktor-websocket](../kcoap-transport-ktor-websocket),
[kcoap-transport-ktor-websocket-server](../kcoap-transport-ktor-websocket-server), [kcoap-dtls](../kcoap-dtls).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| coap-core-1 | RFC 7252 message format and codec; format errors reset or dropped | Done | `UdpMessageCodec.kt`, `OptionCodec.kt`; `UdpMessageCodecTest`, `CodecFuzzTest`, `UdpMessagingTest.rfc7252Section4_2FormatErrorsResetConfirmableMessagesOnly`. | Optional: a test that uint values with leading zeros decode (§3.2). |
| coap-core-2 | RFC 7252 messaging: CON/NON/ACK/RST, back-off, MAX_RETRANSMIT, deduplication, NSTART, PROBING_RATE, ping | Done | Sans-IO `UdpMessaging.kt`, `TransmissionParameters.kt`; `UdpMessagingTest` (51), e.g. retransmissions at 2, 6, 14 and 30 s and give-up at 93 s. PROBING_RATE also paces whole Q-Block bodies through a `BodyLease` (RFC 9177 §7.2), checked at the endpoint in `ExchangeModesTest` (a 100-byte body frees its slot after 100 s, a large one after NON_PROBING_WAIT). | PROBING_RATE applies only as the NSTART slot hold for NON; add an endpoint-level test of it for single requests. |
| coap-core-3 | RFC 7252 request/response: piggybacked and separate responses, token matching, codes, methods | Done | `ClientExchange.kt`, `CoapEndpoint.kt`, `TokenGenerator.kt`, `CoapResource.kt`; `RequestResponseTest`; libcoap over UDP in both roles. | Add a test that an unknown response code counts as x.00 of its class (§5.9). |
| coap-core-4 | RFC 7252 options: registry, critical/elective/unsafe/no-cache-key, 4.02, repeats, lengths, defaults | Partial | `CoapOptions.kt`, `OptionCheck.kt`; `RequestResponseTest.rfc7252Section5_4_1UnrecognizedCriticalOptionsGet402`, `CoapOptionsTest`. The client checks a response's critical options after verification and before it answers it, and no timer answers it meanwhile: a separate response with an unknown one, Confirmable or Non-confirmable (§4.3 MAY), notifications included, gets a Reset, never an ACK, which ends an observation on the server (RFC 7641 §3.6), and the request fails with `BAD_RESPONSE` (`ResponseAcknowledgementTest`, 9; `UdpMessagingTest.rfc7252Section5_4_1NoTimerAnswersADeliveredResponse`). Over TCP and WebSockets, which have no Reset, the client deregisters the observation instead (RFC 8323 §7.4; `ReliableTransportTest.rfc8323Section7_4ARejectedNotificationIsFollowedByADeregistration`). | An elective option with a bad length is not ignored (an 8-byte Observe gives 5.00, §5.4.3); responses are not checked for lengths and repeats. |
| coap-core-5 | RFC 7252 URIs: parsing, decomposition (§6.4), composition (§6.5), percent-encoding | Partial | `CoapUri.kt` (all six schemes, `toOptions`, `fromOptions`); `CoapModelTest.uriDecomposition`, `CoapSchemeTest`. | No dot-segment removal, so `..` reaches Uri-Path and Location-Path (§5.10.1 MUST NOT); userinfo accepted; no DNS resolver; no IPv6 zone IDs. Next: RFC 3986 remove_dot_segments, reject `.` and `..` in the builders, a DNS `EndpointResolver`. |
| coap-core-6 | RFC 7252 §5.6 client caching: Max-Age freshness, ETag validation | To do | No response cache; only `OptionSet.maxAge` and `isNoCacheKey` exist. | An optional `CoapClient` cache keyed per §5.6 (FETCH body included), with 2.03 revalidation and invalidation on unsafe methods. |
| coap-core-7 | RFC 7252 §5.7 and §10 proxying: forward, reverse, Proxy-Uri/Proxy-Scheme, cross-proxy | To do | `CoapServer` answers Proxy-Uri with 5.05 (`RequestResponseTest.rfc7252Section5_10_2ProxyRequestsGet505`); the client has no proxy setting. | A proxy handler with unsafe-option filtering, Hop-Limit, the cache and outer Block-wise; a client proxy setting. |
| coap-core-8 | RFC 7252 §8 multicast: group requests, response suppression, leisure | To do | Not implemented; responses match by peer and token only. | Group addresses in `DatagramTransport` and the testkit, `CoapClient.multicast` as a Flow, server leisure and suppression; then JVM NIO, Android MulticastLock, the iOS multicast entitlement. |
| coap-core-9 | RFC 7252 §9 security modes: NoSec, PreSharedKey, RawPublicKey (mandatory), Certificate | Partial | NoSec works end to end; `SecurityMode.kt`, `PeerIdentity`, session and epoch matching (`RequestResponseTest.rfc7252Section9_1_1AnEndedSessionFailsItsExchanges`). | PreSharedKey, RawPublicKey and Certificate need DTLS (coap-core-21). |
| coap-core-10 | RFC 6690 link format: `/.well-known/core`, attributes, query filtering | Done | `LinkFormat.kt`, `WellKnownCore.kt`; `DiscoveryTest` (RFC 6690 §5 examples); libcoap discovery in both roles. | Multicast discovery waits for coap-core-8. |
| coap-core-11 | RFC 7641 Observe, client and server | Partial | `ObservationRegistry.kt`, `ClientObservation.kt`; `ObserveTest` (17); libcoap Observe in both roles over UDP, TCP and WebSockets. | The server does not keep a notification's Content-Format or answer 4.06 (§4.2 MUST); unacknowledged NON notifications are limited by time only (§7); no client Max-Age freshness or re-registration (MAY). |
| coap-core-12 | RFC 7959 Block-wise: Block1/Block2, Size1/Size2, early negotiation, atomic uploads, 2.31/4.08/4.13, concurrent transfers | Partial | `BlockwiseClient.kt`, `BlockwiseServer.kt`, `Blockwise.kt`; `BlockwiseTest` (23); libcoap Block1 and Block2 in both roles; BERT over TCP since `f0dc39d`. | If-None-Match is repeated on Block1 blocks after the first (§2.10 MUST NOT); inner Block-wise only. Next: drop If-None-Match after block 0, and from the server's upload key, with a test. |
| coap-core-13 | RFC 9177 Q-Block1 and Q-Block2: NON bursts, missing-block recovery | Partial | The sans-IO core (QB-1, [ADR 0006](adr/0006-q-block.md)): `QBlock1Sender`, `QBlock1Receiver`, `QBlock2Sender`, `QBlock2Receiver`, `MissingBlocks` (Content-Format 272), `QBlockCheck`, the §7.2 parameters in `TransmissionParameters`; every RFC 9177 figure 2–20 as a script on virtual time over `UdpMessaging`, with the client forgetting released tokens (`QBlockFiguresTest`, 20), unit tests per machine (71), new files at 97–100% line coverage. The fallback is pinned: a Confirmable request with either option gets 4.02 with a diagnostic, a Non-confirmable one a Reset, and the client rejects a response carrying either (`QBlockFallbackTest`, 8); libcoap's `coap-client -L 7` probes, gets 4.02 and falls back to RFC 7959 within milliseconds (`LibcoapClientAgainstKcoapTest.fallsBackFromQBlockWhileItIsOff`). The endpoint seams (QB-2): exchanges in inbox mode take several responses on one token and report them, Resets, timeouts and failures to a bounded per-body inbox; a `BodyLease` holds one NSTART slot per Non-confirmable body and releases itself at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT) (`ExchangeModesTest`, 20, inbox mode with a piggybacked first response and on a connection too; `UdpMessagingTest`). The server (QB-3), off by default (`CoapServerConfig.qBlock`): `QBlockServer.kt` drives the cores for Q-Block1 uploads (2.31 per set, Non-confirmable 4.08 lists at once and on the ladder, the final response remembered for copies of a block, a Reset to a 4.08 drops the body) and Q-Block2 bodies (sets, Continues on the original token, missing blocks on their request, an ETag per body, Size2, Confirmable lock-step, back to back on connections, the Figure 12 match without Request-Tag and its refusal for two bodies), bounded per peer and in all (bodies still sending finish their sets, uploads hold only the blocks that arrived), nothing left NON_PARTIAL_TIMEOUT after the last use, the address check before bursts counting every set and retransmission sent in answer to a request, unsafe requests with a payload always run their handler; `QBlockServerTest` (58, a client scripted message by message), `QBlockReliableTest` (4, Custody not held up), `LibcoapClientAgainstKcoapQBlockTest` (13: libcoap's `coap-client -L 7` cases L1 to L5, L7, L8, L10 and L12, bodies compared byte for byte). | The client (QB-4), OSCORE with Q-Block (QB-5) and Observe with Q-Block (QB-6; until then a registration with Q-Block2 is served without an observer) in [plan-qblock-and-platforms.md](plan-qblock-and-platforms.md); first in [Biggest gaps](#biggest-gaps). |
| coap-core-14 | RFC 8132 FETCH, PATCH, iPATCH | Partial | Methods, codes 4.09/4.15/4.22, client and resource API; `RequestResponseTest.theServerAnswersEveryMethodThroughTheResourceTemplate`, `ObserveTest.observingAFetchIsAllowed`. | The FETCH body is not in the observation or Block2 cache key, so two FETCH queries share one registration; FETCH without Content-Format is not refused (§2.3.1 MUST). |
| coap-core-15 | RFC 7967 No-Response | Partial | `CoapServer.isSuppressed` (bits 2, 8, 16), CON still acknowledged; `RequestResponseTest.rfc7967NoResponseSuppressesTheChosenClasses`. | The client waits until the request timeout (§2.1 SHOULD stop). Next: complete on the ACK or on send when every class is suppressed. |
| coap-core-16 | RFC 9175 Echo, Request-Tag, token processing | Partial | Client repeats once after 4.01 with Echo (`MessageSecurityTest.rfc9175Section2_3APlain401WithEchoIsRepeatedOnce`); Request-Tag for concurrent Block1 (`BlockwiseTest.rfc9175Section3_5_2ConcurrentUploadsToOneResourceGetDistinctRequestTags`). Server address verification against amplification (§2.4 item 3, §2.6; SRV-1): `CoapServerConfig.amplification`, `AddressVerification.kt`, `HeldBackResponses.kt`; `AmplificationTest` (also: concurrent POSTs of one client run once each, separate responses to an unverified address are Non-confirmable), `AddressVerificationTest`, `HeldBackResponsesTest`, `LibcoapClientAgainstKcoapAddressVerificationTest` (libcoap repeats with Echo; its Q-Block probe is never challenged). | No server Echo for freshness (§2.3) outside OSCORE. The default policy checks only Q-Block2 bursts (QB-3 sends them when Q-Block is on; `LibcoapClientAgainstKcoapQBlockTest.l12TheFirstBurstWaitsForEcho`); checking every large response by default is decided in SRV-2. |
| coap-core-17 | RFC 8768 Hop-Limit | Partial | Option 16, `OptionSet.hopLimit` and code 5.08 exist (`CoapModelTest.optionsAreSortedAndTyped`); an origin server rightly ignores the option. | The processing belongs to proxies (insert, decrement, 5.08), and there is none (coap-core-7). |
| coap-core-18 | RFC 8516 4.29 Too Many Requests | Partial | `CoapCode.TOO_MANY_REQUESTS`; a handler can return it with Max-Age. | No test uses 4.29; no server rate limiter; no client retry-after helper. |
| coap-core-19 | RFC 8974 extended tokens | To do | Tokens stop at 8 bytes and TKL 9–15 is a format error (`EdgeCaseTest.aTokenGeneratorMustStayWithinEightBytes`); no doc mentions RFC 8974. | Decide the scope (it serves stateless proxies) and add a row to rfc-compliance.md; if in scope, TKL 13/14 in both codecs and the CSM Extended-Token-Length option. |
| coap-core-20 | RFC 8323 CoAP over TCP, TLS and WebSockets: codecs, CSM, Ping/Pong, Release, Abort, BERT, transports | Partial | Merged in `f0dc39d`: `ReliableMessaging.kt`, BERT in `Blockwise.kt`, `KtorTcpTransport`, `KtorWebSocketConnector`, `KtorWebSocketListener`. `ReliableMessagingTest` (31), `ReliableTransportTest` (29), `SignalTest`, and 17 libcoap cross-tests in both roles, green in CI. | TLS for `coaps+tcp` is missing (`KtorTcpTransport` refuses `usesTls`); `coaps+ws` relies on the ktor engine's TLS and is not cross-tested (coap-core-x9). |
| coap-core-21 | DTLS binding: RFC 6347, 7925, 9146 | Deferred | Last by the owner's decision ([roadmap.md](roadmap.md), milestone 12). `DtlsEngine.kt` is an interface; `DtlsCipherSuite`, `DtlsConfig` and `DtlsCredentials` exist and are tested. | When its turn comes: a sans-IO DTLS 1.2 engine (record layer, handshake, PSK, RPK, certificates, Connection ID) in `KtorUdpTransport` for `coaps://`, cross-tested with libcoap's DTLS backends. |
| coap-core-x1 | RFC 7252 §5.10.8 conditional requests (If-Match, If-None-Match, 4.12) | Done | Helper `RequestContext.preconditionsHold` with `CoapResource.recognizedOptions`; `RequestResponseTest.rfc7252Section5_4_1UnrecognizedCriticalOptionsGet402` covers it. | The handler returns 4.12 itself. |
| coap-core-x2 | RFC 7252 §5.10.6.2 server-side ETag validation (2.03 Valid) | To do | No helper. | A server helper that answers 2.03 when a GET's ETag matches; the server half of coap-core-6. |
| coap-core-x3 | RFC 7252 §11.1 and §11.3: bounded state against floods and amplification | To do | `DeduplicationCache`, `MessageIdAllocator`, `ObservationRegistry` and the exchange map have no size caps; only Block-wise has `maxConcurrentTransfers`. | Caps with tests for spoofed CON floods. |
| coap-core-x4 | RFC 7252 §4.6 message size | Done | `DatagramTransport.maxDatagramSize`; `UdpMessagingTest.messagesLargerThanTheDatagramAreRefused`, `BlockwiseTest.blockSizesFollowTheDatagramSize`. | None. |
| coap-core-x5 | RFC 7252 §4.8.1 congestion control when the transmission parameters change (RFC 8085, CoCoA) | To do | The parameters are configurable; no congestion control is provided. | Needed only for non-default parameters; document or implement. |
| coap-core-x6 | RFC 8075 HTTP-to-CoAP cross-proxy mapping | To do | Absent. | Goes with coap-core-7; the plan's E11 (kcoap cross-proxy) is to be decided after WP-I1. |
| coap-core-x7 | RFC 9176 CoRE Resource Directory | To do | Absent (an optional companion of RFC 6690). | Decide the scope. |
| coap-core-x8 | RFC 8710 multipart-core Content-Format | To do | Absent (optional). | Decide the scope. |
| coap-core-x9 | RFC 8323 §9 TLS for `coaps+tcp` and `coaps+ws`, ALPN `coap` | Partial | `coaps+ws` works through the ktor client engine's TLS (`wss`); `CoapScheme.ALPN` and `Endpoint.Tcp(usesTls)` exist; `KtorTcpTransportTest` asserts that a TLS endpoint is refused. | A `coaps+tcp` client and a server-side decision (WP-T1), cross-tested with libcoap built with TLS. |

## OSCORE

Modules: [kcoap-oscore](../kcoap-oscore), with the security SPI in [kcoap-coap](../kcoap-coap).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| oscore-1 | RFC 8613 §3 context derivation (HKDF, ID Context, Master Salt), §3.3 ID rules | Done | `SecurityContext.derive`, `SecurityContextParameters` (ID lengths, distinct IDs); `Rfc8613VectorsTest` C.1–C.3 on every target. | Minor: an ID Context over 255 bytes fails late with `IllegalArgumentException` (§5.1); duplicate contexts silently replace each other in `InMemoryContextRepository`. |
| oscore-2 | §4 option classes E/U, Proxy-Uri, Observe, Block, No-Response, Max-Age | Partial | Figure 5 classes in `CoapOptions.kt`, placed by `OscoreProtection`; `OscoreEndToEndTest.requestAndResponseRoundTrip`, `proxyUriIsDecomposed`. | Outer Block options and a MAX_UNFRAGMENTED_SIZE policy are missing (§4.1.3.4.2 SHALL), so a fragmenting proxy breaks OSCORE; nested OSCORE is not stopped (§4.1.3.7 SHALL); No-Response, Size1/Size2 and Echo placement are untested. |
| oscore-3 | §5 COSE object, nonce, AAD; §6 option compression | Done | `OscoreProtection.additionalData`, `SecurityContext.nonce`, `OscoreOptionValue.kt`; C.4–C.8 byte for byte, `OscorePartsTest`, `OscoreFuzzTest`. | None for conformance. |
| oscore-4 | §7 sequence numbers (2^40 limit), replay window, notification replay protection | Partial | `takeSequenceNumber` under a Mutex, `ReplayWindow.kt`; `OscorePartsTest.exhaustedSequenceNumbersStopTheContext`, `replayWindowFollowsRfc6347`, `OscoreEndToEndTest.observeNotificationsAreOrderedAndReplayProtected`. | After exhaustion, responses that reuse the request nonce are still protected (§7.2.1 MUST NOT); the atomicity MUSTs (§7.2, §7.4) have no concurrent test. |
| oscore-5 | §8 protect and verify; error responses 4.02, 4.01, 4.00 with Max-Age 0 | Done | `OscoreProtection` protect and unprotect, `OscoreError`; C.4–C.8 on both sides, `OscoreEndToEndTest.serverErrorsMapToRfcResponses`. | Max-Age 0 is tested with a stand-in security layer, not the real `OscoreServer`; an empty-payload response (§2) is rejected only by the AEAD. |
| oscore-6 | §4.3 signaling messages over reliable transports | To do | Since `f0dc39d`, signaling runs in `ReliableMessaging` and never passes the security layer, so it always goes unprotected. | Protected signaling: Outer code POST or 2.04, every signaling option Class E, the first CSM unprotected; test a protected Ping/Pong. |
| oscore-7 | §10 forwarding-proxy behaviour, §11 OSCORE over HTTP | To do | No proxy and no HTTP transport; the client's Proxy-Uri works (`OscoreEndToEndTest.proxyUriIsDecomposed`). | First a cross-test through libcoap as an OSCORE-unaware proxy (testing-cicd-x1); §11 needs an HTTP transport, or a recorded deferral. |
| oscore-8 | Appendix B.1.1 sequence-number persistence, B.1.2 Echo recovery, B.2 re-derivation | Partial | `SequenceNumberStore.kt`, `FreshnessPolicy.kt`, `SecurityContext.derive` with no default store (WP-A1). `SequenceNumberPersistenceTest`: a recording store and AEAD show every Partial IV durable before its nonce is used, and failing, cancelled or always-failing writes hand out nothing. `FreshnessPolicyTest` for each policy; B.1.2 in `OscoreEndToEndTest.restartedServerChallengesWithEcho` and `aReplayBetweenChallengeAndAnswerDoesNotRevokeTheEcho`, and against libcoap in both roles. B.2 is deferred in rfc-compliance.md. | No durable store ships, by design: recipes per runtime in kcoap-oscore/README.md (kmp-x3). B.1.3 is undocumented. Next: B.2 against libcoap. |
| oscore-9 | Appendix C vectors byte for byte | Done | `Rfc8613VectorsTest` (8): keys, IVs, nonces, AAD and every protected message of C.1–C.8, on every target. | Add C.5 to `serverVerifiesTheVectorRequests`. |
| oscore-10 | Inner Block-wise and Observe through the stack | Done | `FullStackTest.edhocThenOscoreThroughClientAndServer` (3000-byte Block1 and Block2, Observe, lossy network), `MessageSecurityTest`, `StackOscoreInteropTest`. | Not tested with real OSCORE: Observe with Block2, cancellation, re-registration, reordering; an unprotected error can end an observation (§8.4.2). |
| oscore-11 | Interop with libcoap in both roles | Done | `LibcoapOscoreInteropTest` (raw messages) and `StackOscoreInteropTest` (through the stack: B.1.2 Echo, Block1/Block2, Observe), kcoap as client and as server against libcoap v4.3.5b. Changed from Partial: the CI interop job ran all 4 with none skipped at `a5f0123` and `f0dc39d`. | Not cross-tested: the OSCORE test spec, ID Context, other AEADs, error paths (testing-cicd-x1). [interop/README.md](../interop/README.md) claims a replay case the test does not assert. |
| oscore-x1 | §9 web linking: the `osc` attribute | Partial | Possible through `ResourceAttributes` extras and `WebLink.has`; no constant. | A constant and a test that a value is ignored. |
| oscore-x2 | §4.1.3.2: different servers use different contexts, so tampered unprotected fields cannot reach an unintended server | To do | Left to `ClientContextResolver`, with no guidance or test. | Document the rule on the resolver and test it. |
| oscore-x3 | Echo from an unprotected 4.01 repeated as an Outer option (RFC 9175 §2.3 with OSCORE) | To do | The client's Echo retry always puts it Inner under OSCORE. | Repeat it Outer when the 4.01 was unprotected; test against libcoap. |
| oscore-x4 | §12.6 AEAD limits: nonce of at least 7 bytes, no AEADs that need unpredictable nonces | To do | No explicit check. | Validate in `SecurityContextParameters`. |
| oscore-x5 | Drafts: Group OSCORE, KUDOS key update, OSCORE-capable proxies, group communication (groupcomm-bis) | Deferred | Out of scope until published (rfc-compliance.md, Drafts); the Hop-Limit class difference from libcoap is documented there. | Revisit when they become RFCs. |

## EDHOC

Module: [kcoap-edhoc](../kcoap-edhoc).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| edhoc-1 | Methods 0–3, both roles | Done | Sans-IO `EdhocInitiator.kt`, `EdhocResponder.kt`; `RoundTripTest.everyMethodWithEverySuite`, RFC 9529 traces (methods 0 and 3), libedhoc in both roles. | Methods 1 and 2 have no RFC trace; round trips and libedhoc cover them. |
| edhoc-2 | Cipher suites 2 and 3 (mandatory), 0–6, 24, 25, post-quantum -24 | Partial | `CipherSuite.kt`; `CipherSuiteTest`, round trips for 0–6 and 24, traces for 0 and 2, libedhoc for 0, 2, 4, 24 and -24. | Suite 25 is not implemented, and its documented reason (no backend has Ed448) is wrong; suites 1, 3, 5 and 6 have no independent peer (edhoc-9); real ML-KEM and ML-DSA only on the JVM (kmp-8). |
| edhoc-3 | Credentials and identifiers: kid, kccs, kcwt, x5chain, x5t, x5u, C509 | Partial | `CredentialIdentifier.kt` (kid, x5t, x5chain, kccs), `TrustedCredentials`; RFC 9529 traces (x5t, kid), `RoundTripTest.credentialsByValue`. | No kcwt (foundations-x2), x5u, x5bag or C509; no built-in chain validator (foundations-x4); x5t and kccs are not cross-tested. |
| edhoc-4 | EAD (critical, non-critical, padding), EDHOC_Exporter, EDHOC_KeyUpdate, message_4 | Done | `Ead.kt`, `EdhocSession.kt`; `RoundTripTest.eadItemsReachTheOtherSideAndPaddingIsDropped`, RFC 9529 §2.6–2.7 and §3.8–3.9, libedhoc. | EAD sent by libedhoc is not cross-tested. |
| edhoc-5 | Error messages (codes 0–3), suite negotiation (§6.3) | Done | `EdhocError.kt`; RFC 9529 trace 2 (error 0202 and the retry), `ErrorHandlingTest`, libedhoc negotiation in both directions. Every peer-authentication failure sends the same code 1 "Authentication failed" and keeps the reason in the exception (WP-SEC1, `ErrorHandlingTest.everyPeerAuthenticationFailureSendsTheSameError`). Negotiation asks the crypto backend (WP-A2): SUITES_I, SUITES_R and retries hold only runnable suites, a selected suite the Responder cannot run gets code 2, `EdhocPeerErrorException.retryWith` types the retry, and a configuration asks the backend once, not per message_1, also when the backend does not memoize (`SuiteNegotiationTest`). | Code 3 is not cross-tested. |
| edhoc-6 | Connection identifiers (§3.3), deduplication (§7) | Partial | `ConnectionIdentifier.kt` enforces §3.3.2 (`EncodingTest`); the Responder refuses C_R = C_I (`ErrorHandlingTest.connectionIdentifiersMustDiffer`). | The Initiator does not abort on C_R = C_I (RFC 9668 §4.1.3 MUST) and fails later without an EDHOC error; deduplication is left to CoAP and untested through the stack. |
| edhoc-7 | RFC 9529 traces 1 and 2 byte for byte, §4 invalid traces | Done | `Rfc9529TraceTest` (every printed value, both roles), `Rfc9529InvalidTracesTest` (all 15). | None. |
| edhoc-8 | Interop with libedhoc in both roles, all shared suites and methods, PQ suite | Done | `LibedhocInteropTest` (11): suites 0, 2, 4, 24 with methods 0–3 and suite -24, both roles, negotiation, tampering, EAD; green in CI at `f0dc39d`. | Not cross-tested: x5t, kccs, error code 3, EAD from libedhoc; the byte-exact layer (edhoc-x1). |
| edhoc-9 | Independent peers for the suites libedhoc lacks (1, 3, 5, 6) | To do | Only libcoap and libedhoc in [interop](../interop); lakers and aiocoap run suite 2 only, uoscore-uedhoc suites 0–3. | A uoscore-uedhoc peer for suites 1 and 3, or suites 1, 3, 5 and 6 defined in libedhoc's `edhoc_peer`. Suite 3 is mandatory (RFC 9528 §8). |
| edhoc-10 | EDHOC-PSK, method 4 (draft-ietf-lake-edhoc-psk) | Deferred | An unpublished draft (rfc-compliance.md, Drafts); libedhoc 0363bd3 implements it. | If wanted: draft-10 with its Appendix B vectors and libedhoc in both roles. |
| edhoc-x1 | Byte-exact differential tests against libedhoc with injected ephemeral keys | To do | Planned as Layer 1 in [interop/README.md](../interop/README.md). | A deterministic mode in `edhoc_peer` that compares every message. |
| edhoc-x2 | Stateful protocol fuzzing (EDHOC-Fuzzer) | To do | Planned as Layer 3; only byte mutation exists (`EdhocFuzzTest`, method 0, suites 0 and 2). | Run EDHOC-Fuzzer against both roles in a scheduled job. |

## EDHOC with CoAP and OSCORE

Module: [kcoap-edhoc-oscore](../kcoap-edhoc-oscore).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| edhoc-oscore-1 | RFC 9528 Appendix A.1: OSCORE context from EDHOC | Partial | `OscoreFromEdhoc.kt` (exporter labels 0 and 1, IDs from C_I and C_R); `EdhocOscoreTest.sequentialFlowDerivesMatchingOscoreContexts`, RFC 9529 OSCORE values, libedhoc. | No test of the AEAD and HKDF mapping for other suites (24, 4), of custom key and salt lengths, or of the XOF guard. |
| edhoc-oscore-2 | Appendix A.2.1 forward flow: `/.well-known/edhoc`, prefixes, content formats, A.2.3 errors | Partial | `EdhocOverCoap.kt`, `EdhocServer.handle`, `EdhocClientHandshake.run`; `EdhocOscoreTest.requestFraming`, `serverAnswersBadRequestsWithEdhocErrors`. | The client sends no EDHOC error when message_2 or message_4 fails (§5.3.3, §5.5.3 MUST), so the server session waits until `pendingSessionLifetime` expires it (edhoc-oscore-x3) instead of ending at once. A message_1 refused over the session bounds gets a 5.00 (A.2.3); every other server error is a 4.00, also those that are the server's own fault. |
| edhoc-oscore-3 | Appendix A.2.2 reverse flow | To do | No code; todo in rfc-compliance.md. | A server-side Initiator, a client-side Responder and the swapped OSCORE mapping; needed for the `ed-i` attribute. |
| edhoc-oscore-4 | RFC 9668 combined request: client, server, errors, first-block rule, Figure 4 | Partial | `CombinedRequest.kt`; `EdhocOscoreTest.combinedRequestMatchesRfc9668Figure4`, `combinedRequestErrorsFollowRfc9668`, `laterBlocksDoNotCarryMessage3`; `FullStackTest`. | Missing MUSTs: fall back to the sequential flow when COMB_PAYLOAD is too large (§3.2.2 step 3.1), reassemble Outer Block1 first (§3.3.2 step 0). A repeated combined request gets an EDHOC error; an inner EDHOC option is untested. |
| edhoc-oscore-5 | RFC 9668 §4 identifier selection, §5 application profiles, §6 web linking | Partial | The Responder side of §4.1.2 in `EdhocServer.allocate` and `PendingSessions`: C_R is reserved in the locked step that checks it and held until the context is in the repository, which takes it only if its Recipient ID is still free (`InMemoryContextRepository.addIfAbsent`), so a second server on the same repository never replaces a context (WP-SEC1); `EdhocServerSessionsTest` covers the collisions with C_I, with a Recipient ID, with a session composing message_2 and with one completing, with another server's context, exhaustion, and a stress test on real threads that fails without the reservation. | The Initiator side (§4.1.1, §4.1.3) is missing; no application-profile model; no `ed-*` attributes. |
| edhoc-oscore-6 | EDHOC resource and client handshake through the real `CoapClient` and `CoapServer` | Partial | `FullStackTest.edhocThenOscoreThroughClientAndServer`: combined flow, Block1/Block2 and Observe over a lossy in-memory network. | Not through the stack: sequential `run()`, `rt=core.edhoc` discovery, large messages with Block-wise, error responses on the wire; no run over real UDP (`src/jvmTest` does not exist). |
| edhoc-oscore-7 | Cross-test of EDHOC over CoAP and the combined request with an independent implementation | To do | Planned in [interop/README.md](../interop/README.md); WP-I1 plans a run through libcoap as a WebSocket-to-UDP proxy. | An aiocoap peer (suite 2, method 3) or a libcoap and libedhoc application, both roles, in the CI interop job. |
| edhoc-oscore-x1 | RFC 9668 §7: access control on the rebuilt request knows the authenticated EDHOC peer | Partial | Resources see an `OscoreIdentity` (Recipient ID, ID Context) only; the application must map it to CRED_I itself through `EdhocSessionListener`. | Expose the peer credential and EAD to handlers, with a test. |
| edhoc-oscore-x2 | RFC 9528 §9.7 and Appendix A.2: DoS mitigation at the EDHOC resource (4.01 with Echo before message_2) | Partial | `EdhocServer` (WP-SEC1): a pending session answers only the peer that sent message_1, and a continuation from any other peer costs no cryptography and leaves it alone; at most `maxPendingSessions` (64) in total and `maxPendingSessionsPerPeer` (8) per peer, new ones refused before any processing with a 5.00 and `EdhocServerEvent.LimitReached`; when all places are taken, the oldest session waiting longer than `pendingSessionGracePeriod` (10 s) is evicted, wiped and reported as `EdhocServerEvent.SessionEvicted` (`EdhocServerSessionsTest`); the client copes with Echo generically. | Spoofed message_1 floods still cost a key generation, a DH and a signature each, and 64 of them per 10 s keep new clients out while they last: an optional Echo challenge on the first POST, in the CoAP server, with a test. |
| edhoc-oscore-x3 | Time-based expiry of pending EDHOC sessions (RFC 9528 §7, §9.7) | Partial | `EdhocServer(pendingSessionLifetime)`, MAX_TRANSMIT_WAIT (93 s) by default, on an injected `TimeSource`: an expired session is dropped, its Y and PRK_3e2m wiped, and `EdhocServerEvent.SessionExpired` reported (`EdhocServerSessionsTest.pendingSessionsExpireAndAreWiped`, WP-SEC1). A handler cancelled mid-handshake gives back its C_R and place and wipes Y (`cancellingWhile…` tests). | Test duplicates outside the CoAP deduplication window. |
| edhoc-oscore-x4 | Recovery when a combined request is lost | To do | `CombinedRequestSecurity` clears message_3 when it sends it, so later OSCORE requests find no context on the server. | Keep message_3 until a response arrives, or fall back to the sequential flow. |
| edhoc-oscore-x5 | Lifecycle of EDHOC-derived OSCORE contexts: replace a peer's old context, re-derive after EDHOC_KeyUpdate | To do | `InMemoryContextRepository` never evicts; KeyUpdate exists in kcoap-edhoc but is not wired to OSCORE. | A replace-on-new-handshake rule and a key-update helper. |

## Kotlin Multiplatform

Build: [KcoapBuild.kt](../build-logic/src/main/kotlin/KcoapBuild.kt); platform notes in [README.md](../README.md#platforms)
and [architecture.md](architecture.md#browsers).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| kmp-1 | Targets: JVM, Android, iOS arm64 and simulator, macOS, Linux native, JS (browser and Node), Wasm | Partial | `KcoapBuild.kt` builds jvm, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, js and wasmJs for every protocol module; CI tests all of them except iosArm64 (a device target, compiled only by the release workflow) and the browsers. | Android has no target or consumer build; the browser test tasks are off (`webEnvironments()`); no linuxArm64 (kmp-x1). |
| kmp-2 | Universal by design: common cores, capability-based crypto with fallbacks, adapters only where needed, one API everywhere | Partial | All protocol code in commonMain, no expect/actual, and the build checks it: the core modules apply `kcoap.core`, whose `verifyCommonOnly` fails on main code outside commonMain (WP-C1, Gradle TestKit test `CorePluginTest`); adapters declare a target tier (UNIVERSAL, SOCKETS, JVM_LIBRARY); `CryptoBackend.supports` (memoized in `DefaultCryptoBackend`), `CipherSuite.isSupportedBy(crypto, method)`, which EDHOC uses to offer and accept only runnable suites (WP-A2), `PlatformAesBlock` (AES via CTR), `EcPointDecompression`; `CapabilitiesTest`, `AesBlockTest` and `SuiteNegotiationTest` (`isSupportedBy` is true exactly when a loopback handshake succeeds) on every target. | The `supports` contract is checked per backend only through EDHOC; the published conformance suite is WP-C2. |
| kmp-3 | Every common test runs and passes on every target | Done | CI at `f0dc39d`: 490 common tests on linuxX64, jsNode, wasmJsNode, macosArm64 and iosSimulatorArm64, 543 on the JVM, none failed. | js and wasm run on Node only; some tests narrow by capability (suites 4 and 5 on WebCrypto). |
| kmp-4 | Android app use case: dependencies, crypto per API level, Keystore keys, docs, tested | To do | No Android target, AGP, sample or doc; the jvm artifact is assumed to work. | WP-P1: `samples/android-consumer` with an API-signature check at minSdk; then emulator runs across API levels, a crypto matrix per level, a Keystore backend, a guide. |
| kmp-5 | iPhone app use case: XCFramework, Swift-friendly API, CryptoKit, Local Network notes, built and tested on macOS CI | Partial | iosArm64 and iosSimulatorArm64 targets; CryptoKit and CommonCrypto under `DefaultCryptoBackend`; CI runs the common tests on the iOS simulator. | No XCFramework or SwiftPM package, no `@Throws` (a Kotlin exception crashes a Swift caller), no Flow bridge, no iOS guide. Planned: WP-D2 (`@Throws`, Swift smoke test), E4 (kcoap-apple). |
| kmp-6 | JVM server use case: performance, multi-core, persistent and shared state, metrics, guide | Partial | `MessageInterceptor` and `EndpointEventListener` (tested); SPIs `SequenceNumberStore` and `SecurityContextRepository`; [guide-client-server.md](guide-client-server.md). | No benchmarks; one socket and lock per endpoint; replay windows and EDHOC sessions cannot be persisted or shared; no Micrometer adapter, Java facade or server guide. |
| kmp-7 | Web browser use case: JS/Wasm build, WebCrypto, WebSocket transport, proxy to UDP devices, npm and TypeScript | Partial | Changed from To do: `js` and `wasmJs` with WebCrypto, capability checks and fallbacks (`3e28315`); the WebSocket client `KtorWebSocketConnector` builds for both (`f0dc39d`; `KtorWebSocketConnectionTest` on Node). | No browser test run (WP-W1 plans a headless-Chrome job); no browser-to-proxy-to-device cross-test (WP-I1); no `@JsExport`, TypeScript or npm package; no OSCORE over HTTP. |
| kmp-8 | Post-quantum on every platform | To do | Real ML-KEM and ML-DSA only in `kcoap-crypto-bc` (JVM); other targets test suite -24 with a fake backend. | Common-code ML-KEM and ML-DSA (planned E3, kcoap-crypto-pq), with the ACVP vectors in commonTest. |
| kmp-9 | Transports: UDP, TCP, TLS, WebSocket, DTLS per runtime; in-memory network for tests | Partial | `KtorUdpTransport`, `KtorTcpTransport`, `KtorWebSocketConnector`, `KtorWebSocketListener`; testkit `InMemoryNetwork` and `InMemoryConnections` (`f0dc39d`). | No TLS for `coaps+tcp` (coap-core-x9), no DTLS (coap-core-21); the ktor transport tests run on the JVM only; no multicast or Network.framework adapter. |
| kmp-x1 | More native targets: linuxArm64, iosX64, macosX64, mingwX64 | To do | Only linuxX64 and the arm64 Apple targets. | linuxArm64 is planned (E5); x64 Apple targets are not planned and mingwX64 is deferred ([target-architecture.md](target-architecture.md) §5). |
| kmp-x2 | A per-platform matrix of algorithms and EDHOC suites, generated from capability probes | Partial | README lists what WebCrypto lacks, by hand. | A capability report per target in CI (WP-C2) and `docs/platforms.md` (WP-C3). |
| kmp-x3 | Durable stores per platform: sequence numbers, OSCORE contexts, EDHOC credentials | Partial | Changed from To do: [kcoap-oscore/README.md](../kcoap-oscore/README.md) "Writing a durable store" has `SequenceNumberStore` recipes for the JVM and Android, iOS and macOS, Linux, browsers and Node.js, each durable before `store()` returns, as the plan (§3.9) chooses over store modules (WP-A1). | No recipes yet for OSCORE contexts (`MutableSecurityContextRepository`) or EDHOC credentials; the recipes are not compiled or tested. |
| kmp-x4 | Real devices: iPhone (iosArm64), Android emulators across API levels, ARM devices | To do | The iOS simulator runs in CI; no device or emulator runs. | Add after kmp-4; an emulator matrix in CI and a manual device pass per release. |
| kmp-x5 | Node.js socket adapters (dgram UDP, net/tls TCP) | Deferred | Ruled out in [target-architecture.md](target-architecture.md) §3.10; the WebSocket client runs on Node. | Revisit on demand. |
| kmp-x6 | Footprint and dependency policy: static OpenSSL on Linux and its updates, Bouncy Castle on Android, JS/Wasm bundle size, R8 rules | To do | None written. | A browser bundle budget is planned in WP-P1; the rest needs a short policy in docs. |
| kmp-x7 | Common-code ChaCha20-Poly1305 so EDHOC suites 4 and 5 run on WebCrypto | Deferred | WebCrypto lacks ChaCha20; documented in README. The plan defers pure-Kotlin ChaCha20 until a platform cannot run the mandatory suites. | None until that trigger. |

## Architecture, code, logging, docs

Docs: [architecture.md](architecture.md), [code-style.md](code-style.md), [logging.md](logging.md),
[target-architecture.md](target-architecture.md), [adr](adr/README.md).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| architecture-1 | Good patterns (callbacks, SPIs, encapsulation, inheritance where natural), documented where used | Partial | [architecture.md](architecture.md) "Where the patterns are" and the [code-style.md](code-style.md) table; every named type exists, and tests plug in their own implementations. | The table omits several SPIs (`SecurityContextRepository`, `EndpointResolver`, `TokenGenerator`, `CertificateValidator`); no guide to implementing an SPI; `OscoreEndpoints.kt` holds 6 public types. |
| architecture-2 | Clean layering, no cycles, sans-IO cores, injectable time and randomness | Partial | Protocol modules depend on SPIs only; no package cycles; `UdpMessaging` and `ReliableMessaging` are sans-IO; time and randomness are injected. | The public sans-IO engine promised in architecture.md does not exist (deferred in the plan, §5); the module diagram misses edges (WP-C3). By decision ([ADR 0007](adr/0007-build-governance.md)) the build checks only that cores stay common (`verifyCommonOnly`) and the public ABI (architecture-x1); the layering between modules and the dependency scopes ([code-style.md](code-style.md#dependencies-and-api)) are kept by review. |
| architecture-3 | Logging: per-tag levels, sinks, ring buffer, runtime changes, no secrets | Partial | `LogConfig`, `RingBufferSink`, `KcoapLogging`; `LoggerTest`, `SinkTest`, `RoundTripTest.logsNeverContainSecrets` (EDHOC). | OSCORE-protected Uri-Path and Uri-Query appear in DEBUG lines of `CoapClient`, `CoapServer` and `OscoreEndpoints`, against logging.md; no OSCORE log-leak test; no text or environment config; the `crypto` and `dtls` tags are unused. |
| architecture-4 | Readable code: KDoc on all public API, RFC citations, consistent naming, small files | Partial | `explicitApi()` in every module; RFC sections cited throughout. | Hundreds of public declarations lack KDoc (711 by Dokka at `c168980`); 149 lines exceed 120 columns; `loggers` and `loggerFactory` both used; packages break the `dev.kcoap.<module>` rule. |
| architecture-5 | Docs: README, architecture, guides, RFC compliance, roadmap, module READMEs, API reference | Partial | README and the docs listed there; the client and server guide is compiled by `GuideExamplesTest`; Dokka builds in the CI docs job and release.yml publishes it to Pages (`1d95636`). | No OSCORE or EDHOC guide; kcoap-common and kcoap-crypto have no README; README does not link the API reference; several docs are stale ([Notes](#notes)). |
| architecture-x1 | API and ABI compatibility checked in CI (JVM `.api` and klib dumps); opt-in markers on unstable APIs | Done | WP-C1: the Kotlin Gradle plugin's ABI validation, configured in `KcoapBuild.kt`; JVM and klib dumps (Apple targets included) committed in `<module>/api` for every published module; `checkKotlinAbi` in `scripts/test unit` and on both CI runners. `@InternalKcoapApi` (left out of the dumps: `ByteArrayBuilder`, `minimalUnsignedBytes`, `readUnsigned`, `OptionCodec`) and `@ExperimentalKcoapApi` (on `DatagramSecurity`) in kcoap-common; kcoap-dtls is unpublished (`kcoap.unpublished`). | Every later API change commits its dump diff (`updateKotlinAbi`); [releasing.md](releasing.md#abi-dumps) says how to refresh the Apple entries on macOS. |
| architecture-x2 | The code-style rules enforced in CI (ktlint or detekt, `.editorconfig`) | To do | No formatter or linter. | Add one with `max_line_length = 120`. |
| architecture-x3 | A KDoc gate: Dokka `reportUndocumented` and `failOnWarning` | Partial | Dokka runs in CI (docs job), so the reference cannot break unnoticed. | Turn on the gate after architecture-4 documents the missing declarations. |
| architecture-x4 | Project documents: LICENSE, SECURITY.md, CONTRIBUTING.md, CHANGELOG.md, a versioning and stability policy | Partial | [CHANGELOG.md](../CHANGELOG.md) with semantic versioning and migration notes (`05aa66c`). | No LICENSE (blocks Maven Central), SECURITY.md or CONTRIBUTING.md. |
| architecture-x5 | Metrics: counters for retransmissions, OSCORE replays, EDHOC failures; adapters and a guide | Partial | Hooks `EndpointEventListener` and `MessageInterceptor`. | No counters, no Micrometer or OpenTelemetry adapter, no guide. |
| architecture-x6 | Thread-safety contracts on public types, and stress tests on multi-threaded dispatchers | Partial | Changed from To do: `EphemeralSequenceNumberStore` is Mutex-guarded and states its contract, and `EphemeralSequenceNumberStoreTest` stresses it on `Dispatchers.Default` with parallel contexts (WP-A1). Every other test runs on one thread (`runTest`). | The other public types need contracts and stress tests: WP-S1 (shared stress helper on `Dispatchers.Default`, JVM and linuxX64), which `EphemeralSequenceNumberStoreTest` should then use instead of its local one. |
| architecture-x7 | One source of truth, or a check, for protocol status across README, rfc-compliance.md, roadmap.md, CHANGELOG.md and module READMEs | To do | They disagree today ([Notes](#notes)). | Fix the entries, then a script that compares the status tables. |

## Tests, cross-tests, CI/CD

Files: [testing.md](testing.md), [interop/README.md](../interop/README.md), [scripts/test](../scripts/test),
[ci.yml](../.github/workflows/ci.yml), [release.yml](../.github/workflows/release.yml).

| ID | Requirement | Status | Evidence | Gap / next step |
|---|---|---|---|---|
| testing-cicd-1 | Coverage near 100%, line and branch, enforced by the build | Partial | Kover (JVM unit tests): line 98.4%, branch 86.8%; `koverVerify` enforces 90% line ([kcoap.coverage.gradle.kts](../build-logic/src/main/kotlin/kcoap.coverage.gradle.kts)) and 95% for kcoap-edhoc. | No branch rule, and the line floor sits 8 points below the actual value; CI coverage leaves out the interop paths. Next: floors of 98% line and 86% branch, ratcheted up, per module. |
| testing-cicd-2 | RFC vectors byte for byte for every protocol; fuzz tests for every decoder and verifier | Partial | RFC 8613 C, RFC 9529, RFC 9668 Figure 4, RFC 8949 A (subset), RFC 6690 §5, the crypto vectors; `CborFuzzTest`, `CodecFuzzTest`, `OscoreFuzzTest`, `EdhocFuzzTest` on every target. | No byte vectors from the RFC 7252, 7959, 7641 or 8323 examples; no COSE or CWT vectors; not fuzzed: X.509, `CoapUri`, `LinkFormat`, COSE_Key, EDHOC errors and message_4, RFC 9668 parsing; EDHOC fuzzing covers method 0 only. |
| testing-cicd-3 | libcoap cross-tests in both roles: plain CoAP, Block-wise, Observe, OSCORE, TCP, WebSocket, Q-Block, DTLS | Partial | UDP: `KcoapClientAgainstLibcoapTest`, `LibcoapClientAgainstKcoapTest` (17, including the Q-Block fallback against `coap-client -L 7`). TCP and WebSockets with BERT and Observe: `KcoapClientAgainstLibcoapReliableTest`, `LibcoapClientAgainstKcoapReliableTest` (17, new in `f0dc39d`). OSCORE (4). Q-Block with libcoap's client against kcoap's server over UDP, TCP and WebSockets: `LibcoapClientAgainstKcoapQBlockTest` (13, QB-3; libcoap 4.3.5 turns Q-Block on with `-L 7`, it has no `-Q`). All green in CI. | Q-Block with kcoap's client against libcoap's server (QB-4), DTLS (deferred) and `coaps+tcp`. |
| testing-cicd-4 | libedhoc cross-tests in both roles | Done | `LibedhocInteropTest` (11) against an image built from [interop/libedhoc](../interop/libedhoc); green in CI at `f0dc39d`. | Suite 3 has no independent peer (edhoc-9). |
| testing-cicd-5 | One command for unit tests, one for interop (building the images), documented | Partial | [scripts/test](../scripts/test) `unit`, `interop`, `coverage`, `apple`, `all` (`1d95636`), documented in README and [testing.md](testing.md). | Interop can pass without running: `build_images \|\| true` plus JUnit assumptions skip silently, and jvmTest is cacheable outside kcoap-coap. Next: a strict mode that fails on skips, and no caching for interop tasks. |
| testing-cicd-6 | GitHub CI: Linux (JVM, native, JS, Wasm), macOS (iOS simulator, macOS), interop, coverage report | Done | [ci.yml](../.github/workflows/ci.yml) jobs unit, interop, apple and docs; run 41949553396 at `f0dc39d` green (counts above); the coverage table goes to the step summary. | Browsers are not tested; CI coverage is unit-only; hygiene in testing-cicd-x4. |
| testing-cicd-7 | GitHub CD: release from tags, Maven Central and GitHub Packages, signing, release notes, API docs | Partial | [release.yml](../.github/workflows/release.yml) (`1d95636`, `0faabe0`), `kcoap.publishing.gradle.kts`, [releasing.md](releasing.md) with the release freeze and a 0.1.0 checklist (`05aa66c`). | Never run: no tag, no release, Pages not enabled. No LICENSE (Maven Central rejects that), the Maven namespace is open, no signing secrets. |
| testing-cicd-8 | History authored as Adrian Soltys, pushed to github.com/adihack/kcoap | Done | Every commit is authored and committed by Adrian Soltys; `origin` is adihack/kcoap; `f0dc39d` is the remote branch head and PR #1's head. | When written, `05aa66c` was not pushed; `main` holds only the skeleton; PR #1 is a draft; commits are unsigned. |
| testing-cicd-x1 | OSCORE cross-tests beyond the basics: the core-wg test spec (libcoap `oscore_testcases.sh`), the byte-exact oracle, and a run through libcoap as an OSCORE-unaware proxy, both roles | To do | Listed as todo in rfc-compliance.md and [interop/README.md](../interop/README.md). | Start with the test spec in both roles. |
| testing-cicd-x2 | Interop under network impairment (loss, duplication, reordering) | To do | Impairment exists only in the in-memory network. | A netem or proxy layer between kcoap and libcoap or libedhoc. |
| testing-cicd-x3 | Cross-tests for each CoAP feature as it lands: Q-Block, multicast, proxying and caching, Hop-Limit, server Echo | To do | The features do not exist yet. | Make a libcoap cross-test part of each feature's definition of done. |
| testing-cicd-x4 | CI hygiene: job timeouts, a nightly run, Gradle wrapper validation, Dependabot or Renovate, CodeQL, branch protection on `main` | Partial | ci.yml has a concurrency group and `workflow_call`. | The rest is missing; a nightly run also catches upstream libcoap and libedhoc changes. |
| testing-cicd-x5 | Cached or published interop images (GHCR) | To do | Both images are built from source on every run. | Publish pinned images and pull them in CI. |
| testing-cicd-x6 | A CI check that `tools/regen-vectors.sh` and `tools/regen-pq-vectors.sh` reproduce the committed vectors | To do | Not checked. | Run both and fail on a diff. |
| testing-cicd-x7 | Test and coverage results visible in pull requests | Partial | `scripts/coverage-summary` writes the coverage table to the step summary. | The per-target test tables are only in the logs; add them to the step summary. |

## Notes

- Auditor findings that repeat a gap already in a row are folded into that row rather than listed twice:
  C_R = C_I and C_I selection (edhoc-6, edhoc-oscore-5), Outer Block-wise (oscore-2, edhoc-oscore-4),
  the reverse flow (edhoc-oscore-3), EDHOC-over-CoAP interop (edhoc-oscore-7), interop strict mode and
  caching (testing-cicd-5), Swift, Java and JS facades (kmp-5, kmp-6, kmp-7), post-quantum on other
  targets (kmp-8), key storage (foundations-5), multicast per runtime (coap-core-8), Android checks
  (kmp-4), the remaining fuzz targets (testing-cicd-2) and DTLS cross-tests (testing-cicd-3).
- Kover measures JVM tests only. All production code is common, with no expect/actual, except
  kcoap-crypto-bc's jvmMain, so the same commonTest on every target stands in for per-target numbers;
  [target-architecture.md](target-architecture.md) §3.10 records this as an accepted gap.
- Apple results come only from CI's macOS runner. CryptoKit signs Ed25519 hedged, so since `a5f0123` the
  RFC 8032 and RFC 9529 trace tests verify the signature there instead of comparing bytes.
- Stale or conflicting docs: rfc-compliance.md marks RFC 8949 §4.2 and RFC 9052/9053 as todo while
  roadmap.md says CBOR and COSE are done; README calls libedhoc interop "in progress" and the WebSocket
  transport "being built"; interop/README.md
  claims a replay-error case the test does not assert; CipherSuite.kt and kcoap-edhoc/README.md say no
  backend has Ed448, though cryptography-kotlin 0.6.0 and Bouncy Castle do;
  CHANGELOG.md lists Hop-Limit as added, though only the option is registered (coap-core-17).
- [target-architecture.md](target-architecture.md) orders refactoring work packages (WP-A1, WP-T1, …)
  and roadmap features (E1–E11); rows name them where they match. Q-Block is planned in
  [plan-qblock-and-platforms.md](plan-qblock-and-platforms.md); caching and multicast are in neither. [releasing.md](releasing.md) freezes `v*` tags until the API-breaking packages have merged.

## Biggest gaps

In the order that most moves kcoap toward its goals.

1. **Q-Block, RFC 9177 (coap-core-13).** Explicitly required; planned as QB-0 to QB-7 in
   [plan-qblock-and-platforms.md](plan-qblock-and-platforms.md). Today's 4.02 fallback is pinned in
   `QBlockFallbackTest` and against `coap-client -L 7`, and the sans-IO core (MAX_PAYLOADS bursts,
   NON_TIMEOUT and NON_RECEIVE_TIMEOUT, missing-block reports and recovery) passes every RFC 9177 figure on
   virtual time, and the endpoint carries several responses per request and leases one NSTART slot per
   body (QB-2). The server serves it when turned on (QB-3), cross-tested with `coap-client -L 7` over UDP,
   TCP and WebSockets. Next, the client (QB-4), tested end to end on the lossy in-memory network and against
   `coap-server -L 7` (libcoap 4.3.5 has no `-Q`), then OSCORE and Observe with Q-Block (QB-5, QB-6).
2. **Cross-tests in both roles.** 48 pass today: libcoap over UDP, TCP and WebSockets, OSCORE, libedhoc.
   Missing, in order: Q-Block (above); a strict interop mode so a broken image fails the job
   (testing-cicd-5); EDHOC over CoAP and the RFC 9668 combined request against an independent peer
   (edhoc-oscore-7); the OSCORE test spec and a run through an OSCORE-unaware proxy (testing-cicd-x1); an
   independent peer for the mandatory suite 3 and suites 1, 5, 6 (edhoc-9); libedhoc's x5t, kccs, error
   code 3 and EAD (edhoc-8); `coaps+tcp` (coap-core-x9); DTLS last (item 6).
3. **The 100% RFC items.** The MUST-level gaps, by RFC:
   - RFC 7252: caching (coap-core-6), proxying (coap-core-7), multicast (coap-core-8), option and URI
     rules (coap-core-4, coap-core-5), bounded state (coap-core-x3), server ETag validation
     (coap-core-x2).
   - Companions: Observe Content-Format and 4.06 (coap-core-11), If-None-Match on Block1
     (coap-core-12), FETCH key and Content-Format (coap-core-14), server Echo for freshness
     (coap-core-16), extended tokens decision (coap-core-19).
   - OSCORE: Outer Block and nested OSCORE (oscore-2), exhausted sender context (oscore-4),
     signaling (oscore-6), proxies and HTTP (oscore-7).
   - EDHOC: the Initiator's C_R = C_I abort and C_I selection (edhoc-6, edhoc-oscore-5), the error
     message after a failed message_2 (edhoc-oscore-2), the combined-request fallback and Outer Block1
     (edhoc-oscore-4), the reverse flow (edhoc-oscore-3), suite 25 (edhoc-2), CWT credentials
     (edhoc-3, foundations-x2).
   - CBOR and COSE: strict decoding (foundations-1), duplicate labels and key checks (foundations-2).
4. **Platform use cases.**
   - Android (kmp-4): a consumer sample built in CI, an API-signature check at minSdk, emulator runs,
     a crypto matrix per API level (WP-P1).
   - iPhone (kmp-5): `@Throws` and a Swift smoke test (WP-D2), then an XCFramework and SwiftPM package
     (E4).
   - JVM server (kmp-6): benchmarks, persistable OSCORE and EDHOC state (kmp-x3), metrics
     (architecture-x5), a Java facade, a server guide.
   - Browser (kmp-7): headless-browser tests (WP-W1), the browser-to-proxy-to-device cross-test (WP-I1),
     a JS facade and npm package.
   - Everywhere: post-quantum on every target (kmp-8, E3); capability-aware suite selection landed in
     WP-A2.
5. **Coverage toward 100% line and branch (testing-cicd-1).** Today 98.4% line and 86.8% branch, with
   only a 90% line floor. Add a branch rule at the current value and per-module rules, raise them with
   each milestone, and start with the files that miss the most branches: `CoapEndpoint.kt`,
   `CoapServer.kt`, `UdpMessaging.kt`, `BlockwiseClient.kt`, `ObservationRegistry.kt`, `CoseKey.kt`,
   `ConnectionDriver.kt`.
6. **DTLS, last, as decided (coap-core-21, coap-core-9).** Only DTLS brings the mandatory RawPublicKey
   mode of RFC 7252 §9 and `coaps://`; it needs the X.509 validator (foundations-x4) for certificate
   mode and a cross-test matrix against libcoap's DTLS backends (testing-cicd-3). TLS for `coaps+tcp`
   (coap-core-x9, WP-T1) does not have to wait for it.
