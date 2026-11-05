# Changelog

All notable changes to kcoap. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/). Before 1.0, a minor version may break the
API; every breaking change is listed here with a migration note.

## Unreleased

Nothing has been released yet. The API is still changing as the refactoring plan in
[docs/target-architecture.md](docs/target-architecture.md) lands; each work package that breaks the API
adds its entry and migration note below.

### Added

- kcoap is licensed under the [Apache License 2.0](LICENSE), with a [NOTICE](NOTICE) and the third-party
  material listed in [third-party material](docs/third-party.md). Copyright 2026 Adrian Sołtys and the
  kcoap contributors.
- Contributions follow the [Developer Certificate of Origin 1.1](DCO), with no CLA: every commit carries a
  `Signed-off-by` line matching its author, which a check verifies on every pull request.
- A [trademark policy](TRADEMARKS.md) for kcoap and kcoap.dev, and the community files
  [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), [SECURITY.md](SECURITY.md),
  [SUPPORT.md](SUPPORT.md), [GOVERNANCE.md](GOVERNANCE.md) and [SPONSORS.md](SPONSORS.md).
- Every POM names the license (`kcoap.license.name`, `kcoap.license.url`), the website https://kcoap.dev,
  the maintainer, GitHub Issues and the SCM; the API reference carries the copyright and trademark footer.
- A [documentation hub](docs/README.md), the [versioning and API stability policy](docs/versioning.md), and
  a redesigned README with the kcoap logo.
- Storage and routing for EDHOC-derived contexts (WP-A3): `MutableSecurityContextRepository` (kcoap-oscore), a
  `SecurityContextRepository` with `add`, `addIfAbsent` and `remove` (of that very context only), which
  `InMemoryContextRepository` implements and `EdhocServer` takes, so a server can keep its contexts in a bounded
  or shared store;
  `PeerSecurity.install(client, uri, security)`, which resolves the server's URI with the client's own
  resolver and returns the endpoint it installed for; `CombinedHandshake`, the result of `runCombined()`.
