# kcoap documentation

[README](../README.md) · [Website](https://kcoap.dev) · [API reference](https://adihack.github.io/kcoap/) ·
[Changelog](../CHANGELOG.md)

Every page about kcoap, grouped by what you want to do. The API reference is published to GitHub Pages
with the first release; until then `./gradlew dokkaGenerate` builds it into `build/dokka/html`.

## Start here

| Page | What you find there |
|---|---|
| [README](../README.md) | What kcoap is, its highlights and the state of every protocol |
| [Installation](../README.md#installation) | The `dev.kcoap` coordinates and how to use a local snapshot until the first release |
| [Quick start](../README.md#quick-start) | An OSCORE server and client, then EDHOC with the combined request, in a few lines each |
| [Platforms](../README.md#platforms) | Which targets build which modules, and what each platform's cryptography offers |

## Guides

| Page | What you find there |
|---|---|
| [Client and server guide](guide-client-server.md) | Serving and requesting resources over UDP, TCP and WebSockets, endpoint tuning, Q-Block, message security, tests without a network; every example compiles and runs in the tests |
| [Logging](logging.md) | Configuring kcoap's logging, sending records to sinks such as a ring buffer, and tracing one component alone |
| [OSCORE](../kcoap-oscore/README.md) | Security contexts, OSCORE clients and servers, sequence numbers and freshness |
| [Durable sequence-number stores](../kcoap-oscore/README.md#writing-a-durable-store) | Recipes per runtime (JVM and Android, Apple, Linux, browsers and Node.js) that keep pre-shared OSCORE keys safe across restarts |
| [EDHOC with OSCORE over CoAP](../kcoap-edhoc-oscore/README.md) | The EDHOC resource and server, the client handshake, the combined request and what handlers learn about the peer |
| [Android app](../samples/android-consumer/README.md) | A sample app at minSdk 26 with R8, what an app needs, and the API-level findings for kcoap's libraries |
| [Browsers](architecture.md#browsers) | What runs in a browser, how it reaches UDP devices through a WebSocket proxy, and what WebCrypto lacks |

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

## Protocol compliance and interop

| Page | What you find there |
|---|---|
| [RFC compliance](rfc-compliance.md) | Every RFC kcoap implements, section by section, with its status and the vectors and tests behind it |
| [Interop](../interop/README.md) | The libcoap and libedhoc cross-tests in both roles, how they run, and the upstream deviations they found |
| [Testing: live interop](testing.md#4-live-interop-with-libcoap-and-libedhoc) | How the interop suite fits with the vector, differential and fuzz tests |

## Reference

| Page | What you find there |
|---|---|
| [API reference](https://adihack.github.io/kcoap/) | Every public declaration with its KDoc (Dokka); published with the first release |
| [kcoap-common](../kcoap-common) | Logging API and sinks, hex, constant-time helpers, the opt-in markers (no README yet; see the API reference) |
| [kcoap-crypto](../kcoap-crypto) | The `CryptoBackend` SPI, AES-CCM, HKDF, SHAKE256, KMAC256 (no README yet; see the API reference) |
| [kcoap-crypto-default](../kcoap-crypto-default/README.md) | The default backend per platform, its capabilities and fallbacks, and its R8 rules |
| [kcoap-crypto-bc](../kcoap-crypto-bc/README.md) | The post-quantum backend: ML-KEM and ML-DSA on Bouncy Castle |
| [kcoap-cbor](../kcoap-cbor/README.md) | CBOR and CBOR sequences |
| [kcoap-cose](../kcoap-cose/README.md) | The COSE structures and key formats OSCORE and EDHOC need |
| [kcoap-transport](../kcoap-transport/README.md) | The seam between protocol code and the network: transports, endpoints, schemes |
| [kcoap-transport-ktor](../kcoap-transport-ktor/README.md) | UDP and TCP transports on ktor-network |
| [kcoap-transport-ktor-websocket](../kcoap-transport-ktor-websocket/README.md) | The WebSocket client transport, browsers included |
| [kcoap-transport-ktor-websocket-server](../kcoap-transport-ktor-websocket-server/README.md) | The WebSocket server transport, standalone or mounted in a ktor application |
| [kcoap-testkit](../kcoap-testkit/README.md) | The in-memory network and connections for tests of kcoap and of applications |
| [kcoap-coap](../kcoap-coap/README.md) | CoAP messages, codecs, the endpoint, `CoapClient` and `CoapServer` |
| [kcoap-oscore](../kcoap-oscore/README.md) | OSCORE: what is implemented and what verifies it |
| [kcoap-edhoc](../kcoap-edhoc/README.md) | EDHOC Initiator and Responder as sans-IO state machines, suites and credentials |
| [kcoap-edhoc-oscore](../kcoap-edhoc-oscore/README.md) | EDHOC over CoAP, EDHOC→OSCORE and the combined request |
| [kcoap-dtls](../kcoap-dtls/README.md) | The DTLS interfaces and registries; the engine comes last and the module is not published until then |

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
