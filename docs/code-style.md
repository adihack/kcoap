# Code style and conventions

kcoap is security code that people will read to trust it. Every rule here serves one goal: a reader
who knows the RFC can follow the code without guessing.

## Layout

- One concept per file, named after its main type (`CborReader.kt`, `SecurityContext.kt`).
- One module per protocol or seam; packages are `dev.kcoap.<module>` (`dev.kcoap.oscore`).
- Core modules (protocol, wire and SPI code) are common code only: `commonMain`, no `expect`/`actual`,
  no platform imports. They apply the `kcoap.core` plugin, whose `verifyCommonOnly` check fails on any
  other main source set; test source sets such as `jvmTest` stay allowed. Runtime-specific code goes into
  an adapter module (`kcoap-crypto-*`, `kcoap-transport-*`) of the smallest target tier its library
  supports.
- Public API is deliberate: the build runs Kotlin's explicit API mode, so every public declaration
  states its visibility. Anything a user should not call is `internal` or `private`, or
  `@InternalKcoapApi` when another kcoap module needs it (below).
- Kotlin official style, 4 spaces, lines up to 120 characters.

## Dependencies and API

- **Every module declares what it imports.** A module that uses a type of another module lists that
  module itself, even when it already arrives through a third one.
- **`api` exactly when a dependency's types appear in a public or protected signature** of the module:
  parameters (defaulted ones included), return types, supertypes, type arguments and public inline
  bodies. Otherwise `implementation`. A consumer must be able to call everything public without adding
  a dependency of its own. kcoap-cbor and kcoap-crypto declare `api(kcoap-common)` ahead of their
  exceptions extending `KcoapException`.
