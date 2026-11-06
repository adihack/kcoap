# Getting started

Which kcoap modules a use case needs, how to add them with one version, and a first CoAP client and server,
then OSCORE, EDHOC and post-quantum EDHOC on top. Every Kotlin example on this page is compiled and run, over UDP
on the loopback interface with the platform's cryptography, by
`kcoap-edhoc-oscore/src/jvmTest/kotlin/dev/kcoap/edhoc/oscore/GettingStartedExamplesTest.kt` (on free ports
instead of 5683).

> **Before 1.0, a minor release may break the API.** kcoap is at `0.1.0-SNAPSHOT` and nothing is released yet.
> Every breaking change is listed in the [changelog](../CHANGELOG.md) with a migration note, and
> [versioning](versioning.md) says what each kind of release may change.

## Add kcoap to a build

Nothing is published yet. Until the first release reaches Maven Central, publish a snapshot into your local Maven
repository from a clone of the work branch:

```bash
git clone --branch feat/protocol-core https://github.com/adihack/kcoap.git && cd kcoap
./gradlew publishToMavenLocal    # dev.kcoap:*:0.1.0-SNAPSHOT into ~/.m2/repository; Apple artifacts only on a Mac
```

Then import the bill of materials, `kcoap-bom`, and add the modules without versions. kcoap's modules share
internal plumbing, so a build uses one version for all of them, and the BOM sets it for the modules you name and
for those they bring along; a library built on a newer kcoap moves them all to its version together
([kcoap-bom](../kcoap-bom/README.md#version-alignment)):

```kotlin
// build.gradle.kts of a JVM or Android project
repositories {
    mavenLocal { content { includeGroup("dev.kcoap") } } // kcoap, until the first release
    mavenCentral()
}

dependencies {
    implementation(platform("dev.kcoap:kcoap-bom:0.1.0-SNAPSHOT"))
    implementation("dev.kcoap:kcoap-edhoc-oscore")   // EDHOC and OSCORE over CoAP; brings the CoAP stack
    implementation("dev.kcoap:kcoap-crypto-default") // the platform's cryptography
    implementation("dev.kcoap:kcoap-transport-ktor") // UDP and TCP sockets
}
```

[samples/jvm-consumer](../samples/jvm-consumer/README.md) is this build as a project of its own, which CI builds
against `publishToMavenLocal` and runs: the examples below, from a first client and server to EDHOC, over UDP on
loopback. [samples/js-consumer](../samples/js-consumer/README.md) is the same for a browser page, and
[samples/android-consumer](../samples/android-consumer/README.md) for an Android app.

In a Kotlin Multiplatform project the BOM goes into `commonMain` with the modules every target uses, and an adapter
that some targets lack goes into the source sets of the targets that have it:

```kotlin
// build.gradle.kts of a Kotlin Multiplatform module with jvm, iosArm64 and js targets
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("dev.kcoap:kcoap-bom:0.1.0-SNAPSHOT"))
            implementation("dev.kcoap:kcoap-edhoc-oscore")
            implementation("dev.kcoap:kcoap-crypto-default")
            implementation("dev.kcoap:kcoap-transport-ktor-websocket") // every target, browsers included
        }
        // Sockets exist on the JVM, Android, Apple and Linux, not in js or wasmJs.
        jvmMain.dependencies { implementation("dev.kcoap:kcoap-transport-ktor") }
        iosMain.dependencies { implementation("dev.kcoap:kcoap-transport-ktor") }
    }
}
```

A Maven build imports the BOM with `<scope>import</scope>` and names the JVM artifacts (`kcoap-coap-jvm`), as the
[kcoap-bom README](../kcoap-bom/README.md#what-it-constrains) shows. The release coordinates will be the same,
`dev.kcoap:<module>:<version>` from `mavenCentral()`, under the verified `dev.kcoap` namespace, once the
[release freeze](releasing.md#release-freeze) is over.

## Which modules to add

Add the modules of the protocol you use, one crypto backend, and one adapter per transport. The core modules
(kcoap-common, kcoap-crypto, kcoap-cbor, kcoap-cose, kcoap-transport, kcoap-coap-message, kcoap-edhoc) come along
as dependencies of these. Every artifact below is in `kcoap-bom`.

### By protocol

| You want | Add | Notes |
|---|---|---|
| A CoAP client or server | `kcoap-coap` and a transport (below) | `CoapClient`, `CoapServer`, `CoapEndpoint`; Block-wise, Observe, Q-Block (off by default), Echo, access policies. Brings `kcoap-coap-message` |
| OSCORE with pre-shared keys | `kcoap-coap`, `kcoap-oscore`, `kcoap-crypto-default` | `OscoreClient`, `OscoreServer`. A pre-shared key needs a durable `SequenceNumberStore` that you supply ([recipes per runtime](../kcoap-oscore/README.md#writing-a-durable-store)) |
| OSCORE without kcoap's stack (a proxy, another CoAP stack) | `kcoap-oscore`, `kcoap-crypto-default` | On `kcoap-coap-message` only; the caller takes over the duties the [kcoap-oscore README](../kcoap-oscore/README.md) lists |
| EDHOC, then OSCORE, over CoAP | `kcoap-edhoc-oscore`, `kcoap-crypto-default` | `EdhocServer`, `EdhocResource`, `EdhocClientHandshake`, `PeerSecurity`; brings `kcoap-edhoc`, `kcoap-oscore` and `kcoap-coap` |
| EDHOC over another carrier | `kcoap-edhoc`, `kcoap-crypto-default` | The sans-IO `EdhocInitiator` and `EdhocResponder`, independent of CoAP |
| Post-quantum EDHOC (suite -24: ML-KEM-512, ML-DSA-44) | add `kcoap-crypto-bc` | JVM and Android only; compose `BouncyCastleCryptoBackend(classical = DefaultCryptoBackend())`. Other targets do not run suite -24 yet (roadmap E3) |
| Tests without a network | `kcoap-testkit` (test scope) | A lossy in-memory network and in-memory TCP and WebSocket connections on virtual time |
| A crypto backend of your own (key store, HSM) | `kcoap-crypto`; `kcoap-crypto-testsuite` (test scope) | The `CryptoBackend` SPI and the conformance suite every backend's tests extend ([key stores](../kcoap-crypto/README.md#keys-in-a-key-store)) |

### By transport

| Scheme | Module | Client | Server | Runs on |
|---|---|---|---|---|
| `coap` (UDP, RFC 7252) | `kcoap-transport-ktor` | `KtorUdpTransport` | `KtorUdpTransport` | JVM, Android, Apple, Linux |
| `coap+tcp` (RFC 8323) | `kcoap-transport-ktor` | `KtorTcpConnector` | `KtorTcpListener` | JVM, Android, Apple, Linux |
| `coaps+tcp` (TLS) | `kcoap-transport-ktor` | `KtorTlsConnector` (JVM and Android only) | none in kcoap: a TLS-terminating reverse proxy in front of `KtorTcpListener` ([ADR 0010](adr/0010-server-side-tls.md)) | client on JVM and Android |
| `coap+ws` (RFC 8323) | `kcoap-transport-ktor-websocket`; server: `kcoap-transport-ktor-websocket-server` and a ktor server engine you choose | `KtorWebSocketConnector` | `KtorWebSocketListener` | client everywhere, browsers and Node.js included; server on the JVM, Apple and Linux |
| `coaps+ws` (TLS) | as `coap+ws` | `KtorWebSocketConnector()` on Apple, js and wasmJs; on the JVM and Android, `KtorWebSocketConnector(client)` with an `HttpClient` whose engine checks host names (OkHttp, or the Java engine on the JVM), not CIO ([why](platforms.md#clients)) | none in kcoap: a ktor engine that runs TLS (Netty, Jetty) or a TLS-terminating proxy | see [platforms](platforms.md#tls-support-per-runtime) |
| `coaps` (DTLS) | none yet | — | — | DTLS comes last (roadmap E1); a `coaps` request fails with `CoapException(TRANSPORT_UNAVAILABLE)` before anything is sent |
| in memory, for tests | `kcoap-testkit` | `InMemoryNetwork`, `InMemoryConnections` | the same | everywhere |

OSCORE and EDHOC protect requests and responses end to end over every one of these, proxies included, so they are
the way to secure `coap` until DTLS lands. They leave the messaging layer (type, Message ID, token) unprotected,
which DTLS and TLS cover hop by hop ([architecture](architecture.md#layers)).

### By platform

| Platform | Modules | What to know |
|---|---|---|
| **JVM application server** | `kcoap-edhoc-oscore`, `kcoap-crypto-default`, `kcoap-transport-ktor`; `kcoap-transport-ktor-websocket-server` with a ktor engine (`io.ktor:ktor-server-cio` or Netty); `kcoap-crypto-bc` for post-quantum | Java 17 or newer. `coap`, `coap+tcp` and `coap+ws` servers; TLS at a reverse proxy. Supply a bounded `MutableSecurityContextRepository` to an `EdhocServer` and durable sequence-number stores ([sample application](../samples/jvm-consumer/README.md)) |
| **Android app** | `kcoap-edhoc-oscore`, `kcoap-crypto-default`, `kcoap-transport-ktor` and/or `kcoap-transport-ktor-websocket`; `kcoap-crypto-bc` for post-quantum | The jvm artifacts, from API level 26; Kotlin 2.3 or newer and Java 17 bytecode in the app; no R8 rules of the app's own ([sample app](../samples/android-consumer/README.md)). Which algorithms exist depends on the device's providers, and EDHOC offers only what runs |
| **iPhone or Mac app** (a Kotlin Multiplatform shared module) | `kcoap-edhoc-oscore`, `kcoap-crypto-default`, `kcoap-transport-ktor` and/or `kcoap-transport-ktor-websocket` | CryptoKit and CommonCrypto underneath. The app's one framework `export()`s the kcoap types its Swift code uses; never combine it with a second Kotlin framework. Swift catches kcoap's failures as `NSError` (`@Throws`). The UDP and TCP sockets are BSD sockets; the WebSocket client runs on URLSession |
| **Linux gateway** (linuxX64) | as the JVM server, without `kcoap-crypto-bc` | OpenSSL 3, linked into the binary. No TLS in the native socket stack: use a TLS-terminating proxy |
| **Browser** (js, wasmJs) | `kcoap-edhoc-oscore`, `kcoap-crypto-default`, `kcoap-transport-ktor-websocket` | WebCrypto underneath; `coap+ws` and `coaps+ws` (the browser does the TLS) to a proxy that reaches the devices, with OSCORE and EDHOC end to end. No UDP, TCP or DTLS. No ChaCha20-Poly1305, so EDHOC suites 4 and 5 are never offered there. A minimal client with OSCORE bundles to about 1.1 MB minified, 310 KB with gzip ([sample page and its size budget](../samples/js-consumer/README.md); [platforms](platforms.md#bundle-size)) |
| **Node.js** (js, wasmJs) | as the browser | The WebSocket client only: kcoap ships no Node socket transport |

[Platforms](platforms.md) has the minimum versions, the algorithms and EDHOC suites each target runs, the TLS per
runtime and what mobile apps must handle.

## A first client and server

A server on UDP port 5683 with one resource, and a client that asks for it:

```kotlin
suspend fun helloServer(scope: CoroutineScope): CoapServer =
    CoapServer(KtorUdpTransport.bind("0.0.0.0", 5683), scope) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }

suspend fun helloClient(scope: CoroutineScope) {
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { requestTimeout = 5.seconds })
    try {
        println(client.get("coap://127.0.0.1:5683/hello").payloadAsText())
    } catch (e: KcoapException) { // here a CoapException, whose reason says what failed
        println("Failed: ${e.message}")
    } finally {
        client.close()
    }
}
```

- The `scope` is yours: the endpoint's coroutines run in it, and cancelling it closes the endpoint. Give it the
  endpoint's lifetime: on Android an `Application`-level scope (or `ProcessLifecycleOwner`'s) for endpoints and
  contexts that outlive a screen, not an Activity's `lifecycleScope`; on a server the application's
  ([mobile lifecycle](platforms.md#mobile-lifecycle)).
- Error codes come back as responses (`response.isSuccess`). What fails at runtime throws a `CoapException`, with a
  `reason` such as `RESPONSE_TIMEOUT`, `RESET` or `TRANSPORT_UNAVAILABLE`; every protocol's exception extends
  `KcoapException`, so one `catch` covers kcoap. `IllegalArgumentException` and `IllegalStateException` mean a
  mistake in the calling code, such as a string that is not a CoAP URI.
- `CoapClientConfig { ... }` and `CoapEndpointConfig { ... }` are builders: set what you need, the rest keeps its
  default.

The [client and server guide](guide-client-server.md) goes on from here: Observe, block-wise bodies, discovery,
TCP and WebSockets, Q-Block, endpoint tuning and tests on virtual time.

## OSCORE with pre-shared keys

Client and server derive their contexts from the same Master Secret, each with the other's Sender ID as its
Recipient ID (RFC 8613 §3):

```kotlin
val crypto = DefaultCryptoBackend()

suspend fun oscoreServer(scope: CoroutineScope, masterSecret: ByteArray, store: SequenceNumberStore): CoapServer {
    val context = SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = byteArrayOf(0x01),
            recipientId = byteArrayOf(0x00),
        ),
        crypto,
        sequenceStore = store, // durable for a pre-shared key
    )
    val contexts = InMemoryContextRepository().apply { add(context) }
    val config = CoapServerConfig(security = OscoreServer(contexts))
    return CoapServer(KtorUdpTransport.bind("0.0.0.0", 5683), scope, config) {
        resource("lights") { get { CoapResponse.content("on") } } // OSCORE only: the default with security
        resource("status") {
            access = AccessPolicy.Public // anyone, with or without OSCORE
            get { CoapResponse.content("up") }
        }
    }
}

// Once per process, shared by every client that talks to that server (below).
suspend fun clientContext(masterSecret: ByteArray, store: SequenceNumberStore): SecurityContext =
    SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = byteArrayOf(0x00),
            recipientId = byteArrayOf(0x01),
        ),
        crypto,
        sequenceStore = store, // durable for a pre-shared key
    )

suspend fun oscoreClient(scope: CoroutineScope, context: SecurityContext) {
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { security = OscoreClient(context) })
    println(client.get("coap://127.0.0.1:5683/lights").payloadAsText())
    client.close()
}
```

- **The sequence-number store has no default.** A pre-shared Master Secret outlives the process, so its sequence
  numbers must too, or a restarted device reuses nonces (RFC 8613 Appendix B.1.1). Supply a store that is durable
  before `store()` returns; the [kcoap-oscore README](../kcoap-oscore/README.md#writing-a-durable-store) has
  recipes for the JVM and Android, Apple, Linux, browsers and Node.js. A restored context proves its freshness with
  Echo first (Appendix B.1.2), which the client does by itself. `EphemeralSequenceNumberStore` is only for keys that
  are new in this process run, as EDHOC's are.
- **One live context per key.** Derive a pre-shared context once per process and share it, as `clientContext` is
  meant to be used; never derive it twice at the same time from the same store and context name, from two screens
  say. Two live contexts hand out the same sequence numbers, so one nonce would protect two messages under one
  Sender Key, which RFC 8613 §7.2 forbids and kcoap cannot detect
  ([kcoap-oscore](../kcoap-oscore/README.md#sequence-numbers-and-freshness)). On the server, the one
  `OscoreServer` holds the context for every request.
- **It fails closed** ([ADR 0008](adr/0008-fail-closed-message-security.md)). With `security` set, every resource
  takes OSCORE requests only, unless its `AccessPolicy` says otherwise; a plain request gets 4.01 before any handler
  runs. A client without a context for a server sends nothing and fails with `CoapException(SECURITY)`, and it acts
  only on responses it verified.

## EDHOC, then OSCORE

EDHOC (RFC 9528) authenticates both sides with their credentials and gives them a fresh OSCORE context; with the
combined request of RFC 9668 the whole handshake takes two round trips. Each side has a credential, here a CWT
Claims Set with an Ed25519 key, and trusts the other's:

```kotlin
suspend fun credential(subject: String, kid: Byte): LocalCredential {
    val keys = crypto.generateKeyPair(KeyType.ED25519)
    val key = CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, keys.publicKey, kid = byteArrayOf(kid))
    return LocalCredential(
        AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
        CredentialIdentifier.KeyId(byteArrayOf(kid)),
        keys.privateKey,
        AuthenticationKind.SIGNATURE,
    )
}

fun initiatorConfig(client: LocalCredential, server: LocalCredential) = EdhocInitiatorConfig(
    method = EdhocMethod.SIGNATURE_SIGNATURE,
    cipherSuites = listOf(CipherSuite.SUITE_0),
    credentials = listOf(client),
    credentialResolver = TrustedCredentials(crypto, listOf(server.credential)),
)

fun responderConfig(server: LocalCredential, client: LocalCredential) = EdhocResponderConfig(
    methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
    cipherSuites = listOf(CipherSuite.SUITE_0),
    credentials = listOf(server),
    credentialResolver = TrustedCredentials(crypto, listOf(client.credential)),
)
```

The server mounts the EDHOC resource and verifies OSCORE, the combined request included, against the contexts
EDHOC adds; the client runs the handshake and installs the protection it returns:

```kotlin
suspend fun edhocServer(scope: CoroutineScope, config: EdhocResponderConfig): CoapServer {
    val contexts = InMemoryContextRepository() // one context per handshake: bound it in production
    val edhoc = EdhocServer(config, crypto, contexts)
    val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
    return CoapServer(KtorUdpTransport.bind("0.0.0.0", 5683), scope, CoapServerConfig(security = security)) {
        resource(".well-known") { add(EdhocResource(edhoc)) } // POST /.well-known/edhoc, public
        resource("lights") {
            get {
                val peer = (securityIdentity as EdhocIdentity).peerCredential // who EDHOC authenticated
                CoapResponse.content("on for ${peer.claimsSet?.subject}")
            }
        }
    }
}

suspend fun edhocClient(scope: CoroutineScope, config: EdhocInitiatorConfig) {
    val security = PeerSecurity()
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { this.security = security })
    val server = CoapUri.parse("coap://127.0.0.1:5683")
    // message_1 and message_2 here; message_3 rides on the first OSCORE request (RFC 9668).
    val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
    security.install(client, server, protection)
    println(client.get("coap://127.0.0.1:5683/lights").payloadAsText())
    client.close()
}
```

- EDHOC offers and accepts only the cipher suites the backend runs (`CipherSuite.isSupportedBy(crypto, method)`), so
  a list that names suites a platform lacks still works there. Suites 2 and 3 (P-256) are the ones RFC 9528 makes
  mandatory; suite 0 (X25519, Ed25519) is used here because its credentials are short.
- Pinning the peer's credential (`TrustedCredentials`) is how kcoap trusts one today: it ships no X.509 path
  validator yet, so an `x5chain` is trusted by pinning or by a `CertificateValidator` you supply
  ([kcoap-edhoc](../kcoap-edhoc/README.md)).
- A production `EdhocServer` bounds its repository and its pending handshakes; the
  [kcoap-edhoc-oscore README](../kcoap-edhoc-oscore/README.md) says how, and what a handler learns about its peer.
- A handshake fails with an `EdhocException`, also a `KcoapException`: `EdhocPeerErrorException` when the server
  sent an EDHOC error (with the suite to retry with, if that was the problem), `EdhocTransferException` when it did
  not answer with EDHOC at all.

## Post-quantum EDHOC

On the JVM and Android, `kcoap-crypto-bc` adds ML-KEM and ML-DSA to the platform's classical cryptography, and
EDHOC then also runs suite -24 (ML-KEM-512, ML-DSA-44; libedhoc's private suite of draft-ietf-lake-pqsuites):

```kotlin
// JVM and Android: ML-KEM and ML-DSA from Bouncy Castle, everything else from the platform.
val postQuantum = BouncyCastleCryptoBackend(classical = DefaultCryptoBackend())
```

Pass `postQuantum` wherever `crypto` goes above, list `CipherSuite.SUITE_PQC_1` in the configurations, and give each
side an ML-DSA-44 credential (a `CoseKey` of type `AKP` with `SignatureAlgorithm.ML_DSA_44`). Its message_2 and
message_3 exceed a UDP datagram and travel block-wise, and the combined request does not yet fall back to the
sequential flow when message_3 is too large to combine (RFC 9668 §3.2.2 step 3.1), so the client takes `run()`
rather than `runCombined()` and installs the context it returns:

```kotlin
val session = EdhocClientHandshake.over(client, server, config, postQuantum).run() // message_1 to message_3
security.install(client, server, OscoreClient(session.oscore))
```

Suite -24 is cross-tested against libedhoc in both roles with EDHOC's own messages
([kcoap-edhoc](../kcoap-edhoc/README.md)); over kcoap's CoAP stack it runs this way between kcoap's client and
server (message_2 in Block2, message_3 in Block1), not yet against another CoAP implementation. Other targets do not
run suite -24 yet (roadmap E3); EDHOC leaves it out there by itself.

## Over TCP, TLS and WebSockets

The URI scheme picks the transport, so the code above works over any of them once the endpoint has a connector or
listener for the scheme:

```kotlin
fun reliableClient(scope: CoroutineScope): CoapClient =
    CoapClient(listOf(KtorTcpConnector(), KtorTlsConnector(), KtorWebSocketConnector()), scope)
```

That client sends `coap+tcp`, `coaps+tcp` (the TLS connector, on the JVM and Android) and `coap+ws` requests, and
`coaps+ws` where the WebSocket connector's own engine authenticates the server: on Apple, in browsers and on
Node.js. On the JVM and Android, give the connector an `HttpClient` whose engine checks host names, such as OkHttp
or (on the JVM) the Java engine, for `coaps+ws`; not CIO, whose name check falls short of RFC 9525 §6
([platforms](platforms.md#clients)). A request whose scheme the endpoint cannot serve, `coaps` included, fails with
`CoapException(TRANSPORT_UNAVAILABLE)` before anything is sent. Servers over TCP and WebSockets are in the
[guide](guide-client-server.md#over-tcp-and-websockets); TLS for servers ends at a reverse proxy
([platforms](platforms.md#servers)).

## Next steps

- [Client and server guide](guide-client-server.md): Observe, block-wise, Q-Block, discovery, message security,
  tuning, tests without a network.
- [Platforms](platforms.md): minimum versions, the capability matrix, TLS per runtime, mobile lifecycle.
- [Logging](logging.md): levels per tag, sinks, a ring buffer for diagnostics.
- The module READMEs, from the [documentation hub](README.md#reference), and the API reference
  (`./gradlew dokkaGenerate` builds it into `build/dokka/html` until it is published with the first release).
