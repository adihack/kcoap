# Platforms

What kcoap offers on each runtime, where the runtimes differ. WP-C3 completes this page with the minimum
versions, the capability matrix of algorithms and EDHOC suites per target, and the mobile lifecycle
([target-architecture.md](target-architecture.md) §3.10); the targets and the crypto under each are in the
[README](../README.md#platforms) until then. This first part lists the TLS that exists today, written with WP-T1.

## TLS support per runtime

kcoap takes TLS from each runtime's own stack, in its connectors and listeners
([ADR 0003](adr/0003-tls-in-connectors.md)). A CoAP client sends a `coaps+tcp` or `coaps+ws` request only when
a connector or listener of its endpoint serves that scheme, which a transport does only when it runs TLS, and only
to a peer reached over TLS; otherwise the call fails with `CoapException(TRANSPORT_UNAVAILABLE)` before anything is
sent. `coaps` (DTLS) is refused everywhere until kcoap's DTLS engine exists.

Everything below was checked with ktor 3.6.1. "Not run" means the code path exists but no test of kcoap's runs it
against a TLS server.

### Clients

| Runtime | `coaps+tcp` (RFC 8323 §8.2) | `coaps+ws` (RFC 8323 §8.4) |
|---|---|---|
| JVM | `KtorTlsConnector` (kcoap-transport-ktor) on ktor-network-tls. Tested against a JSSE server (`KtorTlsConnectorTest`) and cross-tested with libcoap 4.3.5b on OpenSSL 3.0 (`KcoapClientAgainstLibcoapTlsTest`) | Not on the default client: `KtorWebSocketConnector()` does not list `coaps+ws`, since its CIO engine's TLS, ktor-network-tls, does not check names fully (below; `DefaultConnectorCoapsWsTest`). A connector on an `HttpClient` the application passes serves it, on that client's TLS and trust (for CIO, `https { trustManager }`). Cross-tested with libcoap 4.3.5b on a CIO client that trusts the test authority (`KcoapClientAgainstLibcoapTlsTest`) |
| Android | The same jar as the JVM; `TlsTrust.Platform` checks the chain with the host through `X509TrustManagerExtensions`, so the app's network security configuration applies per domain; not run on Android yet | As on the JVM; not run on Android yet |
| Linux native | None: ktor-network-tls has no TLS there, its handshake throws "TLS sessions are not supported on Native platform." (`NativeTlsUnavailableTest`) | None: the default client's CIO engine fails the same way, so `KtorWebSocketConnector()` does not list `coaps+ws` and a CoAP client refuses it before resolving the host (`LinuxSecureWebSocketTest`) |
| Apple (iOS, macOS) | None, for the same reason: the 3.6.1 klibs for `macosArm64` and `iosArm64` hold the same stub, and `NativeTlsUnavailableTest` checks it in the macOS CI job (`scripts/test apple`), which has not run it yet | The Darwin engine, on URLSession's TLS and the system's trust store; the default connector lists `coaps+ws` (`DefaultEngineTest`); not run against a TLS server |
| Browsers | None: no sockets | The browser's own `wss`; the default connector lists `coaps+ws` (`DefaultEngineTest`); not run against a TLS server |
| Node.js | None: the socket transports are not built for js | The runtime's WebSocket through ktor's Js engine, listed as on browsers; not run against a TLS server |

What the `coaps+tcp` client does, and what it does not:

- **Server authentication by certificate** (RFC 8323 §9.1's Certificate mode). The chain must be valid under the
  platform trust store (`TlsTrust.Platform`: the JDK's `cacerts` or `javax.net.ssl.trustStore`; on Android the
  system's CAs as the app's network security configuration admits them for the host, `<domain-config>` and
  `<pin-set>` included, checked through `android.net.http.X509TrustManagerExtensions`) or under trust anchors the application
  gives (`TlsTrust.Anchors`), and must name the host by its subjectAltName (RFC 9525 §6: DNS names with a wildcard
  only as the whole left-most label, IP addresses by their octets, never the Common Name).
- **A pinning hook** (`CertificatePinner`) on top of that, with SPKI SHA-256 pins built in (RFC 7469 §2.4).
- **TLS 1.2 only**, in ktor's own implementation, with the ECDHE suites over AES-GCM (RFC 9325 §4.2). No TLS 1.3, no
  session resumption, no RFC 7925 CCM_8 suite.
- **No extended master secret (RFC 7627) and no renegotiation indication (RFC 5746)**, which RFC 9325 §3.5 requires
  of TLS 1.2 implementations; ktor-network-tls sends neither (`upstreamKtorNetworkTlsOffersNoAlpnExtendedMasterSecretOrRenegotiationInfo`).
  The attacks they prevent need a renegotiation, a resumed session or a client certificate, none of which the
  connector uses, so the risk is low; the JSSE client of [ADR 0010](adr/0010-server-side-tls.md) would close the gap.
- **No ALPN**, which ktor-network-tls cannot send. RFC 8323 §8.2 has the client offer `coap` on every port but
  5684, so the connector refuses other ports unless `TlsClientConfig.allowsAnyPortWithoutAlpn` is set for a server
  known to accept clients without it (libcoap's does).
- **No PreSharedKey or RawPublicKey mode, and no client certificate.**
- **Server Name Indication** for host names, never for IP literals (RFC 6066 §3).

ktor-network-tls's own name check, which ktor's CIO client relies on, accepts a certificate that has no DNS name for
any host name, and CIO checks an IP literal by the name a reverse DNS lookup gives for it
(`CioWebSocketNameCheckTest`). A publicly trusted certificate for an attacker's IP address would then pass for any
host. `KtorTlsConnector` does not rely on that check and applies RFC 9525 §6 itself; the WebSocket connector's
default client therefore serves no `coaps+ws` on the JVM and Android, and an application that passes a CIO client
for it should trust only an authority that issues DNS names alone for its servers.

### Servers

kcoap has no server-side TLS of its own ([ADR 0010](adr/0010-server-side-tls.md)).

| Runtime | `coaps+tcp` | `coaps+ws` |
|---|---|---|
| JVM, Android | A TLS-terminating reverse proxy in front of `KtorTcpListener` | `KtorWebSocketListener` in an application whose ktor engine runs TLS (Netty or Jetty), with the scheme `coaps+ws`, which closes any session that did not arrive over TLS; or with `coap+ws` behind a TLS-terminating proxy. Neither is tested over TLS in kcoap |
| Linux native, Apple | A TLS-terminating reverse proxy in front of `KtorTcpListener`, the recommended way for native gateways | A TLS-terminating proxy: the CIO server engine has no TLS |
| Browsers, Node.js | No listeners | No listeners |

Behind a proxy the listener keeps the plain scheme it speaks (`coap+tcp`, `coap+ws`), listens only where the proxy
reaches it, and sees every client as `PeerIdentity.None`, so servers authorize by OSCORE or EDHOC identity.
