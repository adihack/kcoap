# Versioning and API stability

kcoap follows [Semantic Versioning 2.0.0](https://semver.org/). This page says what a version number
promises: which API it covers, how breaking changes are announced, what the build checks, and how
protocol behaviour, security fixes and platform minimums are versioned.

Nothing is released yet. The repository carries `0.1.0-SNAPSHOT`, and the first release waits for the
[release freeze](releasing.md#release-freeze).

## Version numbers

- A version is `MAJOR.MINOR.PATCH`, such as `0.1.0`. A pre-release adds a suffix, such as `0.2.0-rc1`,
  and the release workflow marks it as a pre-release on GitHub.
- Every published module is released at once, with the same version: `dev.kcoap:<module>:<version>`.
  Use one version for all kcoap modules in a build. The modules call each other's internal plumbing
  (`@InternalKcoapApi`, below), which may change in any release, so mixed versions are not supported.
- A release is a `v*` tag, and its version is the tag without the `v` ([releasing](releasing.md)).
  Between releases, `kcoap.version` in `gradle.properties` holds the next version with `-SNAPSHOT`.

## What a release may change

| Release | Before 1.0 | From 1.0 |
|---|---|---|
| Major (`1.4.2` → `2.0.0`) | — | Breaking changes, after deprecation (below) |
| Minor (`0.1.0` → `0.2.0`, `1.4.2` → `1.5.0`) | New API, behaviour changes and **breaking changes**, each listed in [CHANGELOG.md](../CHANGELOG.md) under "Changed (breaking)" with a migration note | New API and behaviour changes; no breaking change to the stable API, other than a security fix that cannot avoid one or a raised platform or toolchain minimum (both below) |
| Patch (`0.2.0` → `0.2.1`) | Fixes only; never breaks the API | Fixes only; never breaks the API |

"Breaking" means that code written or compiled against the previous release of the stable API, below,
may fail to compile, fail to link or behave differently in a way the migration note has to explain.

## API tiers

| Tier | How to recognize it | What it promises |
|---|---|---|
| Stable public API | Public and protected declarations of a published module without an opt-in marker | The rules above. It is in the ABI dumps, so every change to it shows in review |
| Experimental | `@ExperimentalKcoapApi`, which needs `@OptIn(ExperimentalKcoapApi::class)` | May change in any minor release, also after 1.0, with a CHANGELOG entry. It marks a published SPI that nothing implements yet (`DatagramSecurity` today); the marker goes in the release that adds the first implementation and its interop test ([code style](code-style.md#dependencies-and-api)) |
| Internal | `@InternalKcoapApi`, which needs `@OptIn(InternalKcoapApi::class)` | None: it is not API. It is public only because Kotlin has no visibility between the modules of one library, may change or disappear in any release, patches included, and is left out of the ABI dumps. Applications must not use it |
| Unpublished module | The module applies `kcoap.unpublished` (kcoap-dtls today) | None: no artifact, no API reference, no ABI dumps |

Declarations that are `internal` or `private` in Kotlin are not API either.

## How the build enforces it

- **Explicit API mode.** Every public declaration states its visibility, so nothing becomes API by
  accident ([code style](code-style.md#layout)).
- **ABI dumps.** Every published module commits its public API in `<module>/api`: `<module>.api` for the
  JVM class files and `<module>.klib.api` for the klibs of the other targets. `checkKotlinAbi`, part of
  `check`, `scripts/test unit` and both CI runners, fails when the code no longer matches them, and
  `./gradlew updateKotlinAbi` rewrites them, so the dump diff is committed with the change it belongs to
  ([releasing](releasing.md#abi-dumps)).
- **What counts as a break.** A removed or changed line in a dump; new lines are additions. That
  includes changes that still compile from source but break compiled callers, such as a new constructor
  parameter with a default value: the CHANGELOG entry then says that compiled callers recompile.
- **Enums that say they may grow.** A new constant of an enum whose KDoc says it may gain constants
  (`CoapException.Reason`, `OscoreError`) is an addition, in any minor release: code that switches over
  such an enum keeps an `else` branch. For a new constant of any other enum, the CHANGELOG entry says that
  an exhaustive `when` over it needs the new branch.
- **Before a release.** The release checklist compares the dump diff since the last release with the
  CHANGELOG entry ([releasing](releasing.md)). A patch release removes or changes no line in the dumps.

## Deprecation, from 1.0

Before 1.0, API is changed or removed in a minor release, with its migration note, and deprecating it
first is optional. It is deprecated rather than removed at once only to let code still in flight move over
with a warning instead of an error, such as other branches of the refactoring plan, and then it is removed
before the next release, so that no release ships API that is already on its way out
([releasing](releasing.md#release-freeze)). From 1.0, the plan is:

1. A declaration that is to go is marked `@Deprecated` in a minor release, with a message that names its
   replacement and a `ReplaceWith` where the replacement is mechanical.
2. It stays for at least one more minor release, and may then move to `DeprecationLevel.ERROR`.
3. It is removed only in the next major release.

A configuration class that gains a parameter keeps its previous constructor as a hidden overload
(`@Deprecated(level = DeprecationLevel.HIDDEN)`), so code compiled against it keeps working, and the
ABI dumps show that the overload stays ([target architecture](target-architecture.md), §3.5).

## Wire compatibility

What kcoap sends and accepts follows the published RFCs, and is versioned as follows.

- **A deviation from an RFC is a bug.** Its fix ships in the next patch or minor release, even when a
  peer relied on the old behaviour, and CHANGELOG.md lists it under "Fixed" or "Security" with the RFC
  section it restores.
- **Deliberate deviations are documented** in [RFC compliance](rfc-compliance.md) with their reason,
  such as the access check that runs before the critical-option check (RFC 7252 §5.4.1,
  [ADR 0008](adr/0008-fail-closed-message-security.md)).
- **A changed default that changes the wire**, such as turning Q-Block on, ships only in a minor or
  major release, and CHANGELOG.md says how to keep the previous behaviour.
- **Other implementations' deviations are not copied.** The interop tests pin what libcoap and libedhoc
  do differently from the RFCs with `upstream…` tests, listed in [interop](../interop/README.md).
- **Features built on Internet-Drafts follow the draft.** The post-quantum EDHOC suite -24 of
  draft-ietf-lake-pqsuites uses a private code point; when the draft changes or gets code points, kcoap
  follows in a minor release, noted in CHANGELOG.md.

## Security fixes

- Every security fix is listed under "Security" in CHANGELOG.md: what was affected, how, and what
  changed.
- A security fix changes behaviour where the old behaviour is the vulnerability, such as a default that
  let unprotected requests reach every handler ([ADR 0008](adr/0008-fail-closed-message-security.md)).
  A change of behaviour is not a break of the API, so such a fix can ship in a patch.
- If a fix cannot be made without breaking the API, it ships in the next minor release with a migration
  note, also after 1.0. This is one of the two exceptions to the rules above, with a raised minimum
  ([below](#platform-and-toolchain-minimums)), and the advisory says so.
- [SECURITY.md](../SECURITY.md) says how to report a vulnerability and which releases receive fixes.

## Platform and toolchain minimums

| What | Minimum | Where it is set and checked |
|---|---|---|
| Android | API level 26 (Android 8.0) | `android-minSdk` in [gradle/libs.versions.toml](../gradle/libs.versions.toml); `checkAndroidApi` checks every jvm jar against the API 26 signature, and CI builds [samples/android-consumer](../samples/android-consumer/README.md) at minSdk 26 |
| Kotlin in a consuming project | 2.3 | kcoap is built with Kotlin 2.4, and a Kotlin compiler reads metadata at most one minor version ahead of its own ([what an app needs](../samples/android-consumer/README.md#what-an-app-needs)) |
| JVM bytecode and runtime | Java 17 | kcoap's jvm classes are Java 17 (`KcoapBuild.kt`): they run on Java 17 or newer, and Kotlin inlines kcoap's inline functions only into bytecode of the same target or newer, so a consumer compiles to Java 17 or newer. D8 handles Java 17 bytecode at any minSdk |
| Building kcoap | JDK 17 | [README](../README.md#build-and-test) |

The Kotlin minimum follows the Kotlin version that builds kcoap: it rises when kcoap moves to a newer
Kotlin. Raising any minimum is listed in CHANGELOG.md under "Changed (breaking)" and ships only in a
minor or major release, never in a patch. From 1.0 this is the second exception to the rule that a minor
release breaks nothing, next to [security fixes](#security-fixes): kcoap cannot stay on one Kotlin
version for a whole major version.

## The release freeze

No `v*` tag is pushed until the API-breaking work packages of the refactoring plan have merged
([releasing](releasing.md#release-freeze)), so the first release starts from the API that plan settles
rather than from one it is about to change.
