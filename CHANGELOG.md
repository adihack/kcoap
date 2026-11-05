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
  New public API:
  - `TransmissionParameters.maxPayloads` (10), `nonTimeout` (ACK_TIMEOUT), `nonReceiveTimeout`
    (2 × NON_TIMEOUT, at least NON_TIMEOUT × ACK_RANDOM_FACTOR + 1 s, which `init` enforces),
    `nonMaxRetransmit` (MAX_RETRANSMIT) and the derived `nonPartialTimeout` (247 s), RFC 9177 §7.2. They are
    new trailing constructor parameters with defaults, so source stays compatible; compiled callers of the
    constructor recompile.
  - `ContentFormat.MISSING_BLOCKS_CBOR_SEQ` (272, RFC 9177 §5).
  - `OptionSet.qBlock1`, `qBlock2` and `qBlock2List`, and `OptionSet.Builder.qBlock1()`, `qBlock2()` and
    `addQBlock2()`.

### Changed (breaking)

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

### Security

- OSCORE: a `SequenceNumberStore.store()` that throws or is cancelled no longer lets the Sender
  Sequence Number advance past the durable limit, which could reuse nonces after a restart (RFC 8613
  Appendix B.1.1).
- OSCORE: a freshness challenge repeats its Echo value until a request returns it, so a replayed
  request can no longer revoke the value the client is about to send back (Appendix B.1.2).
- OSCORE and EDHOC: derivation copies of the Master Secret, Salt, PRK and exporter outputs are wiped,
  also when derivation fails.
