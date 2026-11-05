# kcoap

CoAP, OSCORE and EDHOC for Kotlin Multiplatform: one implementation for Android, iOS, JVM servers,
Linux gateways, browsers and Node.js. kcoap aims at complete compliance with the published RFCs, proven by the RFCs'
own test vectors and by live interop with libcoap (by RFC authors) and libedhoc. It also supports the
post-quantum EDHOC suite.

| Protocol | RFC | State | Verified by |
|---|---|---|---|
| CoAP over UDP: messaging, client, server, Observe, Block-wise, Echo/Request-Tag, link format | 7252, 7641, 7959, 9175, 6690 | done | unit tests on a simulated lossy network; libcoap both directions |
| CoAP over TCP and WebSockets: CSM, Ping/Pong, Release, Abort, BERT, Observe | 8323 | done (TLS for `coaps+tcp` pending) | RFC 8323 figures byte for byte; in-memory and loopback tests; libcoap both directions over TCP and WebSockets |
| OSCORE | 8613 | done (Appendix B.2 pending) | every Appendix C vector byte for byte; libcoap both directions, raw and through the stack |
| EDHOC: methods 0–3, suites 0–6, 24, post-quantum suite -24 | 9528 | done | both RFC 9529 traces byte for byte, all §4 invalid messages; libedhoc interop in progress |
| EDHOC over CoAP, EDHOC→OSCORE, combined request | 9528 App. A, 9668 | done | RFC 9668 Figure 4 byte for byte; full-stack tests |
| Post-quantum crypto: ML-KEM, ML-DSA | FIPS 203, 204 | done (JVM, Android) | NIST ACVP vectors |
| DTLS 1.2 | 6347, 7925, 9146 | interfaces only; engine last | — |

The details, section by section, are in [docs/rfc-compliance.md](docs/rfc-compliance.md).

## Quick start

```kotlin
// A server with message security: its resources take OSCORE requests only, unless they say otherwise.
val contexts = InMemoryContextRepository().apply { add(serverContext) }
val server = CoapServer(KtorUdpTransport.bind("0.0.0.0", 5683), scope, CoapServerConfig(security = OscoreServer(contexts))) {
    resource("lights") { get { CoapResponse.content("on for ${securityIdentity?.description}") } }
    resource("status") {
        access = AccessPolicy.Public // for anyone, with or without OSCORE
        get { CoapResponse.content("up") }
    }
}

// A client that talks OSCORE to it.
val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig(security = OscoreClient(clientContext)))
println(client.get("coap://192.0.2.1/lights").payloadAsText())
```

Message security fails closed ([ADR 0008](docs/adr/0008-fail-closed-message-security.md)):

