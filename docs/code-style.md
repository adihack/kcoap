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

## Documentation

- KDoc on every public declaration: what it is for, then anything surprising. Cite the RFC section
  the code implements (`RFC 8613 §5.2`), so a reviewer can check it against the text.
- Comments explain *why*, never restate the code. If code needs a comment to say *what* it does,
  rename or split it first.
- Every module has a README: purpose, what is implemented, what is verified by which vectors.

## Types and data

- Immutable by default. Types that hold a `ByteArray` copy it on the way in and out, and compare
  by content.
- `toString()` is for humans and logs: it never contains key material, and payloads only as lengths.
- Model protocol states as sealed types; illegal states should not be representable.
- Configuration uses classes with defaults and validation in `init`, plus a builder or DSL when there
  are many options.

## Errors

- `require(...)` for caller mistakes (invalid arguments), which throw `IllegalArgumentException`.
- One exception type per protocol for peer or wire problems (`CborException`, `OscoreException`,
  `EdhocException`), carrying what the protocol needs to answer: an error code, a CoAP response code.
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
| Changing or adding to an implementation | Decorator (`X by delegate`); implementations are final, never subclassed ([ADR 0002](adr/0002-delegation-not-subclassing.md)) | `BouncyCastleCryptoBackend` around a classical backend; test doubles around `DefaultCryptoBackend` |
| Variant behaviour | Strategy | EDHOC key exchange: Diffie-Hellman or KEM |
| User-defined resources | Template method on an abstract class | `CoapResource.handleGet()` |
| Notifications | `fun interface` callbacks or `Flow` | `LogSink`, Observe notifications |
| Cross-cutting processing | Interceptor chain | message tracing, metrics |
| Protocol progress | State machine over sealed states | EDHOC Initiator and Responder |

## Logging

- Take a `LoggerFactory` in constructors (default `LoggerFactory.Global`) and log under the tags in
  `LogTags`.
- Messages are lambdas: `log.debug { "..." }`.
- TRACE: bytes and state transitions. DEBUG: protocol events. INFO: lifecycle. WARN: rejected input.
  ERROR: failures the library could not handle.
- Never log keys, nonces derived from keys, or plaintext; use `log.payload(bytes)` for message bodies.

## Tests

- RFC test vectors first, byte for byte, in tests named after the RFC section they come from.
- Then every public behaviour, including every error path; aim for complete coverage. The build
  enforces a floor (`./gradlew koverVerify`).
- Common tests run on every target; JVM-only tests compare against independent implementations
  (Bouncy Castle, libcoap, libedhoc).
- Tests are documentation too: arrange, act, assert, and a name that states the expectation.

## Commits

Imperative subject line under 72 characters, a body that says why, and the co-author trailer.
