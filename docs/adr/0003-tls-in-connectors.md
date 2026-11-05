# 0003. TLS lives in connectors and listeners; DTLS is a datagram decorator

Date: 2026-11-05. Status: accepted.

## Context

`coaps+tcp` and `coaps+ws` need TLS (RFC 8323 §9), and `coaps` needs DTLS (RFC 7252 §9). Every runtime has
its own TLS stack: JSSE on the JVM and Android, OpenSSL on Linux, the browser for `wss`, URLSession and
Network.framework on Apple platforms. A common `StreamSecurity` decorator would have to reimplement or
wrap all of them, and nothing used it.

## Decision

- TLS is the business of the connector or listener of each runtime: a `coaps+tcp` connector opens a TLS
  socket, a `coaps+ws` connector uses the HTTP client's `wss`. The `Connection` it returns is already
  secure and reports the peer's identity.
- `StreamSecurity` is removed.
- DTLS stays a `DatagramSecurity` decorator over any `DatagramTransport`, because kcoap ships its own
  sans-IO DTLS engine on `CryptoBackend` (the same engine on every platform). The SPI is marked
  experimental until the engine exists.

## Consequences

- Each runtime uses the TLS stack its platform trusts, with its trust store and its certificate handling.
- TLS support differs per runtime; docs/platforms.md lists what exists.
