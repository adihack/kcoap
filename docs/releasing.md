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
I1, P1 and C2 do not block a release. Protecting `v*` tags (Settings → Rules → Rulesets) keeps an
accidental tag from publishing early.

Before tagging 0.1.0:

- the freeze list above has merged and CI is green on `main`;
- [CHANGELOG.md](../CHANGELOG.md) has an entry for the version, with migration notes;
- a license and the Maven namespace are chosen (below);
- `./gradlew publishToMavenLocal` works and a sample consumer builds against it.

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

- **License.** Maven Central rejects artifacts without one. Add a `LICENSE` file and set
  `kcoap.license.name` and `kcoap.license.url` in `gradle.properties`
  (for example `Apache-2.0` and `https://www.apache.org/licenses/LICENSE-2.0.txt`).
- **Namespace.** The group is `dev.kcoap` (`kcoap.group` in `gradle.properties`). Central verifies a
  namespace through DNS, so this needs the `kcoap.dev` domain; without it, use `io.github.adihack`, which
  Central verifies through the GitHub account.

## Trying a release locally

```bash
./gradlew publishToMavenLocal -Pkcoap.version=0.1.0-local   # into ~/.m2, unsigned
./gradlew dokkaGenerate                                     # API reference in build/dokka/html
```

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