- Access control on the server (WP-SEC2): `AccessPolicy` with `Public`, `MessageSecurityRequired` and
  `Authenticated`, `AccessRequest`, `CoapResource.access`, `ResourceBuilder.access`,
  `CoapServerConfig.defaultAccess`, `LinkFormat.OSCORE` (`osc`, RFC 8613 §9). Verification on the client:
  `ResponseVerification`, `CoapException.Reason.UNVERIFIED_RESPONSE` and `CoapException.response`. Who a
  request comes from: `SecurityContext.identity` and the `identity` parameter of `SecurityContext.derive`,
  `EdhocIdentity`. Plaintext by choice: `allowUnprotected` on `OscoreClient` and `PeerSecurity`.

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
- Observe with Q-Block (RFC 9177 §4.5; QB-6), where both sides have Q-Block on: an observation registers
  with Q-Block2, or uploads a FETCH body that needs blocks in Q-Block1, and every notification is a Q-Block2
  body with an ETag of its own and the Observe value on every block, on the registration's token (for a
  Q-Block1 registration the token of the block that completed it, Figure 14). `CoapClient.observe` emits each
  body once, whole; bodies are ordered by their Observe value (RFC 7641 §3.4), and late blocks of a body
  that was delivered, given up or superseded are ignored. The server keeps a body the one outstanding
  notification until every block went out (and, Non-confirmable, for at least
  `nonConfirmableNotificationInterval`, with one NSTART slot towards the client), folds changes meanwhile
  into the next body, so a resource that changes faster than its bodies go out still delivers whole ones,
  and removes the observer on a Reset to any block that carries Observe or an unacknowledged Confirmable
  one; a Reset to an answer for missing blocks only stops that answer. A FETCH registration uploaded in
  Q-Block1 whose 2.05 was lost gets it again on the token its notifications use when the client sends the
  last block again (§4.3). The client releases the tokens of a body's Continues and requests for missing
  blocks with the body, and a fresher notification of one message supersedes a body still being assembled.
  A cancelled observation registered with Q-Block1 forgets its token instead of deregistering, since its body
  sent again would need a new Request-Tag. Behaviour changes for those who turned Q-Block on: a server with
  `QBlockServerConfig.isEnabled` now adds an observer for a registration carrying Q-Block2, which QB-3
  served as a plain request, and a client with `QBlockClientConfig.mode` other than `OFF` now observes with
  Q-Block, where QB-4 kept observations on RFC 7959. Under the default `AmplificationPolicy.Q_BLOCK_BURSTS`
  (and `ALL_RESPONSES`), such a registration from a client that neither OSCORE nor DTLS authenticated now
  gets a 4.01 with Echo first, whatever the size of its first response, since its notification bodies are
  bursts no request asks for (RFC 7641 §7, RFC 9175 §2.4); a FETCH uploaded in Q-Block1 gets it for each
  block until one carries the Echo value. Once the verification lapses (`verifiedAddressLifetime`), its
  notification bodies go Confirmable. An observation under message security takes Q-Block only with
  `QBlockClientConfig.withMessageSecurity` on, like other protected requests (QB-5 and QB-7, below). No API
  change. Tested end to end on Figures 7 to 15
  (`QBlockObserveTest`) and against libcoap both ways (K6, L6). The kcoap-to-kcoap soak of the plan
  (`QBlockTest`: 20 seeds, 10 % and 30 % loss, 3000 B and 20 KiB, PUT, GET and POST) and bodies of 320 blocks
  and notifications over connections (`QBlockReliableTest`) came with it.
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
  context's `replayWindowSize` (32 by default); a replayed block is dropped and asked for again. To such a
  request it passes on only the unprotected errors that end the body (4.00, 4.01 and 5.xx, without Echo or a
  block option) and drops every other unprotected response, so a 4.08, 4.13, 4.02, Echo challenge or block
  from outside OSCORE cannot steer a protected body or turn Q-Block off for the peer (RFC 9177 §11); a server
  without OSCORE answers with such a 4.02, so the request ends as a timeout. Other requests still get every
  unprotected 4.xx and 5.xx. (This rule for unprotected responses is superseded by WP-SEC2, ADR 0008, below:
  `OscoreClient` hands every unprotected 4.xx and 5.xx on as unverified, the stack acts on none of them, and
  such a 4.02 ends the request at once with `UNVERIFIED_RESPONSE`.) This falls outside RFC 8613's
  single-response model (§7.4), which is why it is opt-in; it is tested kcoap against kcoap, at 10 % loss
  too. libcoap 4.3.5b protects, or verifies, only the first response to a request, so with
  `withMessageSecurity` its Q-Block2 bodies break in both roles (pinned as `upstream…` tests); Q-Block1
  uploads to its server work. No API change.
