# Testing

kcoap is tested at three levels, from the most precise to the most realistic.

## 1. RFC test vectors, byte for byte

Every protocol module checks the examples its RFCs publish, value by value. A test fails on the
first byte that differs, which pins down the exact step that is wrong.

| Module | Vectors |
|---|---|
| `kcoap-crypto` | FIPS 202 (SHAKE256), NIST SP 800-185 (KMAC256) |
| `kcoap-crypto-default` | FIPS 197 (the AES block, through ECB and CTR), RFC 3610 (AES-CCM, all 24), RFC 5869 (HKDF), RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 8613 Appendix C (AEAD and key derivation), SEC 2 base points (point decompression) |
| `kcoap-crypto-testsuite` | NIST ACVP ML-KEM and ML-DSA key generation, encapsulation, decapsulation, signing and verification, and one published known answer per classical algorithm (RFC 3610 and the cose-wg examples for AES-CCM, the GCM test cases, RFC 8439, RFC 7748 §6.1 and §6.2, RFC 5903 §8.1 and §8.2, RFC 8032, RFC 6979 §A.2.5 and §A.2.6, FIPS 180-4, FIPS 202, RFC 4231), run through each backend that extends its suites |
| `kcoap-crypto-bc` | The ACVP suites of `kcoap-crypto-testsuite`, through the public backend |
| `kcoap-cbor` | RFC 8949 Appendix A |
| `kcoap-cose` | RFC 8613 C.4 (AAD), RFC 9529 credentials |
| `kcoap-coap-message` | RFC 8613 Appendix C messages (codec) |
| `kcoap-oscore` | RFC 8613 Appendix C.1–C.8, every intermediate value, both sides |
| `kcoap-edhoc` | RFC 9529 traces |

Vectors are extracted from the RFC text by scripts in `tools/`, not typed in by hand.

Every crypto backend also runs the conformance suite of `kcoap-crypto-testsuite`: its `supports` answers must
hold (what it says it runs works and reproduces the known answers, what it says it lacks throws
`UnsupportedAlgorithmException`), on every target it builds for. The suite prints a capability report, which
the build copies into its log: `[jsNodeTest] kcoap-crypto capabilities of DefaultCryptoBackend: AEAD yes ...`.
The crypto modules' test tasks are never taken from the build cache or up to date, so every build that asks for
them logs the report of its own machine.

## 2. Differential tests against independent implementations

On the JVM, kcoap's own code is compared with Bouncy Castle on random inputs: AES-CCM for every tag
and nonce length and the long-AAD encoding, AES-GCM and ChaCha20, SHAKE256 and KMAC256.

## 3. Fuzzing hostile input

