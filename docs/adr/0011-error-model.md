# 0011. One exception type per facade, under KcoapException

Date: 2026-11-06. Status: accepted. Settled by WP-A2, WP-B3 and WP-D2 of
[target-architecture.md](../target-architecture.md) (§3.6; findings API-02, API-03, API-09); written down by WP-C3.

## Context

The review found eleven unrelated exception roots. To handle the failures of one `client.get`, a caller had to
catch `CoapException`, `MessageSecurityException`, `IllegalStateException` and `IllegalArgumentException`, and an
EDHOC client flow added three more. A closed endpoint showed up as `IllegalStateException` or as
`CoapException(CLOSED)` depending on timing, an unresolvable host and a malformed peer URI as
`IllegalArgumentException`, so nothing told "my bug" from "the network or the peer failed". On Apple platforms
Kotlin/Native turns an exception into an `NSError` only for a function that lists its type in `@Throws`; for any
other it ends the process, so a timeout crashed a Swift app. The review also proposed an EDHOC result type for peer
errors and Swift-shaped APIs (`Long` milliseconds, callbacks instead of `Flow`, `fun interface`s instead of suspend
lambdas).

## Decision

- **Each facade fails at runtime with one exception type**, which says what failed and carries what its protocol
  needs: `CoapException` with its `Reason` for `CoapClient`, `CoapEndpoint` and `CoapServer`; `OscoreException` with
  its `OscoreError`; `EdhocException`, with `EdhocPeerErrorException` and `EdhocTransferException` as subtypes;
  `CborException`, also for COSE; `CertificateFormatException` for X.509. Calls after `close()` fail with
  `CoapException(CLOSED)`, also when they race it.
- **All of them extend `KcoapException`** (kcoap-common), an open class with a protected constructor, so one
  `catch` covers kcoap. Applications do not subclass it.
- **`require` and `check` are for the caller's mistakes only**, as `IllegalArgumentException` and
  `IllegalStateException`. Peer input never produces them: peer URIs and link-format documents are read with
  `parseOrNull`, peer DER with parsers that throw only `CertificateFormatException`.
- **The signals of the SPIs between the layers stay outside the root** (`MessageSecurityException`,
  `CoapFormatException`, `SessionEndedException`, `AeadAuthenticationException`, `UnsupportedAlgorithmException`,
  `CertificateValidationException`), and each layer translates them at its boundary: a client-side protection
  failure becomes `CoapException(SECURITY)`, an ended session `SESSION_ENDED`, an unverified answer
  `UNVERIFIED_RESPONSE`, an AEAD failure an `OscoreException` or `EdhocException`, a missing algorithm during a
  handshake a local `EdhocException` and at setup an `IllegalArgumentException`. docs/architecture.md has the table.
- **4.xx and 5.xx responses are responses**, returned, not thrown.
- **`@Throws(KcoapException::class, CancellationException::class)`** on every public suspend entry point that can
  fail: the `CoapClient` requests, `ping` and `discover`, `CoapEndpoint.ping`, the EDHOC steps, `run` and
  `runCombined`, `EdhocServer.completeCombined`, `PeerSecurity.install`, `SecurityContext.derive`,
  `EdhocSession.oscoreContext`. The four operations of the message-security SPI list `MessageSecurityException`,
  since Kotlin/Native requires an override to keep the `@Throws` of what it overrides. `CoapUri.parse` lists
  `IllegalArgumentException`, because URIs are typed in.
- **EDHOC errors are aborts**, as RFC 9528 models them: a peer's error is an `EdhocPeerErrorException` whose
  `retryWith` names the suite to retry with, not a result variant or a typed-state API.
- **The common API stays Kotlin-first**: suspend functions, `Flow`, `Duration`, named and default arguments. Swift
  gets `NSError`s through `@Throws` now, and a curated facade later (roadmap E4, kcoap-apple), not `Long`
  milliseconds or callback forms in common code.

## Consequences

- A caller catches `KcoapException` for everything that can go wrong at runtime and lets a mistake of its own
  surface. `CoapException.Reason` and `OscoreError` may gain constants in any minor release, so a `when` over them
  keeps an `else` branch.
- From Swift, a caller's mistake still ends the process, as a failed precondition does; the KDoc tells Swift callers
  to parse a typed-in URI first. `SecurityContext.derive`, called directly, passes the store's and the backend's
  exceptions on unchanged, so a Swift caller gives it a store that does not throw; the EDHOC handshake translates
  them.
- `ErrorModelTest` (kcoap-edhoc-oscore) asserts that every facade's type is a `KcoapException` and no SPI signal
  is; `ThrowsDeclarationsTest` reads every `@Throws` list back from the JVM class files; kcoap-swift-smoke catches a
  timeout and a malformed URI from Swift in the macOS CI job.

## Amendment (WP-B4)

WP-B4 added `CoapResponseException` (`dev.kcoap.coap.server`), which a resource handler throws with a 4.xx or 5.xx
`CoapResponse` to answer its request from however deep in the application's code. It does not contradict "4.xx and
5.xx responses are responses": the server catches it and sends the response it carries, so it never reaches a
caller of kcoap, and a client still returns error responses. It is a control-flow signal inside the server, neither
a facade's runtime failure nor a signal of an SPI between the layers, so it extends `Exception` rather than
`KcoapException`: a `catch (e: KcoapException)` around a client call must not be expected to see it, and
`ErrorModelTest`'s two lists leave it out.
