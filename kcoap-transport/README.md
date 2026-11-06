# kcoap-transport

The seam between protocol code and the network. Its only dependency is kcoap-common, for the opt-in
markers, which every module above it reaches through this one; no third-party library.

- `DatagramTransport` (UDP, DTLS, multicast, non-IP links), `ByteStreamConnection` (TCP, TLS) and
  `MessageConnection` (WebSocket): the three shapes RFC 7252 and RFC 8323 define. The two connection
  shapes share the sealed `Connection`; a `Connector` opens them for a peer and a
  `ConnectionListener` accepts them.
- `Endpoint`: what the messaging layer matches and deduplicates by. Its variants are final classes whose
  equality is written out as the RFCs' matching rule, not data classes:
  - `Endpoint.Udp`: an address and a port (RFC 7252 §5.3.2), the host kept and compared in `IpLiteral`
    form, so `2001:DB8::1` and `2001:db8::1` are one peer;
  - `Endpoint.Tcp` and `Endpoint.WebSocket`: the peer as RFC 8323 §8 names it, compared as RFC 7252 §6.6
    compares URIs: hosts case-insensitively, a WebSocket URI without a port with its scheme's default, and
    `coaps+tcp` apart from `coap+tcp` and `wss` apart from `ws` on the same host and port (`usesTls`);
  - `Endpoint.Opaque`: link and address, compared exactly;
  - `Endpoint.Secure`: the RFC 7252 §9.1 rule that DTLS messages match only within the same session and
    epoch.

  `EndpointMatchingTest` and `EndpointTest` check each rule.
- `PeerIdentity` and `SecurityMode`: the channel-security identity handed to authorization.
- `DatagramSecurity`: where DTLS plugs in, as a decorator of any `DatagramTransport`, with the contract
  an implementation must meet. It is `@ExperimentalKcoapApi` until kcoap-dtls implements it. TLS for
  `coaps+tcp` and `coaps+ws` is the job of each runtime's connector and listener
  ([ADR 0003](../docs/adr/0003-tls-in-connectors.md)), so there is no stream counterpart.
- `CoapScheme`: `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws`, `coaps+ws`, with the ALPN id and
  the WebSocket subprotocol and path of RFC 8323. `Endpoint.Tcp` records TLS; `Endpoint.WebSocket.of`
  builds the `ws`/`wss` URI of an authority.
- `IpLiteral`: the canonical text of IP addresses in `Endpoint.Udp` (dotted IPv4, RFC 5952 IPv6), so
  endpoints from transports and from URIs compare equal.

Adapters live in their own modules: `kcoap-transport-ktor` (UDP and TCP on ktor-network),
`kcoap-transport-ktor-websocket` and `kcoap-transport-ktor-websocket-server` (WebSockets on the ktor
client and server) and `kcoap-testkit` (in-memory network and connections for tests). Netty and
Network.framework adapters are planned.