- **The public ABI is committed.** Every published module has dumps of its public API under
  `<module>/api` (JVM class files and klibs), and `checkKotlinAbi` fails when the code no longer matches
  them. A change to the public API runs `./gradlew updateKotlinAbi` and commits the dump diff with it, so
  every API change is reviewed as such ([releasing.md](releasing.md#abi-dumps)).
- **Two opt-in markers in kcoap-common** say what is not stable API:
  - `@InternalKcoapApi`: plumbing that kcoap's modules share (`ByteArrayBuilder`,
    `minimalUnsignedBytes`, `readUnsigned`, `OptionCodec`). Public only because Kotlin has no visibility
    between the modules of one library. kcoap's modules opt in through the build; the ABI dumps leave it
    out; applications should not use it.
  - `@ExperimentalKcoapApi`: a published SPI that nothing implements yet (`DatagramSecurity`). Its shape
    may change with the first implementation, and the marker goes in the same change that adds that
    implementation and its interop test. kcoap code that uses one opts in explicitly.
- A module that is built and tested but not ready to ship (kcoap-dtls until its engine exists) applies
  `kcoap.unpublished` instead of carrying a marker on every type.

## Naming

- Full words, and the RFC's own terms: `senderSequenceNumber`, `replayWindow`, `transcriptHash2`.
  EDHOC's `PRK_2e` becomes `prk2e`, `TH_3` becomes `th3`.
- Booleans read as questions: `isCritical`, `hasPayload`.
- Factory functions describe what they make: `CoapMessage.request(...)`, `SecurityContext.derive(...)`.

### API vocabulary

The same concept has the same name in every module, so a named-argument call site reads the same everywhere
([target-architecture.md](target-architecture.md) §3.5):

| Concept | Name | Examples |
|---|---|---|
| A `LoggerFactory` parameter | `loggerFactory`, default `LoggerFactory.Global` | `CoapEndpointConfig.loggerFactory`, `EdhocServer(..., loggerFactory)` |
| A `CryptoBackend` parameter | `crypto` | `SecurityContext.derive(parameters, crypto, ...)`, `CipherSuite.isSupportedBy(crypto, method)` |
| Block sizes | `preferredBlockSize` on the client (it asks for one, RFC 7959 §2.4), `maxBlockSize` on the server (it caps them) | `CoapClientConfig`, `CoapServerConfig` |
| Body limits | `maxRequestBodySize` (the server assembles requests), `maxResponseBodySize` (the client assembles responses) | `CoapServerConfig`, `CoapClientConfig` |
| A lookup by a property | `from<Property>(x)`, null when nothing matches | `CoapMessageType.fromValue`, `CipherSuite.fromValue`, `CoseCurve.fromValue` |
| A conversion | `from(x)` | `CoapResponse.from(message)` |
| A value built from parts | `of(parts)` | `CoapCode.of(4, 4)` |
| Binary formats and transfer encodings | `decode` / `encode` | CBOR, CoAP messages, EDHOC messages, the OSCORE option, `SubjectPublicKeyInfo.decode` |
| Text grammars, and DER certificates by custom | `parse` | `CoapUri.parse`, `LinkFormat.parse`, `IpLiteral.parse`, `X509Certificate.parse` |
| Input a peer sent | `parseOrNull`, `fromOptionsOrNull`: null instead of the `IllegalArgumentException` of `parse` | `CoapUri.parseOrNull`, `LinkFormat.parseOrNull` |
| The configuration with every default | `Default` on the companion | `CoapClientConfig.Default`, `TlsClientConfig.Default` |
| A builder's DSL factory | a function named after the class | `CoapClientConfig { ... }`, `CoapEndpointConfig { ... }` |

## Documentation

- KDoc on every public declaration: what it is for, then anything surprising. Cite the RFC section
  the code implements (`RFC 8613 §5.2`), so a reviewer can check it against the text.
- Comments explain *why*, never restate the code. If code needs a comment to say *what* it does,
  rename or split it first.
- Every module has a README: purpose, what is implemented, what is verified by which vectors.

## Types and data

- Immutable by default.
- `toString()` is for humans and logs: it never contains key material, and payloads only as lengths.
- Model protocol states as sealed types; illegal states should not be representable.
- Configuration: a named-argument constructor with defaults and validation in `init`, the RFC cited in its
  messages. A configuration that is certain to grow gets a builder instead (`Builder`, `toBuilder()`, `Default`,
  a DSL factory named after the class), as `CoapEndpointConfig` and `CoapClientConfig` have; when any other
  configuration gains a parameter, its previous constructor stays as a `@Deprecated(level = HIDDEN)` overload
  ([ADR 0012](adr/0012-configuration-shapes.md)).
- Equality that is a protocol rule is written out, not generated: the `Endpoint` variants compare as RFC 7252 and
  RFC 8323 match peers, so they are final classes with their own `equals`, not data classes. Data classes are for
  shapes an RFC fixes (`CborValue`).

### Ownership of arrays, lists and keys

Who owns a `ByteArray`, a list or a key handle after a call is part of the API, and its KDoc says so when the rule
below does not:

- **Value types copy.** A type that holds a `ByteArray` copies it on the way in and out and compares by content;
  one that holds a list copies it with `toList()` before its `init` checks it (`CoapUri`, `WebLink`,
  `CoapEndpointConfig.interceptors`), so the caller cannot change it afterwards.
- **Transfer types hand over.** On the hot paths, a value that only carries bytes from one layer to the next does
  not copy: the receiver owns the arrays afterwards and the sender must not reuse them. Transport events and
  connections (`DatagramEvent`, `MessageConnection`; the rule is on `Transport.kt`), codec and parse results, the
  backend SPI's results (`KemEncapsulation`) and DTLS effects are such types, and their KDoc says it.
- **Secrets come from functions, public bytes from properties.** `SecurityContextParameters.masterSecret()` is a
  function, so every copy of a secret shows at its call site; `senderId` is a property. Derived keys and the Common
  IV never leave `SecurityContext`.
- **The owner wipes.** Whoever owns secret bytes overwrites them with `wipe()` once they are no longer needed, and
  releases a key handle with `PrivateKey.destroy()` (a KEM result with `KemEncapsulation.destroy()`), in a
  `finally` that also runs on failure and cancellation. `destroy()` must not throw, since it runs while a failed
  handshake cleans up. EDHOC's sealed states wipe what they hold on every transition.

## Errors

The error model is [ADR 0011](adr/0011-error-model.md); in code:

- `require(...)` and `check(...)` for mistakes of the caller only, which throw `IllegalArgumentException` and
  `IllegalStateException`. Input from a peer never does: a peer's URI, link-format document or DER goes through
  `parseOrNull` or a parser whose one exception is the protocol's.
- One exception type per facade for what goes wrong at runtime with the peer, the network, security or the
  lifecycle (`CoapException`, `OscoreException`, `EdhocException`, `CborException`, `CertificateFormatException`),
  carrying what the protocol needs to answer: a reason, an error code, a CoAP response code. It extends
  `KcoapException` (kcoap-common). An enum of reasons that may grow says so in its KDoc, and callers keep an `else`.
- The signals of the SPIs between the layers (`MessageSecurityException`, `CoapFormatException`,
  `SessionEndedException`, `AeadAuthenticationException`, `UnsupportedAlgorithmException`,
  `CertificateValidationException`) do not extend it, and each layer translates them at its boundary
  ([architecture.md](architecture.md#the-coap-endpoint-as-built), "Errors"). A layer that catches broadly lets a
  cancellation of its caller pass unchanged and reports any other exception of what it calls as its own.
- CoAP error responses (4.xx, 5.xx) are responses: a client returns them to its caller, never throws them. On the
  server a resource handler may throw `CoapResponseException` to answer with one from deep in its code; the server
  turns it into that response, so it never leaves the server and is no facade exception
  ([ADR 0011](adr/0011-error-model.md#amendment-wp-b4)).
- A public suspend entry point that can fail lists `@Throws(KcoapException::class, CancellationException::class)`,
  so Swift gets an `NSError` instead of a crash; an override keeps the `@Throws` of what it overrides, which
  Kotlin/Native requires.
- Never leak secret-dependent information through exception messages or timing; compare MACs and tags
  with `constantTimeEquals`.

## Concurrency and I/O

- Cryptography suspends (WebCrypto is asynchronous). Protocol engines are `suspend` and sans-IO:
  bytes and time in, bytes and timers out.
- No global mutable state, except the logging configuration, which is designed for it.
- Time and randomness are injected, so tests run on virtual time with fixed keys.
- Mutable protocol state belongs to one `Mutex` per component, never held across a suspension, and every
  flag that guards that state is read and written under that `Mutex`.
- Read-mostly registries use a copy-on-write `AtomicReference`. `@Volatile` is only for values read
  outside any lock where a stale read is harmless.
- Cleanup in `finally` or `catch` that suspends (taking a lock, settling a transmission, deregistering)
  runs in `withContext(NonCancellable)`: cancellation is how applications stop work, and it must leak
  nothing. Keep lock-guarded sections non-suspending.
- These rules, and how the CoAP endpoint follows them, are in
  [architecture.md](architecture.md#the-coap-endpoint-as-built) ("Concurrency rules").

## Extensibility patterns

Use the pattern that matches the need; do not add abstraction a second implementation will not use.

| Need | Pattern | Example |
|---|---|---|
| Replaceable subsystem | Interface (SPI) with a default implementation | `CryptoBackend`, `DatagramTransport` |
| Changing or adding to an implementation | Decorator (`X by delegate`); implementations are final, never subclassed ([ADR 0002](adr/0002-delegation-not-subclassing.md)) | `BouncyCastleCryptoBackend` around a classical backend; a key-store backend around a software one; test doubles around `DefaultCryptoBackend` |
| Variant behaviour | Strategy | EDHOC key exchange: Diffie-Hellman or KEM |
| User-defined resources | Template method on an abstract class | `CoapResource.handleGet()` |
| Notifications | `fun interface` callbacks or `Flow` | `LogSink`, Observe notifications |
| Cross-cutting processing | Interceptor chain | message tracing, metrics |
| Protocol progress | State machine over sealed states | EDHOC Initiator and Responder |

### Final implementations, delegation over subclassing

- **Implementations are `final`**: crypto backends, transport adapters, stores, the protocol facades. The SPI
  interfaces are the extension points, so an implementation can change without breaking anyone, and the ABI dumps
  show what is public ([ADR 0002](adr/0002-delegation-not-subclassing.md)).
- **Extend by delegation**: `class MyBackend(private val software: CryptoBackend) : CryptoBackend by software`,
  overriding only what changes. Composition happens in the application, by construction
  (`BouncyCastleCryptoBackend(classical = DefaultCryptoBackend())`), never through a registry or a dispatch chain.
- **A decorator that adds algorithms answers for them.** `X by delegate` forwards `supports(...)` to the delegate,
  so a decorator that adds or removes algorithms overrides the matching `supports` members, and its tests extend
  the conformance suite of kcoap-crypto-testsuite, which fails a decorator that forgets
  ([ADR 0001](adr/0001-crypto-capabilities.md)). A hardware or key-store decorator answers statically and never
  probes, since a probe could create a persistent key or prompt for a biometric.
- **Test doubles delegate too**: `object : CryptoBackend by DefaultCryptoBackend() { override ... }`, calling a named
  delegate where a subclass would have called `super`.
- Template methods are for application code that kcoap calls, such as `CoapResource.handleGet()`; abstract classes
  are not a way to extend kcoap's own implementations.

## Logging

- Take a `LoggerFactory` in constructors (default `LoggerFactory.Global`) and log under the tags in
  `LogTags`.
- Messages are lambdas: `log.debug { "..." }`.
- TRACE: bytes and state transitions. DEBUG: protocol events, and input from a peer that was rejected or ignored.
  INFO: lifecycle. WARN: local conditions an operator should act on. ERROR: failures the library could not
  handle. The table and the reasons are in [logging.md](logging.md#what-the-levels-follow).
- **Log or throw, never both.** A layer that throws an exception does not also log the failure: whoever catches it
  decides what it means and logs it, once. A protocol core or security layer may add, at DEBUG, the context its
  caller cannot see (the step, the Partial IV), and nothing above DEBUG.
- **Rejected input is logged once, at DEBUG, without the exception**: the layer that decides what happens to the
  message logs it, and reports it as an `EndpointEvent` where one exists, so it can be counted. A peer chooses how
  often that happens, and a UDP source proves nothing (RFC 7252 §11.4). An expected protocol exception
  (`MessageSecurityException`, `CoapFormatException`, `EdhocException`, `CoapResponseException`) is never attached
  to a record; a stack trace is for a fault on this side, at ERROR.
- Never log keys, nonces derived from keys, or plaintext; use `log.payload(bytes)` for message bodies.

## Tests

- RFC test vectors first, byte for byte, in tests named after the RFC section they come from.
- Then every public behaviour, including every error path; aim for complete coverage. The build
  enforces a floor (`./gradlew koverVerify`).
- Common tests run on every target; JVM-only tests compare against independent implementations
  (Bouncy Castle, libcoap, libedhoc).
- Tests are documentation too: arrange, act, assert, and a name that states the expectation.

## Commits

Imperative subject line under 72 characters, a body that says why, and the trailers last: the `Signed-off-by`
line of the [DCO](../DCO), which every commit needs, then any `Co-authored-by` lines
([CONTRIBUTING.md](../CONTRIBUTING.md#commit-messages)). One logical step per commit, which builds and passes its
tests.
