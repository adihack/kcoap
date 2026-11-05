# Testing

kcoap is tested at three levels, from the most precise to the most realistic.

## 1. RFC test vectors, byte for byte

Every protocol module checks the examples its RFCs publish, value by value. A test fails on the
first byte that differs, which pins down the exact step that is wrong.

| Module | Vectors |
|---|---|
| `kcoap-crypto` | FIPS 202 (SHAKE256), NIST SP 800-185 (KMAC256) |
| `kcoap-crypto-default` | FIPS 197 (the AES block, through ECB and CTR), RFC 3610 (AES-CCM, all 24), RFC 5869 (HKDF), RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 8613 Appendix C (AEAD and key derivation), SEC 2 base points (point decompression) |
| `kcoap-crypto-bc` | NIST ACVP ML-KEM and ML-DSA key generation, encapsulation, signing |
| `kcoap-cbor` | RFC 8949 Appendix A |
| `kcoap-cose` | RFC 8613 C.4 (AAD), RFC 9529 credentials |
| `kcoap-coap` | RFC 8613 Appendix C messages (codec) |
| `kcoap-oscore` | RFC 8613 Appendix C.1–C.8, every intermediate value, both sides |
| `kcoap-edhoc` | RFC 9529 traces |

Vectors are extracted from the RFC text by scripts in `tools/`, not typed in by hand.

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

## Running the suites

`scripts/test` runs each suite with one command and prints a table of tests per target at the end:

| Suite | Runs | Needs |
|---|---|---|
| `scripts/test unit` | levels 1–3 on every target the host builds (`allTests`): JVM, `linuxX64`, `js` and `wasmJs` on Node.js, and the Apple targets on macOS; then the [build checks](#build-checks) | a JDK |
| `scripts/test interop` | level 4 | Docker |
| `scripts/test coverage` | every JVM test, the Kover HTML and XML reports, and the coverage floor | Docker for the interop share |
| `scripts/test apple` | the common tests on `macosArm64` and `iosSimulatorArm64`, and `checkKotlinAbi` with natively built Apple klibs | macOS with Xcode |
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
```

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
(3 runs: about 2½ and 4 minutes). The first nightly L9 completed 14 of 20 uploads and 10 of 20 downloads;
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

Three checks guard the build itself. `scripts/test unit` runs all three after the tests, in the same
Gradle invocation, and `./gradlew check` includes the first two for each module:

| Check | Fails when | Fix |
|---|---|---|
| `checkKotlinAbi` | the public API of a published module differs from its dumps in `<module>/api` (`<module>.api` for the JVM, `<module>.klib.api` for every other target) | if the change is intended, `./gradlew updateKotlinAbi` and commit the dump diff with the change ([releasing.md](releasing.md#abi-dumps)) |
| `verifyCommonOnly` | a core module (`kcoap.core`) has files in a main source set other than `commonMain` | move the code to `commonMain`, or into an adapter module |
| `:build-logic:test` | a convention plugin no longer behaves as its Gradle TestKit fixtures expect | fix the plugin in `build-logic` |

The build-logic tests (`build-logic/src/test`) run small fixture builds with Gradle TestKit, in a TestKit
Gradle home under `build-logic/build/testkit`: a core fixture with a `jvmMain` file must fail
`verifyCommonOnly`, an adapter fixture with the same file must pass, `check` must depend on the
verification, a published fixture's Maven publications must carry `kcoap.group` and `kcoap.version`
(and the version given with `-Pkcoap.version`, as the release workflow passes it), and
`kcoap.unpublished` must remove the publication and the ABI tasks (and fail when it is applied after the
tier plugin). The fixtures compile no Kotlin and take seconds, but the Kotlin plugin resolves the Kotlin
standard libraries for them from Maven Central into the TestKit Gradle home, so the first run on a
machine, or after `build-logic/build` is deleted, needs the network.

## Continuous integration

`.github/workflows/ci.yml` runs on every push to `main` and every pull request:

| Job | Runner | Runs |
|---|---|---|
| Unit tests, build checks and coverage | Linux | `scripts/test unit` (with `checkKotlinAbi`, `verifyCommonOnly` and the build-logic tests), then the coverage floor; the coverage table goes to the job summary |
| Interop | Linux | `scripts/test interop` with freshly built images |
| Native tests | macOS | `scripts/test apple`, `checkKotlinAbi` included |
| API reference | Linux | `dokkaGenerate` |

Test reports are uploaded as artifacts, and the interop logs when an interop test fails. A release
runs the same workflow first ([releasing](releasing.md)).

## The same tests on every target

The common tests are one suite for every target: 404 of them run on `linuxX64`, `js` and `wasmJs` alike
(the JVM adds its own, such as the differential and interop tests). Gradle downloads Node.js for the web
tests; the browser test tasks are off, because Node.js has the same WebCrypto API and a browser run
would need a headless browser wherever `check` runs.

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
  `backgroundScope` and pass `CoapEndpointConfig(timeSource = testScheduler.timeSource)` (the test
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
