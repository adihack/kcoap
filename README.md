# kcoap

CoAP, OSCORE and EDHOC for Kotlin Multiplatform: one implementation for Android, iOS, JVM servers,
Linux gateways and, later, browsers. kcoap aims at complete compliance with the published RFCs, proven by the RFCs'
own test vectors and by live interop with libcoap (by RFC authors) and libedhoc. It also supports the
post-quantum EDHOC suite.

| Protocol | RFC | State | Verified by |
|---|---|---|---|
| CoAP over UDP: messaging, client, server, Observe, Block-wise, Echo/Request-Tag, link format | 7252, 7641, 7959, 9175, 6690 | done | unit tests on a simulated lossy network; libcoap both directions |
| CoAP message codecs for TCP and WebSockets | 8323 | codec done, transport planned | unit tests |
| OSCORE | 8613 | done (Appendix B.2 pending) | every Appendix C vector byte for byte; libcoap both directions, raw and through the stack |
| EDHOC: methods 0–3, suites 0–6, 24, post-quantum suite -24 | 9528 | done | both RFC 9529 traces byte for byte, all §4 invalid messages; libedhoc interop in progress |
| EDHOC over CoAP, EDHOC→OSCORE, combined request | 9528 App. A, 9668 | done | RFC 9668 Figure 4 byte for byte; full-stack tests |
| Post-quantum crypto: ML-KEM, ML-DSA | FIPS 203, 204 | done (JVM, Android) | NIST ACVP vectors |
| DTLS 1.2 | 6347, 7925, 9146 | interfaces only; engine last | — |

The details, section by section, are in [docs/rfc-compliance.md](docs/rfc-compliance.md).

## Quick start

```kotlin
// A server with a protected resource.
val contexts = InMemoryContextRepository().apply { add(serverContext) }
val server = CoapServer(KtorUdpTransport.bind("0.0.0.0", 5683), scope, CoapServerConfig(security = OscoreServer(contexts))) {
    resource("lights") {
        get { if (securityIdentity is OscoreIdentity) CoapResponse.content("on") else CoapResponse.error(CoapCode.UNAUTHORIZED) }
    }
}

// A client that talks OSCORE to it.
val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig(security = OscoreClient(clientContext)))
println(client.get("coap://192.0.2.1/lights").payloadAsText())
```

With EDHOC instead of pre-shared OSCORE keys, the client runs the handshake first and its first
OSCORE request carries EDHOC message_3 (RFC 9668), two round trips in all:

```kotlin
val security = PeerSecurity()
val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig(security = security))
val (session, protection) = EdhocClientHandshake.over(client, serverUri, initiatorConfig, crypto).runCombined()
security.install(serverEndpoint, protection)
client.get("coap://192.0.2.1/lights")
```

The server side mounts `EdhocResource` under `/.well-known` and uses `CombinedRequestServer`; see
[kcoap-edhoc-oscore](kcoap-edhoc-oscore/README.md). More in the
[client and server guide](docs/guide-client-server.md).

## Modules

| Module | What it is |
|---|---|
| [`kcoap-common`](kcoap-common) | Logging API and sinks, hex, constant-time helpers |
| [`kcoap-crypto`](kcoap-crypto) | The `CryptoBackend` SPI (all `suspend`, opaque key handles), AES-CCM, HKDF, SHAKE256, KMAC256 |
| [`kcoap-crypto-default`](kcoap-crypto-default) | Backend on cryptography-kotlin: JCA on JVM/Android, CryptoKit and CommonCrypto on Apple |
| [`kcoap-crypto-bc`](kcoap-crypto-bc/README.md) | Post-quantum backend on Bouncy Castle: ML-KEM and ML-DSA (JVM, Android) |
| [`kcoap-cbor`](kcoap-cbor/README.md) | Deterministic CBOR and CBOR sequences |
| [`kcoap-cose`](kcoap-cose/README.md) | COSE structures, COSE_Key, CWT Claims Sets |
| [`kcoap-transport`](kcoap-transport/README.md) | Transport interfaces, endpoints, peer identities, URI schemes |
| [`kcoap-transport-ktor`](kcoap-transport-ktor) | UDP on ktor-network (JVM, Apple) |
| [`kcoap-testkit`](kcoap-testkit/README.md) | Simulated network with loss, duplication and reordering, for tests |
| [`kcoap-coap`](kcoap-coap) | Messages, codecs, the CoAP endpoint, `CoapClient`, `CoapServer` |
| [`kcoap-oscore`](kcoap-oscore/README.md) | OSCORE |
| [`kcoap-edhoc`](kcoap-edhoc/README.md) | EDHOC Initiator and Responder |
| [`kcoap-edhoc-oscore`](kcoap-edhoc-oscore/README.md) | EDHOC over CoAP, EDHOC→OSCORE, the combined request |
| [`kcoap-dtls`](kcoap-dtls/README.md) | DTLS interfaces and registries; the engine is implemented last |

## Documentation

- [Architecture](docs/architecture.md): layers, transports, security layering, extension points.
- [Client and server guide](docs/guide-client-server.md), [logging](docs/logging.md),
  [testing](docs/testing.md).
- [RFC compliance](docs/rfc-compliance.md), [roadmap](docs/roadmap.md), [interop](interop/README.md).
- [Code style](docs/code-style.md): how the code is written, for contributors.

## Design in one paragraph

Protocol code does no I/O: state machines take bytes and time and return bytes and timers, and thin
adapters connect them to sockets, so every layer is testable with fixed vectors and on virtual time.
All cryptography goes through one `CryptoBackend`, so keys can live in a secure element and
post-quantum algorithms plug in. OSCORE sits between the exchange and messaging layers, as RFC 8613
draws it, behind a small SPI that EDHOC's combined request decorates. Everything that varies is an
interface with a default: transports, crypto, credential resolution, EAD, logging sinks, interceptors.

## Build and test

Needs a JDK 17 or newer.

```bash
./gradlew jvmTest                  # every test on the JVM
./gradlew koverHtmlReport          # coverage report in build/reports/kover/html
./gradlew linuxX64Test             # the common tests as native code (Kotlin/Native, OpenSSL)
./gradlew macosArm64Test           # common tests against Apple crypto, on macOS
```

The interop tests run when Docker and the pinned images are present and skip otherwise:

```bash
docker build -t kcoap/libcoap:v4.3.5b interop/libcoap
docker build -t kcoap/libedhoc:0363bd3 interop/libedhoc
```

Test vectors are generated from the RFC texts and NIST files by `tools/regen-vectors.sh` and
`tools/regen-pq-vectors.sh`.

## Before the first publish

- The Maven group and package are `dev.kcoap`; publishing under it needs the `kcoap.dev` domain (or
  switch to an `io.github.<user>` group).
- Pick a license.
