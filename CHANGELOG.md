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

### Security

- OSCORE: a `SequenceNumberStore.store()` that throws or is cancelled no longer lets the Sender
  Sequence Number advance past the durable limit, which could reuse nonces after a restart (RFC 8613
  Appendix B.1.1).
- OSCORE: a freshness challenge repeats its Echo value until a request returns it, so a replayed
  request can no longer revoke the value the client is about to send back (Appendix B.1.2).
- OSCORE and EDHOC: derivation copies of the Master Secret, Salt, PRK and exporter outputs are wiped,
  also when derivation fails.
