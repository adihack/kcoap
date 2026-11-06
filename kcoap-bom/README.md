# kcoap-bom

The bill of materials: one version for every published kcoap module. Import it once and leave the version
off every kcoap dependency:

```kotlin
dependencies {
    implementation(platform("dev.kcoap:kcoap-bom:0.1.0-SNAPSHOT"))
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
        <version>0.1.0-SNAPSHOT</version>
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

## How it is built

The constraints are not written by hand. The `kcoap.bom` convention plugin (build-logic,
`kcoap.bom.gradle.kts`) adds both artifacts of every module that applies `kcoap.publishing`, which the tier
plugins apply to every module not marked `kcoap.unpublished`, so a new published module joins the BOM by itself.
The BOM is published with the modules, with the same coordinates scheme and POM.

## Verified by

- `verifyBomConstraints`, part of `check` and `scripts/test unit`: the POM and the Gradle module metadata the BOM
  publishes constrain exactly the multiplatform and JVM publications of the build, no more, no fewer, at the
  build's version.
- `BomPluginTest` (build-logic, Gradle TestKit): on a build with modules of two tiers, an unpublished module and
  a BOM, the published BOM lists exactly the published modules' artifacts, `-Pkcoap.version` sets their
  version, and a stray or missing constraint fails the check.
