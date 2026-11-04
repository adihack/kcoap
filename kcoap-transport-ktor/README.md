# kcoap-transport-ktor

Network transports on [ktor-network](https://ktor.io/docs/server-sockets.html) for the targets kcoap
builds: JVM (and so Android) and Apple. Browsers have no UDP; they reach devices through a proxy (see
`docs/architecture.md`).

- `KtorUdpTransport`: a `DatagramTransport` for `coap` over UDP.
  - `KtorUdpTransport.bind(host, port, maxDatagramSize)` binds a socket; port 0 picks a free one.
  - Peers are `Endpoint.Udp` in `IpLiteral` canonical form (dotted IPv4, RFC 5952 IPv6, IPv4-mapped
    as IPv4), built from the raw address and never from a reverse DNS lookup, so CoAP matches
    responses by endpoint equality.
  - `maxDatagramSize` defaults to 1152 bytes, RFC 7252 §4.6's message size for an unknown path; the
    CoAP layer derives block sizes from it. Larger datagrams are still received.

```kotlin
val transport = KtorUdpTransport.bind(port = 5683)
val server = CoapServer(transport, scope) { resource("hello") { get { CoapResponse.content("Hello") } } }
```

## Why ktor-network

It is the one socket library that runs on every kcoap target from common code, so one transport serves
the JVM and iOS; a JVM-only `DatagramChannel` transport would need a second one for Apple. It worked
without trouble on the JVM. Apple builds compile against the same common API but are only verified on
macOS CI.

Planned here: TCP (`coap+tcp`, RFC 8323) and WebSocket client transports, and multicast, which needs
platform APIs (`docs/architecture.md`, "Platform adapters").

Verified by `KtorUdpTransportTest` (real sockets on IPv4 and IPv6 loopback) and by the libcoap
interop tests in `kcoap-coap/src/jvmTest`.
