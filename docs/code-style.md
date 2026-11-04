# Code style and conventions

kcoap is security code that people will read to trust it. Every rule here serves one goal: a reader
who knows the RFC can follow the code without guessing.

## Layout

- One concept per file, named after its main type (`CborReader.kt`, `SecurityContext.kt`).
- One module per protocol or seam; packages are `dev.kcoap.<module>` (`dev.kcoap.oscore`).
- Public API is deliberate: the build runs Kotlin's explicit API mode, so every public declaration
  states its visibility. Anything a user should not call is `internal` or `private`.
- Kotlin official style, 4 spaces, lines up to 120 characters.

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

## Extensibility patterns

Use the pattern that matches the need; do not add abstraction a second implementation will not use.

| Need | Pattern | Example |
|---|---|---|
| Replaceable subsystem | Interface (SPI) with a default implementation | `CryptoBackend`, `DatagramTransport` |
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