- Protected observations with Q-Block notification bodies (RFC 9177 §4.5 under RFC 8613; QB-7): with
  `withMessageSecurity` on both sides an observation now registers with Inner Q-Block2 and takes its
  notifications as Q-Block2 bodies inside OSCORE, where before it kept Inner Block2. Every block of such a body
  is a notification with a Partial IV of its own, so `OscoreClient` checks the notifications of a registration
  that carries Inner Q-Block against a replay window of that registration (the context's `replayWindowSize`)
  instead of taking only Partial IVs above the Notification Number: RFC 8613 §7.4.1 requires only that a
  Partial IV received before is refused. Freshness stays by Partial IV (§4.1.3.5.2), per body: a notification
  below the Notification Number passes only as a block of the body (its ETag) that holds the highest Partial
  IV, so replayed blocks, blocks of an older body and older notifications of one message are still dropped.
  A notification that verifies counts as received even when it is dropped as stale, so it is refused if it
  comes again after its body became the freshest (§7.4.1). Registrations without Inner Q-Block keep the
  strict order. A client with message security whose resolver
  leaves a Q-Block registration unprotected orders its bodies by Observe value, as it does other unprotected
  observations. At 10 % loss with 20 to 50 ms of latency every body arrives whole, where the strict rule
  dropped about 130 blocks per run. No API change.
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
- The WebSocket client's tests run against real servers on every runtime it serves (WP-W1): on Node.js
  against an npm `ws` server that records the subprotocols offered, natively against the listener (on
  macOS and the iOS simulator through the Darwin engine), and in headless Chrome against a JVM listener,
  a new CI job (`scripts/test browser`). Each checks that `coap` is requested and read back from the
  negotiated protocol (RFC 8323 §4.1).
- Android from API level 26 (Android 8.0) on, through the jvm artifacts (PL-1). `checkAndroidApi`, part of
  `check` and `scripts/test unit`, runs animal-sniffer over every jvm jar against the API 26 signature and
  found nothing in kcoap's code. `checkAndroidApiDependencies`, also part of both, runs it over the
  libraries behind them and fails on any use that `gradle/android-api-dependencies.baseline` does not
  list; the listed ones are triaged in `samples/android-consumer`. That sample, an app at minSdk 26 that
  takes kcoap from Maven, is built by a new CI job with AGP, R8 on its release variants, lint and its unit
  tests. Apps need Kotlin 2.3 or newer and Java 17 bytecode, but no R8 rules of their own and no core
  library desugaring.
- `kcoap-crypto-default`'s jvm jar carries R8 and ProGuard rules (`META-INF/proguard`), which AGP applies
  on its own: without them R8 stops on missing classes when it shrinks an app that does not use
  `kcoap-crypto-bc`, the Bouncy Castle classes that cryptography-kotlin's JDK provider names and loads
  only when present (PL-1).

### Changed (breaking)

The EDHOC-OSCORE glue on SPIs and the application model (WP-A3):

- **`EdhocServer.handle(payload, peer)` takes the request body and returns a `CoapResponse`** instead of
  taking and returning a `CoapMessage`; `EdhocResource` passes `RequestContext.payload`. The `EdhocOverCoap`
  builders speak the same model: `message1Request(resource, message1)` and
  `continuationRequest(resource, responderId, message)` return a `CoapRequest` to the EDHOC resource's URI,
  `messageResponse(message)`, `completedResponse()` and `errorResponse(errorMessage, isServerError)` a
  `CoapResponse`, none of them taking a request any more. *Migration:* call `edhoc.handle(context.payload,
  context.peer)` in a resource of your own, and `edhoc.handle(request.payload, peer)` in a test double.
- **`EdhocClientHandshake` sends through `suspend (CoapRequest) -> CoapResponse`** and takes the `server`
  URI as its third parameter: `EdhocClientHandshake(config, crypto, server, path, loggerFactory,
  sequenceStore, exchange)`. The handshake posts to `path` at the scheme, host and port of `server`.
  `over(client, server, ...)` passes `client::request`. *Migration:* pass the server's URI, and for a carrier
  of your own return `CoapResponse` for a `CoapRequest`: `{ client.request(it) }`, or in tests
  `{ edhocServer.handle(it.payload, peer) }`.
- **`EdhocServer` takes `contexts: MutableSecurityContextRepository`** instead of the final
  `InMemoryContextRepository`. Contexts from EDHOC accumulate there, one per handshake. *Migration:* none for
  `InMemoryContextRepository`; a production server supplies a repository with a bound, since an evicted client
  only runs EDHOC again, on OSCORE's 4.01 "Security context not found" or, once its old C_R went to another
  client, 4.00 "Decryption failed".
- **`CombinedRequestServer(edhoc, oscore)` takes any `ServerMessageSecurity`** as `oscore`, not only
  `OscoreServer`, and answers a rebuilt combined request that layer does not take as protected (null) with
  4.00. *Migration:* none; compiled callers recompile.
- **`runCombined()` returns `CombinedHandshake(session, security)`** instead of
  `Pair<EdhocOscoreSession, CombinedRequestSecurity>`. *Migration:* destructuring (`val (session, security) =`)
  is unchanged; replace `.first` and `.second` with `.session` and `.security`.
- **`EdhocTransferException` is an `EdhocException`** (error code 1, `errorMessage` null: nothing to send
  back), and its `response` is a `CoapResponse`. *Migration:* a `catch (e: EdhocException)` now also catches
  it, so catch `EdhocTransferException` first where you handle it apart.
- **`EdhocOverCoap.parseRequest` throws `EdhocException`** for a malformed payload instead of
  `IllegalArgumentException`: what the peer sent is an EDHOC failure (code 1, "Malformed EDHOC request" on the
  wire, the detail in the exception message), and `EdhocServer.handle` no longer catches
  `IllegalArgumentException`. The detail names the type of an offending CBOR item instead of quoting it, so
  the peer's text never reaches a log, and the server logs the payload as its length (docs/logging.md).
  *Migration:* catch `EdhocException`, and answer with its `errorMessage`.
- **`InMemoryContextRepository.remove(context)` removes that very context only, and returns whether it did**
  (`MutableSecurityContextRepository.remove`). It removed whatever context was held under the Recipient ID and
  ID Context of `context`, so a late removal, such as an expiry timer, could take out the context of another
  client that had the same Recipient ID since. *Migration:* remove the context you added or found; to clear a
  pair whatever it holds, `find` its context first and remove that.
- **`LoggerFactory` parameters are named `loggerFactory`** in `EdhocServer`, `EdhocClientHandshake` and
  `EdhocClientHandshake.over`. *Migration:* rename the named argument `loggers =` to `loggerFactory =`.
- `EdhocOscoreSession`, `EdhocSessionListener` and `EdhocTransferException` moved to files of their own, and
  `SecurityContextRepository` and `InMemoryContextRepository` out of `OscoreEndpoints.kt`, in the same
  packages. *Migration:* none: they are classes, so their names stay the same from Kotlin and Java.

Fail-closed message security (WP-SEC2, [ADR 0008](docs/adr/0008-fail-closed-message-security.md)):

- **A server with message security refuses unprotected requests by default.** Resources have an
  `AccessPolicy` (`CoapResource.access`, `access = ...` in the DSL; null inherits from the parent), and
  `CoapServerConfig.defaultAccess` governs the rest: `AccessPolicy.MessageSecurityRequired` when `security`
  is set, `AccessPolicy.Public` otherwise. The server asks the policy right after the resource lookup: a
  refused request gets 4.01, or 4.03 when message security authenticated it, and reaches no handler, buffers
  no Block1 or Q-Block1 body, registers or cancels no observation and gets no burst. A path that does not
  exist is refused like its nearest existing ancestor instead of getting 4.04. `/.well-known/core` and
  `EdhocResource` are `Public`; `/.well-known/core` lists only what the caller may use, with `osc` on
  OSCORE-only resources (RFC 8613 §9). *Migration:* mark resources meant for everyone
  `access = AccessPolicy.Public`, or set `defaultAccess = AccessPolicy.Public` to keep the old behaviour, or
  `AccessPolicy.Authenticated` to also take DTLS-authenticated clients; replace hand-written
  `if (securityIdentity is OscoreIdentity)` checks with the policy. Compiled callers of the
  `CoapServerConfig` constructor recompile.
- **Observe registrations belong to the message-security identity that made them**: a request of another
  identity, or of none, neither replaces nor cancels a registration (RFC 7641 §3.6, §4.1), and a registration
  of another identity on the same endpoint and token is answered as a plain GET, without Observe, since §4.1
  allows one entry per endpoint and token. *Migration:* none for clients that deregister the way they
  registered.
- **`ProtectedRequest.unprotectResponse` returns `ResponseVerification`** (`Verified(message)` or
  `Unverified(message, reason)`) instead of a `CoapMessage`, and still throws `MessageSecurityException` for a
  response to drop. *Migration:* only implementations of the SPI change: wrap a verified response in
  `ResponseVerification.Verified`, and return an unprotected error you used to pass on as `Unverified`.
- **The client acts only on verified responses to protected requests.** An unprotected error to a protected
  request (`ResponseVerification.Unverified`) is no longer returned as a response: the request fails with
  `CoapException(Reason.UNVERIFIED_RESPONSE)`, whose new `response` property holds it, and it is never
  repeated for an Echo option, continued block-wise or taken as a Q-Block answer; an established observation
  ignores it, and drops a notification whose Block2 or Q-Block2 follow-up got only such an answer instead of
  ending. On TCP and WebSockets a registration that ends this way is followed by a deregistration
  (RFC 8323 §7.4), as after a rejected registration response. `OscoreClient` hands every unprotected 4.xx
  and 5.xx on that way, also to requests carrying Inner Q-Block, so an unprotected 4.02 from a server without
  OSCORE ends such a request at once instead of in a timeout. *Migration:* where you read an unprotected 4.01
  such as "Security context not found" from `response.code`, catch `CoapException` with
  `UNVERIFIED_RESPONSE` and read `e.response`; run EDHOC again rather than repeating a request that is not
  idempotent, which may already have run.
- **`OscoreClient` and `PeerSecurity` never send plaintext silently.** A request whose `ClientContextResolver`
  returns null, or to a server for which `PeerSecurity` has no protection installed (also after `remove`),
  fails with `MessageSecurityException` before anything is sent; `PeerSecurity`'s `unprotectedPaths` (the
  EDHOC resource) still go unprotected. A Q-Block probe (`QBlockMode.PROBE` with `withMessageSecurity`) that
  the security layer refuses to send leaves Q-Block unknown for that request only, so EDHOC runs on such a
  client and the first request after `install` probes. *Migration:* pass `allowUnprotected = true` to
  `OscoreClient` or `PeerSecurity` where plain CoAP to some servers is intended, and install protections
  before requesting; discover the EDHOC resource before the handshake with a plain client, or add
  `/.well-known/core` to `PeerSecurity`'s `unprotectedPaths`.
