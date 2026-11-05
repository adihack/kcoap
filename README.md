<div align="center">

<img src="docs/assets/kcoap-logo.svg" alt="kcoap logo" width="112" height="112">

<h1>kcoap</h1>

<p><strong>CoAP, OSCORE and EDHOC for Kotlin Multiplatform</strong></p>

<p>
  <a href="https://github.com/adihack/kcoap/actions/workflows/ci.yml"><img src="https://github.com/adihack/kcoap/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License: Apache-2.0"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&amp;logoColor=white" alt="Kotlin 2.4.21"></a>
  <a href="#platforms"><img src="https://img.shields.io/badge/platforms-JVM%20%7C%20Android%20%7C%20iOS%20%7C%20macOS%20%7C%20Linux%20%7C%20JS%20%7C%20Wasm-0B6E79" alt="Platforms: JVM, Android, iOS, macOS, Linux, JS, Wasm"></a>
  <a href="https://kcoap.dev"><img src="https://img.shields.io/badge/website-kcoap.dev-0B6E79" alt="Website: kcoap.dev"></a>
</p>

<p>
  <a href="https://kcoap.dev">Website</a> ·
  <a href="docs/README.md">Documentation</a> ·
  <a href="docs/guide-client-server.md">Guide</a> ·
  <a href="docs/rfc-compliance.md">RFC compliance</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="SPONSORS.md">Sponsor</a>
</p>

</div>

kcoap™ is CoAP, OSCORE and EDHOC for Kotlin Multiplatform: one implementation, in common Kotlin, for
Android, iOS, JVM servers, Linux gateways, browsers and Node.js. It aims at complete compliance with the
published RFCs and checks each part against the RFCs' own test vectors and in live interop with
libcoap, whose maintainers include RFC authors, and libedhoc.

## Highlights

- **RFC vectors, byte for byte.** Every OSCORE vector of RFC 8613 Appendix C; both EDHOC traces of
  RFC 9529, in both roles, and all of its invalid messages; the combined request of RFC 9668 Figure 4;
  the framing figures of RFC 8323.
- **Live interop in both roles.** CI runs kcoap as client and as server against libcoap 4.3.5b (UDP,
  TCP, WebSockets, OSCORE, Q-Block), and as Initiator and as Responder against libedhoc, for every suite
  and method both implement. The upstream deviations found on the way are listed in
  [interop](interop/README.md) and pinned by tests where they can be.
- **Post-quantum EDHOC.** The KEM-based suite -24 (ML-KEM-512, ML-DSA-44) of
  draft-ietf-lake-pqsuites runs on the JVM and Android, on ML-KEM and ML-DSA checked against NIST's ACVP
  vectors, and live against libedhoc's liboqs build in both roles.
- **Q-Block (RFC 9177).** Client and server, with Observe and under OSCORE, over UDP, TCP and
  WebSockets. Every figure of the RFC replays as a test on virtual time, and soaks run at 10 % and 30 %
  loss. Off by default ([ADR 0006](docs/adr/0006-q-block.md)).
- **Fail-closed message security.** By default a server with OSCORE refuses unprotected requests
  before any handler, buffer or observation sees them, and a client acts only on responses it verified
  ([ADR 0008](docs/adr/0008-fail-closed-message-security.md)).
- **Sans-IO protocol code.** State machines take bytes and time and return bytes and timers, so each
  layer is tested with fixed vectors on virtual time; sockets live in thin adapters.
- **Every target from one code base.** The same common tests pass in CI on the JVM, Linux, macOS, the
  iOS simulator, and JS and Wasm on Node.js; Android uses the JVM artifacts from API level 26.

## Protocol support

States follow [docs/checklist.md](docs/checklist.md), which names every open item and its next step;
[docs/rfc-compliance.md](docs/rfc-compliance.md) has the detail section by section.

