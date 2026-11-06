# 0013. Durable state is an SPI the application supplies

Date: 2026-11-06. Status: accepted. Settled by WP-A1 and WP-A3 of [target-architecture.md](../target-architecture.md)
(§3.4, §3.9; finding API-01); written down by WP-C3.

## Context

`SecurityContext.derive` defaulted to an in-memory sequence-number store. With a provisioned Master Secret, a device
that restarted counted from zero again and reused AEAD nonces (RFC 8613 Appendix B.1.1), and since the replay window
is not persisted either, it accepted replays (Appendix B.1.2). The secure choice took more typing than the unsafe
one. What makes a store durable differs per runtime: a file with fsync and an atomic rename on the JVM and Android,
DataStore, a file or the keychain on Apple platforms, IndexedDB with strict durability in a browser. The review
proposed per-target store modules in the core.

## Decision

- **No default store.** `SecurityContext.derive(parameters, crypto, sequenceStore, ...)` takes a
  `SequenceNumberStore` without a default, so every caller chooses. A sequence number is stored before it is used
  (RFC 8613 Appendix B.1.1). A store's `store()` is durable before
  it returns.
- **Freshness follows from the store.** With `FreshnessPolicy.AUTO`, a context whose store returned a value proves
  freshness with Echo before it processes a request (Appendix B.1.2), because a restored context has always lost its
  replay window; `REQUIRE_PROOF` and `ASSUME_FRESH` override it.
- **The in-memory store is an explicit choice**, named for what it is fit for: `EphemeralSequenceNumberStore`, for
  keys that are new in this process run, such as every EDHOC-derived context. `EdhocServer` and
  `EdhocClientHandshake` use it unless given a durable store.
- **Context storage is an SPI too.** `SecurityContextRepository` finds contexts and `MutableSecurityContextRepository`
  adds and removes them; `EdhocServer` adds one per handshake. `InMemoryContextRepository` has no bound, so a
  production server supplies one with a bound.
- **Recipes, not modules.** The kcoap-oscore README has a durable `SequenceNumberStore` per runtime (JVM and Android,
  Apple, Linux, browsers and Node.js); the core ships no store module. A store adapter, if one is ever wanted, is a
  thin optional module outside the core.

## Consequences

- Every OSCORE setup names its store, and a code review sees which contexts survive a restart.
- An application on a runtime kcoap has no recipe for writes its own store; the contract it must meet is in the
  `SequenceNumberStore` KDoc and the README.
- Replay windows, pending EDHOC sessions and DTLS session caches are not persisted; sharing them across nodes is
  open (checklist kmp-6, kmp-x3).
