# kcoap-swift-smoke

The Swift smoke test: kcoap called from Swift, as an iPhone or Mac app calls it. Not published, and built for
the Apple targets only (macosArm64, iosArm64, iosSimulatorArm64), each with a framework named `KcoapSmoke`
that exports kcoap-coap, kcoap-coap-message and kcoap-common.

[`src/swift/SwiftSmoke.swift`](src/swift/SwiftSmoke.swift) checks three things from Swift and exits with 1
when any of them fails:

1. A `CoapClientConfig` built with explicit values through `CoapClientConfig.Builder`, since Swift does not see
   Kotlin's default arguments.
2. A request nobody answers: `try await client.request(request:)` throws an `NSError` of domain
   `KotlinException` whose `userInfo["KotlinException"]` is a `CoapException`, a `KcoapException`, with reason
   `RESPONSE_TIMEOUT`, instead of ending the process.
3. A malformed URI: `try CoapUri.Companion.shared.parse(text:)` throws an `NSError` whose Kotlin exception is an
   `IllegalArgumentException`.

A watchdog ends the script with 1 and a `FAIL` line after 60 seconds, and the CI step has a 15-minute limit, so a
call whose result never reaches Swift fails the job quickly instead of holding the runner.

Both work because the functions list the exceptions in `@Throws`: `KcoapException` and `CancellationException`
on the public suspend entry points, `IllegalArgumentException` on `CoapUri.parse`
([architecture](../docs/architecture.md), "Errors"). Kotlin/Native turns an exception of a listed type into an
`NSError` and ends the process on any other.

[`SmokeFixture`](src/commonMain/kotlin/dev/kcoap/swiftsmoke/SmokeFixture.kt) holds what Swift cannot write: a
client on kcoap-testkit's in-memory network where nothing answers, its `CoroutineScope`, a request timeout (a
`kotlin.time.Duration`) and a GET request (a `CoapCode`), both value classes that reach Swift without a type of
their own. `SmokeFixtureTest` checks the same three things in Kotlin on the Apple targets. Every other `@Throws`
list is checked on the JVM by kcoap-edhoc-oscore's `ThrowsDeclarationsTest`, since the common annotation writes the
same list into the class files.

## Running it

On a Mac (the CI job `apple` runs it after the Apple tests, as `scripts/test swift`):

```
./gradlew :kcoap-swift-smoke:swiftSmoke
```

This links the debug framework for macosArm64, compiles the script with `xcrun swiftc` against it (the
framework search path and the rpath are the link task's output directory), and runs it. On Linux the Apple
klibs cross-compile, but the framework does not link, so `swiftSmoke` is skipped.

This is a test, not the Apple distribution: an XCFramework, a Swift package and a curated Swift API are planned
separately (E4 in [target-architecture.md](../docs/target-architecture.md) §4.4).
