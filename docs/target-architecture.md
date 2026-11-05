<!--
  The target architecture and refactoring plan. Written on 2026-11-05 by a review of the whole code base:
  eight review lenses produced 83 findings, each one checked by an independent verifier (none refuted),
  then a design was synthesized and critiqued. Status: plan. Work packages land one by one (section 4);
  docs/architecture.md describes what is built, this file describes where it is going.
-->

# kcoap target architecture: universal by design (final)

## 0. Starting point (checked on disk, read-only)

- **Repository state.** HEAD is `f0dc39d`, "Merge CoAP over TCP and WebSockets (RFC 8323)". The tree is clean, there is no `MERGE_HEAD`, and `git worktree list` shows only the main tree.
  - The js/wasmJs work is merged in `3e28315`.
  - The release work is committed in `0faabe0`: `release.yml` publishes to Maven Central on any `v*` tag. No tag exists yet.
  - Neither concurrent agent has unmerged work on disk. If either one continues, it works under the ownership rules in §4.1. The RFC 8323 follow-ups belong to WP-W1. Build-logic and crypto-default follow-ups belong to WP-C1 and WP-A5.
- **Facts this plan relies on.**
  - `KcoapTargets` has three values: `ALL` (jvm, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, js, wasmJs), `NATIVE_AND_JVM` (kcoap-transport-ktor and -websocket-server) and `JVM` (kcoap-crypto-bc).
  - `Capabilities.kt` has `supports(...)` extensions for AEAD, KeyType and Signature only. There is none for KEM or Hash.
  - `CipherSuite.isSupportedBy(backend)` does not take the EDHOC method.
  - `CoapException.Reason` already has `SESSION_ENDED`, `CLOSED` and `CONNECTION_CLOSED`. Only kcoap-coap's stack, client and server packages use `CoapException`. The model, codec and security packages, kcoap-oscore and kcoap-edhoc-oscore do not.
  - The merged endpoint has a named-argument constructor `CoapEndpoint(scope, config, transport?, connectors, listeners)` (CoapEndpoint.kt:75-81). It still exposes `public val transport = datagramTransport ?: NoDatagramTransport`.
  - `KtorTcpTransport` rejects `usesTls`, so `coaps+tcp` does not exist.
  - The WebSocket client uses ktor-client CIO on jvm and native, including Apple. `KtorWebSocketListener` hard-codes ktor-server CIO in commonMain.
  - `StreamSecurity` has no implementation and no users.
  - The WebSocket real-server test is jvmTest only.
  - Coverage: 90% for the whole library (`kcoap.coverage`) and 95% for kcoap-edhoc (`kcoap-edhoc/build.gradle.kts:31`). Kover measures JVM tests only.
- **Findings not in this input.** The concurrency (CLR-\*) and errors/observability findings appear only by reference. They go into WP-S1 and WP-B3 (§4.5) and were not re-checked here.

---

## 1. Principles

1. **Platform neutrality.** Core modules (protocol, wire and SPI) contain only `commonMain` code: no `expect`/`actual` and no platform imports. They build for UNIVERSAL. Runtime-specific code lives only in adapter modules, and each adapter declares the smallest tier its library supports. A `check` task enforces this for cores (WP-C1).
2. **Capabilities, not targets.**
   - Code asks a seam what it can do: `CryptoBackend.supports(...)`, the per-peer link's limits and capabilities, `Connector.schemes`.
   - It asks once per backend instance and the answer is memoized.
   - Exceptions are only a backstop. `supports(x) == false` exactly when the operation would throw `UnsupportedAlgorithmException`, and the conformance suite tests that for every backend.
3. **Common fallbacks.** A small primitive with good test vectors that some runtime lacks lives in `kcoap-crypto`, used through interface defaults or protocol code. Large or side-channel-sensitive fallbacks, such as post-quantum crypto, go in opt-in modules that must pass the shared conformance suite.
4. **Sans-IO cores, thin drivers.** State machines take bytes and time and return typed effects. A driver owns one `Mutex`, never holds it across a suspension, and runs effects after releasing it.
5. **SPI, default, adapter.**
   - Each seam is defined in the lowest module that needs it. Defaults are named companion values or final classes.
   - You extend by delegation (`X by delegate`), never by subclassing an implementation, so backends are final.
   - A decorator that adds algorithms overrides the matching `supports` members.
6. **API discipline.**
   - `explicitApi()` and committed ABI dumps.
   - `api` scope exactly when the dependency's types appear in a public or protected signature (supertypes and defaulted parameters included). Every module declares what it imports.
   - `@Throws` on every public entry point that throws.
   - Written ownership rules for arrays and lists.
   - Two opt-in markers in kcoap-common: `@InternalKcoapApi` (plumbing between modules, left out of the dumps) and `@ExperimentalKcoapApi` (published SPIs that have no implementation yet).
7. **Errors.**
   - Each facade throws one protocol exception type for peer, network, security and lifecycle failures, under the root `KcoapException`.
   - `require`/`check` is for caller mistakes only. Peer input never produces `IllegalArgumentException`.
   - SPI signals are translated at layer boundaries (§3.6).
8. **Concurrency.**
   - The caller supplies the `CoroutineScope`. Cores use no `Dispatchers`, and cleanup runs under `NonCancellable`.
   - Code must be correct on single-threaded JS and on multithreaded JVM and Native, and the multithreaded case is stress-tested.
   - Every stateful public type documents its thread safety.
9. **Observability without dependencies.** Logging goes through an injected `LoggerFactory`. Events are sealed types with enum reasons, and metrics are derived from them. Secrets and payloads are never logged.
10. **Secure defaults.** The call with the least typing is the safe one. That means no hidden in-memory nonce state for provisioned keys, freshness derived automatically, only runnable suites offered (also on the raw EDHOC API), constant-time comparison, and secrets wiped.
11. **Injected time and randomness.** Protocol timers use a monotonic `TimeSource`. A wall-clock `Clock` is used only for log timestamps and certificate validity. Security layers draw from `CryptoBackend.randomBytes`. CoAP tokens and Message IDs use an injected `Random`.

---

## 2. Target module graph

### 2.1 Tiers (build-logic; plugin IDs unchanged)

| Tier (renamed from) | Targets | Plugin | Used by |
|---|---|---|---|
| `UNIVERSAL` (`ALL`) | jvm, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, js, wasmJs; linuxArm64 later (E5) | `kcoap.kmp-library` | all cores, kcoap-testkit, kcoap-crypto-testsuite, kcoap-crypto-default, kcoap-transport-ktor-websocket |
| `SOCKETS` (`NATIVE_AND_JVM`) | jvm and native only. It never includes js: one js artifact serves browsers and Node, so sockets there would ship to browsers | `kcoap.kmp-jvm-native-library` | kcoap-transport-ktor, kcoap-transport-ktor-websocket-server |
| `JVM_LIBRARY` (`JVM`) | jvm (servers and Android) | `kcoap.kmp-jvm-library` | kcoap-crypto-bc, a future JVM TLS adapter |

Two plugins are added on top of the tiers:

- `kcoap.core` marks a core module. It registers `verifyCommonOnly` in `check`, which fails when any `*Main` source set other than `commonMain` has files. `*Test` source sets stay allowed. It is applied explicitly in each core module, not tied to the tier, because UNIVERSAL adapters may need `appleMain` or `webMain`.
- `kcoap.unpublished` is for kcoap-dtls until its engine exists, and for kcoap-swift-smoke.

### 2.2 Graph

`A → B` means A depends on B in main code, `api` unless marked `impl`.

```
                          APPLICATION  (chooses backend, transports, stores)
                               │
 glue    kcoap-edhoc-oscore ──► kcoap-edhoc, kcoap-oscore, kcoap-coap
                               │
 stack   kcoap-coap ──► kcoap-coap-message, kotlinx-coroutines        (CoapException lives here)
         kcoap-oscore ──► kcoap-coap-message, kcoap-cose, kcoap-crypto, impl coroutines
         kcoap-edhoc ──► kcoap-cose, kcoap-cbor, kcoap-crypto, kcoap-common
         kcoap-dtls ──► kcoap-transport, kcoap-crypto                  (unpublished until engine)
                               │
 wire    kcoap-coap-message [NEW] ──► kcoap-transport, kcoap-common    (no coroutines)
         kcoap-cose ──► kcoap-cbor, kcoap-crypto
                               │
 seams   kcoap-crypto ──► kcoap-common     (SPI, common fallbacks, x509; exceptions under KcoapException)
         kcoap-cbor   ──► kcoap-common     (CborException under KcoapException)
         kcoap-transport ──► kcoap-common  (markers only; still no third-party deps)
 base    kcoap-common                       (no deps)

 ADAPTERS (never a main dependency of a core)
         kcoap-crypto-default ──► kcoap-crypto, impl kcoap-common, impl cryptography-kotlin   UNIVERSAL
         kcoap-crypto-bc      ──► kcoap-crypto, impl kcoap-common, impl bcprov                JVM_LIBRARY
         kcoap-transport-ktor ──► kcoap-transport, kcoap-common, ktor-network(-tls)           SOCKETS
         kcoap-transport-ktor-websocket ──► kcoap-transport, ktor-client-core;
               impl engine: Darwin on Apple, CIO on jvm/linux, JS engine on web               UNIVERSAL
         kcoap-transport-ktor-websocket-server ──► ...-websocket, ktor-server-core (no engine) SOCKETS
 SUPPORT kcoap-testkit ──► kcoap-transport, coroutines                      UNIVERSAL, published
         kcoap-crypto-testsuite [NEW] ──► kcoap-crypto, kotlin-test         UNIVERSAL, published
         kcoap-bom [NEW]  java-platform, constraints generated from the published-module list
         samples/{jvm,android,js}-consumer [NEW]  included builds, CI only, not published
 PLANNED kcoap-crypto-pq (E3), kcoap-apple (E4), key-store adapters (E7), JVM TLS listener (T1),
         Network.framework adapter (E10), HTTP transport (RFC 8613 §11)
```

