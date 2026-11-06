# Platforms

What kcoap offers on each runtime, where the runtimes differ: the targets and the minimum versions the build sets,
which modules build where, which algorithms and EDHOC suites each target runs, the TLS each runtime has, and what
mobile apps must handle. The protocol code is the same everywhere: every core module is common Kotlin, and only
the adapters around runtime-bound libraries build for fewer targets ([target-architecture.md](target-architecture.md)
§3.10). Which modules a use case needs is in [getting started](getting-started.md#which-modules-to-add).

## Targets and minimum versions

| Target | Runtime | Minimum | Where it is set and checked |
|---|---|---|---|
| `jvm` | JVM servers and desktops | Java 17 | The jvm classes are Java 17 bytecode (`jvmTarget`, `-Xjdk-release=17` in `KcoapBuild.kt`): they run on Java 17 or newer, and a consumer compiles to Java 17 or newer, since Kotlin inlines kcoap's inline functions only into bytecode of the same target or newer. CI runs the tests on Java 21 |
| `jvm` | Android | API level 26 (Android 8.0) | `android-minSdk` in [gradle/libs.versions.toml](../gradle/libs.versions.toml). `checkAndroidApi` checks every jvm jar against the API 26 signature and `checkAndroidApiDependencies` the libraries behind them; CI builds [samples/android-consumer](../samples/android-consumer/README.md) at minSdk 26 with R8 and lint. Not yet run on an emulator or a device (PL-2) |
| `iosArm64`, `iosSimulatorArm64` | iPhone, and the simulator on Apple-silicon Macs | none set by kcoap | The build sets no deployment target, so Kotlin/Native's default for Kotlin 2.4.21 applies and the app's own deployment target governs; the minimum iOS version is an open decision. Arm64 only: no `iosX64` or `macosX64` |
| `macosArm64` | Apple-silicon Macs | none set by kcoap | As for iOS. The Apple targets are compiled and tested only on macOS, in the CI `apple` job (`scripts/test apple`); Linux cross-compiles their klibs for the ABI dumps |
| `linuxX64` | Linux gateways | none set by kcoap | OpenSSL 3 comes linked into the binary (cryptography-kotlin's prebuilt provider). `linuxArm64` is planned (roadmap E5) |
| `js` | Browsers and Node.js | none set by kcoap | Needs WebCrypto, which browsers offer in secure contexts (HTTPS or `localhost`), and the runtime's WebSocket. The common tests run on Node.js 24.16.0, the version the Kotlin Gradle plugin 2.4.21 downloads; the WebSocket module's js tests also run in a headless browser (Firefox locally, Chrome in the CI `browser` job) |
| `wasmJs` | Browsers and Node.js | none set by kcoap | As for `js`, plus what Kotlin/Wasm needs of the runtime (WebAssembly garbage collection). The common tests run on Node.js 26.2.0, the Kotlin Gradle plugin's version for Wasm |

A consuming project needs Kotlin 2.3 or newer: kcoap is built with Kotlin 2.4.21, and a Kotlin compiler reads
metadata at most one minor version ahead of its own ([versioning](versioning.md#platform-and-toolchain-minimums),
which also says how a raised minimum is released). Building kcoap itself needs a JDK 17 or newer, and a Mac with
Xcode for the Apple artifacts.

Not built ([target-architecture.md](target-architecture.md) §3.10 and §5): `mingwX64` (not without a Windows CI
runner), tvOS and watchOS (on demand), the x64 Apple targets and `androidNative` (not planned; Android apps use the
jvm artifacts), and socket transports for Node.js: one js artifact serves browsers and Node.js, so sockets in it
would ship to browsers.

### Modules per target

| Tier | Targets | Modules |
|---|---|---|
| UNIVERSAL | every target above | the cores (kcoap-common, kcoap-crypto, kcoap-cbor, kcoap-cose, kcoap-transport, kcoap-coap-message, kcoap-coap, kcoap-oscore, kcoap-edhoc, kcoap-edhoc-oscore), kcoap-crypto-default, kcoap-transport-ktor-websocket, kcoap-testkit, kcoap-crypto-testsuite |
| SOCKETS | `jvm`, the Apple targets, `linuxX64`; not `js` or `wasmJs` | kcoap-transport-ktor (UDP, TCP, and the `coaps+tcp` client on the JVM), kcoap-transport-ktor-websocket-server |
| JVM_LIBRARY | `jvm` | kcoap-crypto-bc |

kcoap-bom has no code; kcoap-dtls (UNIVERSAL) is built and tested but not published until its engine exists.

## Algorithms and EDHOC suites per target

Each crypto backend's tests extend the conformance suite of
[kcoap-crypto-testsuite](../kcoap-crypto-testsuite/README.md), which asks the backend every capability question on
every target it builds for, checks that each answer is true (what it says it runs works, what it says it lacks throws
`UnsupportedAlgorithmException`), and prints a capability report into the build log. These are the report's lines,
copied from runs of

```bash
./gradlew :kcoap-crypto-default:jvmTest :kcoap-crypto-default:linuxX64Test :kcoap-crypto-default:jsNodeTest \
    :kcoap-crypto-default:wasmJsNodeTest --tests 'dev.kcoap.crypto.DefaultCryptoBackendConformanceTest'
./gradlew :kcoap-crypto-bc:jvmTest --tests 'dev.kcoap.crypto.bc.BouncyCastleConformanceTest'
```

on Linux x86-64 with JDK 21.0.13, at the commit that wrote this page (WP-C3):

```
[jvmTest] kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, AES_CCM_16_64_256, AES_CCM_64_64_128, AES_CCM_64_64_256, AES_CCM_16_128_128, AES_CCM_16_128_256, AES_CCM_64_128_128, AES_CCM_64_128_256, A128GCM, A192GCM, A256GCM, CHACHA20_POLY1305; no none
[jvmTest] kcoap-crypto capabilities of DefaultCryptoBackend: key types yes X25519, X448, P256, P384, ED25519; no ML_KEM_512, ML_KEM_768, ML_KEM_1024, ML_DSA_44, ML_DSA_65, ML_DSA_87
[jvmTest] kcoap-crypto capabilities of DefaultCryptoBackend: signatures yes ES256, ES384, EDDSA; no ML_DSA_44, ML_DSA_65, ML_DSA_87
[jvmTest] kcoap-crypto capabilities of DefaultCryptoBackend: KEMs yes none; no ML_KEM_512, ML_KEM_768, ML_KEM_1024
[jvmTest] kcoap-crypto capabilities of DefaultCryptoBackend: hashes yes SHA_256, SHA_384, SHAKE256; no none
[linuxX64Test] kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, AES_CCM_16_64_256, AES_CCM_64_64_128, AES_CCM_64_64_256, AES_CCM_16_128_128, AES_CCM_16_128_256, AES_CCM_64_128_128, AES_CCM_64_128_256, A128GCM, A192GCM, A256GCM, CHACHA20_POLY1305; no none
[linuxX64Test] kcoap-crypto capabilities of DefaultCryptoBackend: key types yes X25519, X448, P256, P384, ED25519; no ML_KEM_512, ML_KEM_768, ML_KEM_1024, ML_DSA_44, ML_DSA_65, ML_DSA_87
[linuxX64Test] kcoap-crypto capabilities of DefaultCryptoBackend: signatures yes ES256, ES384, EDDSA; no ML_DSA_44, ML_DSA_65, ML_DSA_87
[linuxX64Test] kcoap-crypto capabilities of DefaultCryptoBackend: KEMs yes none; no ML_KEM_512, ML_KEM_768, ML_KEM_1024
[linuxX64Test] kcoap-crypto capabilities of DefaultCryptoBackend: hashes yes SHA_256, SHA_384, SHAKE256; no none
[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, AES_CCM_16_64_256, AES_CCM_64_64_128, AES_CCM_64_64_256, AES_CCM_16_128_128, AES_CCM_16_128_256, AES_CCM_64_128_128, AES_CCM_64_128_256, A128GCM, A192GCM, A256GCM; no CHACHA20_POLY1305
[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: key types yes X25519, X448, P256, P384, ED25519; no ML_KEM_512, ML_KEM_768, ML_KEM_1024, ML_DSA_44, ML_DSA_65, ML_DSA_87
[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: signatures yes ES256, ES384, EDDSA; no ML_DSA_44, ML_DSA_65, ML_DSA_87
[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: KEMs yes none; no ML_KEM_512, ML_KEM_768, ML_KEM_1024
[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: hashes yes SHA_256, SHA_384, SHAKE256; no none
[wasmJsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes AES_CCM_16_64_128, AES_CCM_16_64_256, AES_CCM_64_64_128, AES_CCM_64_64_256, AES_CCM_16_128_128, AES_CCM_16_128_256, AES_CCM_64_128_128, AES_CCM_64_128_256, A128GCM, A192GCM, A256GCM; no CHACHA20_POLY1305
[wasmJsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: key types yes X25519, X448, P256, P384, ED25519; no ML_KEM_512, ML_KEM_768, ML_KEM_1024, ML_DSA_44, ML_DSA_65, ML_DSA_87
[wasmJsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: signatures yes ES256, ES384, EDDSA; no ML_DSA_44, ML_DSA_65, ML_DSA_87
[wasmJsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: KEMs yes none; no ML_KEM_512, ML_KEM_768, ML_KEM_1024
[wasmJsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: hashes yes SHA_256, SHA_384, SHAKE256; no none
[jvmTest] kcoap-crypto capabilities of BouncyCastleCryptoBackend: AEAD yes AES_CCM_16_64_128, AES_CCM_16_64_256, AES_CCM_64_64_128, AES_CCM_64_64_256, AES_CCM_16_128_128, AES_CCM_16_128_256, AES_CCM_64_128_128, AES_CCM_64_128_256, A128GCM, A192GCM, A256GCM, CHACHA20_POLY1305; no none
[jvmTest] kcoap-crypto capabilities of BouncyCastleCryptoBackend: key types yes X25519, X448, P256, P384, ED25519, ML_KEM_512, ML_KEM_768, ML_KEM_1024, ML_DSA_44, ML_DSA_65, ML_DSA_87; no none
[jvmTest] kcoap-crypto capabilities of BouncyCastleCryptoBackend: signatures yes ES256, ES384, EDDSA, ML_DSA_44, ML_DSA_65, ML_DSA_87; no none
[jvmTest] kcoap-crypto capabilities of BouncyCastleCryptoBackend: KEMs yes ML_KEM_512, ML_KEM_768, ML_KEM_1024; no none
[jvmTest] kcoap-crypto capabilities of BouncyCastleCryptoBackend: hashes yes SHA_256, SHA_384, SHAKE256; no none
```

The Bouncy Castle lines are `BouncyCastleCryptoBackend` around `DefaultCryptoBackend` on the JVM. The js and wasmJs
lines are Node.js's WebCrypto (24.16.0 and 26.2.0); the AES-CCM answers there come from the common-code CCM on
WebCrypto's AES-CTR, and SHAKE256 from the common-code Keccak, on every target.

The same as a matrix, with the EDHOC cipher suites that follow from it (`CipherSuite.isSupportedBy(crypto, method)`:
the suite's AEADs, hashes, key exchange and, for a method where a side signs, its signature algorithm):

| | JVM (JDK) | JVM with `kcoap-crypto-bc` | `linuxX64` (OpenSSL 3) | `js`, `wasmJs` on Node.js | Apple (CryptoKit, CommonCrypto) | Browsers | Android |
|---|---|---|---|---|---|---|---|
| AES-CCM (all eight COSE variants), AES-GCM 128/192/256 | yes | yes | yes | yes | CI only | yes, but no 192-bit AES keys in Chromium | not run yet |
| ChaCha20/Poly1305 | yes | yes | yes | **no** | CI only | **no** (WebCrypto has none) | not run yet |
| X25519, Ed25519; P-256, P-384 | yes | yes | yes | yes | CI only | P-256 and P-384 yes; X25519 and Ed25519 in current browsers only | not run yet |
| ML-KEM-512/768/1024, ML-DSA-44/65/87 | no | **yes** | no | no | no | no | with `kcoap-crypto-bc` |
| SHA-256, SHA-384, SHAKE256 | yes | yes | yes | yes | CI only | yes | not run yet |
| **EDHOC suites** | 0–6, 24 | 0–6, 24, -24 | 0–6, 24 | 0–3, 6, 24 | CI only | 0–3, 6 and 24 where X25519 and Ed25519 exist, else 2, 3 and 24 | not run yet; -24 with `kcoap-crypto-bc` |

- **Apple: CI only.** The Apple targets run the same suite in the CI `apple` job, whose log carries their report
  lines; this page was written on Linux and does not copy them.
- **Android: not run yet.** The jvm lines are the desktop JDK's providers. On Android, cryptography-kotlin's JDK
  provider sits on the device's providers, which differ by API level; the emulator runs of PL-2 will print a report
  per API level (checklist kmp-4). Until then, capability filtering is what keeps a missing algorithm from being
  offered.
- **Browsers** are not covered by the report: the common tests run on Node.js. What differs in browsers is
  WebCrypto's coverage per browser and version, which `supports(...)` asks at runtime, and the backend memoizes per
  instance.
- **Suite 25** (X448, Ed448, SHAKE256) runs nowhere: kcoap's crypto layer does not model Ed448. Suite -24 is
  libedhoc's private post-quantum suite of draft-ietf-lake-pqsuites (ML-KEM-512, ML-DSA-44); a pure-Kotlin ML-KEM and
  ML-DSA for the other targets is roadmap E3.

EDHOC asks these questions itself: an Initiator offers and a Responder accepts only the suites the backend runs, and
a Responder that cannot run the selected suite answers with error code 2 and the suites it can
([ADR 0001](adr/0001-crypto-capabilities.md)). An application asks the same way, `crypto.supports(algorithm)` and
`CipherSuite.isSupportedBy(crypto, method)`, before it offers a suite or provisions a key type; the Android sample
prints its device's answers ([samples/android-consumer](../samples/android-consumer/README.md)).

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

### Terminating TLS in front of kcoap

kcoap has no TLS listener of its own, so a `coaps+tcp` or `coaps+ws` server terminates TLS in front of kcoap
([ADR 0010](adr/0010-server-side-tls.md)). The options, for every runtime the listeners build for:

- **`coaps+tcp`: a TCP proxy that terminates TLS**, such as HAProxy, nginx's `stream` module, stunnel or Envoy,
  configured to offer ALPN `coap` (RFC 8323 §8.2), forwarding plain `coap+tcp` to a `KtorTcpListener`.
- **`coaps+ws`: an HTTPS reverse proxy that forwards WebSocket upgrades** to a `KtorWebSocketListener` speaking
  `coap+ws`; or, on the JVM, the listener mounted in a ktor application whose engine runs TLS (Netty or Jetty with an
  SSL connector), created with the scheme `coaps+ws`, which closes any session that did not arrive over TLS. Neither
  is tested over TLS in kcoap.
- **Behind any proxy**, bind the plain listener only where the proxy reaches it (the loopback interface or a private
  network), so the `coaps` origin is never reachable in plaintext, and keep the plain scheme on the listener, as the
  table above says. Authorize by OSCORE or EDHOC identity (`AccessPolicy.MessageSecurityRequired`, the default with
  message security), which reaches kcoap end to end; the client's TLS identity ends at the proxy.

A JVM listener on JSSE's `SSLEngine`, with ALPN, TLS 1.3 and client certificates, is the planned alternative for JVM
servers; it is not scheduled, and ADR 0010 names what would trigger it. DTLS (`coaps`) is kcoap's own engine,
which comes last (roadmap E1); no runtime offers a `coaps` server or client in kcoap until then.

## Mobile lifecycle

Phones suspend apps, change networks and sleep; kcoap leaves the policy to the application and behaves as follows
today. The helpers of roadmap E9 (a re-observe helper, a battery-aware keepalive policy, an endpoint event and an
application-driven rebind when a datagram transport fails) are not built yet.

**What kcoap does when the network goes away.**

- **A datagram transport that fails ends its endpoint.** When `receive` of the UDP transport throws (a socket the
  system closed or reclaimed, a network that went away), the endpoint ends with `closeCause`
  `CoapException(TRANSPORT_FAILED)`: pending requests, observations and the server fail, and every later call fails
  at once with that reason. Recreating the endpoint, with a new transport, is the application's decision
  (`awaitClosed()` tells it when).
- **A closed connection** (TCP, TLS, WebSocket) fails the exchanges on it with `CONNECTION_CLOSED` and ends its
  observations; the next request opens a new connection. `ReliableTransportConfig.idleTimeout` (30 s) releases
  connections this side opened, and `keepAliveInterval` (off by default) sends a CoAP Ping on a quiet connection and
  aborts it when nothing answers: each Ping wakes the radio, so set it no shorter than the NAT and proxy timeouts
  require.
- **An observation over UDP does not notice that the server is gone.** Nothing is sent to it, so its `Flow` stays
  open and silent; the client does not re-register by itself after Max-Age (RFC 7641 §3.3.1). An app that resumes,
  or changes network, cancels the collection and collects again, which registers anew. Over a connection the
  observation fails with `CONNECTION_CLOSED` when the connection does.
- **A new address is a new peer.** The server keys exchanges, observations and block-wise state by the client's
  `Endpoint` (its address and port over UDP), so after a network change it no longer knows the client's
  observations, and an EDHOC handshake in progress has to start again (its pending session is bound to the address
  that sent message_1). OSCORE contexts are not bound to addresses and keep working; until DTLS Connection IDs
  (RFC 9146, with E1) there is no rebinding below OSCORE.
- **Q-Block bodies** in flight end with their exchange; a body is not resumed across endpoints.

**What the app must do.**

- **Give the endpoint a scope that matches its lifetime**: the process (an `Application`-level scope on Android, the
  app's on iOS) for contexts and endpoints that outlive a screen, not an Activity's or a view's. Cancelling the scope
  closes the endpoint.
- **Keep OSCORE sequence numbers durable.** The process can be killed at any moment, so a pre-shared context's
  `SequenceNumberStore` must be durable before `store()` returns, and one context name has one live
  `SecurityContext` per process ([kcoap-oscore](../kcoap-oscore/README.md#writing-a-durable-store);
  [samples/android-consumer](../samples/android-consumer/README.md#what-an-app-needs)). EDHOC-derived contexts are
  fresh per handshake and use `EphemeralSequenceNumberStore`; after a restart the app runs EDHOC again.
- **Android.** In Doze and App Standby the system suspends network access outside maintenance windows, so
  retransmissions run out and requests fail with `TRANSMISSION_TIMEOUT` or `RESPONSE_TIMEOUT`; observations wait
  silently. Re-observe and retry when the app is back in the foreground or the network callback reports a new
  network. UDP needs the `INTERNET` permission even on the loopback interface; multicast (not built yet) would need
  a `MulticastLock`.
- **iOS and macOS.** A suspended app's sockets may be reclaimed by the system (Apple's TN2277), which ends a UDP
  endpoint as above when the app resumes: recreate it. kcoap-transport-ktor uses BSD sockets, which do not bring up
  the cellular radio by themselves and do not trigger VPN On Demand; the WebSocket client runs on URLSession (the
  Darwin engine), which does. Talking to devices on the local network needs the Local Network permission
  (`NSLocalNetworkUsageDescription`). A Network.framework adapter for these cases is roadmap E10.
- **Browsers.** A tab in the background may be throttled or frozen, and a closed WebSocket fails its exchanges with
  `CONNECTION_CLOSED`; the next request reconnects.
