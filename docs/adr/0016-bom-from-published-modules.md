# 0016. One version for every module, through a BOM generated from the published modules

Date: 2026-11-06. Status: accepted. WP-C3 of [target-architecture.md](../target-architecture.md) (§2.2, §2.6; finding
MB-9).

## Context

kcoap publishes seventeen modules, all at one version, and they call each other's internal plumbing
(`@InternalKcoapApi`), so a build that mixes versions breaks in ways no compiler reports. Without a bill of materials
a consumer repeats the version for every module, and a module that arrives as a dependency of another can still be
resolved at a different version. A BOM whose constraints are written by hand drifts: a new module is forgotten, or an
unpublished one (kcoap-dtls) is listed and the consumer's resolution fails. On Apple platforms the question is a
different one: a Swift app needs one Kotlin framework that exports the kcoap types it uses, and two separately built
Kotlin frameworks do not share types.

## Decision

- **kcoap-bom**, a Java platform published with the modules, with the same coordinates scheme and POM. Consumers
  write `implementation(platform("dev.kcoap:kcoap-bom:<version>"))` and leave the version off every kcoap module;
  Maven builds import it with `<scope>import</scope>`.
- **Its constraints are generated, never written.** The `kcoap.bom` convention plugin constrains every module that
  `kcoap.publishing` publishes, the multiplatform module and its JVM artifact (Gradle resolves the other targets
  through the module metadata; Maven names the JVM artifact). The tier plugins apply `kcoap.publishing` to every
  module not marked `kcoap.unpublished`, so the published set and the BOM cannot disagree.
- **Aligned in Gradle.** A platform's constraints are only `requires` versions: when another library asks for one
  kcoap module at a higher version, Gradle picks that version for that module alone. So every published module
  also depends on kcoap-bom at its own version, as a platform (Gradle's version alignment, as kotlinx-coroutines
  and Jackson publish it): the higher module brings the higher BOM, conflict resolution keeps it, and its
  constraints lift every other kcoap module to the same version. The POM carries the same dependency as a
  `dependencyManagement` import.
- **Checked, not trusted.** `verifyBomConstraints` (part of `check` and `scripts/test unit`) compares the POM and
  the module metadata the BOM publishes with the build's own publications, every variant of the latter;
  `BomPluginTest` checks the plugin on TestKit builds, including a stray and a missing constraint, a variant
  without constraints, and a module that arrives at a higher version through another library.
- **No export-everything umbrella framework now.** A KMP app's own shared framework `export()`s the kcoap types its
  Swift code needs. A prebuilt XCFramework for Swift-only apps comes from a dedicated facade module with a curated
  surface (roadmap E4, kcoap-apple), after the first publish.

## Consequences

- One line pins kcoap's version in a consumer's build, and the versions of modules that arrive transitively follow
  it. In Gradle that line is a minimum, as any Gradle version is: a library built on a newer kcoap moves every kcoap
  module to that version together, and `enforcedPlatform(...)` holds them at the BOM's version instead. In Maven the
  imported BOM's versions win.
- The BOM has no code, no ABI dump and no API reference page; it is published wherever the modules are.
- A Swift-only app cannot use kcoap until E4; a KMP app can, through its own framework.
