# Changelog

All notable changes to kcoap. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/). Before 1.0, a minor version may break the
API; every breaking change is listed here with a migration note.

## Unreleased

Nothing has been released yet. The API is still changing as the refactoring plan in
[docs/target-architecture.md](docs/target-architecture.md) lands; each work package that breaks the API
adds its entry and migration note below.

### Added

- CoAP over UDP (RFC 7252) with Observe (RFC 7641), Block-wise (RFC 7959), FETCH/PATCH/iPATCH (RFC 8132),
  Echo and Request-Tag (RFC 9175), No-Response (RFC 7967), Hop-Limit (RFC 8768) and link format (RFC 6690).
- CoAP over TCP and WebSockets (RFC 8323): CSM, Ping/Pong, Release, Abort, BERT.
- OSCORE (RFC 8613) with Appendix B.1 sequence-number persistence and Echo-based replay-window recovery.
- EDHOC (RFC 9528, RFC 9529 traces), including the post-quantum suite -24 of draft-ietf-lake-pqsuites.
- EDHOC over CoAP and the combined EDHOC + OSCORE request (RFC 9528 Appendix A, RFC 9668).
- Targets: JVM, iOS, macOS, Linux native, JS and Wasm, with capability-based crypto selection.
- Live interop tests against libcoap (CoAP, OSCORE, TCP, WebSockets) and libedhoc (both roles, PQ).
- One-command test suites (`scripts/test`) and CI/CD (tests on Linux and macOS, tag-driven releases).
- `CoapEndpoint.closeCause` and `awaitClosed()`, and `CoapException.Reason.TRANSPORT_FAILED`: an endpoint
  ends in one terminal transition, whichever way (`close()`, its scope cancelled, its datagram transport's
  `receive` failing), and says why (WP-S1).
- Resource bounds for what unauthenticated peers can make an endpoint keep or do (RFC 7252 §11.3), each
  reported as the new `EndpointEvent.LimitReached` with its `Limit`: `CoapEndpointConfig.maxDeduplicationEntries`
  (50 000), `maxConcurrentRequests` (256), `maxExchangesPerPeer` (256); `CoapServerConfig.maxObservers`
  (10 000), `maxObserversPerPeer` (32), `maxObserversPerResource` (1 000);
  `ReliableTransportConfig.maxPendingConnections` (128) and `maxConnections` (1 024) (WP-S1).
- `kcoap-testkit`: `Stress.run` and `Stress.race`, bounded concurrency stress tests on the real
  `Dispatchers.Default` for common tests (WP-S1).
- Groundwork for Q-Block (RFC 9177), which stays off: a Q-Block request still gets 4.02 (Confirmable) or a
  Reset (Non-confirmable), and the client still rejects a response carrying Q-Block, now pinned by tests
  (QB-0). The Q-Block state machines exist but are not yet used by the client or server (QB-1, ADR 0006).
  The endpoint can carry several responses to one request into a per-body inbox, and holds one NSTART
  slot per Non-confirmable body, released at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT) (QB-2; internal,
  no API change). New public API:
  - `TransmissionParameters.maxPayloads` (10), `nonTimeout` (ACK_TIMEOUT), `nonReceiveTimeout`
    (2 × NON_TIMEOUT, at least NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s, which `init` enforces),
    `nonMaxRetransmit` (MAX_RETRANSMIT) and the derived `nonPartialTimeout` (247 s), RFC 9177 §7.2. They are
    new trailing constructor parameters with defaults, so source stays compatible; compiled callers of the
    constructor recompile.
  - `ContentFormat.MISSING_BLOCKS_CBOR_SEQ` (272, RFC 9177 §5).
  - `OptionSet.qBlock1`, `qBlock2` and `qBlock2List`, and `OptionSet.Builder.qBlock1()`, `qBlock2()` and
    `addQBlock2()`.