### 2.3 One line per module

| Module | Responsibility | Allowed main dependencies | Tier |
|---|---|---|---|
| kcoap-common | Logging API and sinks, Hex, constant-time compare, `wipe`, byte helpers (`@InternalKcoapApi`), `KcoapException`, both markers, `LogTags` | none | UNIVERSAL, core |
| kcoap-transport | `Endpoint` (sealed, explicit equality), `CoapScheme`, `IpLiteral`, `PeerIdentity`, `DatagramTransport`, `Connection`/`Connector`/`ConnectionListener`, `DatagramSecurity` (`@ExperimentalKcoapApi`) | kcoap-common | UNIVERSAL, core |
| kcoap-cbor | Deterministic CBOR and CBOR sequences | kcoap-common | UNIVERSAL, core |
| kcoap-crypto | `CryptoBackend` with `supports` members; fallbacks (AES-CCM, HKDF, Keccak/SHAKE/KMAC, `Sec1`, ECDSA DER↔raw); `x509` package; `CertificateValidator`; key handles | kcoap-common | UNIVERSAL, core |
| kcoap-cose | COSE headers, structures, COSE_Key, CWT | kcoap-cbor, kcoap-crypto | UNIVERSAL, core |
| **kcoap-coap-message** | CoAP model, options, URI, link format, UDP and RFC 8323 codecs, `CoapFormatException`, message-security SPI | kcoap-transport, kcoap-common | UNIVERSAL, core |
| kcoap-coap | `CoapEndpoint`, internal `PeerLink`, UDP and reliable messaging, block-wise, Observe, Echo/Request-Tag, client, server, DSL, `CoapException` | kcoap-coap-message, coroutines | UNIVERSAL, core |
| kcoap-oscore | Contexts, protect/verify, replay window, persistence SPIs | kcoap-coap-message, kcoap-cose, kcoap-crypto, coroutines | UNIVERSAL, core |
| kcoap-edhoc | EDHOC state machines, suites, credentials, EAD; independent of CoAP | kcoap-cose, kcoap-cbor, kcoap-crypto, kcoap-common | UNIVERSAL, core |
| kcoap-edhoc-oscore | EDHOC over CoAP, EDHOC→OSCORE derivation, RFC 9668, `PeerSecurity` | kcoap-edhoc, kcoap-oscore, kcoap-coap | UNIVERSAL, core |
| kcoap-dtls | DTLS 1.2 SPIs; later a pure-Kotlin sans-IO engine plus a `DatagramSecurity` decorator | kcoap-transport, kcoap-crypto | UNIVERSAL, core, unpublished |
| kcoap-crypto-default | cryptography-kotlin adapter: JDK, CryptoKit and CommonCrypto, OpenSSL 3, WebCrypto | see graph | UNIVERSAL |
| kcoap-crypto-bc | ML-KEM / ML-DSA decorator; `classical` is required | see graph | JVM_LIBRARY |
| kcoap-transport-ktor | UDP and TCP on ktor-network; TLS client after T1 | see graph | SOCKETS |
| kcoap-transport-ktor-websocket | `coap+ws`/`coaps+ws` connector, the browser transport | see graph | UNIVERSAL |
| kcoap-transport-ktor-websocket-server | WebSocket listener; the application chooses the ktor engine | see graph | SOCKETS |
| kcoap-testkit | Virtual-time datagram network, `InMemoryConnections`, shared reliable fixture, `ByteMutator`, stress helper | kcoap-transport, coroutines | UNIVERSAL |
| **kcoap-crypto-testsuite** | Abstract backend conformance tests, the `supports` invariant, ACVP vectors, capability report; published for key-store and HSM authors | kcoap-crypto, kotlin-test | UNIVERSAL |
| **kcoap-bom** | One version for all artifacts | — | — |

### 2.4 Splits, moves and renames

- **Split kcoap-coap into kcoap-coap-message and kcoap-coap (MB-2).**
  - The wire vocabulary is stable and needs no coroutines, while the stack changes fast. After the split, OSCORE's API no longer exports `CoapEndpoint`, `CoapClient` or `CoapServer`, the layering is checked by the compiler, and OSCORE can be reused over HTTP and in proxies.
  - The packages `dev.kcoap.coap`, `.codec` and `.security` move unchanged.
  - **`CoapException` stays in kcoap-coap and moves to the package `dev.kcoap.coap.stack`.** Its `Reason` values are stack vocabulary (timeouts, connection, resolution), and only stack, client and server use it. Changing the package avoids a split package; it is one import change, acceptable before 1.0.
- **X.509/DER moves from kcoap-edhoc to `kcoap-crypto` `dev.kcoap.crypto.x509` (MB-7, U10).** It sits next to `CertificateValidator`. DTLS needs it.
- **`EcPointDecompression` and `PrimeField` move from kcoap-crypto-default to `kcoap-crypto` as `Sec1` (U2, U3).** Point encoding is a protocol concern.
- **kcoap-crypto-bc no longer depends on kcoap-crypto-default (MB-4).** The application composes `BouncyCastleCryptoBackend(classical = DefaultCryptoBackend())`.
- **kcoap-transport gains `api(kcoap-common)`.** That makes the markers reachable from every module without a crypto→transport edge. kcoap-common itself has no dependencies.
- **`StreamSecurity` is removed.** TLS lives in connectors and listeners (§3.2).
- **No module renames.** Only the tier enum values are renamed, inside build-logic.

### 2.5 Where fallbacks and adapters live

| Primitive | Location | Used when |
|---|---|---|
| AES-CCM | kcoap-crypto `AesCcm`, the `aead` default on `aesBlockCipher` | Unless a backend overrides it |
| AES block without ECB (WebCrypto) | kcoap-crypto-default `PlatformAesBlock` (CTR) | Inside the adapter |
| HKDF | kcoap-crypto `Hkdf` on `hmac` | Always |
| SHAKE256 / KMAC256 | kcoap-crypto `Keccak` | `digest(SHAKE256)` by contract; KMAC as common code, like HKDF |
| SEC1 decompression | kcoap-crypto `Sec1` | Protocol code, before any backend call. The SPI receives uncompressed points |
| ECDSA DER ↔ raw | kcoap-crypto | DTLS; JCA, Android KeyStore and SecKey adapters |
| X.509 / SPKI parsing | kcoap-crypto `x509` | EDHOC x5chain, DTLS certificate and raw-public-key modes |
| Constant-time compare, `wipe` | kcoap-common | Everywhere |
| ML-KEM / ML-DSA | kcoap-crypto-bc now; kcoap-crypto-pq later | Through `supports` |
| IP literals | kcoap-transport `IpLiteral` | Everywhere |
| TLS | Inside each connector or listener (runtime TLS stack) | Stream transports |

Runtime adapters live only in `kcoap-crypto-*`, `kcoap-transport-*`, and the future key-store and `kcoap-apple` modules.

### 2.6 Dependency sets per use case

All modules below come from `kcoap-bom`.

| Use case | Modules | What works today, and the caveats |
|---|---|---|
| **Android app** | kcoap-edhoc-oscore (or kcoap-oscore / kcoap-coap), kcoap-crypto-default, kcoap-transport-ktor and/or -websocket; kcoap-crypto-bc optional for PQ | Uses the jvm artifact. minSdk will be documented after P1 checks it against API signatures. Which algorithms exist depends on API level, and capability filtering drops what is missing. The app supplies a durable `SequenceNumberStore` (DataStore, or a file with fsync and atomic rename) |
| **iPhone app** (KMP shared module) | Same, without kcoap-crypto-bc | CryptoKit and CommonCrypto. The app's single framework `export()`s the kcoap types its Swift code needs; never combine it with a second Kotlin framework. kcoap-transport-ktor uses BSD sockets, which do not bring up the cellular radio and do not support VPN On Demand. The WebSocket connector runs on the Darwin engine (W1), so it uses the URL loading system. Swift-safe after D2. Swift-only apps wait for E4 |
| **JVM application server** | kcoap-edhoc-oscore, kcoap-crypto-default, kcoap-crypto-bc, kcoap-transport-ktor, -websocket-server plus a ktor engine the app chooses | Today: `coap`, `coap+tcp`, `coap+ws`, with OSCORE and EDHOC end to end. `coaps+tcp` and `coaps+ws` need TLS terminated at a reverse proxy until T1. `coaps` (DTLS) is not available until E1. Bounded `MutableSecurityContextRepository` |
| **Browser** | kcoap-edhoc-oscore, kcoap-crypto-default, kcoap-transport-ktor-websocket | `coap+ws`/`coaps+ws` (the browser does the TLS) to a proxy, with OSCORE and EDHOC end to end. No UDP, TCP or DTLS. Runs only the suites WebCrypto can run. AES-CCM goes through the CTR block fallback. IndexedDB store with strict durability. A bundle-size budget is set in P1 |
| **Linux gateway** | Server set without kcoap-crypto-bc | linuxX64 (linuxArm64 in E5), OpenSSL 3. TLS through a proxy (stunnel or nginx stream) until T1 |

```kotlin
dependencies {
    implementation(platform("dev.kcoap:kcoap-bom:<v>"))
    implementation("dev.kcoap:kcoap-edhoc-oscore")
    implementation("dev.kcoap:kcoap-crypto-default")
    implementation("dev.kcoap:kcoap-transport-ktor")   // browser: kcoap-transport-ktor-websocket
}
```

