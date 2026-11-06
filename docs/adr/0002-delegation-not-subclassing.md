# 0002. Backends and adapters are final; extension is by delegation

Date: 2026-11-05. Status: accepted.

## Context

`DefaultCryptoBackend` was open so that tests could override single operations, and that made its
internals (the cryptography-kotlin provider, key decoding) part of the public API. Subclasses of an
implementation break silently when the implementation changes.

## Decision

- Implementations (crypto backends, transport adapters, stores) are `final`. The SPI interfaces are the
  extension points.
- To change or add behaviour, wrap: `class MyBackend(d: CryptoBackend) : CryptoBackend by d { ... }`.
  Bouncy Castle's post-quantum backend already composes this way, and a key-store backend composes the
  same way (`KeyStoreBackend(software = BouncyCastleCryptoBackend(classical = DefaultCryptoBackend()))`).
- Third-party types (cryptography-kotlin, ktor) stay out of kcoap's public signatures unless the module
  exists to adapt them.

## Consequences

- Implementations can change without breaking users; the ABI check shows what is public.
- Tests that needed a subclass use a delegating test double instead.
- A wrapper must forward `supports` deliberately (ADR 0001).