- Amplification mitigation by address verification (RFC 9175 §2.4 item 3, §2.6; SRV-1). A response larger
  than `CoapServerConfig.amplificationFactor` (3) times its request, to a client that neither OSCORE nor
  DTLS authenticated on a datagram transport, waits until the client's address is verified: the server
  sends a 4.01 Unauthorized with an 8-byte Echo value instead, piggybacked or Non-confirmable, never as a
  separate Confirmable response, and a request that returns the value verifies the address for
  `verifiedAddressLifetime` (1 hour). `CoapServerConfig.amplification` chooses what is checked:
  `AmplificationPolicy.Q_BLOCK_BURSTS` (the default; the server sends no Q-Block bursts yet, so nothing
  changes for now), `ALL_RESPONSES` (also every Observe registration, and a POST or PATCH keeps its
  response for the repeat, so it does not run twice) or `OFF`. `maxVerifiedAddresses` (10 000) bounds the
  verified addresses and, separately, the outstanding challenges. New `EndpointEvent.LimitReached.Limit`
  values: `AMPLIFICATION_FACTOR` (a response was held back), `VERIFIED_ADDRESSES` and `ADDRESS_CHALLENGES`
  (an entry was evicted for room); an exhaustive `when` over `Limit` needs the three new branches. The
  configuration properties are new trailing constructor parameters with defaults, so source stays
  compatible; compiled callers of the constructor recompile. libcoap's `coap-client` repeats its request
  with the Echo value and completes, and its Q-Block probe of `/.well-known/core` is never challenged.
- Opt-in markers in kcoap-common: `@InternalKcoapApi` for plumbing shared between kcoap's modules, and
  `@ExperimentalKcoapApi` for published SPIs that nothing implements yet (WP-C1).
- ABI dumps of every published module's public API in `<module>/api` (JVM and klib), checked by
  `checkKotlinAbi` in CI, so every API change shows up in review (WP-C1).
- Capability questions on the crypto seam ([ADR 0001](docs/adr/0001-crypto-capabilities.md), WP-A2):
  `CryptoBackend.supports(AeadAlgorithm | KeyType | SignatureAlgorithm | KemAlgorithm | HashAlgorithm)`,
  false exactly when the operations would throw `UnsupportedAlgorithmException`, which now names its
  `algorithm`. The defaults probe; `DefaultCryptoBackend` probes once per instance and memoizes the answer,
  `BouncyCastleCryptoBackend` answers ML-KEM and ML-DSA without probing.
- EDHOC negotiates only suites the crypto backend runs (WP-A2): `CipherSuite.isSupportedBy(crypto, method)`;
  `EdhocInitiatorConfig.supportedBy(crypto)` and `EdhocResponderConfig.supportedBy(crypto)`, which narrow a
  configuration once and fail fast; `EdhocPeerErrorException.retryWith`; `Message3`, which
  `EdhocInitiator.createMessage3()` returns. A configuration asks the backend once and remembers the answers,
  so sessions do not probe it again, also when the backend keeps the probing defaults; the copies that
  `supportedBy` and the new `EdhocResponderConfig.withConnectionIdentifiers(provider)` make keep them.

### Changed (breaking)

EDHOC capability-aware negotiation, typed retry and returned sessions (WP-A2):

- **EDHOC offers and accepts only the cipher suites its crypto backend runs.** The Initiator's SUITES_I and
  retries draw only on them; the Responder names only them in SUITES_R and answers a selected suite it is
  configured with but cannot run with error code 2 (RFC 9528 §6.3), where it used to fail later with error
  code 1. An Initiator that runs none of its suites with a credential, or not its `initialCipherSuite`, fails
  in `createMessage1()` with an `EdhocException` naming what is missing. *Migration:* none for working
  configurations; call `config.supportedBy(crypto)` at startup to find a configuration the backend cannot run.
- **`EdhocInitiator.createMessage3()` returns `Message3(bytes, session)`** instead of the message bytes, and
  **`EdhocResponder.processMessage3()` returns the `EdhocSession`**. *Migration:* send `message3.bytes`, or
  write `val (message3, session) = initiator.createMessage3()`; drop `session!!` after these calls.
- **`CipherSuite.isSupportedBy(backend)` is now `isSupportedBy(crypto, method)`**: what a suite needs depends
  on the method (method 3 signs nothing), and the check now covers the hashes, HMAC and KEMs too.
  *Migration:* pass the method.
- **The `supports(...)` extension functions of kcoap-crypto are `CryptoBackend` members.** *Migration:* drop
  `import dev.kcoap.crypto.supports`; calls stay the same. A decorator (`CryptoBackend by delegate`) that adds
  algorithms must override the matching `supports` members, or its delegate answers for it.
- **`CryptoBackend` parameters are called `crypto`** in `EdhocInitiator`, `EdhocResponder`,
  `TrustedCredentials`, `ConnectionIdentifierProvider.choose`, `EphemeralKeySource.keyPair`,
  `CredentialIdentifier.X509Thumbprint.matches`, `CredentialIdentifier.x509Thumbprint` and `Hkdf`.
  *Migration:* rename the named argument `backend =` to `crypto =`.
- After error code 2, `EdhocPeerErrorException.retryWith` tells a retry from an abort. *Migration:* replace
  `if (initiator.phase != EdhocPhase.START) throw e` with `if (e.retryWith == null) throw e`; the old check
  still works.