---

## 3. Cross-cutting designs

### 3.1 Crypto capabilities and backend composition

```kotlin
public interface CryptoBackend {
    // existing operations unchanged
    public suspend fun supports(algorithm: AeadAlgorithm): Boolean = Probe.aead(this, algorithm)
    public suspend fun supports(type: KeyType): Boolean = Probe.keyType(this, type)
    public suspend fun supports(algorithm: SignatureAlgorithm): Boolean = Probe.signature(this, algorithm)
    public suspend fun supports(algorithm: KemAlgorithm): Boolean = Probe.kem(this, algorithm)
    public suspend fun supports(algorithm: HashAlgorithm): Boolean = Probe.hash(this, algorithm)
}
```

- **Contract.**
  - A backend that cannot run an operation throws `UnsupportedAlgorithmException`, never a provider exception. `capabilityCall` already enforces this in kcoap-crypto-default.
  - `supports(x) == false` exactly when that operation would throw. The kcoap-crypto-testsuite checks this for every backend.
  - P-256/P-384 keys at the SPI are uncompressed SEC1.
  - `PrivateKey` gains `open fun destroy()`.
- **Answers.**
  - Software adapters memoize per instance, with a `Mutex`-guarded map. That also absorbs differences between browsers.
  - Hardware and secure-element backends answer statically and are never probed, because a probe could create a persistent key or trigger a biometric prompt.
- **Delegation caveat (documented in KDoc).** `X by delegate` forwards `supports` to the delegate. A decorator that adds algorithms (BC, kcoap-crypto-pq) must override the matching members, and the conformance invariant catches one that forgets.
- **Protocol use.**
  - `CipherSuite.isSupportedBy(crypto, method)` checks:
    - the EDHOC and application AEADs;
    - the hash and HMAC;
    - the ephemeral exchange (DH curve or KEM);
    - for each side of the method, the signature algorithm if that side signs, or key agreement on the static key type if it uses static DH.
  - `EdhocInitiator` and `EdhocResponder` filter lazily inside their first suspend step (`createMessage1` / `processMessage1`) against the memoized answers, so the raw API is safe too.
  - `suspend fun EdhocInitiatorConfig.supportedBy(crypto)` (and the Responder equivalent) is the fail-fast helper. `EdhocServer` and `EdhocClientHandshake` call it at startup. It throws `IllegalArgumentException` naming the dropped suites if nothing remains or if `initialCipherSuite` was dropped.
  - A Responder whose selected suite is not runnable answers with EDHOC error code 2 and a SUITES_R it can run (RFC 9528 §6.3).
  - OSCORE `derive` keeps its eager check. It is a configuration mistake, so it throws `IllegalArgumentException`.
- **Composition is Kotlin delegation only,** for example `KeyStoreBackend(software = BouncyCastleCryptoBackend(classical = DefaultCryptoBackend()))`.
- **DefaultCryptoBackend.** `final`, a public no-argument constructor and an internal provider constructor (needed by the memo test). cryptography-core moves to `implementation` scope. A provider-choice API, for example for a FIPS JCA provider, can be added later as an additive change.

### 3.2 Transports and connection management

- **SPI (kcoap-transport, as merged).** `DatagramTransport`; sealed `Connection` (byte stream or message stream); `Connector { val schemes; suspend fun connect(peer): Connection }`; `ConnectionListener`. `Endpoint` is both identity and routing key. `Udp`, `Tcp` (with `usesTls`), `WebSocket` and `Opaque` become final classes with deliberate equality, like `Endpoint.Secure` (D1).
- **Adapters move bytes and own TLS.** There is no CoAP logic, pooling or retry in adapters.
- **TLS lives in connectors and listeners, one design.** Each runtime's TLS stack differs (JSSE, OpenSSL, the browser for `wss`, URLSession), so TLS cannot be a common decorator. `StreamSecurity` is removed. DTLS stays a `DatagramSecurity` decorator, `dtls.secure(udp): DatagramTransport`, because kcoap ships its own engine. That SPI stays `@ExperimentalKcoapApi` until E1.
- **Connection management stays in kcoap-coap (`ReliableConnections`).** One connection per peer, CSM, Ping/Pong, idle close and Release/Abort, shared by TCP and WebSocket.
- **Routing.** An exhaustive `when (peer)` selects the per-peer link, and `Connector.schemes` is the lookup. There is no scheme registry. A peer whose transport is not configured fails with `CoapException(TRANSPORT_UNAVAILABLE)`, naming the scheme.
- **Resolution.**
  - `EndpointResolver.Default` (renamed from `IpLiterals`) accepts IP literals for every scheme and passes host names to connectors.
  - Datagram host names need a resolver the application supplies.
  - A lookup that fails at runtime throws `CoapException(UNRESOLVED_HOST)`.
- **Lifecycle (documented now in C3; helpers in E9).**
  - Today a closed connection fails its observations with `CONNECTION_CLOSED`, and the next request reconnects.
  - E9 adds:
    - documented transport-failure semantics;
    - a re-observe helper;
    - a battery-aware keepalive policy (configurable CSM Ping interval);
    - an endpoint event plus application-driven rebind when a `DatagramTransport` fails;
    - guidance for iOS backgrounding and Android Doze.

### 3.3 The CoAP stack as layers

```
CoapClient / CoapServer      application model: CoapRequest, CoapResponse, RequestContext, Flow
─────────────────────────────
exchange layer               tokens, (peer, token) matching, block-wise, Observe registry,
                             Echo/Request-Tag, interceptors, events
message security SPI         ClientMessageSecurity / ServerMessageSecurity   (OSCORE, RFC 9668)
──────── PeerLink (internal) send / respond / acknowledge / reject / ping
                             limits (max size, BERT) · capabilities (hasReset, hasMessageTypes, isOrdered)
                             DeliveryOutcome { Delivered(piggybacked), Rejected, TimedOut, Failed(cause) }
                             onClosed(reason) · notification policy (datagram only)
DatagramLink                 ConnectionLink
  UdpMessaging (sans-IO)       ReliableMessaging (sans-IO, RFC 8323)
  driver: Mutex, timers, recv  ConnectionDriver + ReliableConnections
DatagramTransport [+DTLS]    Connector / ConnectionListener (TCP, TLS, WebSocket)
```

- Code above `PeerLink` sees only `DeliveryOutcome`, `limits` and `capabilities`, never `Transmission`, `TransmissionOutcome` or `CoapMessageType`.
- Block sizes come from `link.limits`.
- **Observe.**
  - Coalescing to the latest state stays in `ServerObservation` and applies on every transport.
  - The CON/NON choice, the periodic CON (RFC 7641 §4.5) and the NON slot hold are a notification policy that only the datagram link applies.
  - `notificationType` and `isConfirmable` are documented as hints for datagram links only.
- `UdpMessaging` stays on `UdpMessageCodec`. Only the reliable side chooses a codec.
- **Public shape.** Keep the merged named-argument constructors (`CoapEndpoint(scope, config, transport?, connectors, listeners)` and the merged `CoapClient`/`CoapServer` shortcuts the guide uses). Add no DSL and no further overloads. Remove only the public `transport`, `maxDatagramSize` and `NoDatagramTransport`.
- The seam stays internal until the public sans-IO API level.

### 3.4 Security SPI

- **Message security.**
  - `ClientMessageSecurity` and `ServerMessageSecurity` move to kcoap-coap-message's `security` package.
  - OSCORE implements them, and RFC 9668 decorates them: `CombinedRequestServer(oscore: ServerMessageSecurity)`.
  - `PeerSecurity` stays in kcoap-edhoc-oscore, keyed by `Endpoint`, and gains `install(client, uri, security)`, which resolves the URI through the client's own resolver.
- **Context storage.** `SecurityContextRepository` (find) and `MutableSecurityContextRepository` (add, remove). `InMemoryContextRepository` implements the mutable one. Production servers supply a bounded store.
- **OSCORE persistence.**
  - `derive(parameters, crypto, sequenceStore, persistInterval = 64, freshness: FreshnessPolicy = AUTO)`. `sequenceStore` has no default.
  - `AUTO` requires an Echo freshness proof when the store returned a value. That is always correct, because the replay window is never persisted.
  - `REQUIRED` and `NOT_REQUIRED` override it.
  - `EphemeralSequenceNumberStore` is the explicit choice for fresh keys, such as EDHOC-derived contexts.
- **Credentials.** EDHOC keeps `LocalCredential`. DTLS replaces `DtlsSigner` with `DtlsLocalCredential(privateKey, spki, chain)` and signs through `CryptoBackend.sign` plus the DER codec, so one key-store decorator serves both protocols.
- **Certificates.**
  - The SPI stays `CertificateValidator.validate(chain)`. TLS connectors use the platform trust store by default, with a pinning hook.
  - The planned default is a common-code `X509PathValidator(crypto, trustAnchors, clock, …)` for a constrained profile (E2). OS trust stores come later as optional adapters.

### 3.5 Configuration

- **Builders only where growth is certain:** `CoapEndpointConfig` and `CoapClientConfig` follow the `LogConfig` pattern (private constructor, `Builder`, `toBuilder()`, DSL factory).
- **Every other config keeps named-argument constructors.** When one gains a parameter, the old signature stays as a `@Deprecated(level = HIDDEN)` overload, and the ABI check enforces it. Validation stays in `init` with messages that cite the RFC.
- **Vocabulary (docs/code-style.md):**
  - `loggerFactory` and `crypto` as parameter names;
  - `preferredBlockSize` (client) and `maxBlockSize` (server);
  - `maxRequestBodySize` and `maxResponseBodySize`;
  - `from<Property>()` returns null when nothing matches, `of(parts)` builds from parts;
  - `decode` for binary formats, `parse` for text grammars and DER certificates.

