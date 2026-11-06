# 0004. No scheme or transport registry

Date: 2026-11-05. Status: accepted.

## Context

A CoAP endpoint has to send to `coap://`, `coap+tcp://`, `coap+ws://` and secure variants. A registry
that maps URI schemes to transport factories is a common design for this.

## Decision

- `Endpoint` is a sealed type (`Udp`, `Tcp`, `WebSocket`, `Opaque`, and `Secure` for DTLS sessions). The
  endpoint routes with an exhaustive `when (peer)`, so a new kind of peer is a compile error everywhere it
  matters.
- Connectors declare the schemes they serve (`Connector.schemes`); the endpoint picks the connector for a
  URI's scheme from the ones it was given.
- A peer whose transport is not configured fails with `CoapException(TRANSPORT_UNAVAILABLE)` naming the
  scheme.

## Consequences

- No global state and no string-keyed lookup; what an endpoint can reach is visible at its construction.
- Adding a transport kind means adding an `Endpoint` subtype, deliberately a library change.