- kcoap-crypto-default depends on kotlinx-coroutines-core (`implementation`) for the capability memo.

OSCORE safe persistence and secret hygiene (WP-A1):

- `SecurityContext.derive` has no default `sequenceStore`. Pass a durable `SequenceNumberStore` for a
  provisioned Master Secret (recipes per runtime in `kcoap-oscore/README.md`), or
  `EphemeralSequenceNumberStore()` only for keys that are new in this process run.
- `InMemorySequenceNumberStore` is renamed `EphemeralSequenceNumberStore`, which is now thread safe.
- `derive(requireFreshnessProof: Boolean = false)` is replaced by `freshness: FreshnessPolicy = AUTO`.
  For a restored context, drop `requireFreshnessProof = true`: `AUTO` requires the Echo proof for it.
  Use `REQUIRE_PROOF` to require it always. Under `AUTO`, a context with a store other than the
  ephemeral one challenges its first request after every start; pass `ASSUME_FRESH`, the old default,
  only when no request under these keys can have been accepted before.
- `SecurityContext.parameters` is internal. Read `context.name` and `context.aeadAlgorithm` instead of
  `context.parameters.name` and `context.parameters.aeadAlgorithm`.
- `SecurityContextParameters.masterSecret` and `masterSalt` are functions that return copies: change
  `parameters.masterSecret` to `parameters.masterSecret()`, and wipe the copy when done with it.
- `OscoreClient` and `OscoreServer` name their `LoggerFactory` parameter `loggerFactory`: change the
  named argument `loggers =` to `loggerFactory =`.

CoAP stack concurrency, resource bounds and crash safety (WP-S1):

- **Calls after close throw `CoapException`.** Requests, pings and `observe` on a closed endpoint throw
  `CoapException` with the reason of `closeCause` (`CLOSED`, or `TRANSPORT_FAILED` after the datagram
  transport failed) instead of `IllegalStateException`, and an endpoint whose scope was cancelled or whose
  transport failed is closed like one closed by `close()`. *Migration:* catch `CoapException` (and check
  `reason`) where you caught `IllegalStateException`; to recover from a failed transport, create a new
  endpoint.
- **An endpoint receives only once a server attaches or the first request or ping goes out.** A bare
  `CoapEndpoint`, or one with only a `CoapClient` that has not sent anything, leaves datagrams and
  connections waiting in the transports. A server's resources are therefore in place before the first
  request, whichever of a server and a client on a shared endpoint is created first. *Migration:* none for
  servers and clients; code that used a bare endpoint as a Reset-only peer must start it by sending.
- **`CoapEndpointConfig.tokenGenerator` is nullable and defaults to null**, which gives every endpoint its
  own `TokenGenerator.sessionSequence()`, so one configuration can serve several endpoints. *Migration:*
  code that read the property must handle null; a generator passed explicitly that keeps state must not
  be shared between endpoints.
- **`CoapServer.close()` on a shared endpoint cancels the server's running handlers** and takes no new
  observers; a request on a connection whose handler was cancelled, or never started, gets 5.03 (RFC 8323
  §3.3). *Migration:* handlers that must finish their work should do it under `NonCancellable`, and
  should not rely on running after `close()`.
- **Security-layer and pipeline failures are answered.** Any exception in the server pipeline (the
  security layer, an application callback behind it such as a context repository or sequence-number
  store, protecting or sending the response) is answered with an unprotected 5.00 with Max-Age 0 instead
  of leaving the client to time out; a failing notification removes its observer; nothing escapes to the
  application's exception handler. *Migration:* clients that treated a timeout as "security failure"
  get a 5.00 response instead.
- **A send the transport refuses with `IllegalArgumentException` fails at once** with
  `TRANSPORT_FAILED` instead of as a peer timeout after MAX_TRANSMIT_WAIT. *Migration:* handle
  `TRANSPORT_FAILED` next to the timeout reasons.
- **The new default bounds refuse work beyond them:** more than 256 concurrent handlers get a piggybacked
  5.03 with Max-Age 2 (a NON is dropped); more than 256 exchanges in progress with one peer fail with
  `IDENTIFIERS_EXHAUSTED`; registrations beyond the observer bounds are served without Observe (RFC 7641
  §4.1); new messages beyond the deduplication bound are dropped silently; accepted connections beyond
  the connection bounds are closed. *Migration:* raise the bounds in `CoapEndpointConfig`,
  `CoapServerConfig` and `ReliableTransportConfig` for gateways or load tests that need more, and count
  `EndpointEvent.LimitReached` in production.