### 3.6 Error model

| Facade | Peer, network, security and lifecycle failures | Caller mistakes |
|---|---|---|
| CoapClient / CoapEndpoint / CoapServer | `CoapException(Reason)` (package `dev.kcoap.coap.stack`). Existing values: `CLOSED` (now also for calls after close), `SESSION_ENDED`, `CONNECTION_CLOSED`. New: `SECURITY`, `UNRESOLVED_HOST`, `TRANSPORT_UNAVAILABLE`. Wording neutral to the transport | `IllegalArgumentException` / `IllegalStateException` |
| OSCORE | `OscoreException(OscoreError)` | same |
| EDHOC | `EdhocException`; `EdhocPeerErrorException(retryWith: CipherSuite?)`; `EdhocTransferException` becomes a subtype | same |
| CBOR | `CborException` | same |
| X.509 | `CertificateFormatException` (new, kcoap-crypto `x509`) | same |
| URI | `CoapUri.parse` throws `IllegalArgumentException` for strings the caller supplies, listed in `@Throws`. Peer-supplied URIs (link format, Location-\*) go through `parseOrNull` | — |

All rows except URI extend `open class KcoapException` in kcoap-common, the single `@Throws` target for Swift.

> **Amendment (S1).** S1 added `CoapException.Reason.TRANSPORT_FAILED` (EO-2 item 2): the endpoint's own
> transport failed, either its datagram `receive` (which ends the endpoint, `closeCause`) or a `send` it
> refused with `IllegalArgumentException`. It stays next to the planned `TRANSPORT_UNAVAILABLE`, which
> names a different failure, a peer whose transport is not configured at all, so B1 adds that reason
> without renaming this one. S1 also did EO-1, EO-2 items 2 and 3 with the first two tests of item 5,
> and the B3 item "calls after close throw `CoapException(CLOSED)`, re-checked under the lock". EO-2
> item 4 (refusing `coaps` on a transport without DTLS) and the rest of B3 stay with B3.

SPI signals stay outside the root and are translated at the boundary:

| SPI signal | Translated to |
|---|---|
| `AeadAuthenticationException` | `OscoreException` / `EdhocException` |
| `UnsupportedAlgorithmException` during a handshake | Local `EdhocException` (code 1). Its message names the algorithm and never says "invalid key" |
| `UnsupportedAlgorithmException` at setup | `IllegalArgumentException` (configuration mistake) |
| `CertificateValidationException` | `EdhocException` |
| `SessionEndedException` | `CoapException(SESSION_ENDED)` |
| `CoapFormatException` | RST or drop, plus a `MalformedMessage` event |
| `MessageSecurityException` on the server | An error response |
| `MessageSecurityException` on the client | `CoapException(SECURITY, cause)` |

The table goes into docs/architecture.md.

### 3.7 Logging, events and metrics

- **Logging.** Every component that logs takes `loggerFactory: LoggerFactory = LoggerFactory.Global`. Bridges to SLF4J, Timber and OSLog are README recipes, not dependencies. `LogTags` stays the central catalogue.
- **Events.**
  - `EndpointEvent` stays sealed, is emitted outside locks, and the listener is isolated by try/catch.
  - Reasons become enums plus an optional `detail`. `ObserverRemoved` gets RESET, NOT_ACKNOWLEDGED, DEREGISTERED, RESOURCE_REMOVED, ERROR_RESPONSE, FAILED.
  - Connection opened/closed events come from RFC 8323. EDHOC sessions are reported through `EdhocSessionListener`.
- **Metrics.** There is no metrics library. `EndpointEventListener` is the hook, and docs/architecture.md maps events to counters.
- **Diagnostics.** `RingBufferSink`; `payloadDumps` is off by default; `toString()` never includes secrets.

### 3.8 Time and randomness

- Timers use the injected `TimeSource`. Only log timestamps and `X509PathValidator` use `Clock`, and the validator lets a device without a trusted clock skip the validity check explicitly.
- Security layers use `CryptoBackend.randomBytes`. CoAP tokens and MIDs use `CoapEndpointConfig.random` and `tokenGenerator`, defaulting to the internal `PlatformCsprng` (renamed from `SecureRandom`, built on `Uuid.random()`, with the now-redundant opt-in dropped).
- There is no public `SecureRandom` in kcoap-common. docs/architecture.md gets a "Randomness" paragraph.

### 3.9 Key storage and durable state

- **Keys.**
  - `PrivateKey` is an opaque handle with `destroy()`.
  - A key-store adapter decorates the software backend and overrides `sign`, `keyAgreement` and `decapsulate` for its own key subtype.
  - Future thin adapters: Android KeyStore, Secure Enclave and Keychain, non-extractable WebCrypto `CryptoKey`, PKCS#11. The cores do not change for any of them.
- **Durable state.** `SequenceNumberStore`, `MutableSecurityContextRepository` and a future DTLS session cache are SPIs the application supplies. The README gives recipes per runtime, each stating that `store()` is durable before it returns. There are no store modules in the core.

### 3.10 Platform support policy

- `docs/platforms.md` (C3) covers:
  - minimum versions: Android minSdk from P1, the iOS/macOS deployment target, browsers, Node;
  - a capability matrix of which suites and AEADs run where, taken from the per-target capability report that C2 prints in CI;
  - TLS termination options;
  - the mobile lifecycle.
- Android: an API-signature check of the jvm jars against minSdk (P1).
- Browser: a bundle-size budget for a minimal js/wasm client (P1).
- Node sockets: ruled out of SOCKETS. A Node-only artifact would need its own module and a runtime check, and is not planned.
- Coverage: Kover measures JVM tests only. js and native paths are covered by running the same commonTest, not by a coverage number. This is an accepted gap.

---

## 4. Ordered refactoring work packages

### 4.1 Rules, file ownership, standard gate

- **Branches.** Every WP runs on its own branch or worktree cut from the current tip. Merge order follows §4.2. Nobody edits the main working tree while a WP is in flight.
- **ABI dumps.** Each WP that merges after C1 rebases, runs `updateKotlinAbi`, and commits a dump diff that matches its listed API changes.
- **CHANGELOG.md** (created in WP-0): every breaking WP adds an "Unreleased" entry with a migration note. Every WP that settles a decision listed in §5 adds an ADR to `docs/adr/`.
- **Release freeze.**
  - No `v*` tag until A1–A5, W1, B1–B3, C1, D1, D2 and C3 have merged. That is the API-breaking set.
  - T1, I1, P1 and C2 do not block the release.
  - WP-0 writes this into docs/releasing.md. Protecting `v*` tags in the GitHub settings is the user's action.

| Files | Owners, in order |
|---|---|
| build-logic/\*\*, core `build.gradle.kts`, settings.gradle.kts | C1. After C1, only one-line module additions by C2, B2, C3, D2; the second to merge rebases |
| .github/workflows/ci.yml | C1 → W1 → P1 → D2 |
| kcoap-crypto/src/commonMain | A2 → A4 → C2 |
| kcoap-crypto-default/\*\*, kcoap-crypto-bc/\*\* (sources and build files) | A2 → A4 → A5 → C2 (tests) |
| kcoap-edhoc/\*\* | A2 → A4 → D2 |
| kcoap-edhoc-oscore/\*\* | A1 (`OscoreFromEdhoc.kt` only) and A2 (renames only, in `EdhocClientHandshake.kt` and `EdhocServer.kt`) → A3 → I1 (jvmTest) → D2 |
| kcoap-oscore/\*\* | A1 → A3 → B1 (commonTest and jvmTest) → B2 (build file) → D2 |
| kcoap-coap/\*\* | S1 → C1 (`OptionCodec` annotation only) → B1 → B2 → B3 → D1 → D2 |
| kcoap-transport/\*\* | C1 → D1 |
| kcoap-transport-ktor\*/\*\* | C1 (build scopes) → W1 → T1 |
| docs/architecture.md | Only the section a WP invalidates (A1, A4, B1, B2); C3 does the final full pass |

**Standard gate** for every WP:

- `scripts/test unit` (all targets that run on the host);
- `scripts/test coverage` (koverVerify: 90% for the library, 95% for kcoap-edhoc, JVM only);
- `scripts/test interop` (libcoap, libedhoc);
- the macOS CI job (`scripts/test apple`) green on the PR;
- after C1, `checkKotlinAbi` green with the dump diff reviewed.

> **Amendment (Q-Block).** Q-Block (RFC 9177) is scheduled before B1 so that B1 carries it onto PeerLink:
> `kcoap-coap/**` ownership is S1 → C1 (annotation) → QB-2 → SRV-1 → QB-3 → QB-4 → QB-6 → B1 → B2 → …,
> and `kcoap-oscore/**` is A1 → QB-5 → A3 → B1. The Q-Block packages are in
> [plan-qblock-and-platforms.md](plan-qblock-and-platforms.md).

### 4.2 Schedule

```
WP-0 ─┬─► C1 ─┬─► W1
      ├─► S1 ─┤
      │       └─(C1+S1)─► B1 ─► B2 ─► B3 ─► D1 ─► D2   (D2 also needs A3, A4)
      ├─► A1 ─┐               └─► T1
      └─► A2 ─┼─► A3 ──────────────(A3+B1)─► I1
              └─► A4 ─► A5 ─► C2 ─(B2+C2)─► C3 ─► P1   (P1 also needs W1)
```

