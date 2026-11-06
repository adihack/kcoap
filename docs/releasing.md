# Releasing

A release is a tag. Pushing `v0.1.0` (or `v0.2.0-rc1`) runs `.github/workflows/release.yml`:

1. **Test**: the whole CI workflow (unit tests on every target, coverage floor, interop with libcoap and
   libedhoc, the Apple targets, the API reference). Nothing is published if any of it fails.
2. **Release notes**, in parallel with the tests: the `notes` job takes the version's summary from
   [CHANGELOG.md](../CHANGELOG.md) ([below](#release-notes)). If CHANGELOG.md has no section for the version,
   the job fails, and nothing is published.
3. **Publish** on a macOS runner, because only macOS can build the iOS and macOS artifacts; the same
   host builds the JVM, Linux, JS and Wasm ones. The version is the tag without its `v`.
   - to **GitHub Packages** always, with the workflow's own token;
   - to **Maven Central** when its secrets are configured.
4. **GitHub Release** whose body is that summary, followed by the notes GitHub generates from the merged
   pull requests. A version with a `-` (`0.2.0-rc1`) is marked as a pre-release.
5. **API reference** (Dokka) deployed to GitHub Pages.

A release can also be started by hand (Actions → Release → Run workflow) with a version number, without
the `v`; that creates the tag through the GitHub Release step, and the release notes come from CHANGELOG.md
in the same way.

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Release notes

The body of the GitHub Release comes from CHANGELOG.md, so the version's entry must be in the commit that is
tagged, written before the tag is pushed: the `notes` job reads CHANGELOG.md at that commit. How it finds the
summary:

- The version is the tag without its `v`: `v0.1.0` gives `0.1.0`. A manual run turns its version input into
  the tag `v<version>` first, so both ways find the same entry.
- The entry's heading is `## <version>`, alone or followed by a space, as in `## 0.1.0 - 2026-11-06`. The match
  is exact, so `0.1.0` never finds `## 0.1.0-rc1 - …`, and `## Unreleased` is never used: rename it to the
  version and its date, and start a new `## Unreleased` above it.
- The summary is every line after that heading up to the entry's first `### ` heading (`### Added` and the
  others), or up to the next `## ` heading when the entry has no subsections, without the blank lines around
  it. Links relative to the repository root, such as `docs/checklist.md`, are pointed at the tag, since the
  release page would resolve them against itself. A last line links to the whole entry, CHANGELOG.md at the tag.
- The job passes the result to the GitHub Release step as a file (`body_path`, through the `release-notes`
  artifact), and `generate_release_notes` stays on, so GitHub's list of merged pull requests follows it. The run's
  summary page shows the body as soon as the `notes` job ends, before anything is published.
- No section for the version: the job fails with an error that names the version, so the artifacts are not
  published and no GitHub Release is created (nor, in a manual run, the tag). Delete a tag pushed without the
  entry (`git push --delete origin v<version>`), add the entry, and tag the commit that has it. An entry with no
  summary above its first `### ` heading only warns: its body is the link alone.

## Release freeze

No `v*` tag until the API-breaking work packages of the refactoring plan have merged
([target-architecture.md](target-architecture.md), section 4): A1–A5, W1, B1–B3, C1, D1, D2 and C3. T1,
I1, P1, C2 and B4 do not block a release. Protecting `v*` tags (Settings → Rules → Rulesets) keeps an
accidental tag from publishing early.

**The freeze list is complete.** C3, its last package, merged into `feat/protocol-core` on 2026-11-06, after A1–A5,
W1, B1–B3, C1, D1 and D2. What stands between the branch and a tag is the checklist below.

Before tagging 0.1.0:

- the freeze list above has merged and CI is green on `main`;
- **done (WP-P1):** nothing deprecated before the release is left in the API, so 0.1.0 ships no API that is
  already on its way out ([versioning](versioning.md#deprecation-from-10)): the constructors of `CoapEndpointConfig`
  and `CoapClientConfig`, deprecated by WP-D1 for their builders, are gone, and no other `@Deprecated` declaration
  is left in the published modules;
- [CHANGELOG.md](../CHANGELOG.md) has an entry for the version, with migration notes, headed
  `## <version> - <date>` and opening with the summary that becomes the release body
  ([release notes](#release-notes)): **done** for 0.1.0, dated 2026-11-06 (a tag pushed on another day changes
  the date first);
- the Maven Central and signing secrets are set ([below](#before-the-first-maven-central-release)): done on
  2026-11-06; the license is set, and the `dev.kcoap` namespace is verified;
- **in CI (WP-P1):** `./gradlew publishToMavenLocal` works and the sample consumers build against it through
  `kcoap-bom` ([getting started](getting-started.md#add-kcoap-to-a-build)): the CI `jvm-consumer` job builds and runs
  [samples/jvm-consumer](../samples/jvm-consumer/README.md), whose `verifyKcoapFromBom` fails when a kcoap module on
  its classpath has a version the BOM does not set, and the same program from the POMs alone, as Maven reads them
  (`pom-only`), and the `js-consumer` job builds
  [samples/js-consumer](../samples/js-consumer/README.md) within its bundle-size budget and runs it in headless
  Chrome. `verifyBomConstraints` (the unit job) keeps the BOM equal to the published modules. The modules' own
  POMs name the license, the developer and the SCM, as `PublishingPluginTest` checks the convention plugin does in
  a fixture build: **done (WP-P1)** for all 127 POMs of a `publishToMavenLocal` at its commit, and checked again on
  the release commit, where this prints nothing:

  ```bash
  for p in ~/.m2/repository/dev/kcoap/*/<version>/*.pom; do
      grep -q '<license>' "$p" && grep -q '<developer>' "$p" && grep -q '<scm>' "$p" || echo "$p"
  done
  ```

- the ABI dumps are current (`checkKotlinAbi` is green on both CI runners) and their diff since the last
  release matches the CHANGELOG entry ([versioning](versioning.md)).

## What is published

Every module whose build applies a tier plugin (`kcoap.kmp-library`, `kcoap.kmp-jvm-native-library`,
`kcoap.kmp-jvm-library`), except the modules that apply `kcoap.unpublished` first. Today that is
kcoap-dtls, whose SPIs have no engine yet; it joins the published set, with its ABI dumps, when the
engine lands (roadmap E1). kcoap-swift-smoke, the Swift smoke test of the macOS CI job, is never published:
it applies `kcoap.unpublished` and the Kotlin plugin, not a tier plugin. An unpublished module is still built
and tested, but has no Maven publication, no API reference page and no ABI dumps.

And `kcoap-bom`, the bill of materials, which applies `kcoap.bom`: a Java platform, published with the same
coordinates scheme and POM, whose constraints are the multiplatform and JVM artifacts of every published module,
generated from the modules that apply `kcoap.publishing` and checked by `verifyBomConstraints`
([ADR 0016](adr/0016-bom-from-published-modules.md)). It has no code, so no ABI dumps and no API reference page.

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
- **Namespace: done.** The group is `dev.kcoap` (`kcoap.group` in `gradle.properties`), backed by the
  `kcoap.dev` domain, whose DNS is on Cloudflare. The Central Portal verified it on 2026-11-06 through a
  DNS `TXT` record on `kcoap.dev`. The steps below stay as the record of how, and for a namespace that
  ever needs verifying again.
- **User token: done.** Generated in the [Central Portal](https://central.sonatype.com) under *Account* →
  *Generate User Token*; its username and password are the `MAVEN_CENTRAL_USERNAME` and
  `MAVEN_CENTRAL_PASSWORD` repository secrets ([Secrets](#secrets)), set on 2026-11-06.
- **Signing key: done.** The `SIGNING_KEY`, `SIGNING_KEY_ID` and `SIGNING_PASSWORD` secrets hold the release
  key below, set on 2026-11-06. The passphrase is checked only when a release first signs; a wrong one fails
  the publish step, and nothing is published.

### The release signing key

Every artifact, on Maven Central and on GitHub Packages, carries a detached signature (`.asc`) made with this
key:

| | |
|---|---|
| Fingerprint | `5833 7699 D9CB 8E6F 5EB1  BDFB 4A7E 4910 69FA EAB3` |
| User ID | Adrian Sołtys (kcoap release signing) &lt;adrian.soltys@seelso.net&gt; |
| Algorithm | RSA 4096, one primary key that certifies and signs, with no subkeys |
| Created, expires | 2026-11-06, 2029-11-05 |
| Published on | `keyserver.ubuntu.com`, one of the three key servers Maven Central checks |

To check a downloaded artifact: `gpg --keyserver keyserver.ubuntu.com --recv-keys 58337699D9CB8E6F5EB1BDFB4A7E491069FAEAB3`,
then `gpg --verify <artifact>.asc <artifact>`.

The key has no subkeys because Maven Central verifies signatures against the primary key only
([its GPG requirements](https://central.sonatype.org/publish/requirements/gpg/)). It was made with
`gpg --quick-generate-key "<user id>" rsa4096 sign 3y`, which creates the primary key alone, and its secret
part went into the secret through a pipe (`gpg --export-secret-keys --armor <fingerprint> | gh secret set
SIGNING_KEY`), so it was never written to the repository. The owner keeps an offline copy and the revocation
certificate.

**Before it expires**, extend it (`gpg --edit-key <fingerprint>`, then `expire` and `save`), send the public
key to the key server again (`gpg --keyserver keyserver.ubuntu.com --send-keys <fingerprint>`) and set
`SIGNING_KEY` again from the extended key. A new key instead needs all three secrets, the key server, and
this table and the website's releases page updated, and the CHANGELOG of the first release it signs says so.

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
target, and `kcoap-bom`; kcoap-dtls is not among them.

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