- **Observers that no security authenticated get a Confirmable notification at least every ninth
  notification** (RFC 7641 §7), not only after `confirmableNotificationInterval`. *Migration:* none; a
  client that does not acknowledge them is removed as an observer.
- **`CoapEndpointConfig.timeSource` must advance with the clock of the endpoint scope's dispatcher**, and
  the endpoint warns once when it does not. *Migration:* under `runTest`, pass
  `testScheduler.timeSource`.

Build governance (WP-C1):

- **`StreamSecurity` is removed** from kcoap-transport. It had no implementation and no users; TLS for
  `coaps+tcp` and `coaps+ws` belongs to each runtime's connector and listener
  (docs/adr/0003-tls-in-connectors.md). *Migration:* none.
- **`DatagramSecurity` requires `@OptIn(ExperimentalKcoapApi::class)`**: it has no implementation yet and
  may change when the DTLS engine implements it. *Migration:* opt in where you implement or use it.
- **`ByteArrayBuilder`, `minimalUnsignedBytes` and `ByteArray.readUnsigned` (kcoap-common) and
  `OptionCodec` (kcoap-coap) are `@InternalKcoapApi`**, plumbing between kcoap's modules that may change
  in any release and is not in the ABI dumps. Code outside kcoap no longer compiles against them without
  `@OptIn(InternalKcoapApi::class)`. *Migration:* keep a copy of the helper in your code; `constantTimeEquals`,
  `wipe` and `toHex` stay public API.
- **kcoap-dtls is no longer published** until its engine exists (it held interfaces only). *Migration:*
  drop the dependency; nothing could use it without an implementation.
- **kcoap-transport depends on kcoap-common (`api`)**, for the opt-in markers (`ExperimentalKcoapApi` on
  `DatagramSecurity`); it still has no third-party dependencies. A custom transport that depends on
  kcoap-transport alone now has kcoap-common on its compile classpath. *Migration:* none.

### Fixed

- A connection that an endpoint accepted or opened while it closed could stay open, and the accept loop
  could fail with `IllegalStateException: Already started`: a new connection was registered in the
  connection pool before it started, so a `close()` in between shut it down unstarted. It is now
  registered and started in one step (coap-core-20; the known flake of
  `ConcurrencyStressTest.closeRacingConnectionSetupFailsTheWaitingRequestsAndClosesEveryConnection`).
- The client answers a separate response only after verifying it and checking its critical options (RFC
  7252 §5.4.1), however long verification takes; the Empty ACK timer for slow requests no longer
  acknowledges a response first. A Confirmable separate response, or a notification, with a critical
  option the client does not recognize gets a Reset instead of an ACK; the request still fails with
  `BAD_RESPONSE`. A Non-confirmable response or notification with one, which got no answer before, now
  gets a Reset too (RFC 7252 §4.3 MAY). Over UDP that Reset ends an observation on the server (RFC 7641
  §3.6); over TCP and WebSockets, which have no Reset, the client deregisters with Observe 1 instead
  (RFC 8323 §7.4), after a rejected registration response as well. A response that fails verification is
  still acknowledged and dropped, but no longer ends a Confirmable request's retransmission or frees a
  Non-confirmable request's NSTART slot (QB-2, coap-core-4).
- Publishing used the default coordinates `kcoap:<module>:unspecified` because they were read before the
  build set the group and version; artifacts now carry `kcoap.group` and `kcoap.version` from
  `gradle.properties` (`dev.kcoap:<module>:0.1.0-SNAPSHOT`) (WP-C1).
- kcoap-transport-ktor and the two WebSocket modules export the dependencies their public signatures use
  (`api`): kcoap-common for `LoggerFactory`, and in kcoap-transport-ktor also ktor-network for
  `SelectorManager`, so a caller can pass them without declaring those modules itself (WP-C1).
- EDHOC: a crypto backend that lacks a curve, KEM or algorithm no longer fails the handshake as if the peer
  had sent an invalid key or as "Processing message_2 failed"; the diagnostic names what is missing
  ("P_256 is not available in this crypto backend") (WP-A2).

### Security

- OSCORE: a `SequenceNumberStore.store()` that throws or is cancelled no longer lets the Sender
  Sequence Number advance past the durable limit, which could reuse nonces after a restart (RFC 8613
  Appendix B.1.1).
- OSCORE: a freshness challenge repeats its Echo value until a request returns it, so a replayed
  request can no longer revoke the value the client is about to send back (Appendix B.1.2).
- OSCORE and EDHOC: derivation copies of the Master Secret, Salt, PRK and exporter outputs are wiped,
  also when derivation fails.
