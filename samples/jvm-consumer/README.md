# JVM consumer sample

A JVM application that uses kcoap as any application would: from a Maven repository, with one version for every
kcoap module, set by `kcoap-bom`. Its program is the tour of [getting started](../../docs/getting-started.md): a CoAP
client against a CoAP server in the same process, over UDP on the loopback interface, then an OSCORE exchange with a
pre-shared Master Secret, then an EDHOC handshake that gives the client its OSCORE context. It is a Gradle build of
its own, outside kcoap's, so that it sees only what kcoap publishes; CI builds and runs it in the `jvm-consumer` job
([testing](../../docs/testing.md#continuous-integration)). Work package P1 of
[target-architecture.md](../../docs/target-architecture.md) (§4.3).

## What it checks

| | |
|---|---|
| The BOM | the build names `dev.kcoap:kcoap-bom` with the version and the modules without one, as [getting started](../../docs/getting-started.md#add-kcoap-to-a-build) does; a module it names that the BOM lacks fails resolution |
| Every kcoap module from the BOM | `verifyKcoapFromBom` (part of `check`) walks the runtime classpath and fails when a kcoap module on it, also one that only arrives as a dependency of another, is not constrained by the BOM, or not at the BOM's version |
| Published dependencies | kcoap-edhoc-oscore, kcoap-crypto-default and kcoap-transport-ktor are the only dependencies: everything else the code uses (the CoAP stack, OSCORE, EDHOC, COSE, coroutines, ktor's sockets) must arrive through kcoap's published POMs and module metadata, so a missing, unpublished or wrong dependency fails compilation, resolution or the run |
| Compilation | the sample's Kotlin against kcoap's public API, to Java 17 bytecode, kcoap's minimum, with the Kotlin version kcoap is built with |
| The run | `run` executes the tour and checks each answer (`Hello`, `on`, `on for client`); any failure exits with status 1 and fails the build |

The code (`src/main/kotlin`) is getting started's, one file per section: `FirstClientAndServer.kt`,
`OscoreWithPreSharedKeys.kt` and `EdhocThenOscore.kt`, with the port as a parameter where the document writes 5683,
servers on `127.0.0.1` instead of every interface, and clients that return the answer instead of printing it, so
that `Main.kt` can check it. `GettingStartedExamplesTest` in kcoap-edhoc-oscore runs the same code inside kcoap's
build; this sample runs it from the published artifacts. The Master Secret is new in each run, so an ephemeral
sequence-number store is right; a Master Secret that outlives the process needs a durable one
([kcoap-oscore](../../kcoap-oscore/README.md#writing-a-durable-store)).

## Building it

```bash
./gradlew publishToMavenLocal                            # in the repository root: kcoap into ~/.m2
./gradlew -p samples/jvm-consumer build run              # verifyKcoapFromBom, then the tour
```

The sample takes the kcoap version from `-Pkcoap.version` or, without it, from the repository's
`gradle.properties`, which is what a plain `publishToMavenLocal` publishes; pass the same `-Pkcoap.version` to both
commands to use another. It takes `dev.kcoap` from the local Maven repository only (`exclusiveContent`), never from
Maven Central, so a kcoap module the commit did not publish fails resolution. Gradle reads the local repository
from `-Dmaven.repo.local=<directory>` when it is given, so both commands with it keep a trial away from `~/.m2`.

## Shown to fail
The settings read that property, which makes it an input of Gradle's configuration cache: without that, a run
against another directory reused the dependency graph of the last run and passed on artifacts it never read.

WP-P1 broke the published artifacts in scratch copies of the local repository and ran `build run` against each:

| Broken | Fails at | With |
|---|---|---|
| `kcoap-cbor` (a dependency of kcoap-cose, never named here) left out of the BOM's POM and module metadata | `verifyKcoapFromBom` | `kcoap-cbor:0.1.0-SNAPSHOT is not constrained by kcoap-bom:0.1.0-SNAPSHOT`, and the same for `kcoap-cbor-jvm` |
| `kcoap-edhoc-oscore` (named here) left out of the BOM | `compileKotlin` | `Could not find dev.kcoap:kcoap-edhoc-oscore:.` |
| kcoap-coap-jvm's module metadata depending on kcoap-dtls, which is not published | `compileKotlin` | `Could not find dev.kcoap:kcoap-dtls:0.1.0-SNAPSHOT`, required through kcoap-bom and kcoap-coap-jvm |

Dropping kotlinx-coroutines from the api variants of kcoap's jvm artifacts did not fail it: ktor's own published
dependencies still bring coroutines to the compile classpath, so a consumer is not affected either.
