# kcoap-bom

The bill of materials: one version for every published kcoap module. Import it once and leave the version
off every kcoap dependency:

```kotlin
dependencies {
    implementation(platform("dev.kcoap:kcoap-bom:0.1.0"))
    implementation("dev.kcoap:kcoap-coap")
    implementation("dev.kcoap:kcoap-crypto-default")
}
```

kcoap's modules share internal plumbing (`@InternalKcoapApi`), so a build must use one version for all of them
([versioning](../docs/versioning.md#version-numbers)); the BOM makes that the default, also for the modules that
arrive as dependencies of others. Which modules a use case needs is in
[getting started](../docs/getting-started.md).

## What it constrains

For every published module, two artifacts at the BOM's version:

- the Kotlin Multiplatform module, `dev.kcoap:<module>`, which a Gradle build resolves to the artifact of each of
  its targets (JVM, Android, Apple, Linux, js, wasmJs) through its module metadata;
- its JVM artifact, `dev.kcoap:<module>-jvm`, which Maven builds name directly, since Maven reads no Gradle
  module metadata:

  ```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>dev.kcoap</groupId>
        <artifactId>kcoap-bom</artifactId>
        <version>0.1.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.kcoap</groupId>
      <artifactId>kcoap-coap-jvm</artifactId>
    </dependency>
  </dependencies>
  ```

Unpublished modules (kcoap-dtls until its engine exists, kcoap-swift-smoke) are not in it.

## Version alignment

A platform's constraints are `requires` versions, so on their own they would not hold when another library asks
for one kcoap module at a higher version: Gradle would pick that version for that module alone and leave the
others at the BOM's, a mix that fails at runtime. So every published module also depends on kcoap-bom at its own
version, as a platform, in its Gradle module metadata (and in its POM, as a `dependencyManagement` import). A
library built on kcoap 0.2.0 brings kcoap-bom 0.2.0 along; conflict resolution keeps the newer BOM, and its
constraints lift every kcoap module of the build to 0.2.0 together. This is Gradle's
[version alignment](https://docs.gradle.org/current/userguide/how_to_align_dependency_versions.html), as
kotlinx-coroutines and Jackson publish it.

The version in a build's `platform(...)` line is therefore a minimum, as any Gradle version is.
`enforcedPlatform("dev.kcoap:kcoap-bom:<version>")` holds every kcoap module at exactly that version instead, even
under a library that asks for a newer one. A Maven build's imported BOM sets the version of every kcoap artifact
in the build, those that arrive as dependencies included.

## How it is built

The constraints are not written by hand. The `kcoap.bom` convention plugin (build-logic,
`kcoap.bom.gradle.kts`) adds both artifacts of every module that applies `kcoap.publishing`, which the tier
plugins apply to every module not marked `kcoap.unpublished`, so a new published module joins the BOM by itself.
The same plugin makes each of those modules depend on the BOM as a platform from `commonMain`, for the alignment
above. The BOM is published with the modules, with the same coordinates scheme and POM.

## Verified by

- `verifyBomConstraints`, part of `check` and `scripts/test unit`: the POM and every variant of the Gradle module
  metadata the BOM publishes constrain exactly the multiplatform and JVM publications of the build, no more, no
  fewer, at the build's version.
- `BomPluginTest` (build-logic, Gradle TestKit): on a build with modules of two tiers, an unpublished module and
  a BOM, the published BOM lists exactly the published modules' artifacts, `-Pkcoap.version` sets their
  version, and a stray or missing constraint, or a variant without constraints, fails the check. On modules
  published at 1.0.0 and 2.0.0, an application that imports the BOM at 1.0.0 and uses a library built on one
  module at 2.0.0 resolves every module at 2.0.0, and the modules' POMs import the BOM.