- **Requests of a context that EDHOC established carry an `EdhocIdentity`**, with the peer credential EDHOC
  authenticated and the context's `OscoreIdentity`, instead of the bare `OscoreIdentity`
  (`SecurityContext.identity`, which `OscoreServer` reports). *Migration:* authorize on
  `(securityIdentity as? EdhocIdentity)?.peerCredential`, or on `.oscore` where the Recipient ID is what you
  used; a provisioned context may name its peer with `SecurityContext.derive(..., identity = ...)`.
- **Binary signatures changed**, so compiled callers recompile: the `CoapException` constructor (a fourth
  parameter, `response`), `OscoreClient(ClientContextResolver, LoggerFactory)` and `PeerSecurity(Set)` (a new
  `allowUnprotected` parameter each), `SecurityContext.Companion.derive` (a new `identity` parameter) and the
  `CoapServerConfig` constructor (`defaultAccess`). Source stays compatible for all of them, as the new
  parameters have defaults, except that an exhaustive `when` over `CoapException.Reason` needs a branch for
  `UNVERIFIED_RESPONSE`.

WebSocket transports (WP-W1):

- **`KtorWebSocketListener.start` takes the ktor server engine first, with no default**, and
  `kcoap-transport-ktor-websocket-server` no longer depends on `ktor-server-cio`, so an application gets
  only the engine it chooses. *Migration:* add `io.ktor:ktor-server-cio` (or your engine) to your
  dependencies and write `KtorWebSocketListener.start(CIO, host, port)` for `start(host, port)`, with
  `io.ktor.server.cio.CIO`. Mounting with `coapOverWebSockets(listener)` is unchanged.
