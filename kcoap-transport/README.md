# kcoap-transport

The seam between protocol code and the network, with no dependencies.

- `DatagramTransport` (UDP, DTLS, multicast, non-IP links), `ByteStreamConnection` (TCP, TLS) and
  `MessageConnection` (WebSocket): the three shapes RFC 7252 and RFC 8323 define. The two connection
  shapes share the sealed `Connection`; a `Connector` opens them for a peer and a
  `ConnectionListener` accepts them.
- `Endpoint`: what the messaging layer matches and deduplicates by. `Endpoint.Secure` implements the
  RFC 7252 §9.1 rule that DTLS messages match only within the same session and epoch.
- `PeerIdentity` and `SecurityMode`: the channel-security identity handed to authorization.
- `DatagramSecurity` and `StreamSecurity`: where DTLS and kcoap's own TLS plug in, with the contract
  an implementation must meet.
- `CoapScheme`: `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws`, `coaps+ws`, with the ALPN id and
  the WebSocket subprotocol and path of RFC 8323. `Endpoint.Tcp` records TLS; `Endpoint.WebSocket.of`
  builds the `ws`/`wss` URI of an authority.
- `IpLiteral`: the canonical text of IP addresses in `Endpoint.Udp` (dotted IPv4, RFC 5952 IPv6), so
  endpoints from transports and from URIs compare equal.

Adapters live in their own modules: `kcoap-transport-ktor` (UDP and TCP on ktor-network),
`kcoap-transport-ktor-websocket` and `kcoap-transport-ktor-websocket-server` (WebSockets on the ktor
client and server) and `kcoap-testkit` (in-memory network and connections for tests). Netty and
Network.framework adapters are planned.