| Protocol | RFC | State | Verified by |
|---|---|---|---|
| CoAP over UDP: messaging, client and server, options, URIs, conditional requests | 7252 | partial: caching (§5.6), proxying (§5.7) and multicast (§8) to do; DTLS (§9) last | per-section unit tests and end-to-end runs on a simulated lossy network; libcoap both directions |
| Link format, `/.well-known/core` | 6690 | done | RFC 6690 §5 examples; libcoap discovery both directions |
| Observe | 7641 | partial: the Content-Format of notifications and 4.06 (§4.2) to do | `ObserveTest`; libcoap over UDP, TCP and WebSockets |
| Block-wise transfers | 7959 | partial: inner Block-wise only; If-None-Match is repeated after the first Block1 block (§2.10) | `BlockwiseTest`; libcoap both directions |
| Q-Block | 9177 | done, off by default; §4.8 (multicast) and §8–9 (proxies) not applicable | every figure of §10 and Appendices A–B on virtual time; soaks at 10 % and 30 % loss; libcoap 4.3.5b both directions over UDP, TCP and WebSockets |
| FETCH, PATCH, iPATCH | 8132 | partial: the FETCH body is not part of the observation and Block2 keys | `RequestResponseTest`, `ObserveTest` |
| No-Response | 7967 | partial: the server is done; the client still waits for its timeout | `RequestResponseTest`, `EdgeCaseTest` |
| Echo, Request-Tag | 9175 | partial: address verification against amplification done; server freshness checks (§2.3) to do | `MessageSecurityTest`, `BlockwiseTest`, `AmplificationTest`; libcoap |
| Hop-Limit | 8768 | partial: the option and 5.08 exist; the processing belongs to proxies, which kcoap does not have yet | `CoapModelTest` |
| CoAP over TCP and WebSockets: CSM, Ping/Pong, Release, Abort, BERT, Observe | 8323 | done, except TLS for `coaps+tcp` | RFC 8323 figures byte for byte; in-memory and loopback tests; libcoap both directions over TCP and WebSockets |
| OSCORE | 8613 | partial: Outer Block-wise, protected signaling over TCP, proxies, HTTP and Appendix B.2 to do | every Appendix C vector byte for byte; libcoap both directions, raw and through the stack |
| EDHOC: methods 0–3, suites 0–6 and 24 | 9528 | partial: suite 25, CWT and C509 credentials, and the Initiator's C_R = C_I check to do | both RFC 9529 traces byte for byte, all §4 invalid messages; libedhoc both roles |
| Post-quantum EDHOC: KEM-based suite -24 | draft-ietf-lake-pqsuites §3 | done (JVM, Android) | round trips with real ML-KEM and ML-DSA; libedhoc (liboqs) both roles |
| EDHOC over CoAP, EDHOC→OSCORE, combined request | 9528 App. A, 9668 | partial: forward flow and combined request done; reverse flow (App. A.2.2) to do; no independent peer yet | RFC 9668 Figure 4 byte for byte; full-stack tests |
| ML-KEM, ML-DSA | FIPS 203, 204 | done (JVM, Android) | NIST ACVP vectors |
| DTLS 1.2 | 6347, 7925, 9146 | interfaces only; the engine comes last | — |

## Installation

Nothing is published yet. The coordinates are `dev.kcoap:<module>:<version>`; the first release goes
to Maven Central once the `dev.kcoap` namespace is verified and the release freeze
([releasing](docs/releasing.md)) is over. Until then, publish a snapshot into your local Maven
repository from a clone:

```bash
git clone https://github.com/adihack/kcoap.git && cd kcoap
./gradlew publishToMavenLocal    # dev.kcoap:*:0.1.0-SNAPSHOT into ~/.m2/repository
```

The iOS and macOS artifacts build only on a Mac. Then, in your build:

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()    // kcoap 0.1.0-SNAPSHOT, until the first release is on Maven Central
    mavenCentral()  // kcoap's own dependencies
}

dependencies {
    val kcoap = "0.1.0-SNAPSHOT"
    implementation("dev.kcoap:kcoap-coap:$kcoap")            // CoAP client and server
    implementation("dev.kcoap:kcoap-transport-ktor:$kcoap")  // UDP and TCP sockets
    implementation("dev.kcoap:kcoap-crypto-default:$kcoap")  // the platform's cryptography
    implementation("dev.kcoap:kcoap-oscore:$kcoap")          // OSCORE
    implementation("dev.kcoap:kcoap-edhoc-oscore:$kcoap")    // EDHOC, over CoAP and into OSCORE
}
```

In a Kotlin Multiplatform project the same coordinates go into `commonMain.dependencies`, except the
adapters a target lacks: `kcoap-transport-ktor` builds for the JVM, Apple and Linux, and browsers use
`kcoap-transport-ktor-websocket` instead ([platforms](#platforms)). Use one version for every kcoap
module ([versioning](docs/versioning.md)).

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
val server = CoapUri.parse("coap://192.0.2.1")
val (session, protection) = EdhocClientHandshake.over(client, server, initiatorConfig, crypto).runCombined()
security.install(client, server, protection)
client.get("coap://192.0.2.1/lights")
```