- **Server.** Each resource has an `AccessPolicy` (`Public`, `MessageSecurityRequired`, `Authenticated`, or
  your own on the request's identities), inherited down the tree; with `security` set, the default is
  `MessageSecurityRequired`. A refused request gets 4.01 before any handler, block-wise buffer, observation
  or burst, and `/.well-known/core` lists only what the caller may use.
- **Client.** It acts only on responses it verified. An unprotected error, which anyone on the path can
  send, is never repeated with its Echo or continued: the request fails with
  `CoapException(UNVERIFIED_RESPONSE)`, which carries it. A request without a context or installed
  protection fails instead of going out in plaintext, unless `allowUnprotected` says otherwise.

With EDHOC instead of pre-shared OSCORE keys, the client runs the handshake first and its first
OSCORE request carries EDHOC message_3 (RFC 9668), two round trips in all:

```kotlin
val security = PeerSecurity()
val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig(security = security))
val (session, protection) = EdhocClientHandshake.over(client, serverUri, initiatorConfig, crypto).runCombined()
security.install(serverEndpoint, protection)
client.get("coap://192.0.2.1/lights")
```

The server side mounts `EdhocResource` (public of its own) under `/.well-known` and uses
`CombinedRequestServer`; its handlers and policies see an `EdhocIdentity` with the client's authenticated
credential. See
[kcoap-edhoc-oscore](kcoap-edhoc-oscore/README.md). More in the
[client and server guide](docs/guide-client-server.md).

## Modules

| Module | What it is |
|---|---|
| [`kcoap-common`](kcoap-common) | Logging API and sinks, hex, constant-time helpers, the opt-in markers `@InternalKcoapApi` and `@ExperimentalKcoapApi` |
| [`kcoap-crypto`](kcoap-crypto) | The `CryptoBackend` SPI (all `suspend`, opaque key handles), AES-CCM, HKDF, SHAKE256, KMAC256 |
| [`kcoap-crypto-default`](kcoap-crypto-default/README.md) | Backend on cryptography-kotlin: JCA on JVM/Android, CryptoKit and CommonCrypto on Apple, OpenSSL on Linux, WebCrypto in browsers and Node.js |
| [`kcoap-crypto-bc`](kcoap-crypto-bc/README.md) | Post-quantum backend on Bouncy Castle: ML-KEM and ML-DSA (JVM, Android) |
| [`kcoap-cbor`](kcoap-cbor/README.md) | Deterministic CBOR and CBOR sequences |
| [`kcoap-cose`](kcoap-cose/README.md) | COSE structures, COSE_Key, CWT Claims Sets |
| [`kcoap-transport`](kcoap-transport/README.md) | Transport interfaces, endpoints, peer identities, URI schemes |
| [`kcoap-transport-ktor`](kcoap-transport-ktor) | UDP and TCP on ktor-network (JVM, Apple, Linux; not the web) |
| [`kcoap-transport-ktor-websocket`](kcoap-transport-ktor-websocket/README.md) | WebSocket client on the ktor client, any engine, browsers included |
| [`kcoap-transport-ktor-websocket-server`](kcoap-transport-ktor-websocket-server/README.md) | WebSocket server on the ktor server, standalone or mounted |
| [`kcoap-testkit`](kcoap-testkit/README.md) | Simulated network with loss, duplication and reordering, and in-memory TCP/WebSocket connections, for tests |
| [`kcoap-coap`](kcoap-coap) | Messages, codecs, the CoAP endpoint, `CoapClient`, `CoapServer` |
| [`kcoap-oscore`](kcoap-oscore/README.md) | OSCORE |
| [`kcoap-edhoc`](kcoap-edhoc/README.md) | EDHOC Initiator and Responder |
| [`kcoap-edhoc-oscore`](kcoap-edhoc-oscore/README.md) | EDHOC over CoAP, EDHOC→OSCORE, the combined request |
| [`kcoap-dtls`](kcoap-dtls/README.md) | DTLS interfaces and registries; the engine is implemented last, and the module is not published until then |

Core modules (protocol, wire and SPI code) are common Kotlin only, which the build checks. Every
published module has its public API committed as ABI dumps in `<module>/api`, so API changes are
reviewed as such; declarations marked `@InternalKcoapApi` are plumbing between kcoap's modules, not API
([code style](docs/code-style.md#dependencies-and-api)).

## Platforms

Everything is common Kotlin; the platforms differ only in what their cryptography offers, which the
crypto backend reports through capability checks (`crypto.supports(algorithm)`,
`CipherSuite.isSupportedBy(crypto, method)`; [ADR 0001](docs/adr/0001-crypto-capabilities.md)) and fills
with common-code fallbacks where they are small and safe. EDHOC offers and accepts only the suites the
backend runs. Only adapters that wrap a runtime-bound library build for fewer targets.

| Target | Runtime | Crypto under `DefaultCryptoBackend` | Modules |
|---|---|---|---|
| `jvm` | JVM servers, Android | JCA | all |
| `iosArm64`, `iosSimulatorArm64`, `macosArm64` | Apple | CryptoKit, CommonCrypto | all but `kcoap-crypto-bc` |
| `linuxX64` | Linux gateways | OpenSSL 3 | all but `kcoap-crypto-bc` |
| `js`, `wasmJs` | browsers, Node.js | WebCrypto | all but `kcoap-crypto-bc`, `kcoap-transport-ktor` and `kcoap-transport-ktor-websocket-server` |

**In a browser today**: CoAP messages and codecs (UDP and RFC 8323 framing), OSCORE, EDHOC (suites
0–3, 6 and 24) and the EDHOC→OSCORE glue, and the client and server stack over a `DatagramTransport`
the application supplies (as `kcoap-testkit`'s in-memory network does in the tests), and over
WebSockets (RFC 8323) with `kcoap-transport-ktor-websocket`, whose js tests also run in a headless
browser against a JVM listener (Firefox so far; a CI job in Chrome). Browsers have no UDP, so reaching a
device takes the WebSocket transport and a proxy such as libcoap's `coap-server`; OSCORE and EDHOC stay
end to end across it. What WebCrypto lacks:

- ChaCha20/Poly1305: `UnsupportedAlgorithmException`, so EDHOC suites 4 and 5 are neither offered nor
  accepted there.
- AES-ECB: the AES block under CCM comes from AES-CTR instead, one asynchronous call per block.
- Compressed points: expanded in common code on every platform.
- X25519 and Ed25519 in browsers that predate them, 192-bit AES keys in Chromium:
  `UnsupportedAlgorithmException`; ask `supports(...)` before offering them.

Details in [docs/architecture.md](docs/architecture.md#browsers) and
[kcoap-crypto-default](kcoap-crypto-default/README.md).

## Documentation

- [Architecture](docs/architecture.md): layers, transports, security layering, extension points.
- [Client and server guide](docs/guide-client-server.md), [logging](docs/logging.md),
  [testing](docs/testing.md).
- [RFC compliance](docs/rfc-compliance.md), [roadmap](docs/roadmap.md), [interop](interop/README.md).
- [Project checklist](docs/checklist.md): every requirement with its status, evidence and next step.
- [Code style](docs/code-style.md): how the code is written, for contributors.

## Design in one paragraph

Protocol code does no I/O: state machines take bytes and time and return bytes and timers, and thin
adapters connect them to sockets, so every layer is testable with fixed vectors and on virtual time.
All cryptography goes through one `CryptoBackend`, so keys can live in a secure element and
post-quantum algorithms plug in. OSCORE sits between the exchange and messaging layers, as RFC 8613
draws it, behind a small SPI that EDHOC's combined request decorates. Everything that varies is an
interface with a default: transports, crypto, credential resolution, EAD, logging sinks, interceptors.

## Build and test

Needs a JDK 17 or newer; Gradle downloads everything else, Node.js for the web tests included. Each
test suite is one command:

```bash
scripts/test unit       # every unit test on every target this machine builds: JVM, Linux native, JS, Wasm,
                        # then the build checks (ABI dumps, common-only cores, build-logic tests)
scripts/test interop    # cross-tests against libcoap and libedhoc in both roles (builds their Docker images)
scripts/test coverage   # unit and interop tests with the coverage report and the 90% floor
scripts/test apple      # the common tests against CryptoKit and CommonCrypto (macOS only)
scripts/test all        # unit, interop and coverage
```

Extra arguments go to Gradle (`scripts/test interop --tests '*Oscore*'`), and every run ends with a
table of tests per target. CI runs the same suites on Linux and macOS for every push and pull request;
a `v*` tag publishes a release ([releasing](docs/releasing.md)). Details, including what each suite
covers, are in [testing](docs/testing.md).

Test vectors are generated from the RFC texts and NIST files by `tools/regen-vectors.sh` and
`tools/regen-pq-vectors.sh`.

## Before the first publish

- The Maven group and package are `dev.kcoap`, backed by the `kcoap.dev` domain; the namespace still
  has to be verified in the Central Portal ([docs/releasing.md](docs/releasing.md)).
- Pick a license.
