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
  (QB-0). The Q-Block state machines (QB-1, ADR 0006) drive the server (QB-3, below), not yet the client.
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
  `AmplificationPolicy.Q_BLOCK_BURSTS` (the default; it acts only on a server with Q-Block on, QB-3),
  `ALL_RESPONSES` (also every Observe registration; a separate response to an address
  not yet verified goes Non-confirmable, so it is never retransmitted there; and each POST or PATCH keeps
  its own response for the repeat of that request, so it does not run twice, up to `maxConcurrentTransfers`
  of them) or `OFF`. `maxVerifiedAddresses` (10 000) bounds the verified addresses and, separately, the
  outstanding challenges. New `EndpointEvent.LimitReached.Limit` values: `AMPLIFICATION_FACTOR` (a
  response was held back), `VERIFIED_ADDRESSES`, `ADDRESS_CHALLENGES` and `HELD_BACK_RESPONSES` (an entry
  was evicted for room; for the last, that POST or PATCH runs again when it is repeated); an exhaustive
  `when` over `Limit` needs the four new branches. The
  configuration properties are new trailing constructor parameters with defaults, so source stays
  compatible; compiled callers of the constructor recompile. libcoap's `coap-client` repeats its request
  with the Echo value and completes, and its Q-Block probe of `/.well-known/core` is never challenged.