The server side mounts `EdhocResource` (public of its own) under `/.well-known` and uses
`CombinedRequestServer` around its `OscoreServer`; the `EdhocServer` behind both adds a context per handshake
to a `MutableSecurityContextRepository`, which a production server bounds. Its handlers and policies see an
`EdhocIdentity` with the client's authenticated credential. See
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
| [`kcoap-transport-ktor`](kcoap-transport-ktor/README.md) | UDP and TCP on ktor-network (JVM, Apple, Linux; not the web) |
| [`kcoap-transport-ktor-websocket`](kcoap-transport-ktor-websocket/README.md) | WebSocket client on the ktor client, any engine, browsers included |
| [`kcoap-transport-ktor-websocket-server`](kcoap-transport-ktor-websocket-server/README.md) | WebSocket server on the ktor server, standalone or mounted |
| [`kcoap-testkit`](kcoap-testkit/README.md) | Simulated network with loss, duplication and reordering, and in-memory TCP/WebSocket connections, for tests |
| [`kcoap-coap`](kcoap-coap/README.md) | Messages, codecs, the CoAP endpoint, `CoapClient`, `CoapServer` |
| [`kcoap-oscore`](kcoap-oscore/README.md) | OSCORE |
| [`kcoap-edhoc`](kcoap-edhoc/README.md) | EDHOC Initiator and Responder |
| [`kcoap-edhoc-oscore`](kcoap-edhoc-oscore/README.md) | EDHOC over CoAP, EDHOC→OSCORE, the combined request |
| [`kcoap-dtls`](kcoap-dtls/README.md) | DTLS interfaces and registries; the engine is implemented last, and the module is not published until then |