- **The WebSocket client runs on the Darwin engine on iOS and macOS**: `KtorWebSocketConnector()`'s
  default client uses URLSession, which brings up the cellular radio and honours VPN On Demand; CIO's BSD
  sockets did neither. `kcoap-transport-ktor-websocket` depends on `ktor-client-darwin` on Apple, and on
  `ktor-client-cio` only on the JVM and Linux. The default client names its engine on every platform
  (Darwin, CIO, or Js on the web), so a second ktor engine in the application no longer decides it.
  *Migration:* none for the connector; an Apple app that used ktor-client-cio through this module adds
  it itself, and passes its own `HttpClient` to keep CIO.

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
- A connection's reader delivered every request of a burst before any of their handlers ran on a
  dispatcher of one thread, so a peer that writes many requests back to back, as kcoap's Q-Block client does
  with the blocks of a body on TCP (RFC 9177 Appendix B), ran the endpoint into `maxConcurrentRequests` and
  got 5.03 for a block of a 320-block upload. The reader now yields after each read (QB-6).
- `OscoreClient` passed on a registration's answer without a Partial IV that arrived after a later
  notification, and the stack, which leaves the order of protected notifications to OSCORE, emitted the
  older state last. That notification counts as the oldest (RFC 8613 §4.1.3.5.2) and is now dropped once a
  notification with a Partial IV was taken (QB-7).
