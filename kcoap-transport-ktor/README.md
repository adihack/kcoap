# kcoap-transport-ktor

Network transports on [ktor-network](https://ktor.io/docs/server-sockets.html) for the targets that have
sockets (the SOCKETS tier): JVM (and so Android), Apple and Linux; not the web. Browsers have no UDP or
TCP; they use `kcoap-transport-ktor-websocket` and reach devices through a proxy (see
`docs/architecture.md`).

This is the ktor adapter, so ktor's types are part of its API: `bind()` and the TCP connector take an
optional `SelectorManager` from ktor-network, and every entry point an optional `LoggerFactory` from
kcoap-common. The module exports both dependencies (`api`), so a caller can pass either without adding
it, and its ABI follows ktor-network's.

- `KtorUdpTransport`: a `DatagramTransport` for `coap` over UDP.
  - `KtorUdpTransport.bind(host, port, maxDatagramSize)` binds a socket; port 0 picks a free one.
  - Peers are `Endpoint.Udp` in `IpLiteral` canonical form (dotted IPv4, RFC 5952 IPv6, IPv4-mapped
    as IPv4), built from the raw address and never from a reverse DNS lookup, so CoAP matches
    responses by endpoint equality.
  - `maxDatagramSize` defaults to 1152 bytes, RFC 7252 §4.6's message size for an unknown path; the
    CoAP layer derives block sizes from it. Larger datagrams are still received.

- `KtorTcpConnector` and `KtorTcpListener`: `coap+tcp` (RFC 8323) connections, opened on demand by
  the CoAP client and accepted by the server; accepted peers are `Endpoint.Tcp` in `IpLiteral` form.
  The adapter only moves bytes; framing, CSM and signaling are common code in `kcoap-coap`.

```kotlin
val transport = KtorUdpTransport.bind(port = 5683)
val server = CoapServer(transport, scope) { resource("hello") { get { CoapResponse.content("Hello") } } }
```

## Why ktor-network

It is the one socket library that runs on every kcoap target from common code, so one transport serves
the JVM and iOS; a JVM-only `DatagramChannel` transport would need a second one for Apple. It worked
without trouble on the JVM. Apple builds compile against the same common API but are only verified on
macOS CI.

```kotlin
val server = CoapServer(listOf(KtorTcpListener.bind(port = 5683)), scope) { ... }
val client = CoapClient(listOf(KtorTcpConnector()), scope)
client.get("coap+tcp://127.0.0.1/hello")
```

WebSockets are in `kcoap-transport-ktor-websocket`. Planned here: TLS for `coaps+tcp` (with ALPN
`coap`, RFC 8323 §8.2), and multicast, which needs platform APIs (`docs/architecture.md`, "Platform
adapters").

Verified by `KtorUdpTransportTest` and `KtorTcpTransportTest` (real sockets on IPv4 and IPv6
loopback) and by the libcoap interop tests in `kcoap-coap/src/jvmTest`, over UDP and TCP.
