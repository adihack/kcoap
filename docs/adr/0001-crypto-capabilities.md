# 0001. Crypto capabilities are asked, memoized and checked

Date: 2026-11-05. Status: accepted.

## Context

kcoap runs on the JVM, Android, Apple platforms, Linux and in browsers. Their cryptography differs: the
browser's WebCrypto has no ChaCha20-Poly1305 and no raw AES block, Apple's CryptoKit has post-quantum
algorithms only in recent releases, and a secure element may support only P-256. Protocol code used to
find a missing algorithm only when an operation threw, possibly in the middle of a handshake, and EDHOC
offered cipher suites the backend could not run.

## Decision

- `CryptoBackend` answers `supports(...)` for AEADs, key types, signatures, KEMs and hashes. The answers
  are suspend functions, because WebCrypto can only be probed asynchronously.
- Software backends probe once per instance and memoize the answer. Hardware and secure-element backends
  answer statically and are never probed, since a probe could create a persistent key or prompt for a
  biometric.
- The contract is checked, not trusted: `supports(x)` is false exactly when the operation would throw
  `UnsupportedAlgorithmException`, and the crypto conformance suite tests this for every backend.
- Protocols ask before they commit: EDHOC offers and selects only runnable suites
  (`CipherSuite.isSupportedBy(crypto, method)`), and a Responder that cannot run the selected suite
  answers with error code 2 and the suites it can run (RFC 9528 §6.3).

## Consequences

- One code path for every platform; no test or protocol asks which target it is on.
- A decorator that adds algorithms (Bouncy Castle, a future pure-Kotlin PQ backend) must also override
  the matching `supports` members. The conformance suite catches one that forgets.
- A synchronous capability set was rejected: it cannot be computed on WebCrypto without a suspension.
