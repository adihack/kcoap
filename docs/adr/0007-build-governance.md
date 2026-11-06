# 0007. The build guards the architecture: common-only cores, ABI dumps, opt-in markers

Date: 2026-11-05. Status: accepted.

## Context

kcoap's cores stayed common code, its dependency scopes matched its signatures and its public API
changed only on purpose, but all by discipline: nothing failed the build when a core gained a `jvmMain`
file, a public signature used a dependency declared as `implementation`, or a refactoring changed the
API. Helpers shared between kcoap's modules were ordinary public API, and kcoap-dtls published SPIs that
nothing implements. The review proposed Konsist, a scope-checking plugin and a generated module diagram
(findings MB-2, MB-6, MB-10); this records what was chosen instead.

## Decision

- **Tiers by runtime capability.** `UNIVERSAL`, `SOCKETS` and `JVM_LIBRARY`, one convention plugin each
  (plugin IDs unchanged). An adapter declares the smallest tier its library supports.
- **Core modules are common only, checked.** `kcoap.core` registers `verifyCommonOnly` in `check`; it
  fails when a main source set other than `commonMain` has files. It is a check task, not a
  configuration-time assertion, so a misplaced file is reported with its path like any other failure.
  Compiling every target already catches platform imports and `expect` declarations.
- **The public API is reviewed through ABI dumps.** The Kotlin Gradle plugin's own ABI validation, with
  JVM and klib dumps committed per module and `checkKotlinAbi` in CI. No binary-compatibility-validator
  plugin.
- **Two opt-in markers in kcoap-common**, reachable from every module because kcoap-transport exports
  kcoap-common: `@InternalKcoapApi` for plumbing between kcoap's modules (opted in by the build, left out
  of the dumps) and `@ExperimentalKcoapApi` for published SPIs with no implementation yet.
- **Unfinished modules are unpublished, not marked.** `kcoap.unpublished` keeps kcoap-dtls out of the
  published set until its engine exists, instead of an experimental marker on each of its types.
- **Dependency scopes follow a written rule** (docs/code-style.md): every module declares what it
  imports, and `api` exactly when the dependency's types are in its public signatures. Review enforces
  it; there is no scope-checking plugin and no generated diagram.

## Consequences

- An API change is a dump diff in the same commit; a work package's dump diff must match the API changes
  it lists, and parallel work packages regenerate the dumps when they merge.
- Apple klib dumps are written on Linux by cross-compilation and checked again on macOS CI; a host that
  cannot build a target keeps its entries (docs/releasing.md).
- A scope mistake the dumps do not show (a too-wide `api`) is caught only in review.