Parallel from the start: C1, S1, A1, A2. Then A3 runs alongside A4, W1 alongside B1, and T1 and I1 alongside B2 and B3.

### 4.3 Packages

**WP-0: Baseline, release freeze, changelog, ADRs**

- **Scope:** docs/releasing.md (0.1.0 checklist and freeze list); new CHANGELOG.md; `docs/adr/README.md`, plus ADRs for the capability probe and memo, delegation instead of subclassing, TLS in connectors, no scheme registry, and the PeerLink seam.
- **API changes:** none.
- **Risk:** none.
- **Accept:** documentation only; every relative link resolves.

**WP-S1: Concurrency fixes and stress harness**

- **Findings:** CLR-\* (§4.5).
- **Scope:** the kcoap-coap stack, client and server files those findings name; a stress helper in kcoap-testkit (real `Dispatchers.Default`, bounded iterations); tests.
- **API changes:** none expected.
- **Depends on:** WP-0.
- **Risk:** medium.
- **Accept:**
  - Each fix has a regression test that failed before the fix.
  - Stress tests in jvmTest and linuxX64Test cover `ReliableConnections`, the connection pool, `ObservationRegistry`, and close racing `openExchange`.
  - Standard gate.

**WP-C1: Build governance**

- **Findings:** U7 (1, 2, 4); MB-3 (2, 3); MB-6 (1, 4, 5, 6); API-06 (1); API-07 (1); MB-8 (1–3, transport SPIs only); MB-10 (3).
- **Scope:**
  - build-logic:
    - rename the tier enum values;
    - `kcoap.core` with the `verifyCommonOnly` check task, plus a TestKit test with core and adapter fixtures;
    - `kcoap.unpublished`, applied to kcoap-dtls;
    - `abiValidation` with klib dumps and `keepUnsupportedTargets`;
    - an opt-in for `InternalKcoapApi` in kcoap's own modules, excluded from the dumps.
  - kcoap-common: `InternalKcoapApi` and `ExperimentalKcoapApi`, applied to `ByteArrayBuilder`, `minimalUnsignedBytes` and `readUnsigned`.
  - kcoap-transport: `api(kcoap-common)`; `DatagramSecurity` marked experimental; `StreamSecurity` deleted.
  - kcoap-coap: `OptionCodec` gets `@InternalKcoapApi`.
  - kcoap-transport-ktor: `api(kcoap-common)` and `api(ktor-network)`.
  - The kcoap-edhoc-oscore jvmTest block's comment is reworded as "for I1".
  - Comments in settings.gradle.kts and the plugin files.
  - ci.yml runs `checkKotlinAbi`; dumps committed; docs/code-style.md gets the `api` rule.
  - Not in scope: kcoap-cbor and kcoap-crypto scopes (rejected, §5), and the crypto-default and crypto-bc build files (A5).
- **API changes:** opt-in markers; `StreamSecurity` removed.
- **Depends on:** WP-0.
- **Risk:** low.
- **Accept:**
  - `checkKotlinAbi` green.
  - The TestKit test fails a core fixture that has a `jvmMain` file and passes the adapter fixture.
  - In kcoap-transport-ktor's generated `module.json`, kcoap-common and ktor-network appear under `apiElements`.
  - `publishToMavenLocal` produces no kcoap-dtls artifact.
  - `grep -rn StreamSecurity` finds nothing.

**WP-W1: RFC 8323 transport follow-ups**

- **Findings:** U6 (3); MB-3 (1, already on disk; verified here); critique D (CIO, iOS cellular).
- **Scope:**
  - kcoap-transport-ktor-websocket: build file with appleMain → ktor-client-darwin and linux → CIO; a jsTest that starts an npm `ws` server with `handleProtocols`.
  - kcoap-transport-ktor-websocket-server: `KtorWebSocketListener` takes an `engineFactory` parameter (no default), and ktor-server-cio leaves the main dependencies.
  - ci.yml: one headless-Chrome job for the websocket module.
  - READMEs.
- **API changes:** the listener requires an engine factory.
- **Depends on:** C1.
- **Risk:** low to medium. Darwin subprotocol negotiation must be verified first; if it fails, keep CIO on Apple and record the cellular risk.
- **Merge gate:** the macOS job and the browser job green on the PR; both were written on Linux and have not run yet. The fallback if the macOS job fails is in docs/testing.md ("Continuous integration").
- **Accept:**
  - The jsTest asserts that `coap` is requested and read back from the negotiated protocol.
  - The browser job runs the same check against a JVM listener. If that proves infeasible, the gap is recorded in docs/platforms.md.
  - The macOS job connects through the Darwin engine.
  - ktor-server-cio is absent from the published dependencies of -websocket-server.

**WP-A1: OSCORE safe persistence and secret hygiene** (critical)

- **Findings:** API-01; API-10 (SecurityContext and SecurityContextParameters parts); API-11 (OSCORE `loggers`).
- **Scope:**
  - kcoap-oscore `SecurityContext.kt`, `SequenceNumberStore.kt`, `SecurityContextParameters.kt`; `OscoreEndpoints.kt` (rename only); a test helper for the roughly 15 call sites; README.
  - kcoap-edhoc-oscore `OscoreFromEdhoc.kt` (passes the ephemeral store explicitly).
  - kcoap-crypto-default/README.md:15.
  - The pattern-table row in docs/architecture.md.
- **API changes:**
  - `derive(..., sequenceStore, ..., freshness: FreshnessPolicy = AUTO)`;
  - `InMemorySequenceNumberStore` becomes `EphemeralSequenceNumberStore` (Mutex-guarded);
  - `SecurityContext.parameters` becomes internal, with `name` and `aeadAlgorithm` added;
  - `masterSecret()` and `masterSalt()` become functions;
  - `loggers` becomes `loggerFactory`.
- **Depends on:** WP-0.
- **Risk:** low.
- **Accept:**
  - With `AUTO`, a restored context resumes from the stored value and requires Echo; an ephemeral one does not.
  - Each persisted sequence number is written before it is used (recording store).
  - The ephemeral store passes the S1 stress helper.
  - `grep InMemorySequenceNumberStore` finds nothing.
  - `scripts/test interop --tests '*Oscore*'`.

**WP-A2: EDHOC capability-aware negotiation, typed retry, returned sessions**

- **Findings:** U1; API-05; DP-02 (1–2); API-09 (1–3); U3 (5); API-11 (`backend` → `crypto`).
- **Scope:**
  - kcoap-crypto `CryptoBackend.kt` (five `supports` members) and `Capabilities.kt` (internal `Probe`).
  - kcoap-crypto-default `DefaultCryptoBackend.kt` (memoized `supports`).
  - kcoap-crypto-bc `BouncyCastleCryptoBackend.kt` (static ML-KEM/ML-DSA answers, delegates the rest).
  - kcoap-edhoc `EdhocConfig.kt`, `CipherSuite.kt` (`isSupportedBy(crypto, method)`), `EdhocInitiator.kt` and `EdhocResponder.kt` (lazy filter, error code 2, `retryWith`, `RetryWithSuite` deleted, sessions returned), `EphemeralKeyExchange.kt` (`backendCall`), `EdhocError.kt`, README.
  - Named-argument call sites in kcoap-edhoc-oscore `EdhocClientHandshake.kt` and `EdhocServer.kt` (renames only).
- **API changes:** additive `supports`; `supportedBy(crypto)`; `retryWith`; `processMessage3(): EdhocSession`; `createMessage3(): Message3(bytes, session)`; `crypto` parameter name.
- **Depends on:** WP-0.
- **Risk:** medium.
- **Accept** (invariants, not environment-dependent facts):
  - For every suite and method on every target, `isSupportedBy` is true exactly when a loopback handshake succeeds.
  - SUITES_R that is sent ⊆ the runnable set.
  - A non-runnable selected suite gets error code 2.
  - The Initiator retries through `retryWith` and never reads `phase` (grep).
  - A stub lacking a curve yields an `EdhocException` whose message names the curve.
  - A memo test with a counting provider shows each `supports` reaches the provider once.
  - RFC 9529 traces, libedhoc interop, kcoap-edhoc ≥ 95%.

**WP-A3: EDHOC↔OSCORE glue on SPIs and the application model**

- **Findings:** API-08 (2–6); API-02 (2); API-03 (5); API-11 (`loggers`); MB-7 (2); U1 (apply the filter once).
- **Scope:** kcoap-edhoc-oscore `EdhocServer.kt`, `EdhocResource.kt`, `EdhocClientHandshake.kt`, `EdhocOverCoap.kt`, `CombinedRequest.kt`, `PeerSecurity.kt`, README; kcoap-oscore `OscoreEndpoints.kt` and `InMemoryContextRepository`.
- **API changes:**
  - `EdhocServer(contexts: MutableSecurityContextRepository)`;
  - `handle(payload): CoapResponse`;
  - handshake port `suspend (CoapRequest) -> CoapResponse`;
  - `CombinedHandshake` replaces `Pair`;
  - `CombinedRequestServer(oscore: ServerMessageSecurity)`;
  - `PeerSecurity.install(client, uri, security)`;
  - `EdhocTransferException : EdhocException`;
  - `parseRequest` throws `EdhocException.malformed`.
- **Depends on:** A1, A2.
- **Risk:** low to medium.
- **Accept:** no `session!!` in the module (grep); `EdhocServer.handle` no longer catches `IllegalArgumentException`; EdhocOscoreTest and the combined-request tests pass; standard gate.

