// The Swift smoke test of WP-D2 (docs/target-architecture.md): kcoap from Swift, through the KcoapSmoke framework
// that kcoap-swift-smoke links for macOS. Run by `./gradlew :kcoap-swift-smoke:swiftSmoke` (scripts/test swift) in
// the macOS CI job. It checks that
//
//   1. a CoapClientConfig is built from Swift with explicit values, through CoapClientConfig.Builder (WP-D1), since
//      Swift does not see Kotlin's default arguments;
//   2. a request nobody answers fails with an NSError that Swift catches, whose userInfo holds the Kotlin
//      CoapException (a KcoapException) with reason RESPONSE_TIMEOUT, instead of ending the process;
//   3. CoapUri.parse throws an NSError for a malformed URI, with the Kotlin IllegalArgumentException in its userInfo.
//
// Every check prints one line; the process exits with 1 when any of them failed, and 0 otherwise. A watchdog ends
// it with 1 after 60 s, so a completion handler that never reaches Swift fails the CI step with a message rather than
// hanging the runner.

import Foundation
import KcoapSmoke

/// The key under which Kotlin/Native puts the Kotlin exception in the userInfo of the NSError it throws.
let kotlinExceptionKey = "KotlinException"

/// The target of the request in check 2: nothing is attached there on the client's in-memory network.
let unansweredUri = "coap://127.0.0.1:5683/smoke"

/// The URI of check 3: its IPv6 literal has no closing bracket.
let malformedUri = "coap://[::1/broken"

/// How long the whole script may take; check 2 waits for a 300 ms timeout, everything else is immediate.
let watchdogSeconds = 60.0

@main
struct SmokeMain {
    static func main() async {
        DispatchQueue.global().asyncAfter(deadline: .now() + watchdogSeconds) {
            print("FAIL  the smoke test did not finish within \(Int(watchdogSeconds)) s")
            exit(1)
        }
        var failures = 0
        func check(_ condition: Bool, _ what: String) {
            if condition {
                print("ok    \(what)")
            } else {
                failures += 1
                print("FAIL  \(what)")
            }
        }

        // 1. A configuration with explicit values, set one by one on the builder.
        let builder = CoapClientConfig.Builder()
        builder.preferredBlockSize = KotlinInt(int: 256)
        builder.maxResponseBodySize = 65536
        builder.echoRetry = false
        builder.securityReserve = 0
        builder.observeCancellation = ObserveCancellation.forget
        SmokeFixture.shared.applyRequestTimeout(builder: builder, milliseconds: 300)
        let config = builder.build()
        check(config.preferredBlockSize?.intValue == 256, "the builder keeps preferredBlockSize 256")
        check(config.maxResponseBodySize == 65536, "the builder keeps maxResponseBodySize 65536")
        check(config.echoRetry == false, "the builder keeps echoRetry false")
        check(config.securityReserve == 0, "the builder keeps securityReserve 0")
        check(config.observeCancellation == ObserveCancellation.forget, "the builder keeps observeCancellation FORGET")
        check(
            SmokeFixture.shared.requestTimeoutMilliseconds(config: config) == 300,
            "the builder keeps a request timeout of 300 ms"
        )

        // 2. A request that times out: CoapException as an NSError, not a crash.
        do {
            let target = try CoapUri.Companion.shared.parse(text: unansweredUri)
            let client = try await SmokeFixture.shared.unansweredClient(config: config)
            do {
                let response = try await client.request(request: SmokeFixture.shared.getRequest(uri: target))
                check(false, "a request nobody answers throws, but it returned \(response)")
            } catch {
                let nsError = error as NSError
                let kotlinException = nsError.userInfo[kotlinExceptionKey]
                let name = SmokeFixture.shared.kotlinClassName(value: kotlinException)
                check(nsError.domain == "KotlinException", "the timeout is an NSError of domain KotlinException (\(nsError.domain))")
                check(kotlinException is KcoapException, "the timeout's Kotlin exception is a KcoapException (\(name))")
                if let coapException = kotlinException as? CoapException {
                    check(
                        coapException.reason.name == "RESPONSE_TIMEOUT",
                        "the CoapException's reason is RESPONSE_TIMEOUT (\(coapException.reason.name))"
                    )
                } else {
                    check(false, "the timeout's Kotlin exception is a CoapException (\(name))")
                }
            }
            try await client.close()
        } catch {
            check(false, "setting up the timeout check throws nothing (\(error))")
        }

        // 3. A malformed URI: IllegalArgumentException as an NSError, not a crash.
        do {
            let uri = try CoapUri.Companion.shared.parse(text: malformedUri)
            check(false, "parse throws for \(malformedUri), but it returned \(uri)")
        } catch {
            let nsError = error as NSError
            let kotlinException = nsError.userInfo[kotlinExceptionKey]
            let name = SmokeFixture.shared.kotlinClassName(value: kotlinException)
            check(nsError.domain == "KotlinException", "the malformed URI is an NSError of domain KotlinException (\(nsError.domain))")
            check(
                SmokeFixture.shared.isIllegalArgument(throwable: kotlinException),
                "the malformed URI's Kotlin exception is an IllegalArgumentException (\(name))"
            )
            check(!(kotlinException is KcoapException), "a mistake of the caller is not a KcoapException")
        }

        print(failures == 0 ? "Swift smoke test passed" : "Swift smoke test: \(failures) check(s) failed")
        exit(failures == 0 ? 0 : 1)
    }
}
