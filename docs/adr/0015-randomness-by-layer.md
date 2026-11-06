# 0015. Randomness by layer

Date: 2026-11-06. Status: accepted. Settled by WP-B3 of [target-architecture.md](../target-architecture.md)
(§3.8; finding U8); written down by WP-C3.

## Context

The platform's CSPRNG was reached three ways: an internal `SecureRandom` object in kcoap-coap's token generator, built
on `kotlin.uuid.Uuid.random()`; cryptography-kotlin's random in `DefaultCryptoBackend`; and the abstract
`CryptoBackend.randomBytes`, which EDHOC and OSCORE draw from. The review proposed one public `SecureRandom` in
kcoap-common for all of them.

## Decision

- **Two sources, by layer.** The CoAP stack draws its secrets (default tokens, RFC 7252 §5.3.1; the Echo values of
  address verification, RFC 9175 §2.4) from `PlatformCsprng`, internal to kcoap-coap: a synchronous
  `kotlin.random.Random` on `Uuid.random()`, which the standard library backs with the platform's CSPRNG on every
  target. Plain CoAP therefore needs no crypto module. The security layers (OSCORE's Echo values, EDHOC's connection
  identifiers and ephemeral keys) draw from `CryptoBackend.randomBytes`, the injectable seam that known-answer tests
  and trace replay (RFC 9529) need.
- **What needs no secrecy is injected.** Retransmission jitter and the first Message ID come from
  `CoapEndpointConfig.random`, and tokens from its `tokenGenerator`, so tests replay them.
- **No public `SecureRandom` in kcoap-common.** It would make plain CoAP depend on a crypto-grade source the security
  layers must not use anyway, since they need theirs injectable.

## Consequences

- A custom `CryptoBackend` implements `randomBytes` itself, for instance with the bytes of `Uuid.random()` or with
  cryptography-kotlin's random, and a test double that needs determinism overrides it.
- docs/architecture.md ("Randomness") says which layer uses which source.