- Publishing used the default coordinates `kcoap:<module>:unspecified` because they were read before the
  build set the group and version; artifacts now carry `kcoap.group` and `kcoap.version` from
  `gradle.properties` (`dev.kcoap:<module>:0.1.0-SNAPSHOT`) (WP-C1).
- kcoap-transport-ktor and the two WebSocket modules export the dependencies their public signatures use
  (`api`): kcoap-common for `LoggerFactory`, and in kcoap-transport-ktor also ktor-network for
  `SelectorManager`, so a caller can pass them without declaring those modules itself (WP-C1).
- EDHOC: a crypto backend that lacks a curve, KEM or algorithm no longer fails the handshake as if the peer
  had sent an invalid key or as "Processing message_2 failed"; the diagnostic names what is missing
  ("P_256 is not available in this crypto backend") (WP-A2).
- The JVM and Android `SequenceNumberStore` recipe in `kcoap-oscore/README.md` reads its file with
  `Files.readAllBytes`: it called `Files.readString`, which Android has only from API level 33, so on
  API 26 to 32 every OSCORE context derived with it failed (PL-1).

### Security

- EDHOC server (WP-A3): when adding the OSCORE context after message_3, the sequence number store or the
  `EdhocSessionListener` failed or was cancelled, the context could stay in the repository although the client
  was told the handshake failed, PRK_4e3m, PRK_out and PRK_exporter were left for the garbage collector, and
  the client got the CoAP server's plain 5.00. A session is now established all or nothing: the context is
  removed again, the session wiped (RFC 9528 §9.8), and the client gets "EDHOC failed" in a 5.00 with
  Content-Format application/edhoc+cbor-seq (RFC 9528 Appendix A.2.3), also for the combined request; message_4
  is composed before the context is added.
- EDHOC client (WP-A3): a handshake that failed or was cancelled left its secrets for the garbage collector:
  the ephemeral key X when the request for message_2 failed or was answered with a 4.04, PRK_4e3m and the
  session's PRK_out and PRK_exporter when the server rejected message_3 or the context could not be derived.
  `EdhocClientHandshake` now aborts its Initiator and destroys the session on any failure (RFC 9528 §9.8).
- CoAP server (WP-SEC2): configuring message security protected no resource by itself. Unprotected
  requests reached every handler that did not check `securityIdentity`, and before any handler ran they
  could buffer request bodies, register, replace or cancel observations of authenticated clients, get Q-Block2
  bursts and list every resource. The default access policy of such a server now refuses them before any
  work (ADR 0008).
- CoAP client (WP-SEC2): anyone who saw a token could answer a protected request with an unprotected 4.01
  and Echo, and the client sent the request again under a fresh Partial IV, so a POST could run twice
  although OSCORE's replay protection never failed (RFC 8613 §8.2 errors are not authenticated). The client
  now acts only on verified responses, and reports an unverified one as `UNVERIFIED_RESPONSE`.
- OSCORE client and `PeerSecurity` (WP-SEC2): a missing context or protection no longer sends a request in
  plaintext.
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