Core modules (protocol, wire and SPI code) are common Kotlin only, which the build checks. Every
published module has its public API committed as ABI dumps in `<module>/api`, so API changes are
reviewed as such; declarations marked `@InternalKcoapApi` are plumbing between kcoap's modules, not API
([code style](docs/code-style.md#dependencies-and-api), [versioning](docs/versioning.md)).

## Platforms

Everything is common Kotlin; the platforms differ only in what their cryptography offers, which the
crypto backend reports through capability checks (`crypto.supports(algorithm)`,
`CipherSuite.isSupportedBy(crypto, method)`; [ADR 0001](docs/adr/0001-crypto-capabilities.md)) and fills
with common-code fallbacks where they are small and safe. EDHOC offers and accepts only the suites the
backend runs. Only adapters that wrap a runtime-bound library build for fewer targets.

| Target | Runtime | Crypto under `DefaultCryptoBackend` | Modules |
|---|---|---|---|
| `jvm` | JVM servers, Android 8.0 and later (minSdk 26) | JCA | all |
| `iosArm64`, `iosSimulatorArm64`, `macosArm64` | Apple | CryptoKit, CommonCrypto | all but `kcoap-crypto-bc` |
| `linuxX64` | Linux gateways | OpenSSL 3 | all but `kcoap-crypto-bc` |
| `js`, `wasmJs` | browsers, Node.js | WebCrypto | all but `kcoap-crypto-bc`, `kcoap-transport-ktor` and `kcoap-transport-ktor-websocket-server` |

<details>
<summary><strong>In a browser</strong>: what runs today, how it reaches devices, and what WebCrypto lacks</summary>

<br>

CoAP messages and codecs (UDP and RFC 8323 framing), OSCORE, EDHOC (suites 0–3, 6 and 24) and the
EDHOC→OSCORE glue run in a browser, and so does the client and server stack, over a `DatagramTransport`
the application supplies (as `kcoap-testkit`'s in-memory network does in the tests) and over WebSockets
(RFC 8323) with `kcoap-transport-ktor-websocket`, whose js tests also run in a headless browser against
a JVM listener (Firefox so far; a CI job in Chrome). Browsers have no UDP, so reaching a device takes the
WebSocket transport and a proxy such as libcoap's `coap-server`; OSCORE and EDHOC stay end to end across
it. What WebCrypto lacks:

- ChaCha20/Poly1305: `UnsupportedAlgorithmException`, so EDHOC suites 4 and 5 are neither offered nor
  accepted there.
- AES-ECB: the AES block under CCM comes from AES-CTR instead, one asynchronous call per block.
- Compressed points: expanded in common code on every platform.
- X25519 and Ed25519 in browsers that predate them, 192-bit AES keys in Chromium:
  `UnsupportedAlgorithmException`; ask `supports(...)` before offering them.

Details in [docs/architecture.md](docs/architecture.md#browsers) and
[kcoap-crypto-default](kcoap-crypto-default/README.md).

</details>

<details>
<summary><strong>On Android</strong>: API level 26 and later, and the checks that hold that line</summary>

<br>

The app uses the `jvm` artifacts, from API level 26 (Android 8.0) on. Checks hold that line:
`checkAndroidApi`, part of `check` and `scripts/test unit`, runs animal-sniffer over every module's jvm
jar against the API 26 signature without the Android SDK, `checkAndroidApiDependencies` does the same for
the libraries behind them and fails on any use not yet triaged, and CI builds
[samples/android-consumer](samples/android-consumer/README.md), an app at minSdk 26 that uses kcoap from
Maven, with R8 on its release builds and lint. Apps need no R8 rules of their own and no core library
desugaring; the sample's README lists what an app needs (Kotlin 2.3 or newer, Java 17 bytecode) and what
the check found in ktor, cryptography-kotlin and Bouncy Castle. Not yet run on an emulator or device
(PL-2).

</details>

## Documentation

The [documentation hub](docs/README.md) lists every guide, design record and reference page. To start:

| Read | For |
|---|---|
| [Client and server guide](docs/guide-client-server.md) | serving and requesting resources over UDP, TCP and WebSockets, Q-Block, message security |
| [Architecture](docs/architecture.md) | layers, transports, security layering, extension points |
| [RFC compliance](docs/rfc-compliance.md) and [interop](interop/README.md) | what is implemented, section by section, and how it is cross-tested |
| [API reference](https://adihack.github.io/kcoap/) | every public declaration (Dokka); published with the first release, until then `./gradlew dokkaGenerate` builds it into `build/dokka/html` |

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
                        # then the build checks (ABI dumps, common-only cores, Android API level,
                        # build-logic tests)
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

## Project status

kcoap is in development: version `0.1.0-SNAPSHOT`, nothing released yet, and the website at
[kcoap.dev](https://kcoap.dev) goes live later.

- [Roadmap](docs/roadmap.md): the milestones, built bottom-up, each verified before the next depends on it.
- [Checklist](docs/checklist.md): every requirement with its status, evidence and next step, ending with
  the biggest gaps in the order that most moves kcoap toward its goals.
- [Release freeze](docs/releasing.md#release-freeze): no release until the API-breaking work packages of
  the [refactoring plan](docs/target-architecture.md) have merged. Before 1.0 a minor release may still
  break the API, always with a migration note ([versioning](docs/versioning.md)).
- **DTLS comes last.** Until it lands there is no `coaps://`; OSCORE and EDHOC protect messages end to
  end over any transport.

## Contributing

Contributions are welcome. kcoap uses the [Developer Certificate of Origin](DCO) instead of a CLA: sign
off every commit with `git commit -s`, which adds a `Signed-off-by` line matching the commit's author,
and a check on every pull request verifies it. Contributions are licensed under Apache-2.0 and you keep
your copyright. [CONTRIBUTING.md](CONTRIBUTING.md) has the workflow, and the
[Code of Conduct](CODE_OF_CONDUCT.md) applies to everyone who takes part. The [code style](docs/code-style.md) and
[testing](docs/testing.md) pages say how the code is written and tested.

## Security

Do not report vulnerabilities in public issues. [SECURITY.md](SECURITY.md) says how to report them
privately and what to expect.

## Support

Questions, bug reports and feature requests go to [GitHub Issues](https://github.com/adihack/kcoap/issues);
[SUPPORT.md](SUPPORT.md) says what to include and what to expect from a project with one maintainer.

## Sponsors

kcoap has no sponsors yet. Sponsorship through GitHub Sponsors or Open Collective is planned, and
[SPONSORS.md](SPONSORS.md) says what it pays for. Sponsors will be listed in SPONSORS.md, in this
section and at [kcoap.dev/sponsors](https://kcoap.dev/sponsors).

## License

kcoap is licensed under the [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE) and the
[third-party material](docs/third-party.md).

Copyright 2026 Adrian Sołtys and the kcoap contributors.

kcoap™ and kcoap.dev™ are trademarks of Adrian Sołtys. The license grants no permission to use them
beyond describing the origin of the work (Apache-2.0, section 6); [TRADEMARKS.md](TRADEMARKS.md) says
how the names may be used.