- Q-Block on the server (RFC 9177; QB-3), off by default: `CoapServerConfig.qBlock` takes a
  `QBlockServerConfig` with `isEnabled` (false), `withMessageSecurity` (false: OSCORE-protected requests keep
  getting 4.02 for Q-Block), `onReliableTransports` (true), `maxPartialBodies` (64), `maxCachedBodies` (64)
  and `maxBodiesPerPeer` (4). With it on, Q-Block1 uploads are assembled from Non-confirmable bursts with a
  2.31 per complete set and 4.08 lists of missing blocks (Content-Format 272) at once and on the
  NON_RECEIVE_TIMEOUT ladder, Confirmable ones block by block, and responses to requests carrying Q-Block1
  or Q-Block2 go out as Q-Block2 bodies: sets of MAX_PAYLOADS blocks NON_TIMEOUT_RANDOM apart, Continues and
  requests of missing blocks served from a cache, every block with an ETag of its own body (which replaces
  the resource's ETag) and Size2. A request of another method than GET or FETCH that carries a payload is
  always a new request whose handler runs, also with Q-Block2 `0/M=1`; only payload-less Continues, requests
  of missing blocks, and `0/M=1` with the cached body's Request-Tag are served from the cache. A body that
  gives way in the cache while it still sends finishes its sets, bodies done sending give way first, and a
  GET or FETCH body of one block is not cached. An upload holds only the blocks that arrived, whatever its
  Size1 claims, and any request body of one message beyond `maxBodySize` gets 4.13, Q-Block2 or not. On TCP
  and WebSockets blocks go back to back and the requests in between count as answered, so a Ping with
  Custody is not held up. Under the default `AmplificationPolicy.Q_BLOCK_BURSTS`, everything sent in answer
  to one request to an address not yet verified counts against the factor, later sets and the
  retransmissions of a Confirmable block included: a first burst beyond it gets a 4.01 with Echo instead,
  later sets beyond it stop, and a Continue is a request of its own. The server warns at start when Q-Block
  is on (RFC 9177 §11): without `security`, or for the requests without OSCORE that a server with `security`
  still answers unprotected. It serves an Observe registration that carries Q-Block2 without adding an
  observer until QB-6. libcoap's `coap-client -L 7` completes uploads and
  downloads against it over UDP, TCP and WebSockets. New public API besides the configuration:
  `EndpointEvent.QBlockRecovery` (missing blocks asked for, by either side), `EndpointEvent.QBlockFallback`
  (reported by the client, below), `EndpointEvent.PartialBodyDropped`, and the `EndpointEvent.LimitReached.Limit`
  values `PARTIAL_BODIES` and `BODIES_PER_PEER` (an upload refused with 5.03 and Max-Age, or a peer's oldest
  body dropped) and `CACHED_BODIES`; an exhaustive `when` over `EndpointEvent` or `Limit` needs the new
  branches. `qBlock` is a new trailing constructor parameter with a default, so source stays compatible;
  compiled callers of the `CoapServerConfig` constructor recompile.
- The Q-Block client (RFC 9177; QB-4), off by default: `CoapClientConfig.qBlock` takes a
  `QBlockClientConfig` whose `mode` is `QBlockMode.OFF` (the default; nothing changes), `PROBE` (each server
  is asked once, with the Confirmable `GET /.well-known/core` carrying Q-Block2 0/0/16 that libcoap sends,
  and the endpoint keeps the answer for `capabilityLifetime`, 1 hour, for all its clients) or
  `ASSUME_SUPPORTED`. With a server that
  supports it, bodies that need blocks go in Q-Block1 and responses come in Q-Block2, with 2.31, Continue,
  4.08 lists and requests for missing blocks recovering lost blocks without a round trip per block. A
  body follows its request's message type: use `nonConfirmable()` for the bursts of §7.2; Confirmable
  bodies go in lock-step. `CoapClientConfig.blockSize` and `maxResponseBodySize` apply. A NON upload holds
  at most four sets of unconfirmed requests (40 by default) and waits for the server's 2.31 or 4.08 beyond
  that, so a long body stays well below `maxExchangesPerPeer`. Requests with No-Response, Observe or block
  options of their own, observations, requests under message security and requests over TCP or WebSockets
  (unless `onReliableTransports`) keep RFC 7959; so does a request a server rejects with 4.02 or a Reset
  before answering anything else, which is sent again with RFC 7959. Requests under message security keep
  it unless `withMessageSecurity` (QB-5, below).
  `missingBlockMBit` asks for the missing tail of a set with one option. The client reports the Q-Block
  events of the server's entry above: `EndpointEvent.QBlockFallback` once per finding that a server does
  not take Q-Block, `EndpointEvent.QBlockRecovery` and `EndpointEvent.PartialBodyDropped` for its bodies.
  `qBlock` is a new trailing constructor parameter with a default, so source stays compatible; compiled
  callers of the constructor recompile. Tested against
  libcoap's `coap-server -L 7` (K1–K5, K7–K10, K12, with 300-block uploads over UDP and TCP) and soaked at
  10 % and 30 % loss.
- `kcoap-testkit`: `RebindingTransport`, a client whose source address changes at a chosen datagram, as
  behind a NAT that rebinds (QB-4).
- Q-Block under OSCORE (RFC 9177 with RFC 8613; QB-5), opt-in on both sides: with
  `QBlockClientConfig.withMessageSecurity` (it had no effect before) and `QBlockServerConfig.withMessageSecurity`
  on, protected bodies go in Q-Block1 and Q-Block2 with the options inside OSCORE (RFC 9177 Table 2), and the
  probe and the capability it finds are kept apart from unprotected requests. Off, the default on both sides,
  protected bodies keep Inner Block1 and Block2, and a client that opts in against a server that does not gets
  an inner 4.02 (or a Reset to a Non-confirmable request) and falls back to RFC 7959. `OscoreClient` takes the
  several responses of a body to a request that carries an Inner Q-Block1 or Q-Block2 option: at most one
  with the request's nonce, every other with a Partial IV new in a replay window of that request, of the
  context's `replayWindowSize` (32 by default); a replayed block is dropped and asked for again. This falls
  outside RFC 8613's single-response model (§7.4), which is why it is opt-in; it is tested kcoap against kcoap,
  at 10 % loss too. libcoap 4.3.5b protects, or verifies, only the first response to a request, so with
  `withMessageSecurity` its Q-Block2 bodies break in both roles (pinned as `upstream…` tests); Q-Block1 uploads
  to its server work. No API change.
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
- EDHOC server session bounds and events (WP-SEC1): `EdhocServer` takes `maxPendingSessionsPerPeer` (8),
  `pendingSessionLifetime` (MAX_TRANSMIT_WAIT, 93 s), `pendingSessionGracePeriod` (10 s), `timeSource`
  (`TimeSource.Monotonic`) and `eventListener`, which receives the new `EdhocServerEvent`s: `LimitReached`
  (with `Limit.PENDING_SESSIONS` or `PENDING_SESSIONS_PER_PEER`), `SessionEvicted`, `SessionExpired` and
  `ContinuationFromAnotherPeer`, through the new `EdhocServerEventListener`.
- `EdhocServer`, `EdhocClientHandshake` and `EdhocClientHandshake.over` take a `sequenceStore` and derive
  their OSCORE contexts with it, so a durable store can make those contexts resumable (WP-SEC1, a WP-A1
  follow-up). It is null by default, which gives each context its own `EphemeralSequenceNumberStore` as
  before. Context names repeat across servers: give each server its own durable store.
- `InMemoryContextRepository.addIfAbsent(context)` adds a context only when no context has its Recipient ID
  and ID Context, and says whether it did (WP-SEC1).

### Changed (breaking)

EDHOC server session safety (WP-SEC1):

- **`EdhocServer.handle(request, peer)` and `completeCombined(responderId, message3, peer)` take the peer.**
  A session now continues only for the peer that sent its message_1 (for DTLS, the same session in any
  epoch); a message_3, an error message or a combined request from another peer gets the answer for an
  unknown C_R and leaves the session waiting. `EdhocResource` and `CombinedRequestServer` pass the peer
  themselves. *Migration:* pass `RequestContext.peer`, or the `peer` of `unprotectRequest`, where you call
  these directly. A client whose address changes between message_1 and message_3 has to start again.
- **Beyond `maxPendingSessions`, the oldest session is evicted only once it has waited for message_3
  longer than `pendingSessionGracePeriod`** (10 s), and wiped (`EdhocServerEvent.SessionEvicted`). While
  every session is within its grace period, a new message_1 is refused, and so is one beyond the new
  per-peer bound: an EDHOC error ("Too many pending EDHOC sessions") in a 5.00 response, before any
  cryptography, reported as `EdhocServerEvent.LimitReached`. Pending sessions now also expire after
  `pendingSessionLifetime`. With the defaults, a flood of 64 message_1 per 10 s from spoofed addresses
  still keeps new clients out while it lasts, until an Echo check before message_2 lands.
  *Migration:* raise the bounds for gateways that start many handshakes from one address, give clients
  that answer message_2 slowly a longer grace period, and count `LimitReached` and `SessionEvicted` in
  production.
- **The `EdhocServer` constructor has new parameters between `maxPendingSessions` and `loggers`**
  (`maxPendingSessionsPerPeer`, `pendingSessionLifetime`, `pendingSessionGracePeriod`, `sequenceStore`,
  `timeSource`, before `listener`; `eventListener` after it). *Migration:* pass `listener` and `loggers` by
  name. `EdhocClientHandshake` has `sequenceStore` after `loggers`, so its trailing `exchange` lambda is
  unchanged.
- **A destroyed `EdhocSession` or `SoftwarePrivateKey` throws `IllegalStateException`** from `prkOut`,
  `prkExporter`, `exporter`, `oscoreMasterSecret`, `oscoreMasterSalt`, `keyUpdate` and `encoded` instead of
  returning, or deriving from, zeroed bytes, which are valid, publicly known keys. *Migration:* do not use a
  session or key after `destroy()`; tests that asserted the zeros assert the exception.
- **Every peer-authentication failure sends the same EDHOC error**, code 1 "Authentication failed": an
  untrusted credential sent by value, one that does not fit the cipher suite, a certificate chain the
  `CertificateValidator` rejects, and a Signature_or_MAC_2 or _3 that does not verify. The reason stays in
  `EdhocException.message` and the log; `EdhocException.error.diagnostic` is the generic text. Error code 3
  for an unknown 'kid' or 'x5t' is unchanged (RFC 9528 §6.4). *Migration:* read `message`, not
  `error.diagnostic`, for the local reason.

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
- OSCORE client (QB-5): a request takes one response (RFC 8613 §7.4). A second response, also a replay of
  the first, was accepted whenever the first carried a Partial IV, and the non-notification responses to an
  Observe registration were not checked for replay at all; now the second is rejected, also for a
  registration, which takes its notifications and one response without Observe, verified as a non-Observe
  response (§4.1.3.5.2).
- OSCORE and EDHOC: derivation copies of the Master Secret, Salt, PRK and exporter outputs are wiped,
  also when derivation fails.
- EDHOC server (WP-SEC1): two concurrent handshakes could get the same C_R (it was checked under the lock
  but recorded only after message_2), so both failed, and a C_R could equal the Recipient ID of a context
  being added (RFC 9668 §4.1.2). C_R is now reserved in the step that checks it and held until the context
  is in the repository.
- EDHOC server (WP-SEC1): any client could abort or consume another client's pending handshake by sending
  a message_3, an error message or a combined request with its C_R; such continuations are now ignored
  unless they come from the peer that sent message_1. Pending sessions expire, and dropped ones (expired,
  aborted by their client) have their ephemeral key and PRK_3e2m wiped (RFC 9528 §9.7, §9.8).
- EDHOC (WP-SEC1): the error sent for a failed peer authentication no longer tells a probing Initiator
  whether a credential it presents by value is trusted.
- EDHOC and kcoap-crypto (WP-SEC1): a destroyed `EdhocSession` handed out OSCORE secrets derived from
  zeros, and a destroyed `SoftwarePrivateKey` its zeroed bytes; both now fail closed, the key also when it
  is destroyed on another thread while `encoded()` copies it.
- EDHOC server (WP-SEC1): a context could replace another client's context in the repository when two
  servers shared it (two application profiles, RFC 9668 §5) or the application added one concurrently,
  because each server reserves C_R only among its own sessions. The context is now added only if its
  Recipient ID is free; otherwise the handshake fails with "Connection identifier in use".
- EDHOC (WP-SEC1): when composing message_2 failed or was cancelled (C_R equal to C_I, an EAD handler
  failing, a server handler cancelled), the Responder's ephemeral key Y and PRK_3e2m were left for the
  garbage collector; they are wiped now (RFC 9528 §9.8).