> **Amendment (A3).** Done as listed, with these shapes: `handle(payload, peer)` keeps the peer SEC1 added;
> `EdhocClientHandshake(config, crypto, server, path, loggerFactory, sequenceStore, exchange)` takes the
> server URI and builds the resource URI from it, and the `EdhocOverCoap` builders return `CoapRequest` and
> `CoapResponse`; `MutableSecurityContextRepository` has `addIfAbsent` besides `add` and `remove`, the atomic
> insert SEC1's C_R guard needs; `PeerSecurity.install(client, uri, security)` returns the endpoint it
> resolved. `EdhocException.malformed` is internal to kcoap-edhoc, so `parseRequest` raises the same error
> (code 1) itself, with a generic diagnostic on the wire. `CombinedRequestServer` answers 4.00 when the
> decorated layer does not take the rebuilt request. The A1 follow-up (an optional `sequenceStore` on
> `EdhocServer` and `EdhocClientHandshake`) had landed with SEC1; A3 keeps it. U1's "filter once" is A2's
> per-configuration memo, which both classes reuse across sessions, now tested at this level.

**WP-A4: kcoap-crypto as the home of shared crypto blocks**

- **Findings:** MB-7 (1); U10 (1–2); U3 (1–4, 6–7); U2 (1–2); U5 (2–4); API-10 (`KemEncapsulation`).
- **Scope:**
  - kcoap-crypto: `x509/` (`X509Certificate`, `SubjectPublicKeyInfo.decode`, internal `DerReader`, `CertificateFormatException`); `Sec1.kt` and internal `PrimeField.kt`; `EcdsaSignatureCodec.kt`; `Keys.kt` (`destroy()`, `KemEncapsulation.sharedSecret()`); KDoc of `CryptoBackend` (uncompressed points, `SHAKE256` contract) and of `CertificateValidator` (no validator is shipped yet).
  - kcoap-edhoc: X.509 files deleted; `Sec1` used in `EphemeralKeyExchange`; `destroyIfSoftware` removed; tests moved.
  - kcoap-crypto-default: `EcPointDecompression.kt` and `PrimeField.kt` deleted.
  - kcoap-dtls `DtlsCredentials.kt`: `DtlsLocalCredential` replaces `DtlsSigner`.
  - kcoap-cose `CoseKey.kt`: y sign bit (optional).
  - The certificate paragraph in docs/architecture.md.
- **API changes:** the x509 package moves (no typealias); additions; `DtlsSigner` removed.
- **Depends on:** A2. Runs alongside A3.
- **Risk:** medium (backed by test vectors).
- **Accept:** compressed-point tests on every target; peer DER input never throws `IllegalArgumentException` (fuzzed with `ByteMutator`); RFC 9529 traces, Keccak/CCM differential tests, X.509 tests, libedhoc interop.

> **Amendment (A4).** Done as listed, with these shapes: `Sec1` has `isCompressed` and `decompress`;
> `EcdsaSignatureCodec.fromDer(algorithm, der)` returns null rather than throwing for anything but a strict
> DER signature, since a malformed peer signature is one that does not verify; `KemEncapsulation` has
> `destroy()` beside `sharedSecret()`, because a copying accessor alone could not overwrite the array the
> backend handed over; `CertificateFormatException` extends `Exception` until D2 adds `KcoapException`.
> `DtlsLocalCredential(privateKey, subjectPublicKeyInfo, certificateChain)` is a file of its own, takes P-256
> only (RFC 7925 §4.4) and checks that the SubjectPublicKeyInfo and the end-entity certificate hold the same
> key. `DerReader` also reads the DER signatures, and the parsers check what they read more strictly (no
> fields after the subject public key, `04` before a 65-byte point). EDHOC's Diffie-Hellman Responder checks
> G_X before it generates Y and destroys Y when the agreement fails. The optional COSE_Key sign bit is done.
> Beyond the list: the kcoap-crypto README carries the key-store recipe of U5 item 1, which C3 listed. Not
> done, as planned: the parser extensions of U10 item 2 (TBS bytes, validity, extensions, SAN) wait for E2.

**WP-A5: DefaultCryptoBackend as a final adapter**

- **Findings:** MB-5; U9; API-06 (2); API-07 (4–5, option (b)); U2 (5); MB-4 (1); MB-6 (2, the crypto-default and crypto-bc parts).
- **Scope:**
  - kcoap-crypto-default `DefaultCryptoBackend.kt`; build file (`implementation(cryptography-core)`, `implementation(kcoap-common)`); README.
  - kcoap-edhoc commonTest `ErrorHandlingTest.kt`, `Fixtures.kt` and `PostQuantumTest.kt`, rewritten as delegates.
  - kcoap-crypto-bc: `classical` required; build file `api(kcoap-crypto)` and `implementation(kcoap-common)`, with crypto-default as a test dependency only.
- **API changes:** `final`; internal provider constructor; private `softwareKey`; required `classical`.
- **Depends on:** A4.
- **Risk:** low.
- **Accept:** the ABI dump shows `final`; crypto-bc's `jvmRuntimeClasspath` does not contain kcoap-crypto-default; the memo stress test (S1 helper) passes; BackendDefaultsTest, the BC differential tests and ACVP pass.

**WP-C2: Crypto conformance suite (published) and PQ metadata**

- **Findings:** U4 (1); critique C and E.
- **Scope:**
  - New kcoap-crypto-testsuite: abstract suites; the `supports` ⇔ throws invariant; ACVP vectors moved from `kcoap-crypto-bc/src/jvmTest/.../vectors`; a capability report printed per target.
  - kcoap-crypto `Algorithms.kt`: seed, expanded-key and `tr` lengths.
  - kcoap-crypto-bc `MlKem.kt` and `MlDsa.kt` read that metadata.
  - kcoap-crypto-default and kcoap-crypto-bc tests extend the suite.
  - settings.gradle.kts.
- **Depends on:** A5, C1.
- **Risk:** low.
- **Accept:**
  - The invariant passes for `DefaultCryptoBackend` on every target and for BC on the JVM.
  - BC with its `supports` override removed fails the invariant (a negative test).
  - ACVP passes through the suite.
  - Report lines appear in the CI logs.

**WP-B1: One internal PeerLink seam**

- **Findings:** MB-1; DP-01; U6 (1, 2, 4, 5); API-04; MB-10 (2).
- **Scope:**
  - kcoap-coap `stack/`: `CoapEndpoint.kt`; new `PeerLink.kt` and `DatagramLink.kt` (driver, receive and timer loops); `ConnectionDriver`/`ReliableConnections`; `ClientExchange.kt`; `InboundRequest`; `ClientObservation`. `Transmission` becomes internal to the datagram link.
  - `server/ObservationRegistry.kt`, `CoapServer.kt`, `BlockwiseServer`, `ObservableResource.kt` (KDoc).
  - `client/BlockwiseClient.kt`, `CoapClient.kt`, `CoapClientConfig.kt`.
  - `CoapException.kt` (wording, `TRANSPORT_UNAVAILABLE`).
  - The shared reliable fixture moves to kcoap-testkit.
  - kcoap-oscore commonTest and jvmTest.
  - The endpoint and transport sections of docs/architecture.md.
- **API changes:** public `transport`, `maxDatagramSize` and `NoDatagramTransport` removed; `EndpointResolver.Default`; merged constructors kept.
- **Depends on:** C1, S1.
- **Risk:** high.
- **Accept:**
  - Existing suites pass with only construction lines changed. The PR lists those test files.
  - New `abstract class ExchangeConformanceTest` in kcoap-coap commonTest, with `DatagramLinkExchangeTest` and `ConnectionLinkExchangeTest` subclasses. It covers block-wise in both directions (BERT on reliable links), Observe (superseding, deregistration, connection loss → `CONNECTION_CLOSED`), Echo/Request-Tag and limits, on every target.
  - New `abstract class OscoreOverLinksTest` in kcoap-oscore commonTest, on kcoap-testkit `InMemoryNetwork` and `InMemoryConnections`.
  - grep finds no `isReliable(`, `requireDatagramTransport`, `NoDatagramTransport` or nullable `ConnectionDriver` fields.
  - libcoap interop for UDP, TCP and WS, plus new OSCORE over TCP and WS against libcoap.
  - The S1 stress tests pass.

> **Amendment (B1).** Done as [ADR 0005](adr/0005-peer-link-seam.md) records under "As built": `DeliveryOutcome`
> gained `Released` and `LinkCapabilities` gained `verifiesAddress`; the shared reliable fixture became
> kcoap-testkit's `InMemoryLink`, with `InMemorySessions` so that a datagram link can be lost as an ended DTLS
> session, while `ReliableFixture` keeps what needs kcoap-coap (CoAP framing, `CoapClient` and `CoapServer`).
> The internal endpoint API the tests use (`openExchange`, `leaseBody`, `limits(peer)`) stays, routing to the
> links. B2 and B3 are unchanged by it. Ownership deviation: for the OSCORE over TCP and WebSocket interop, B1
> adds jvmTest dependencies (the Ktor WebSocket transports and `ktor-server-cio`) to
> `kcoap-oscore/build.gradle.kts`, which the table above gives to B2; B2 and A3 rebase over those lines.

**WP-B2: Split out kcoap-coap-message**

- **Findings:** MB-2; critique C (CoapException).
- **Scope:**
  - `git mv` of `dev.kcoap.coap`, `.codec` and `.security` (without `CoapException.kt`) and their tests.
  - `CoapException` moves to `dev.kcoap.coap.stack`.
  - Build files of kcoap-coap and kcoap-oscore; kcoap-oscore commonTest depends on kcoap-coap.
  - settings.gradle.kts, READMEs, the graph in docs/architecture.md.
