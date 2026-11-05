# Android consumer sample

An Android app that uses kcoap as any application would: from a Maven repository, built with the
Android Gradle plugin (AGP) at kcoap's minimum Android version, and shrunk with R8. It is a Gradle build
of its own, outside kcoap's, because it needs the Android SDK; CI builds it in the `android` job
([testing](../../docs/testing.md#continuous-integration)). Work package PL-1 of
[plan-qblock-and-platforms.md](../../docs/plan-qblock-and-platforms.md).

## What it checks

| | |
|---|---|
| minSdk | 26 (Android 8.0), from `android-minSdk` in kcoap's [version catalog](../../gradle/libs.versions.toml); compileSdk and targetSdk 36; AGP 9.4.1 |
| Dependencies | kcoap's jvm artifacts resolve for Android from the published Gradle module metadata, with ktor, cryptography-kotlin and Bouncy Castle behind them |
| Compilation | the app's Kotlin against kcoap's public API, with the Kotlin version kcoap is built with |
| Dexing | D8 at minSdk 26 |
| Shrinking | R8 on both release variants, with no rule of the app's own (`app/proguard-rules.pro` is empty): whatever kcoap needs ships in its jars |
| Lint | AGP's lint on the default variant |
| Unit tests | `OscoreAssociationTest` on the JVM in both flavors (`test`, the debug build type in AGP 9): one OSCORE derivation per process, and tours running at once sharing it |

The app's code (`app/src/main/kotlin`) is a tour of the public API in plain Kotlin: the crypto capability
report (`CryptoBackend.supports`, `CipherSuite.isSupportedBy`), OSCORE contexts with a durable
`SequenceNumberStore` under `noBackupFilesDir`, an EDHOC Initiator configuration narrowed with
`supportedBy`, and a CoAP GET and an OSCORE-protected GET over `KtorUdpTransport` to a `CoapServer` in
the same app on the loopback interface. `MainActivity` runs it and shows the result, which also goes to
logcat under the tag `kcoap`. The crypto backend and the OSCORE contexts belong to the process
(`SampleApplication`), not to the Activity.

Two flavors cover the two ways an app sets up kcoap's cryptography:

| Flavor | Backend | Brings |
|---|---|---|
| `classical` | `DefaultCryptoBackend` | the platform's JCA providers through cryptography-kotlin |
| `postQuantum` | `BouncyCastleCryptoBackend` over it | Bouncy Castle (`kcoap-crypto-bc`) for ML-KEM and ML-DSA |

API levels below 26 are checked without the Android SDK, in kcoap's own build: `checkAndroidApi` runs
animal-sniffer over every jvm jar against the API 26 signature ([build checks](../../docs/testing.md#build-checks)).
Running the app on emulators across API levels is PL-2.

## Building it

```bash
./gradlew publishToMavenLocal                                     # in the repository root: kcoap into ~/.m2
./gradlew -p samples/android-consumer assembleRelease lint test   # needs the Android SDK (ANDROID_HOME)
```

The sample takes the kcoap version from `-Pkcoap.version` or, without it, from the repository's
`gradle.properties`, which is what a plain `publishToMavenLocal` publishes; pass the same
`-Pkcoap.version` to both commands to use another. It takes only `dev.kcoap` from the local Maven
repository. The APKs land in `app/build/outputs/apk/<flavor>/release/`, signed with the debug key so they
install on an emulator, and R8's mapping in `app/build/outputs/mapping/`.

## What an app needs

- **Kotlin 2.3 or newer.** kcoap is built with Kotlin 2.4, and a Kotlin compiler reads metadata at most
  one minor version ahead of its own. AGP 9 compiles Kotlin with the Kotlin Gradle plugin on the build's
  classpath; the sample puts kcoap's version there (`build.gradle.kts`).
- **Java 17 bytecode** (`compileOptions`): kcoap's jvm classes are Java 17, and Kotlin inlines kcoap's
  inline functions only into bytecode of the same target or newer. D8 handles Java 17 bytecode at any
  minSdk.
- **The `INTERNET` permission**, for UDP sockets even on the loopback interface.
- **No R8 rules of its own.** `kcoap-crypto-default` ships the rules R8 needs
  ([its README](../../kcoap-crypto-default/README.md#android-and-r8)).
- **No core library desugaring** at minSdk 26: none of the findings below is an API it provides, and
  none needs one.
- **A durable `SequenceNumberStore`** for pre-shared OSCORE keys, outside Auto Backup:
  `FileSequenceNumberStore` here is the JVM and Android recipe of
  [kcoap-oscore's README](../../kcoap-oscore/README.md), which uses only API 26 calls.
- **One set of OSCORE contexts per process.** Derive a context once per context name and share it, in
  the `Application` or a singleton, never per Activity: Android can run two Activities of the app at once,
  and two live contexts under one name hand out the same sequence numbers, which reuses nonces
  (RFC 8613 Appendix B.1.1). Here `SampleApplication` holds an `OscoreAssociation`, which derives the
  client and server contexts on first use.

## API level findings

`./gradlew checkAndroidApi` checks kcoap's 16 jvm jars against the API 26 signature
(`net.sf.androidscents.signature:android-api-level-26:8.0.0_r2`), and
`./gradlew checkAndroidApiDependencies` the external libraries behind them. The libraries' uses below
are listed in [`gradle/android-api-dependencies.baseline`](../../gradle/android-api-dependencies.baseline),
and `checkAndroidApiDependencies`, part of `check` and `scripts/test unit`, fails on any use it does not
list: a library update that brings a new one fails the build until the use is triaged here and added
there. Whether R8 keeps a flagged class comes from shrinking this sample's code, without `MainActivity`,
with standalone R8 9.4.34 and JDK 21 as the library instead of the Android SDK; the shrunk code then ran
its tour on the JVM, in both flavors.

| Where | Uses | Android has it | Kept by R8 | Outcome |
|---|---|---|---|---|
| kcoap's own jvm jars | nothing outside API 26 | — | — | nothing to fix |
| ktor-utils 3.6.1, `IntellijIdeaDebugDetector` | `java.lang.management` | never | no | unreachable from kcoap |
| kotlinx-serialization-core 1.10–1.11, `ClassValueReferences` (through ktor and cryptography-kotlin) | `java.lang.ClassValue` | never | no | guarded by the library, which also ships a `-dontwarn` for it |
| cryptography-provider-jdk 0.6.0, `JdkDsa$DsaParametersGenerator` | `java.security.spec.DSAGenParameterSpec` | never | no | DSA is unused |
| cryptography-provider-jdk 0.6.0, `BouncyCastleBridge` | Bouncy Castle; `java.security.interfaces.EdECPrivateKey` and `XECPrivateKey` | Bouncy Castle only with `kcoap-crypto-bc`; the interfaces from API 33 | yes | touches Bouncy Castle only after `Class.forName` finds it at runtime; R8 failed the classical flavor on the missing classes, so `kcoap-crypto-default` now ships `-dontwarn` rules for them. The interfaces are tested only on Ed25519, X25519, Ed448 and X448 keys, and only when Bouncy Castle is present: PL-2 checks on emulators that those capability probes answer at API 26–32 |
| bcprov-jdk18on 1.86, LDAP certificate store (`X509LDAPCertStoreSpi`, `CrlCache`, `LDAPStoreHelper`) | `javax.naming` | never | no | `kcoap-crypto-bc` uses only the lightweight ML-KEM and ML-DSA classes |
| typesafe config 1.4.9 and kotlin-reflect 2.3, through ktor-server | `java.beans`, `java.lang.ClassValue` | never | — | only `kcoap-transport-ktor-websocket-server`, which an Android app does not use |

**minSdk 26 holds.** The violations are all in libraries, on paths kcoap does not reach or guarded at
runtime, and core library desugaring would not supply any of them: it covers `java.time`,
`java.util.stream`, `java.util.function` and similar, not `java.lang.management`, `javax.naming`,
`java.beans` or `ClassValue`. What holds it from here on: `checkAndroidApi` for kcoap's own code, the
baseline for the libraries, and R8 in CI for what a release build keeps.

## Verified where

- **Here, without the Android SDK:** the build configures with AGP 9.4.1 and the Kotlin Gradle plugin
  2.4.21; both release classpaths resolve kcoap from the local Maven repository; each variant compiles the
  intended Kotlin sources; the tour, compiled for the JVM against the published artifacts, runs in both
  flavors (two GETs, one under OSCORE, and a second process restoring sequence numbers from the files),
  and so do the unit tests; standalone R8 9.4.34 shrinks it to class files with JDK 21 as the library,
  failing without kcoap-crypto-default's rules and passing with them, and the shrunk jar runs; no Java
  resource is duplicated across the jars except `META-INF/MANIFEST.MF`, which AGP drops.
- **Here, with a stand-in for `android.jar`:** the same R8 shrinks it to DEX at min API 26 against a
  library of only the Java classes of API level 26 (from the signature `checkAndroidApi` uses) plus the
  EdEC and XEC key classes of API 33, which `android.jar` 36 has. In both flavors it reports no class
  missing from the code it keeps: `java.lang.management`, `javax.naming`, `DSAGenParameterSpec` and
  `ClassValue` are not reached. Without the API 33 classes it reports `EdECPrivateKey` and
  `XECPrivateKey`, from `BouncyCastleBridge`; with compileSdk 36 they resolve. To DEX it also passes
  without kcoap-crypto-default's rules, unlike the class-file run; the rules stay, since they cost
  nothing, until the CI job shows whether AGP's R8 needs them. The stand-in's own JDK classes refer to
  JDK internals it lacks, which were told to R8 as missing after checking that no app or library jar
  refers to them.
- **Only in CI:** the Kotlin compilation against `android.jar`, D8, AGP's R8 against the real
  `android.jar` 36, AGP's packaging, lint, and the unit tests under AGP.
- **Only on an emulator or device (PL-2):** that the app runs at API 26 and later, and the capability
  report per API level.
