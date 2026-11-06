# Browser consumer sample

A browser application that uses kcoap as any application would: Kotlin/JS (IR) compiled to a production webpack
bundle, with kcoap from a Maven repository and every kcoap version set by `kcoap-bom`. The page asks the server it
came from for a new OSCORE session and reads a `lights` resource with an OSCORE-protected GET over CoAP over
WebSockets (`coap+ws`, or `coaps+ws` from an HTTPS page), the browser row of
[getting started](../../docs/getting-started.md#by-platform). The build measures the bundle and fails when it
exceeds its budget. It is a Gradle build of its own, outside kcoap's, so that it sees only what kcoap publishes; CI
builds it and runs the bundle in headless Chrome in the `js-consumer` job
([testing](../../docs/testing.md#continuous-integration)). Work package P1 of
[target-architecture.md](../../docs/target-architecture.md) (§3.10, §4.3).

## What is in it

| Source set | What |
|---|---|
| `commonMain` | `readLights`: an OSCORE client context derived from an `OscoreSession`, and a `CoapClient` on `KtorWebSocketConnector()` with OSCORE. The page runs it; the JVM tests run the same code |
| `jsMain` | the page: `Main.kt` asks for a session, calls `readLights` on the page's own host and port, shows the answer and reports it to `/report`; `index.html` loads the bundle |
| `jvmMain` | `SampleServer`, one ktor application on CIO that serves the page and its bundle, `POST /oscore-session` and CoAP over WebSockets at `/.well-known/coap` with OSCORE; `runServer` starts it on port 8080 for a browser |
| `jvmTest` | the tests below |

Its kcoap modules are the minimal set for a browser client with OSCORE: kcoap-coap, kcoap-oscore,
kcoap-crypto-default (WebCrypto in browsers) and kcoap-transport-ktor-websocket. A browser app with EDHOC adds
kcoap-edhoc and kcoap-edhoc-oscore, as getting started's browser row lists, and its bundle grows with them.

**Keys new for each page.** A browser cannot keep the sequence numbers of a pre-shared Master Secret durable, so
reusing one across page loads would reuse nonces (RFC 8613 Appendix B.1.1;
[kcoap-oscore](../../kcoap-oscore/README.md#writing-a-durable-store)). The server makes a Master Secret and a Sender
ID for every page load instead and hands them over the page's own connection, as an ACE authorization server
provisions OSCORE input material (RFC 9203), so the page's ephemeral sequence-number store is right. EDHOC is the
other way to keys that are new per session. The server keeps the contexts of the newest 1,000 page loads
(`RecentSessions`), so asking for sessions over and over costs it bounded memory; an older page gets 4.01 and a
reload gets it a new session.

**Who gets a session.** The sample hands a session to anyone who asks, and every session may read `lights`: OSCORE
protects the exchange, it does not decide who may make it. In production `/oscore-session` sits behind the
application's own user authentication, as the ACE authorization server authenticates and authorizes a client before
it provisions keys, and the context it creates grants exactly what that user may do.

## What it checks

| | |
|---|---|
| The BOM | the build names `dev.kcoap:kcoap-bom` with the version and the modules without one; a module it names that the BOM lacks fails resolution, for the js and the jvm target |
| The production bundle | `jsBrowserDistribution` compiles the page with the Kotlin/JS IR compiler and bundles it with webpack in production mode (minified by terser) into `build/dist/js/productionExecutable` |
| Its size | `checkBundleSize` (part of `check`) fails when the bundle exceeds [bundle-budget.properties](bundle-budget.properties), minified or gzip-compressed ([below](#bundle-size)) |
| The page's code on the JVM | `ReadLightsTest`: `readLights` against the sample server reads `on`; each page load gets a Master Secret and a Sender ID of its own; the server keeps the newest sessions only, and the oldest gets 4.01; a request without OSCORE gets 4.01; a session the server does not know gets an unverified 4.01 (RFC 8613 §8.2), which the client refuses with `CoapException(UNVERIFIED_RESPONSE)` |
| The minified bundle on Node.js | `NodePageTest` runs `js-consumer.js` unchanged on the Node.js the Kotlin plugin set up, with a stand-in for the page (`node-page.js`): fetch, WebSocket and WebCrypto are Node's, which have the browsers' APIs, and the page reports `on` |
| The bundle in a browser | `BrowserSmokeTest`, with `-Pkcoap.browser=<command>`: a headless browser loads the page from the sample server, and the page reports `on`, read with OSCORE over the browser's own WebSocket on WebCrypto. **CI only**: the `js-consumer` job runs it in the runner's Chrome; without the property it skips, and nothing here downloads a browser |

`NodePageTest` found what a JVM test could not: kotlinx-browser's `RequestInit(...)` factory sets every member it is
not given to null, and fetch refuses null for `cache`, `mode` and `redirect` with a TypeError, in Node.js as in
Chrome. The page builds its request options as a plain object instead.

There is no `wasmJs` variant. A Kotlin/Wasm production bundle needs Binaryen, which the Kotlin Gradle plugin
downloads for it and which this machine's Gradle cache does not hold, and a webpack install of its own; WP-P1 added
nothing that needs a new download. The budget is for the js bundle; a wasm variant would get one of its own.

## Building it

```bash
./gradlew publishToMavenLocal                                     # in the repository root: kcoap into ~/.m2
./gradlew -p samples/js-consumer build                            # bundle, size check, the JVM and Node.js tests
./gradlew -p samples/js-consumer build -Pkcoap.browser=google-chrome   # also in headless Chrome (chromium, firefox)
./gradlew -p samples/js-consumer runServer                        # then open http://127.0.0.1:8080/ in a browser
```

The sample takes the kcoap version from `-Pkcoap.version` or, without it, from the repository's `gradle.properties`,
which is what a plain `publishToMavenLocal` publishes; it takes `dev.kcoap` from the local Maven repository only
(`exclusiveContent`), never from Maven Central, so a kcoap module the commit did not publish fails resolution.
Node.js and Yarn come from the same repositories as kcoap's own build, which the Kotlin Gradle plugin downloads them
from, and the npm packages webpack needs are pinned by [kotlin-js-store/yarn.lock](kotlin-js-store/yarn.lock).

## Bundle size

The budget covers every `.js` file webpack writes, as served (minified) and gzip-compressed at level 9 by
`java.util.zip`, within a few bytes of `gzip -9`. Source maps and license comments are not served, so they do not
count. Measured by WP-P1 on 2026-11-06, with Kotlin 2.4.21, ktor 3.6.1 and webpack 5:

| | Measured | Budget (measured + 5 %, rounded up to a KiB) |
|---|---:|---:|
| Minified | 1 149 569 bytes (1.10 MiB) | 1 207 296 bytes (1 179 KiB) |
| gzip -9 | 316 415 bytes (309 KiB) | 332 800 bytes (325 KiB) |

The file is `js-consumer.js`, plus a 272-byte chunk with the `ws` package's browser stub, which only Node.js would
load. Where the bytes go, read from the source map (approximate: the compiler maps only part of what it writes):
kcoap's modules about 17 % (kcoap-coap 12 %, of which the Q-Block client and receivers a few percent although
Q-Block is off by default), ktor's client and HTTP code 12 %, kotlinx-coroutines 6.5 %, kotlinx-serialization 3 %
(which cryptography-kotlin's ASN.1 and key-format code uses), cryptography-kotlin 3 %, the Kotlin standard library
and kotlinx-io 3 %, and more than half compiler-generated code (class metadata, intrinsics) and code without a
mapping, spread over all of them.

To re-measure: `./gradlew publishToMavenLocal`, then `./gradlew -p samples/js-consumer checkBundleSize`, which prints
both sizes and writes them to `build/reports/bundle-size.txt`. A change that is meant to grow the bundle raises both
numbers in [bundle-budget.properties](bundle-budget.properties) and the measurement here and in
[platforms](../../docs/platforms.md#bundle-size) in the same commit; one that is not meant to is the regression the
check exists for.
