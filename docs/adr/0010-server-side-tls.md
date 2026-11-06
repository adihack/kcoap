# 0010. Server-side TLS: termination at a reverse proxy now, a JVM SSLEngine listener later

Date: 2026-11-06. Status: accepted.

## Context

`coaps+tcp` and `coaps+ws` are CoAP over TLS (RFC 8323 §8.2, §8.4, §9). [ADR 0003](0003-tls-in-connectors.md) put
TLS in each runtime's connectors and listeners. WP-T1 ([target-architecture.md](../target-architecture.md), critique
D: server and gateway TLS) built the client side and asks for a decision on the server side: a JVM SSLEngine
listener, or termination at a reverse proxy, which the plan recommends for native gateways.

What kcoap's dependencies offer, checked in the ktor 3.6.1 artifacts and by tests:

- **ktor-network-tls is a client only.** Its JVM artifact has a TLS 1.2 client handshake (`TLSClientHandshake`) and
  nothing that answers one; on Linux native and Apple platforms its handshake is a stub that throws "TLS sessions are
  not supported on Native platform." (`NativeTlsUnavailableTest`, `LinuxSecureWebSocketTest`). The client side,
  `KtorTlsConnector`, therefore exists on the JVM only and offers no ALPN, which ktor cannot send.
- **ktor's CIO server engine has no TLS.** The 3.6.1 artifact has no TLS connector, so `KtorWebSocketListener` on
  CIO, the engine kcoap's tests use, serves `ws` only. ktor documents TLS connectors (`sslConnector`) for its Netty
  and Jetty engines, which run on the JVM only; kcoap neither depends on nor tests them.
- **No server TLS on native at all.** Linux native and Apple builds have no TLS stack in kcoap's dependencies.
  Network.framework would bring one to Apple platforms (E10), and nothing is planned for Linux native.

The options for a `coaps+tcp` server:

1. **An SSLEngine listener (JVM_LIBRARY tier).** JSSE's `SSLEngine` over a TCP socket, in a JVM-only module: TLS 1.2
   and 1.3, ALPN `coap` (`SSLParameters.setApplicationProtocols`, Java 9 and Android API level 29), client
   certificates reported as `PeerIdentity.Certificate`, so `AccessPolicy.Authenticated` can admit a TLS-authenticated
   client (ADR 0008). The same module could hold a JSSE client with ALPN and TLS 1.3 next to `KtorTlsConnector`.
   It serves JVM and Android servers only, and is a TLS integration of its own to write and test.
2. **Termination at a reverse proxy.** A TLS terminator in front of kcoap (HAProxy, nginx's `stream` module, stunnel,
   Envoy for `coaps+tcp`; any HTTPS reverse proxy that forwards WebSocket upgrades for `coaps+ws`) accepts TLS,
   negotiates ALPN `coap` and forwards plain `coap+tcp` or `coap+ws` to `KtorTcpListener` or `KtorWebSocketListener`
   on a loopback or private address. It works for every runtime, native gateways included, today, and certificates
   are managed where operators already manage them (ACME, rotation). kcoap then sees the proxy, not the client: each
   connection comes from the proxy's address, and the TLS identity of a client is not passed on (kcoap reads no PROXY
   protocol), so kcoap's peer is `PeerIdentity.None`.

## Decision

- **Now: proxy termination, the documented way for every runtime and the recommended one for native gateways.**
  kcoap ships no server-side TLS in WP-T1. Behind a proxy, the listener reports the scheme it speaks, `coap+tcp` or
  `coap+ws`, never `coaps+tcp` or `coaps+ws`: a listener's scheme is its word that it runs TLS (RFC 8323 §9), which
  the CoAP client relies on since WP-T1. The plain port listens only where the proxy reaches it, so the `coaps+tcp`
  namespace is not reachable in plaintext (RFC 8323 §8.2 makes `coap+tcp` and `coaps+tcp` different origins).
  Authorization of a client behind a proxy uses OSCORE or EDHOC identities (`AccessPolicy.MessageSecurityRequired`),
  which reach kcoap end to end, not a TLS client certificate, which ends at the proxy.
- **`coaps+ws` servers:** `KtorWebSocketListener` mounted in an application whose ktor engine runs TLS (Netty or
  Jetty with an SSL connector) and created with the scheme `coaps+ws`, or the same listener with `coap+ws` behind a
  proxy. Neither is tested over TLS in kcoap; the listener takes the engine as a parameter (`verifyNoServerEngine`).
  A `coaps+ws` listener holds its word: it closes, with 1008, any session whose request did not arrive on a TLS
  connector, as on an engine that also has a plain one (`request.local.scheme`, tested with CIO's plain server).
- **Later: an SSLEngine listener in a JVM_LIBRARY module**, with ALPN `coap`, TLS 1.3 and client certificates, and a
  JSSE client beside it. It is not scheduled. Its triggers: a JVM server that must authorize by the TLS client
  certificate, or clients that require ALPN on a port other than 5684, which `KtorTlsConnector` cannot offer.

## Alternatives considered

- **A ktor-network-tls listener.** None exists: the library has no server handshake.
- **A common-code TLS engine, as kcoap plans for DTLS.** Rejected. DTLS is in kcoap because no platform stack runs its
  mandatory suites (docs/architecture.md, "Why kcoap implements DTLS itself"); TLS 1.2 and 1.3 with certificates are
  in every platform stack, and ADR 0003 leaves TLS to them.
- **A Netty listener for raw TCP.** It would bring Netty in for what JSSE's `SSLEngine` does without a dependency.
- **Bouncy Castle `bctls` instead of JSSE.** JVM-only like JSSE but a dependency; it is the option for RFC 8323 §9.1's
  PreSharedKey and RawPublicKey modes over TLS, which JSSE lacks, if those are ever needed.

## Consequences

- kcoap has no `coaps+tcp` or `coaps+ws` server of its own; checklist coap-core-x9 stays Partial, and
  docs/platforms.md lists what exists per runtime.
- A server behind a proxy cannot identify its clients by TLS certificate or by address; message security does,
  end to end.
- The TLS handshakes, and the proxy's limits on them, happen before a connection reaches kcoap, whose own limits
  (`ReliableTransportConfig.maxPendingConnections`) apply to what the proxy forwards.