- **API changes:** a different artifact; one package change for `CoapException`.
- **Depends on:** B1.
- **Risk:** low.
- **Accept:**
  - kcoap-oscore main compiles without kcoap-coap.
  - `sort(kcoap-coap.api before)` equals `sort(kcoap-coap.api after ∪ kcoap-coap-message.api)`, apart from the one `CoapException` package line.
  - Standard gate.

**WP-B3: kcoap-coap public API cleanup**

- **Findings:** API-03 (1–4); API-08 (1, 7); API-10 (list copies); API-11 (3, 5, 6); U8 (1–3, 5); API-07 (2); the errors/observability findings for coap.
- **Scope:**
  - Calls after close throw `CoapException(CLOSED)`, with `isClosed` re-checked under the lock.
  - `SECURITY` wraps client-side protect failures; the resolver contract and `UNRESOLVED_HOST`; the `CoapClient` KDoc contract.
  - `RequestContext.request` becomes `message` (`@InternalKcoapApi`), plus a `path` accessor.
  - `CoapRequest` gets equality; `toList()` copies in `CoapUri`, `WebLink`, `ResourceAttributes` and the interceptors.
  - `CoapMessageType.fromValue` returns null; `CoapResponse.of` becomes `from`; `CoapUri.parseOrNull`, used on peer-supplied URIs.
  - Config renames; the `ObserverRemoved` enum; `PlatformCsprng`.
- **Depends on:** B2.
- **Risk:** low to medium.
- **Accept:** a virtual-time test shows an exchange opened during `close()` fails with `CLOSED` immediately; link-format fuzzing finds no `IllegalArgumentException`; standard gate.

**WP-T1: TLS for stream transports**

- **Findings:** critique D (server and gateway TLS).
- **Scope:**
  - First, verify ktor-network-tls client support on each SOCKETS target.
  - kcoap-transport-ktor: a `coaps+tcp` client (`usesTls`), validated by the platform trust store, with a pinning hook.
  - An ADR on the server side: a JVM_LIBRARY SSLEngine listener versus reverse-proxy termination (recommended for native gateways). The coaps+ws server uses an engine that does TLS (Netty or Jetty) or a proxy.
  - Docs.
- **Depends on:** B1.
- **Risk:** medium.
- **Accept:** coaps+tcp client interop with libcoap built with a TLS backend; docs/platforms.md lists the TLS support that actually exists per runtime.

**WP-I1: Browser-topology interop**

- **Findings:** critique D.
- **Scope:** kcoap-edhoc-oscore jvmTest (new) and interop/ docker-compose. A kcoap WebSocket client goes through the libcoap proxy (`-P`) to a kcoap UDP server: OSCORE GET, an EDHOC handshake, and an RFC 9668 combined request, end to end. If libcoap cannot forward ws→udp, use a test-only kcoap forwarder and record the result in an ADR.
- **Depends on:** A3, B1.
- **Risk:** medium.
- **Accept:** green in `scripts/test interop`; the server authorizes the client by its `OscoreIdentity` through the proxy.

**WP-D1: Two config builders and explicit Endpoint classes**

- **Findings:** API-06 (3, 5 reduced, 6, 7); API-02 (3).
- **Scope:** builders for `CoapEndpointConfig` and `CoapClientConfig`; `Endpoint.Udp`, `Tcp`, `WebSocket` and `Opaque` become final classes (kcoap-transport `Endpoint.kt`); "may gain constants" KDoc on `Reason` and `OscoreError`.
- **Depends on:** B3.
- **Risk:** low, with a large diff at call sites.
- **Accept:** RFC 7252/8323 matching tests for equality, including `usesTls`; the ABI diff matches the listed changes.

**WP-D2: KcoapException root, @Throws, Swift smoke test**

- **Findings:** API-02 (1); API-03 (6); API-09 (5).
- **Scope:**
  - `KcoapException` in kcoap-common, with the rows in §3.6 reparented under it.
  - `@Throws(KcoapException::class, CancellationException::class)` on `CoapClient` requests, `discover` and `ping`; the EDHOC steps; `run`/`runCombined`; `derive`; OSCORE protect/verify.
  - `@Throws(IllegalArgumentException::class)` on `CoapUri.parse`.
  - An unpublished kcoap-swift-smoke module plus a Swift script run from the macOS CI job.
- **Depends on:** D1, A3, A4.
- **Risk:** low.
- **Accept:** the Swift script catches a timeout and a malformed URI as `NSError` and builds a config with explicit arguments.

**WP-C3: Assembly and documentation**

- **Findings:** MB-9 (1–2); MB-10 (1, 2, 4, 5); U7 (3–4); U8 (4); U5 (1); U10 (docs); API-11 (7); MB-4 (docs); U4 (4); API-03 (7); MB-8 (6).
- **Scope:**
  - kcoap-bom, with constraints generated from the published-module list.
  - `docs/getting-started.md` (the §2.6 table) and `docs/platforms.md` (§3.10).
  - README: links, a 0.x stability note.
  - New READMEs for kcoap-common and kcoap-crypto; the key-store recipe goes in the latter.
  - The full pass over docs/architecture.md; docs/roadmap.md; docs/code-style.md (ownership, vocabulary, errors, final backends); the remaining ADRs.
- **Depends on:** B2, C2.
- **Risk:** none.
- **Accept:**
  - `dokkaGenerate` passes.
  - A link check finds no relative link to a missing file.
  - The BOM's constraints equal the published-module list.
  - No doc claims `coaps` or a `coaps+tcp` server before T1 or E1 lands (review checklist).

**WP-P1: Consumer samples and platform checks**

- **Findings:** MB-9; critique D (Android, browser size).
- **Scope:**
  - `samples/jvm-consumer` (BOM, against mavenLocal).
  - `samples/android-consumer` (AGP, declared minSdk, an animal-sniffer check of the kcoap jvm jars against Android API signatures at minSdk).
  - `samples/js-consumer` (production bundle, size budget, headless smoke test).
  - ci.yml jobs.
- **Depends on:** C3, W1.
- **Risk:** low.
- **Accept:** all three samples build in CI; the bundle stays within a budget committed in the repository; minSdk is recorded in docs/platforms.md.

**WP-SEC1: EDHOC server session safety**

- **Findings:** SEC-6 (1); CLR-6; SEC-9; SEC-7 (1, the EDHOC and software-key parts); SEC-10 (1, docs).
- **Scope:**
  - kcoap-edhoc-oscore: an internal `PendingSessions` that reserves C_R atomically, binds each pending
    handshake to the peer that sent message_1, and expires entries by an injected `TimeSource`; a bound on
    pending handshakes per peer and in total.
  - kcoap-edhoc: one internal factory for peer-authentication failures that sends a generic error and keeps
    the detail in the exception; `EdhocSession` and `SoftwarePrivateKey` throw after `destroy()`.
  - `CertificateValidator` KDoc and docs/architecture.md: what is trusted with and without a validator.
- **API changes:** calls on a destroyed session or key throw `IllegalStateException`.
- **Depends on:** A2 (shares kcoap-edhoc). Runs before A3, which then owns kcoap-edhoc-oscore.
- **Accept:**
  - Two concurrent handshakes never get the same C_R (stress test on real threads, failing without the lock).
  - A message_3 from another peer cannot take or abort a pending handshake.
  - Pending handshakes expire, and the bounds refuse new ones with an `EndpointEvent`-style report.
  - The wire error for every peer-authentication failure is the same.

**WP-SEC2: Fail-closed message security**

- **Findings:** SEC-3; SEC-4.
- **Scope:**
  - kcoap-coap server: an `AccessPolicy` on resources with the built-ins `Public`, `MessageSecurityRequired`
    and `Authenticated`, and a server-wide default; the policy sees the authenticated identity.
  - kcoap-coap client and the `ClientMessageSecurity` SPI: response verification is reported as verified or
    unverified; the client acts on an Echo retry or any other decision only for verified responses, and never
    falls back to plaintext silently.
  - kcoap-oscore: implements the changed SPI.
- **API changes:** the SPI result type; the resource policy.
- **Depends on:** QB-6 (kcoap-coap ownership) and QB-5 (kcoap-oscore). Lands before B1.
- **Accept:**
  - An unprotected request to a resource that requires message security gets 4.01 and never reaches the
    handler.
  - An unprotected 4.01 with Echo does not make the client resend under OSCORE unless it was verified.
  - libcoap OSCORE interop stays green.

> **Amendment (SEC2).** Done as [ADR 0008](adr/0008-fail-closed-message-security.md) records. Beyond the scope
> above: Observe registrations are keyed by identity; a missing path is refused like its nearest ancestor;
> `/.well-known/core` is filtered per caller and marks `osc`; `SecurityContext.identity` and kcoap-edhoc-oscore's
> `EdhocIdentity` carry the peer credential (SEC-3 item 5); `OscoreClient` and `PeerSecurity` fail without a
> context or protection unless `allowUnprotected`. An unverified response surfaces as
> `CoapException.Reason.UNVERIFIED_RESPONSE` with the new `CoapException.response`. The client still lets a
> `MessageSecurityException` from `protectRequest` escape as it is; wrapping it in `CoapException(SECURITY)`
> stays with B3. SEC-3 item 6 (a Block1 budget per identity class) is not done.

### 4.4 Roadmap features (not refactors)