Every decoder and verifier is fed thousands of mutated RFC messages and random bytes (with
`kcoap-testkit`'s `ByteMutator`, seeded so failures reproduce): CBOR, the UDP, TCP and WebSocket codecs,
the OSCORE option and OSCORE verification, and EDHOC message_1 to message_3. Each must fail only with
its own protocol exception, never with an index or state error, and a tampered protected message must
never verify. These run on every platform, natively included.

## 4. Live interop with libcoap and libedhoc

Containers built from `interop/` run the reference implementations, and JVM tests talk to them over
real sockets, with kcoap in each role: CoAP client and server against libcoap, OSCORE client and
server against libcoap, EDHOC Initiator and Responder against libedhoc, post-quantum suite included.
The tests skip themselves when Docker or an image is missing. See `interop/README.md`.

They need Docker's host network, so Linux, and one port: the browser-topology test (WP-I1) puts its device on
UDP port 5683 of a loopback address of its own, 127.a.b.c, since libcoap's proxy forwards only there, so
nothing may hold UDP 5683 on the wildcard address (a manual `coap-server -p 5683`, for example). Runs at the
same time, from several worktrees too, do not collide ([ADR 0009](adr/0009-browser-topology-interop.md)).

## Running the suites

`scripts/test` runs each suite with one command and prints a table of tests per target at the end:

| Suite | Runs | Needs |
|---|---|---|
| `scripts/test unit` | levels 1–3 on every target the host builds (`allTests`): JVM, `linuxX64`, `js` and `wasmJs` on Node.js, and the Apple targets on macOS; then the [build checks](#build-checks) | a JDK |
| `scripts/test interop` | level 4 | Docker |
| `scripts/test coverage` | every JVM test, the Kover HTML and XML reports, and the coverage floor | Docker for the interop share |
| `scripts/test apple` | the common tests on `macosArm64` and `iosSimulatorArm64`, and `checkKotlinAbi` with natively built Apple klibs | macOS with Xcode |
| `scripts/test swift` | the Swift smoke test ([kcoap-swift-smoke](../kcoap-swift-smoke/README.md)): links the `KcoapSmoke` framework for macOS, compiles `SwiftSmoke.swift` against it with `xcrun swiftc` and runs it | macOS with Xcode |
| `scripts/test images` | only builds the libcoap and libedhoc images | Docker |
| `scripts/test all` | unit, interop, coverage | Docker |

Extra arguments go to Gradle: `scripts/test interop --tests '*Oscore*'`.

The suites are selected with one Gradle property, so plain Gradle works too: `-Pkcoap.tests=unit`
leaves out the interop tests (classes named `*Interop*` or in an `interop` package), `interop` runs
only those, and `all` (the default) runs both.

```bash
./gradlew jvmTest -Pkcoap.tests=unit   # the JVM unit tests only
./gradlew linuxX64Test                 # the common tests as native code: Kotlin/Native runtime, OpenSSL crypto
./gradlew jsNodeTest wasmJsNodeTest    # the common tests as JavaScript and WebAssembly on Node.js, WebCrypto underneath
./gradlew koverHtmlReport              # coverage report: build/reports/kover/html/index.html
./gradlew koverVerify                  # fails below the coverage floor
./gradlew checkAndroidApi              # every jvm jar against Android API level 26, without the Android SDK
./gradlew checkAndroidApiDependencies  # the libraries behind them, against the triaged baseline
```

The sample consumers are builds of their own, against `./gradlew publishToMavenLocal`:
`./gradlew -p samples/jvm-consumer build run` ([samples/jvm-consumer](../samples/jvm-consumer/README.md)),
`./gradlew -p samples/js-consumer build` with `-Pkcoap.browser=<browser command>` for its browser run
([samples/js-consumer](../samples/js-consumer/README.md)), and, with the Android SDK,
`./gradlew -p samples/android-consumer assembleRelease lint test`
([samples/android-consumer](../samples/android-consumer/README.md)).

## Nightly soak

The Q-Block (RFC 9177) cross-tests include two soaks with 10 % loss on both sides of the wire, libcoap's
(`-l 10%`) and kcoap's (a lossy transport), that run 3 times in `scripts/test interop` and 20 times when the
Gradle property `kcoap.soak` is `true`, which `kcoap-coap`'s build passes to the test JVM as a system property:

| Soak | Test | Each run |
|---|---|---|
| L9 | `LibcoapClientAgainstKcoapQBlockTest.l9UploadsAndBodiesAt10PercentLossBothWays` | `coap-client -L 7 -N -b 64` PUTs 3000 bytes to a kcoap server and GETs 3000 bytes from it: the upload is stored whole or not at all; the download is whole, given up by libcoap, or ends with one block written, which counts as its pinned deviation only when every block reached libcoap and kcoap served one body (one ETag, every block on a token that asked for it), and as a failure otherwise; never bytes that are not the body's; afterwards the server holds no partial body |
| K soak | `KcoapClientAgainstLibcoapQBlockTest.kSoakBodiesBothWaysAt10PercentLoss` | kcoap's client PUTs 3000 bytes to `coap-server -L 7` and GETs them back: the right body, a clean `CoapException`, or libcoap's plain 4.08 for three gaps; no exchange left |

```bash
scripts/test interop --tests '*QBlock*' -Pkcoap.soak=true                  # the Q-Block cross-tests, soaks 20 times
./gradlew :kcoap-coap:jvmTest -Pkcoap.tests=interop -Pkcoap.soak=true \
    --tests '*LibcoapClientAgainstKcoapQBlockTest.l9*' --tests '*kSoak*'    # the two soaks only
```

At 20 runs L9 takes about a quarter of an hour and the K soak about half an hour, mostly libcoap's timers
(3 runs: about 2½ and 4 minutes). The first 20-run L9 completed 14 of 20 uploads and 10 of 20 downloads;
8 downloads met libcoap's pinned deviation and 2 were given up by libcoap. Only at 20 runs do the soaks
also assert how many complete (L9: half the uploads and half the downloads reach their end; K soak: a
quarter of the uploads, since libcoap gives up those with three gaps, and half the downloads); 3 runs say
too little for that. The
kcoap-to-kcoap soaks run in every unit run on virtual time instead: `QBlockTest` (20 seeds at 10 % and 30 %
loss, 3000 bytes and 20 KiB, both ways), `QBlockClientSoakTest`, and under OSCORE `OscoreQBlockEndToEndTest`
(also protected notification bodies at 10 % loss with 20 to 50 ms of latency).

The soak is not scheduled in CI yet: `ci.yml` cancels a run in progress when another starts on the same ref
(`concurrency`), so a nightly run on `main` would be cut short by a merge, and a `schedule` trigger there would
run every job nightly. It belongs in a workflow of its own (`schedule` plus `workflow_dispatch`, one job that
runs the second command above), to be added by the next owner of the CI files (target-architecture.md §4.1).

## Build checks

Seven checks guard the build itself. `scripts/test unit` runs all seven after the tests, in the same
Gradle invocation, and `./gradlew check` includes all but the build-logic tests in each module that has
them:

| Check | Fails when | Fix |
|---|---|---|
| `checkKotlinAbi` | the public API of a published module differs from its dumps in `<module>/api` (`<module>.api` for the JVM, `<module>.klib.api` for every other target) | if the change is intended, `./gradlew updateKotlinAbi` and commit the dump diff with the change ([releasing.md](releasing.md#abi-dumps)) |
| `verifyCommonOnly` | a core module (`kcoap.core`) has files in a main source set other than `commonMain` | move the code to `commonMain`, or into an adapter module |
| `verifyNoServerEngine` | a ktor server engine (CIO, Netty, Jetty, Tomcat) appears in a Gradle module file or POM that `kcoap-transport-ktor-websocket-server` would publish, or on its JVM runtime classpath; the application chooses the engine (WP-W1) | keep the engine in the test source sets; `KtorWebSocketListener.start` takes it as a parameter |
| `checkAndroidApi` | a module's jvm jar, which Android apps use, calls a Java class, method or field that Android API level 26 (kcoap's minSdk, `android-minSdk` in the version catalog) lacks; animal-sniffer checks the jar against that level's API signature, and the jar's runtime classpath ships with it, so uses of it pass (PL-1) | use an API the level has, or move the code behind a capability check in an adapter module; raising minSdk is the owner's decision ([plan](plan-qblock-and-platforms.md) §4, question 8) |
| `checkAndroidApiDependencies` | the same check over a module's external jvm runtime dependencies (ktor, cryptography-kotlin, Bouncy Castle, ...) finds a use that [`gradle/android-api-dependencies.baseline`](../gradle/android-api-dependencies.baseline) does not list, typically after a library update; the baseline holds the uses triaged as safe in [samples/android-consumer](../samples/android-consumer/README.md#api-level-findings), by jar without version, class file and use (PL-1) | triage the new use there (kept by R8, reachable from kcoap, guarded?), then add the line the failure prints to the baseline; if it is not safe, keep the old library or raise minSdk |
| `verifyBomConstraints` | the POM or a variant of the Gradle module metadata that `kcoap-bom` publishes does not constrain exactly the multiplatform and JVM publications of the build at its version: a published module missing, an unpublished one listed, a wrong group or version, a variant without constraints (WP-C3) | the constraints are generated by `kcoap.bom` from the modules that apply `kcoap.publishing`, so fix the plugin or the module's plugins, never add a constraint by hand |
| `:build-logic:test` | a convention plugin no longer behaves as its Gradle TestKit fixtures expect | fix the plugin in `build-logic` |

The build-logic tests (`build-logic/src/test`) run small fixture builds with Gradle TestKit, in a TestKit
Gradle home under `build-logic/build/testkit`: a core fixture with a `jvmMain` file must fail
`verifyCommonOnly`, an adapter fixture with the same file must pass, `check` must depend on the
verification, a published fixture's Maven publications must carry `kcoap.group` and `kcoap.version`
(and the version given with `-Pkcoap.version`, as the release workflow passes it), and
`kcoap.unpublished` must remove the publication and the ABI tasks (and fail when it is applied after the
tier plugin). A multi-module fixture shaped like kcoap (a UNIVERSAL module, a JVM_LIBRARY one, an unpublished one
and a BOM) must publish a BOM that constrains exactly the two published modules' multiplatform and JVM artifacts,
with kcoap's POM, at the version `-Pkcoap.version` gives, and `verifyBomConstraints` must be part of the BOM's
`check` and fail on a stray and on a missing constraint and on a variant without constraints; and an application
that imports that BOM at 1.0.0 and uses a library built on one module at 2.0.0 must resolve every module at 2.0.0
(`BomPluginTest`). For the Android API level, a multiplatform fixture must get `checkAndroidApi` over its jvm
jar and `checkAndroidApiDependencies` over its libraries, both failing on violations, against the API 26
signature and in `check`; a jvm function that calls `java.util.HexFormat` (API 34) must fail its
`checkAndroidApi`; and a catalog whose API signature is not that of `android-minSdk` must fail the
build. Java fixtures test the task itself: a jar that calls `HexFormat` fails, also inside a `try`, one
that uses only level 26, its own classpath, lambdas and string concatenation passes, and a baseline lets
through exactly the uses it lists. One fixture compiles a Kotlin function, the Android ones a little
Java, and they take seconds, but the Kotlin plugin resolves the Kotlin standard libraries for them, and
the Android ones animal-sniffer and the API signature, from Maven Central into the TestKit Gradle home,
so the first run on a machine, or after `build-logic/build` is deleted, needs the network.

Both Android checks write everything they find to `<module>/build/reports/android-api/` (`jvm.txt`,
`dependencies.txt`), also what the baseline lists.

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:

| Job | Runner | Runs |
|---|---|---|
| Unit tests, build checks and coverage | Linux | `scripts/test unit` (with `checkKotlinAbi`, `verifyCommonOnly`, `verifyNoServerEngine`, `checkAndroidApi`, `checkAndroidApiDependencies` and the build-logic tests), then the coverage floor; the coverage table goes to the job summary |
| Interop | Linux | `scripts/test interop` with freshly built images |
| Native tests | macOS | `scripts/test apple`, `checkKotlinAbi` included, then `scripts/test swift`: Swift builds a `CoapClientConfig` and catches a request timeout and a malformed URI as `NSError` |
| WebSocket client in headless Chrome | Linux | `scripts/test browser google-chrome`: the js tests of `kcoap-transport-ktor-websocket` in the runner's Chrome against a JVM `KtorWebSocketListener` (`BrowserAgainstJvmListenerTest`) |
| Browser consumer | Linux, with the runner's Chrome | `publishToMavenLocal`, then [samples/js-consumer](../samples/js-consumer/README.md), a build of its own through `kcoap-bom`: `build -Pkcoap.browser=google-chrome`, which bundles the page with webpack in production mode, fails when the bundle exceeds its size budget (`checkBundleSize`), and runs the page's code on the JVM, the minified bundle on Node.js and the bundle in headless Chrome against the sample server, reading a resource with OSCORE over CoAP over WebSockets (WP-P1) |
| API reference | Linux | `dokkaGenerate` |
| Android consumer | Linux, with the runner's Android SDK | `publishToMavenLocal`, then [samples/android-consumer](../samples/android-consumer/README.md), a build of its own: `assembleRelease lint test` with AGP 9.4.1 at minSdk 26, R8 on the release variants of both flavors (classical, post-quantum) with no app rules, lint on the default variant, and the unit tests, which run the sample's tour on the JVM (PL-1) |
| JVM consumer | Linux | `publishToMavenLocal`, then [samples/jvm-consumer](../samples/jvm-consumer/README.md), a build of its own through `kcoap-bom` alone: `build run`, where `verifyKcoapFromBom` fails when a kcoap module on the runtime classpath has a version the BOM does not set, and `run` executes getting started's tour (a CoAP GET, an OSCORE GET, EDHOC then OSCORE over UDP on loopback) compiled to Java 17 bytecode (WP-P1) |

Test reports are uploaded as artifacts, and the interop logs when an interop test fails; the Android job
uploads its lint and test reports, APKs and R8 mappings, the browser consumer its reports, bundle and the browser's
log, and the JVM consumer its reports when it fails. A release runs the same workflow first
([releasing](releasing.md)).

PL-1 added the Android job, written where no Android SDK is installed. What was checked here: the sample
configures and resolves kcoap from `~/.m2`, its tour and unit tests compile for the JVM against the
published artifacts and pass, standalone R8 shrinks the tour, in both flavors, and the shrunk code runs,
and R8 also shrinks it to DEX at min API 26 against a stand-in library of the API 26 Java classes without
reporting a missing class (details in the sample's README). What only the job checks: the compilation
against `android.jar`, D8, AGP's R8 and packaging, lint, and the unit tests under AGP. Its merge gate is
that job green on the pull request. Running the app on an emulator is PL-2.

WP-P1 added the two consumer jobs. What was checked here, against a `publishToMavenLocal` of the same commit: both
samples build; the JVM consumer runs its tour, and fails as its README shows when a module is left out of the BOM or a
published module depends on an unpublished one (scratch copies of the local repository); the browser consumer's
bundle builds, its size check passes and fails one byte below either measurement, and its JVM and Node.js tests pass,
with `BrowserSmokeTest` driven by a stand-in browser command that runs the bundle on Node.js. What only the job
checks: the bundle in Chrome. Its merge gate is both jobs green on the pull request.

WP-W1 added the browser job and moved the WebSocket client to the Darwin engine on Apple; both were
written on Linux, where the Apple test binaries only compile and the browser tests ran in Firefox. Its
merge gate is the macOS job and the browser job green on the pull request. If
`NativeWebSocketLoopbackTest` or `DefaultEngineTest` fails on `macosArm64` or `iosSimulatorArm64`
because Darwin does not negotiate the subprotocol, Apple goes back to CIO: `DefaultClient.apple.kt`
names `CIO`, appleMain depends on `ktor-client-cio` instead of `ktor-client-darwin`, and the module
README records that the WebSocket client then neither brings up the iOS cellular radio nor honours VPN
On Demand.

## The same tests on every target

The common tests are one suite for every target: 404 of them run on `linuxX64`, `js` and `wasmJs` alike
(the JVM adds its own, such as the differential and interop tests). Gradle downloads Node.js for the web
tests; the browser test tasks are off, because Node.js has the same WebCrypto API and a browser run
would need a headless browser wherever `check` runs. The one exception is the WebSocket client, whose
js tests CI's browser job runs in Chrome against a JVM listener (`scripts/test browser`, which also
takes `firefox`); its own page and a ktor server in a JVM test drive the browser, so no Karma or
bundler is involved.

A test never asks which target it is on. Where a platform may lack an algorithm, the test asks the
backend (`crypto.supports(algorithm)`, `CipherSuite.isSupportedBy(crypto, method)`) and covers what is
supported, and the backend's own tests check that whatever is reported unsupported fails with
`UnsupportedAlgorithmException` and nothing else. `SuiteNegotiationTest` checks the answers themselves
on every target: for every EDHOC suite and method, `isSupportedBy` is true exactly when a loopback
handshake succeeds. On WebCrypto that leaves out:

| Test | Left out where unsupported | Unsupported on |
|---|---|---|
| `PlatformAeadTest.everyAeadRoundTripsAndRejectsTamperingOrIsUnsupported` | ChaCha20/Poly1305 round trip (checked to be unsupported instead) | WebCrypto |
| `OscorePartsTest.otherAeadAlgorithms` | the ChaCha20/Poly1305 context | WebCrypto |
| `RoundTripTest.everyMethodWithEverySuite` | suites 4 and 5 | WebCrypto |
| `AesBlockTest.ctrConstructionAgreesWithEcbOnRandomBlocks` | the whole test: no ECB to compare with | WebCrypto |

What must work everywhere is asserted, not probed: the mandatory AEADs, the classical key types and
signatures, EDHOC suites 2 and 3 (`PlatformAeadTest`, `CapabilitiesTest`, `CipherSuiteTest`).

## Coverage

The build enforces a line-coverage floor for the whole library (`build-logic/.../kcoap.coverage`),
currently 90%; the codebase is above 97%. New code comes with tests for every public behaviour and
error path, so the floor only ever goes up.

## Writing tests

- Name the test after the expectation, or after the RFC section its vector comes from.
- Use `runTest` for anything that suspends; time-dependent code takes an injected clock or runs on the
  test dispatcher's virtual time.
- A CoAP endpoint reads "now" from `CoapEndpointConfig.timeSource` and waits with the delays of its
  scope's dispatcher, so the two must be one clock. Under `runTest`, run the endpoint in
  `backgroundScope` and pass `CoapEndpointConfig { timeSource = testScheduler.timeSource }` (the test
  fixtures `StackFixture` and `ReliableFixture` do). With the default `TimeSource.Monotonic` there,
  retransmissions, lifetimes and request timeouts disagree, and the endpoint logs one warning: "The
  endpoint's timeSource does not advance with its dispatcher's clock".
- Cryptography can really suspend (WebCrypto answers through promises). While it does, `runTest` finds
  nothing to run and jumps virtual time to the next timer, so a handshake under CoAP timers would see
  retransmissions and timeouts fire. Tests that run real cryptography under timers wrap the backend in
  `InstantCrypto` (`kcoap-edhoc-oscore` tests), and advance time with `delay`, not `advanceTimeBy`.
- Choose algorithms by capability, never by target, and keep regexes valid in JavaScript's Unicode
  mode (escape a literal `]`, `{` or `}`).
- Keep fixed keys and randomness explicit, so a failure reproduces exactly.

## Concurrency stress tests

Virtual time runs everything on one thread, so it never shows a race between threads. Code that is shared
between coroutines (registries, connection pools, the endpoint's lifecycle) also gets a stress test with
`kcoap-testkit`'s `Stress` helper, in commonTest, so it runs on a thread pool on the JVM and
Kotlin/Native and interleaves at suspension points on JS and Wasm:

- `Stress.run(iterations, parallelism, timeout) { ... }` runs the body a bounded number of times on the
  real `Dispatchers.Default`, several iterations at once, and fails the test when they do not finish
  within the real-time `timeout`, so a deadlock fails instead of hanging the build. An iteration that
  throws, or is cancelled from inside (a `withTimeout` in it that expires), fails the run. Each
  iteration gets a `background` scope for the endpoints it creates, cancelled when it ends.
- `Stress.race(a, b, ...)` starts several actions at the same moment and returns each one's `Result`, so
  the test checks that the loser of a race failed the documented way (`CoapException` with `CLOSED`, for
  example) rather than hanging or leaking.
- Endpoints under stress use the real clock: the default `TimeSource.Monotonic`, never
  `testScheduler.timeSource`, because their dispatcher is a real one. Keep the iterations bounded (tens,
  not thousands) and wait for invariants with `withTimeoutOrNull(...) ?: fail(...)`.
- `kcoap-coap`'s `ConcurrencyStressTest` shows the pattern: close racing requests, observe collectors,
  observer churn against the bounds, the connection pool and connection setup. `QBlockStressTest` does the
  same for Q-Block bodies: five bodies at once both ways (Non-confirmable and Confirmable, an upload answered
  in Q-Block2) that arrive whole and leave no exchange or partial body, `close()` racing bodies, and
  notification bodies of a resource changed from three coroutines at once, whole and in order. They run on
  the thread pools of `jvmTest` and `linuxX64Test`, and on the event loop of the web targets.
