# kcoap-transport-ktor

Network transports on [ktor-network](https://ktor.io/docs/server-sockets.html) for the targets that have
sockets (the SOCKETS tier): JVM (and so Android), Apple and Linux; not the web. Browsers have no UDP or
TCP; they use `kcoap-transport-ktor-websocket` and reach devices through a proxy (see
`docs/architecture.md`). A TLS client for `coaps+tcp` is on the JVM only, where ktor-network-tls has one.

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
  The adapter only moves bytes; framing is common code in `kcoap-coap-message`, CSM and signaling in
  `kcoap-coap`.

```kotlin
val transport = KtorUdpTransport.bind(port = 5683)
val server = CoapServer(transport, scope) { resource("hello") { get { CoapResponse.content("Hello") } } }
```

## Why ktor-network

It is the one socket library that runs from common code on every kcoap target that has sockets (JVM,
Apple and Linux), so one transport serves the JVM and iOS; a JVM-only `DatagramChannel` transport would need a second one for Apple. It worked
without trouble on the JVM. Apple builds compile against the same common API but are only verified on
macOS CI.

```kotlin
val server = CoapServer(listOf(KtorTcpListener.bind(port = 5683)), scope) { ... }
val client = CoapClient(listOf(KtorTcpConnector()), scope)
client.get("coap+tcp://127.0.0.1/hello")
```

## TLS: `coaps+tcp` on the JVM

`KtorTlsConnector` opens `coaps+tcp` connections (RFC 8323 §8.2, §9.1) on ktor-network-tls, on the JVM and
Android. It authenticates the server by certificate: the chain must be valid under `TlsClientConfig.trust`, the
platform trust store by default or `TlsTrust.Anchors` for a private CA or a device's self-signed certificate, and
must name the host of the URI by its subjectAltName (RFC 9525 §6, IP literals by their octets, never the Common
Name). The connector checks names itself: ktor-network-tls's own check accepts a certificate without DNS names for
any host name (`CioWebSocketNameCheckTest`). On Android, `TlsTrust.Platform` is checked with the host through
`android.net.http.X509TrustManagerExtensions`, so the app's network security configuration applies per domain,
`<pin-set>` included; without the host, a configuration with a `<domain-config>` would refuse every chain. That
path has not run on Android yet. A `CertificatePinner` then checks the validated path on top, for example against SPKI SHA-256 pins
(`CertificatePinner.publicKeySha256`, RFC 7469 §2.4). The connection reports that path, end-entity first and
trust anchor last, as its `PeerIdentity.Certificate`, which a server's handlers see as `peerIdentity`.

```kotlin
val tls = KtorTlsConnector(TlsClientConfig(trust = TlsTrust.Anchors(listOf(deviceCa))))
val client = CoapClient(listOf(KtorTcpConnector(), tls), scope)
client.get("coaps+tcp://gateway.example.org/sensors/temp")
```

What ktor-network-tls 3.6.1 allows, and so what the connector does not do:

- **TLS 1.2 only**, ktor's own implementation of it, with the ECDHE suites over AES-GCM (RFC 9325 §4.2); ktor's
  RSA key transport and CBC suites are not offered. No TLS 1.3, no session resumption, no RFC 7925 CCM_8 suite.
- **No extended master secret (RFC 7627) and no renegotiation indication (RFC 5746)**, neither the extension nor
  the signaling suite, which RFC 9325 §3.5 requires of TLS 1.2 implementations: ktor-network-tls sends neither.
  The triple-handshake and renegotiation attacks they prevent need a renegotiation, a resumed session or a client
  certificate, and the connector uses none of them, so the risk is low. The JSSE client that ADR 0010 plans
  would close the gap.
- **No ALPN.** RFC 8323 §8.2 has the client offer `coap` on every port but 5684, so a port other than 5684 is
  refused before connecting unless `TlsClientConfig(allowsAnyPortWithoutAlpn = true)` is set, for a server known
  to accept clients without ALPN (libcoap's does).
- **Certificates only**: no PreSharedKey or RawPublicKey mode, no client certificate.
- **JVM only.** On Linux native and Apple platforms ktor-network-tls compiles, but its handshake throws "TLS
  sessions are not supported on Native platform." `NativeTlsUnavailableTest` pins that, so the connector is in
  `jvmMain` and the klib API does not have it.

Servers: kcoap has no TLS listener. Terminate TLS at a reverse proxy in front of `KtorTcpListener`, which keeps
the scheme `coap+tcp` ([ADR 0010](../docs/adr/0010-server-side-tls.md)). Per runtime:
[docs/platforms.md](../docs/platforms.md#tls-support-per-runtime).

## Not here

WebSockets are in `kcoap-transport-ktor-websocket`. Planned: multicast, which needs platform APIs
(`docs/architecture.md`, "Platform adapters").

## Verification

`KtorUdpTransportTest` and `KtorTcpTransportTest` use real sockets on IPv4 and IPv6 loopback.
`KtorTlsConnectorTest` runs the TLS connector against a JSSE server with certificates the test writes in DER:
the validated path, the negotiated suite and SNI, intermediates and anchors that are not roots, untrusted
authorities, certificates for another host, the pinner, the ALPN port rule (on port 5684 itself when the test can
listen there) and a TLS 1.3-only server. A recorder of the ClientHello shows the four ECDHE and AES-GCM suites
offered where ktor's default list adds RSA key transport and CBC, and pins what ktor-network-tls leaves out: ALPN,
the extended master secret and the renegotiation indication. `ServerIdentityTest` has RFC 9525's name rules,
`HostAwareTrustTest` the host-aware check with a stand-in for Android's wrapper, and `CioWebSocketNameCheckTest`
pins ktor's weaker name check through its CIO WebSocket client. The libcoap interop tests in `kcoap-coap/src/jvmTest` cover UDP, TCP and, in
`KcoapClientAgainstLibcoapTlsTest`, `coaps+tcp` against libcoap 4.3.5b on OpenSSL with a throwaway CA.