| Feature | Notes |
|---|---|
| E1. DTLS 1.2 engine | Pure Kotlin, sans-IO, on `CryptoBackend`; uses A4's x509 package and codec; publishes kcoap-dtls and drops the experimental marker from `DatagramSecurity` |
| E2. `X509PathValidator` | Constrained profile, injected `Clock`, tested against an x509-limbo subset; needed before DTLS certificate mode |
| E3. kcoap-crypto-pq | ML-DSA verify first; needs a public incremental XOF and SHA3; constant-time rules; must pass C2 on every target |
| E4. kcoap-apple | Curated Swift facade, owned scope, optional SKIE, XCFramework as an SPM binary target |
| E5. linuxArm64 | In UNIVERSAL and SOCKETS |
| E6. Large-body streaming | New functions alongside the ByteArray API |
| E7. Key-store and platform validator adapters | §3.9 |
| E8. Message-level AES-CCM on WebCrypto | Only if a browser benchmark shows the need |
| E9. Mobile lifecycle | Transport-failure semantics, re-observe helper, keepalive policy, transport rebind |
| E10. Network.framework adapter | Trigger: iOS cellular or VPN On Demand needs, multicast, entitlements |
| E11. kcoap cross-proxy | For JVM servers that front browsers. Decide after I1 |

### 4.5 Findings not in this input

| Area | Package |
|---|---|
| CLR-\* in kcoap-coap | S1, landing before B1 so that each fix can be reverted on its own |
| CLR-\* in oscore / edhoc / edhoc-oscore | A1 / A2 / A3 |
| Errors/observability in coap | B3 |
| Errors/observability in other modules | The package that owns those files |
| Crypto | A4 / A5 |
| Build | C1 |
| SEC-6, SEC-7, SEC-9, SEC-10, CLR-6 (EDHOC server) | SEC1 |
| SEC-3, SEC-4 (message security) | SEC2 |

---

## 5. Keep, rejected, deferred

### Keep (strengths)

- **Dependency direction.**
  - Protocol modules depend only on SPIs. Backends, sockets and the testkit appear only in test configurations.
  - An OSCORE-only build does not pull in EDHOC.
  - kcoap-edhoc does not depend on CoAP.
  - kcoap-transport has no third-party dependencies.
- **Platform neutrality and code structure.**
  - All code is `commonMain` with no `expect`/`actual`. The only platform main code is in adapters.
  - `explicitApi()` everywhere, and package layering inside kcoap-coap is acyclic.
- **Sans-IO cores.**
  - Sans-IO `UdpMessaging`, the EDHOC state machines (whose sealed states wipe secrets on every transition), and the `DtlsEngine` effect contract.
  - Driver discipline: the Mutex is never held across a suspension, and effects run outside the lock.
- **Security SPI.** The message-security SPI sits where RFC 8613 Figure 1 places OSCORE, and RFC 9668 is built as decorators.
- **Crypto.** The all-suspend `CryptoBackend` with opaque keys and common fallbacks. BC composes by delegation and draws randomness from the wrapped backend.
- **Endpoints.** `Endpoint` is sealed, `Endpoint.Secure` equality is (session, epoch), and `IpLiteral` is common code.
- **Injection and concurrency.**
  - Injected time, randomness, tokens, ephemeral keys and connection identifiers, plus the virtual-time testkit.
  - `Mutex` and atomics only, with `NonCancellable` cleanup.
- **Bounded resources and API semantics.**
  - Bounded resources (`maxPendingSessions`, `ExpiringCache`, body limits, `DROP_OLDEST`/`CONFLATED` buffers).
  - 4.xx/5.xx are returned as responses, not thrown. Observe is a cold `Flow`.
  - Structured exceptions, and `CancellationException` is always rethrown.
- **Logging and testing.**
  - Logging has no framework dependency; payload dumps are off by default; `toString()` never shows secrets.
  - Fuzzing with `ByteMutator`, ACVP vectors, BC differential tests, libcoap and libedhoc interop.
- **Strategy decisions.** The browser strategy (end-to-end OSCORE and EDHOC over `coap+ws` through a proxy), and building kcoap's own DTLS engine.

### Rejected

| Proposal | Reason |
|---|---|
| Scheme or transport-factory registry (MB-1, DP-01, U6) | An exhaustive `when` over sealed `Endpoint` plus `Connector.schemes` covers it with types. The docs are corrected instead |
| Splitting CoapEndpoint into kinds, or making it an interface (API-04) | One endpoint over an internal `PeerLink` |
| Endpoint-construction DSL (draft §3.3) | It would be a third way to build an endpoint, it would replace merged constructors the guide uses, and its trailing lambda would mean something else on `CoapServer` |
| Injecting `MessageCodec` into UdpMessaging (DP-01) | Only the reliable side chooses a codec |
| A synchronous `CryptoCapabilities` set (API-05, DP-02) | WebCrypto can only be probed asynchronously. Suspend members plus memoization cover both software and hardware backends |
| `compose()`, `owns()` routing, dispatch chains (U2, U5, MB-4) | Kotlin delegation already composes backends |
| `implementation(kcoap-common)` in kcoap-cbor and kcoap-crypto (MB-6 item 2) | `CborException` and `CertificateFormatException` extend `KcoapException`, so the `api` rule requires `api` |
| Public `SecureRandom` in kcoap-common (U8) | Plain CoAP stays free of crypto. Security layers use the injectable backend |
| Konsist, a diagram generator, a scope-checking plugin (MB-2, MB-6, MB-10) | Module dependency direction, `verifyCommonOnly`, ABI dumps and review are enough |
| Renaming kcoap-transport-ktor; a per-module target DSL (MB-3) | Tiers already give each module the right targets |
| Separate kcoap-x509 module (U10) | It is pure Kotlin and belongs next to `CertificateValidator` |
| Public `CryptographyProvider`; `@KcoapDelicateApi` (U9, API-07) | Nothing uses it today. Provider choice can be added later without breaking anything |
| Removing the "redundant" declarations (MB-6) | Every module declares what it imports |
| Converting the `CborValue` and `KeyExchangeAlgorithm` data classes (API-06) | Their shapes are fixed by the RFCs |
| Builders on every config (API-06 item 5, in full) | Only two configs are certain to grow. Elsewhere, HIDDEN overloads plus the ABI check |
| `Long` milliseconds, callback-style Observe, `fun interface` instead of suspend lambdas (API-02) | Swift support belongs in the Apple facade |
| An EDHOC `PeerError` result variant or a typed-state API (API-09) | RFC 9528 models errors as aborts, and `retryWith` is enough |
| Per-target store modules in the core (API-01) | Stores are application SPIs, with README recipes |
| Caching decoded provider keys (U5) | Non-extractable keys come as a future adapter |
| Making `LogTags` internal; moving `PeerSecurity` into kcoap-coap now (MB-7) | One catalogue is easier to find. `PeerSecurity` moves only when a second producer appears |
| Node in SOCKETS | It would ship socket code to browsers |

### Deferred (with trigger)

| Item | Trigger |
|---|---|
| Pure-Kotlin post-quantum (U4, MB-4) | E3, after C2 |
| Pure-Kotlin X25519, Ed25519, ChaCha20 (U2) | A platform where the mandatory suites cannot run |
| Message-level CCM on WebCrypto | A browser benchmark |
| Platform certificate validators (U10) | After E2 |
| kcoap-apple, XCFramework, SKIE (API-02, MB-9) | After the first publish |
| mingwX64, tvOS, watchOS (U7) | Demand plus a CI runner. linuxArm64 comes first. x64 Apple targets and androidNative are not planned |
| Large-body streaming (API-11) | E6 |
| HTTP transport, Netty, Network.framework | OSCORE-over-HTTP users; E10's triggers |
| Coverage numbers for non-JVM targets | Accepted gap. The same commonTest runs everywhere |
| Public sans-IO API level (MB-1) | After B1 has been stable for one release |

---

## 6. How the critique was handled

**Accepted:**

- **A2, A3.** Stale release gates dropped; release freeze added (§4.1, WP-0).
- **A4.** Kover is JVM-only; `CLOSED` already exists; KEM and Hash probes are new.
- **B1–B6.** File-ownership table; C1 lands before B1 and later WPs regenerate their dumps; MB-6 item 2 rejected to resolve the D2 conflict; both markers in kcoap-common, with kcoap-transport on `api(kcoap-common)`; CLR fixes in S1 before B1; the A2 rename includes its call sites.
- **C.**
  - `isSupportedBy(crypto, method)` covers static DH and signatures.
  - The raw API filters lazily.
  - The delegation caveat is documented and the invariant is tested.
  - Exception targets are named (§3.6).
  - `CertificateFormatException` is new.
  - `CoapException` moves to the stack.
  - One TLS design, and `StreamSecurity` removed.
- **D.** T1, I1, P1, W1 (Darwin engine, CIO optional), E9, E10, the rule against Node in SOCKETS, and docs/platforms.md.
- **E.** DSL dropped; builders limited to two configs; `FreshnessPolicy` enum instead of a nullable Boolean; kcoap-dtls unpublished with no marker; `kcoap.core` guard as a check task; the conformance suite is published.
- **F.** Every acceptance criterion rewritten to be checkable (TestKit, abstract base classes with named homes, the dump-union check, invariants, a counting provider, a named `ws` server plus a browser job, `@Throws(IllegalArgumentException)` on `parse`, samples, the macOS CI gate).
- **G and H.** Stress tests, OSCORE over TCP/WS interop, the Kover gap, CHANGELOG, ADRs, the capability matrix, and no TLS claims before the code supports them.

**Rejected or superseded:**

- **A4 "no 95% rule for kcoap-edhoc".** Wrong: `kcoap-edhoc/build.gradle.kts:31` sets `minBound(95)`.
- **A1 "the merge is half done".** Superseded: the merge is committed as `f0dc39d`, the tree is clean and no worktrees remain. B0 is replaced by W1.
- **"ktor-network-tls is client-only".** Not assumed either way; T1 checks it first.