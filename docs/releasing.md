# Releasing

A release is a tag. Pushing `v0.1.0` (or `v0.2.0-rc1`) runs `.github/workflows/release.yml`:

1. **Test**: the whole CI workflow (unit tests on every target, coverage floor, interop with libcoap and
   libedhoc, the Apple targets, the API reference). Nothing is published if any of it fails.
2. **Publish** on a macOS runner, because only macOS can build the iOS and macOS artifacts; the same
   host builds the JVM, Linux, JS and Wasm ones. The version is the tag without its `v`.
   - to **GitHub Packages** always, with the workflow's own token;
   - to **Maven Central** when its secrets are configured.
3. **GitHub Release** with notes generated from the merged pull requests. A version with a `-`
   (`0.2.0-rc1`) is marked as a pre-release.
4. **API reference** (Dokka) deployed to GitHub Pages.

A release can also be started by hand (Actions → Release → Run workflow) with a version number; that
creates the tag through the GitHub Release step.

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Release freeze

No `v*` tag until the API-breaking work packages of the refactoring plan have merged
([target-architecture.md](target-architecture.md), section 4): A1–A5, W1, B1–B3, C1, D1, D2 and C3. T1,
I1, P1, C2 and B4 do not block a release. Protecting `v*` tags (Settings → Rules → Rulesets) keeps an
accidental tag from publishing early.

Before tagging 0.1.0:

- the freeze list above has merged and CI is green on `main`;
- nothing deprecated before the release is left in the API, so 0.1.0 ships no API that is already on its way
  out ([versioning](versioning.md#deprecation-from-10)): the constructors of `CoapEndpointConfig` and
  `CoapClientConfig`, deprecated by WP-D1 for their builders, are gone;
- [CHANGELOG.md](../CHANGELOG.md) has an entry for the version, with migration notes;
- the `dev.kcoap` namespace shows as verified in the Central Portal, and the Maven Central and signing
  secrets are set ([below](#before-the-first-maven-central-release)); the license is already set;
- `./gradlew publishToMavenLocal` works, a sample consumer builds against it, and the POMs
  (`<module>/build/publications/<target>/pom-default.xml`) name the license, the developer and the SCM;
- the ABI dumps are current (`checkKotlinAbi` is green on both CI runners) and their diff since the last
  release matches the CHANGELOG entry ([versioning](versioning.md)).

## What is published

Every module whose build applies a tier plugin (`kcoap.kmp-library`, `kcoap.kmp-jvm-native-library`,
`kcoap.kmp-jvm-library`), except the modules that apply `kcoap.unpublished` first. Today that is
kcoap-dtls, whose SPIs have no engine yet; it joins the published set, with its ABI dumps, when the
engine lands (roadmap E1). An unpublished module is still built and tested, but has no Maven
publication, no API reference page and no ABI dumps.

## ABI dumps

Every published module commits dumps of its public API in `<module>/api`: `<module>.api` for the JVM
class files and `<module>.klib.api` for the klibs of every other target (Apple, Linux, js, wasmJs). They
come from the Kotlin Gradle plugin's ABI validation, configured once in `build-logic` (`KcoapBuild.kt`):

```bash
./gradlew checkKotlinAbi    # fails when the public API differs from the dumps (part of check and scripts/test unit)
./gradlew updateKotlinAbi   # rewrites the dumps; commit the diff together with the API change
```

Declarations marked `@InternalKcoapApi` are left out. Every API change therefore shows up as a dump diff
in review, and a work package lists the changes its diff must match.

**Apple targets.** Linux builds the Apple klibs too (Kotlin's klib cross-compilation), so the dumps
written on Linux include `iosArm64`, `iosSimulatorArm64` and `macosArm64`, and the macOS CI job checks
them again with the native toolchain. Should a host ever be unable to build an Apple target (for example
after an Apple source set gains cinterop), `updateKotlinAbi` keeps that target's entries from the
committed dump instead of dropping them (`keepLocallyUnsupportedTargets`), and they must be refreshed on
macOS:

```bash
./gradlew updateKotlinAbi   # on a Mac with Xcode; commit the changed */api/*.klib.api files
```

## Secrets

Set under Settings → Secrets and variables → Actions. All are optional: without them the release still
goes to GitHub Packages, unsigned.

| Secret | Used for |
|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | the user token from central.sonatype.com (Account → Generate User Token) |
| `SIGNING_KEY` | an ASCII-armoured GPG private key (`gpg --export-secret-keys --armor <id>`); Maven Central requires signed artifacts |
| `SIGNING_KEY_ID` | the last 8 characters of the key id |
| `SIGNING_PASSWORD` | the key's passphrase |

GitHub Pages must be enabled once, with "GitHub Actions" as the source (Settings → Pages); the
workflow's own token cannot turn it on.

## Before the first Maven Central release

- **License: done.** kcoap is licensed under the Apache License 2.0. [LICENSE](../LICENSE) and
  [NOTICE](../NOTICE) are in the repository root; `gradle.properties` sets `kcoap.license.name=Apache-2.0`
  and `kcoap.license.url=https://www.apache.org/licenses/LICENSE-2.0.txt`; and `kcoap.publishing` writes
  the license into every POM with distribution `repo`, together with the website (`https://kcoap.dev`),
  the maintainer, GitHub Issues and the SCM URLs (`build-logic/src/main/kotlin/KcoapProject.kt`). A
  module without the two properties fails to configure instead of publishing a POM that Maven Central
  rejects.
- **Namespace: to do, by the owner.** The group is `dev.kcoap` (`kcoap.group` in `gradle.properties`),
  backed by the `kcoap.dev` domain, whose DNS is on Cloudflare. The Central Portal verifies it through a
  DNS `TXT` record, in the steps below.
- **User token.** Generate one in the [Central Portal](https://central.sonatype.com) under *Account* →
  *Generate User Token*. Its username and password become the `MAVEN_CENTRAL_USERNAME` and
  `MAVEN_CENTRAL_PASSWORD` repository secrets ([Secrets](#secrets)), with the signing key.

### Verifying the dev.kcoap namespace

1. **Central Portal.** Sign in at [central.sonatype.com](https://central.sonatype.com), go to
   *Namespaces*, choose *Add Namespace* and enter `dev.kcoap`. The new namespace's card shows its
   **Verification Key**; copy it from the card's menu (*View ID*) or with the clipboard icon. Do not press
   *Verify Namespace* yet.
2. **Cloudflare.** In the dashboard, choose `kcoap.dev` → *DNS* → *Records* → *Add record*, fill in the
   record and save it:

   | Field | Value |
   |---|---|
   | Type | `TXT` |
   | Name | `@` (Cloudflare then shows `kcoap.dev`) |
   | Content | the Verification Key, exactly as copied, without quotes |
   | TTL | Auto |

   The Portal looks the key up on the exact domain `kcoap.dev`, so the record goes on the domain itself,
   not on a subdomain such as `dev.kcoap.dev` or `_sonatype.kcoap.dev`. Other `TXT` records on
   `kcoap.dev` do not get in the way.
3. **Check that it resolves** from a public resolver:

   ```bash
   dig +short TXT kcoap.dev @1.1.1.1    # prints the key in quotes
   ```

   Without `dig`: `nslookup -type=TXT kcoap.dev 1.1.1.1`.
4. **Only then press *Verify Namespace*** on the namespace's card, and wait until it shows as verified.
   If the Portal looks before the record resolves, its resolver may cache the empty answer for the
   zone's negative-caching time (30 minutes with Cloudflare's default SOA, which kcoap.dev has), which
   delays verification by that long.
5. **Keep the record** after the namespace is verified. Removing it gains nothing, and it shows how the
   namespace was verified.

## Trying a release locally

```bash
./gradlew publishToMavenLocal -Pkcoap.version=0.1.0-local   # into ~/.m2/repository/dev/kcoap, unsigned
./gradlew dokkaGenerate                                     # API reference in build/dokka/html
```

The artifacts land under `kcoap.group` (`dev/kcoap/<module>/<version>`), one per published module and
target; kcoap-dtls is not among them.

Consumers of GitHub Packages need a GitHub token with `read:packages`:

```kotlin
repositories {
    maven("https://maven.pkg.github.com/adihack/kcoap") {
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.token").get()
        }
    }
}
```
