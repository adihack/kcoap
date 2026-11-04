# kcoap-transport

The seam between protocol code and the network, with no dependencies.

- `DatagramTransport` (UDP, DTLS, multicast, non-IP links), `ByteStreamConnection` (TCP, TLS) and
  `MessageConnection` (WebSocket): the three shapes RFC 7252 and RFC 8323 define.
- `Endpoint`: what the messaging layer matches and deduplicates by. `Endpoint.Secure` implements the
  RFC 7252 §9.1 rule that DTLS messages match only within the same session and epoch.
- `PeerIdentity` and `SecurityMode`: the channel-security identity handed to authorization.
- `DatagramSecurity` and `StreamSecurity`: where DTLS and kcoap's own TLS plug in, with the contract
  an implementation must meet.
- `CoapScheme`: `coap`, `coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws`, `coaps+ws`.
- `IpLiteral`: the canonical text of IP addresses in `Endpoint.Udp` (dotted IPv4, RFC 5952 IPv6), so
  endpoints from transports and from URIs compare equal.

Adapters live in their own modules: `kcoap-transport-ktor` (UDP on ktor-network) and `kcoap-testkit`
(an in-memory network for tests). Netty, Network.framework and browser WebSocket adapters are planned.
