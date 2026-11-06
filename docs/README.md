# kcoap documentation

[README](../README.md) · [Website](https://kcoap.dev) · [API reference](https://adihack.github.io/kcoap/) ·
[Changelog](../CHANGELOG.md)

Every page about kcoap, grouped by what you want to do. Each release publishes the API reference to GitHub
Pages; `./gradlew dokkaGenerate` builds it into `build/dokka/html`.

## Start here

| Page | What you find there |
|---|---|
| [README](../README.md) | What kcoap is, its highlights and the state of every protocol |
| [Getting started](getting-started.md) | Which modules each use case needs (client, server, OSCORE, EDHOC, post-quantum, each transport, each platform), adding them with one version through `kcoap-bom`, and a first client and server, OSCORE and EDHOC, compiled and run by the tests |
| [Installation](../README.md#installation) | The `dev.kcoap` coordinates on Maven Central and the BOM |
| [Quick start](../README.md#quick-start) | An OSCORE server and client, then EDHOC with the combined request, in a few lines each |
| [Platforms](platforms.md) | The minimum versions per target, the algorithms and EDHOC suites each target runs, the TLS each runtime has (client and server) and the ways to terminate it, the mobile lifecycle |

## Guides

| Page | What you find there |
|---|---|
| [Client and server guide](guide-client-server.md) | Serving and requesting resources over UDP, TCP and WebSockets, endpoint tuning, Q-Block, message security, tests without a network; every example compiles and runs in the tests |
| [Logging](logging.md) | Configuring kcoap's logging, sending records to sinks such as a ring buffer, and tracing one component alone |
| [OSCORE](../kcoap-oscore/README.md) | Security contexts, OSCORE clients and servers, sequence numbers and freshness |
| [Durable sequence-number stores](../kcoap-oscore/README.md#writing-a-durable-store) | Recipes per runtime (JVM and Android, Apple, Linux, browsers and Node.js) that keep pre-shared OSCORE keys safe across restarts |
| [EDHOC with OSCORE over CoAP](../kcoap-edhoc-oscore/README.md) | The EDHOC resource and server, the client handshake, the combined request and what handlers learn about the peer |
| [JVM application](../samples/jvm-consumer/README.md) | A sample application through `kcoap-bom` that runs getting started's tour: CoAP, OSCORE and EDHOC over UDP on loopback |
| [Android app](../samples/android-consumer/README.md) | A sample app at minSdk 26 with R8, what an app needs, and the API-level findings for kcoap's libraries |
| [Browser page](../samples/js-consumer/README.md) | A sample page with OSCORE over CoAP over WebSockets, keys new for each page load, and the size of its production bundle |
| [Browsers](architecture.md#browsers) | What runs in a browser, how it reaches UDP devices through a WebSocket proxy, and what WebCrypto lacks |
| [Mobile lifecycle](platforms.md#mobile-lifecycle) | What kcoap does when a phone suspends the app or changes networks, and what the app must do on Android and iOS |

## Architecture and design decisions

| Page | What you find there |
|---|---|
| [Architecture](architecture.md) | What is built: modules and their dependencies, layers, the CoAP endpoint, transports, platform adapters, extension points |
| [Target architecture](target-architecture.md) | Where the code is going: the review findings, the target module graph and the ordered refactoring work packages (WP-…) |
| [Q-Block and platforms plan](plan-qblock-and-platforms.md) | The plan behind Q-Block (QB-0 to QB-7), the server work (SRV-1, SRV-2) and first-class platform use (PL-1 to PL-4) |
| [Decision records](adr/README.md) | The index of accepted decisions and how records are kept |
| [ADR 0001](adr/0001-crypto-capabilities.md) | Crypto capabilities are asked, memoized per backend, and checked against what throws |
| [ADR 0002](adr/0002-delegation-not-subclassing.md) | Backends and adapters are final; extension is by delegation |
| [ADR 0003](adr/0003-tls-in-connectors.md) | TLS lives in connectors and listeners; DTLS is a datagram decorator |
| [ADR 0004](adr/0004-no-scheme-registry.md) | No scheme or transport registry: a sealed `Endpoint` and `Connector.schemes` |
| [ADR 0005](adr/0005-peer-link-seam.md) | One CoAP endpoint over an internal `PeerLink` seam for datagrams and connections |
| [ADR 0006](adr/0006-q-block.md) | Q-Block as sans-IO state machines, off by default, with kcoap's token, retry and lifetime policies |
| [ADR 0007](adr/0007-build-governance.md) | The build guards the architecture: common-only cores, ABI dumps, opt-in markers |
| [ADR 0008](adr/0008-fail-closed-message-security.md) | Fail-closed message security: access policies on the server, only verified responses acted on by the client |
| [ADR 0009](adr/0009-browser-topology-interop.md) | The browser topology is cross-tested through libcoap's own forward-proxy, with the device on the default port |
| [ADR 0010](adr/0010-server-side-tls.md) | Server-side TLS: termination at a reverse proxy now, a JVM SSLEngine listener later |
| [ADR 0011](adr/0011-error-model.md) | One exception type per facade under `KcoapException`; SPI signals translated at each layer's boundary; `@Throws` for Swift |
| [ADR 0012](adr/0012-configuration-shapes.md) | Builders only for the two configurations certain to grow; named-argument constructors elsewhere; no endpoint DSL; explicit `Endpoint` classes |
| [ADR 0013](adr/0013-durable-state-is-the-applications.md) | Durable state is an SPI the application supplies: no default sequence-number store, recipes instead of store modules |
| [ADR 0014](adr/0014-shared-crypto-blocks.md) | Shared crypto building blocks (X.509, SEC1, ECDSA DER) live in kcoap-crypto; no kcoap-x509 module |
| [ADR 0015](adr/0015-randomness-by-layer.md) | Randomness by layer: the CoAP stack's own CSPRNG, the security layers' `CryptoBackend.randomBytes`; no public `SecureRandom` |
| [ADR 0016](adr/0016-bom-from-published-modules.md) | One version for every module through `kcoap-bom`, generated from the published modules and checked against them |

## Protocol compliance and interop

| Page | What you find there |
|---|---|
| [RFC compliance](rfc-compliance.md) | Every RFC kcoap implements, section by section, with its status and the vectors and tests behind it |
| [Interop](../interop/README.md) | The libcoap and libedhoc cross-tests in both roles, how they run, and the upstream deviations they found |
| [Testing: live interop](testing.md#4-live-interop-with-libcoap-and-libedhoc) | How the interop suite fits with the vector, differential and fuzz tests |

## Reference

| Page | What you find there |
|---|---|
| [API reference](https://adihack.github.io/kcoap/) | Every public declaration with its KDoc (Dokka); published by each release |
| [kcoap-bom](../kcoap-bom/README.md) | The bill of materials: one version for every published module, for Gradle and Maven builds, and how its constraints are generated |
| [kcoap-common](../kcoap-common/README.md) | Logging API and sinks, hex, constant-time helpers, the opt-in markers, `KcoapException`, the root of every protocol's exception |
| [kcoap-crypto](../kcoap-crypto/README.md) | The `CryptoBackend` SPI and its contract, the common-code blocks (AES-CCM, HKDF, SHAKE256, KMAC256, SEC1 points, ECDSA DER), X.509 parsing, keys in a key store |
| [kcoap-crypto-default](../kcoap-crypto-default/README.md) | The default backend per platform, its capabilities and fallbacks, and its R8 rules |
| [kcoap-crypto-bc](../kcoap-crypto-bc/README.md) | The post-quantum backend: ML-KEM and ML-DSA on Bouncy Castle |
| [kcoap-crypto-testsuite](../kcoap-crypto-testsuite/README.md) | The conformance suite every crypto backend's tests extend: the capability contract, NIST's ACVP vectors, the capability report per target |
| [kcoap-cbor](../kcoap-cbor/README.md) | CBOR and CBOR sequences |
| [kcoap-cose](../kcoap-cose/README.md) | The COSE structures and key formats OSCORE and EDHOC need |
| [kcoap-transport](../kcoap-transport/README.md) | The seam between protocol code and the network: transports, endpoints, schemes |
| [kcoap-transport-ktor](../kcoap-transport-ktor/README.md) | UDP and TCP transports on ktor-network, and the `coaps+tcp` TLS client on the JVM |
| [kcoap-transport-ktor-websocket](../kcoap-transport-ktor-websocket/README.md) | The WebSocket client transport, browsers included |
| [kcoap-transport-ktor-websocket-server](../kcoap-transport-ktor-websocket-server/README.md) | The WebSocket server transport, standalone or mounted in a ktor application |
| [kcoap-testkit](../kcoap-testkit/README.md) | The in-memory network and connections for tests of kcoap and of applications |
| [kcoap-coap-message](../kcoap-coap-message/README.md) | CoAP messages, options, URIs, link format, the codecs and the message-security SPI, without the stack |
| [kcoap-coap](../kcoap-coap/README.md) | The CoAP endpoint, `CoapClient` and `CoapServer`, on kcoap-coap-message |
| [kcoap-oscore](../kcoap-oscore/README.md) | OSCORE: what is implemented and what verifies it |
| [kcoap-edhoc](../kcoap-edhoc/README.md) | EDHOC Initiator and Responder as sans-IO state machines, suites and credentials |
| [kcoap-edhoc-oscore](../kcoap-edhoc-oscore/README.md) | EDHOC over CoAP, EDHOC→OSCORE and the combined request |
| [kcoap-dtls](../kcoap-dtls/README.md) | The DTLS interfaces and registries; the engine comes last and the module is not published until then |
| [kcoap-swift-smoke](../kcoap-swift-smoke/README.md) | The Swift smoke test of the macOS CI job: kcoap called from Swift through a framework; not published |

## Project

| Page | What you find there |
|---|---|
| [Roadmap](roadmap.md) | The milestones, built bottom-up, with what verifies each one and its state |
| [Checklist](checklist.md) | Every project requirement with its status, evidence and next step, and the biggest gaps in order |
| [Versioning](versioning.md) | Semantic versioning, the API tiers and the ABI dumps that enforce them, deprecation, wire compatibility, platform minimums |
| [Releasing](releasing.md) | How a tag becomes a release, the release freeze, the ABI dumps, secrets, and the Maven Central namespace on kcoap.dev |
| [Testing](testing.md) | The test levels, one command per suite, build checks, CI, coverage, and how to write tests |
| [Code style](code-style.md) | How the code is laid out, named, documented and kept safe; the rules for contributors |
| [Changelog](../CHANGELOG.md) | Every notable change, with migration notes for breaking ones |
| [Website](../web/README.md) | How kcoap.dev is built and deployed: the page files, local preview, the site checks, and the Cloudflare Worker settings |

## Community and legal

| Page | What you find there |
|---|---|
| [Contributing](../CONTRIBUTING.md) | How to propose a change: issues, pull requests, tests, and the DCO sign-off on every commit |
| [Code of Conduct](../CODE_OF_CONDUCT.md) | The Contributor Covenant 2.1, adopted by reference, and how to report a problem |
| [Security policy](../SECURITY.md) | How to report a vulnerability privately, and what happens next |
| [Support](../SUPPORT.md) | Where to ask questions and report bugs, and what to expect from one maintainer |
| [Governance](../GOVERNANCE.md) | Who maintains kcoap and how decisions are made |
| [Trademarks](../TRADEMARKS.md) | How the names kcoap and kcoap.dev may be used |
| [Sponsors](../SPONSORS.md) | How to support kcoap, and its sponsors once there are any |
| [License](../LICENSE) | The Apache License 2.0 |
| [Notice](../NOTICE) | The copyright notice that travels with every copy (Apache-2.0, section 4) |
| [Developer Certificate of Origin](../DCO) | The DCO 1.1 that every commit's `Signed-off-by` line certifies |
| [Third-party material](third-party.md) | What comes from others (runtime libraries, RFC and NIST test vectors, interop peers, build tools), its licenses, and whether it ships |
