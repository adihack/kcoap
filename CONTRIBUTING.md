# Contributing to kcoap

Thank you for helping. kcoap is security code that people read before they trust it, so the rules
below keep every change reviewable: what it does, why, and how it was proven.

| You want to | Go to |
|---|---|
| Report a bug | [Bug report](https://github.com/adihack/kcoap/issues/new?template=bug_report.yml) |
| Report a problem with another implementation | [Interop report](https://github.com/adihack/kcoap/issues/new?template=interop_report.yml) |
| Report a deviation from an RFC | [RFC compliance gap](https://github.com/adihack/kcoap/issues/new?template=rfc_compliance.yml) |
| Propose a feature | [Feature request](https://github.com/adihack/kcoap/issues/new?template=feature_request.yml) |
| Ask a question | [Question](https://github.com/adihack/kcoap/issues/new?template=question.yml), after [SUPPORT.md](SUPPORT.md) |
| Report a vulnerability | [SECURITY.md](SECURITY.md), **never** a public issue |

<details>
<summary><b>Contents</b></summary>

- [Ways to contribute](#ways-to-contribute)
- [Before you start](#before-you-start)
- [Development setup](#development-setup)
- [Code style](#code-style)
- [Tests](#tests)
- [Public API](#public-api)
- [Dependencies](#dependencies)
- [Documentation to update](#documentation-to-update)
- [Commit messages](#commit-messages)
- [Developer Certificate of Origin](#developer-certificate-of-origin)
- [Pull requests](#pull-requests)
- [Licensing](#licensing)
- [Tool-assisted contributions](#tool-assisted-contributions)
- [Trademarks](#trademarks)
- [Code of conduct](#code-of-conduct)
- [Security issues](#security-issues)

</details>

## Ways to contribute

| Contribution | What helps most |
|---|---|
| **Bug reports** | the kcoap version, the platform, and the smallest program or test that shows the bug |
| **Interop reports** | the other implementation and its version, the transport, the security mode, which side kcoap plays, and logs or a capture from both sides |
| **RFC compliance gaps** | the RFC and section, what it requires, what kcoap does, and the evidence; [docs/rfc-compliance.md](docs/rfc-compliance.md) lists the known gaps |
| **Documentation** | corrections, missing steps, examples that run; the [docs hub](docs/README.md) shows what exists |
| **Code** | issues labelled [`good first issue`](https://github.com/adihack/kcoap/labels/good%20first%20issue) and [`help wanted`](https://github.com/adihack/kcoap/labels/help%20wanted) are good places to start |

Interop reports are especially valuable: every peer implementation that kcoap is tested against
catches mistakes that tests against kcoap itself cannot.

## Before you start

- **Open an issue before a large change**: a new module, a change to the public API or to protocol
  behaviour, a new dependency, or a change to the build. Agreeing on the approach first saves you from
  rewriting a finished pull request. Small fixes and documentation can go straight to a pull request.
- **Record design decisions as ADRs.** A decision that shapes the architecture gets an architecture
  decision record in [docs/adr](docs/adr/README.md): the next number, the format of the existing
  records, and a row in the index. An accepted record's decision is never changed; a later record
  supersedes it. An "As built" section may be appended to record how it was implemented.
- **Check what is planned.** [docs/checklist.md](docs/checklist.md) tracks every requirement with its
  status, and [docs/roadmap.md](docs/roadmap.md) the order of the work.

## Development setup

You need a JDK 17 or newer. Gradle, through the wrapper, downloads everything else, Node.js for the web
tests included.

| For | You also need |
|---|---|
| unit tests on the JVM, Linux native, JS and Wasm | nothing else |
| interop tests against libcoap and libedhoc | Docker |
| the Apple targets (iOS, macOS) | a Mac with Xcode |
| the Android sample app | the Android SDK |

```bash
git clone https://github.com/adihack/kcoap.git
cd kcoap
scripts/test unit
```

Each test suite is one command, and every run ends with a table of tests per target:

| Command | Runs |
|---|---|
| `scripts/test unit` | every unit test on every target this machine builds, then the build checks: ABI dumps, common-only core modules, the Android API level, the build-logic tests |
| `scripts/test interop` | cross-tests against libcoap and libedhoc in both roles; builds their Docker images |
| `scripts/test coverage` | unit and interop tests, the coverage report and the coverage floor |
| `scripts/test apple` | the common tests on macOS and the iOS simulator (macOS only) |
| `scripts/test all` | unit, interop and coverage |

Extra arguments go to Gradle, for example `scripts/test interop --tests '*Oscore*'`.
[docs/testing.md](docs/testing.md) explains each suite, the build checks and how to run single tests.

## Code style

[docs/code-style.md](docs/code-style.md) is the reference. In short:

- Kotlin official style, 4 spaces, lines up to 120 characters; explicit API mode, so every public
  declaration states its visibility.
- Core modules (protocol, wire and SPI code) are common code only; runtime-specific code goes into an
  adapter module.
- KDoc on every public declaration, citing the RFC section it implements (`RFC 8613 §5.2`). Comments
  explain why, not what.
- Never put key material in `toString()`, logs or exception messages; compare MACs and tags with
  `constantTimeEquals`.
- Time and randomness are injected, so tests run on virtual time with fixed keys.

## Tests

- **RFC vectors first.** Where an RFC or a NIST document publishes examples, the code is checked
  against them byte for byte, in tests named after the section they come from. Vectors are extracted
  from the source documents by the scripts in `tools/`, not typed in by hand. A vector source kcoap
  did not use before is also recorded in [docs/third-party.md](docs/third-party.md).
- **Then every public behaviour and every error path.** Common tests run on every target, so choose
  algorithms by capability (`crypto.supports(...)`), never by target.
- **Coverage floors.** The build fails below **90 %** line coverage for the whole library and below
  **95 %** for `kcoap-edhoc` (`./gradlew koverVerify`, also part of `scripts/test coverage`). The
  library is above 97 % today; new code comes with tests, so the floors only go up.
- **Interop when protocol behaviour changes.** Run `scripts/test interop` for any change to what goes on
  the wire or to how messages are processed, and add a cross-test when you add a feature that libcoap
  or libedhoc also implement.

[docs/testing.md](docs/testing.md#writing-tests) has the details: virtual time, `InstantCrypto`, stress
tests for shared state.

## Public API

- **ABI dumps.** Every published module commits dumps of its public API under `<module>/api`.
  `./gradlew checkKotlinAbi` fails when the code no longer matches them. If an API change is intended,
  run `./gradlew updateKotlinAbi` and commit the dump diff in the same commit as the change, so that
  review sees every API change ([releasing](docs/releasing.md#abi-dumps)).
- **`@InternalKcoapApi`** marks plumbing that kcoap's modules share. It is public only because Kotlin
  has no visibility between the modules of one library; the ABI dumps leave it out and applications
  should not use it.
- **`@ExperimentalKcoapApi`** marks a published SPI that nothing implements yet. Its shape may change
  with the first implementation, which removes the marker in the same change.
- **Compatibility.** [docs/versioning.md](docs/versioning.md) says what a version number promises.
  Before 1.0 a minor version may break the API; every breaking change gets a CHANGELOG entry with a
  migration note.

## Dependencies

- A new third-party dependency needs an issue first. Its license must be compatible with Apache-2.0
  (permissive licenses such as Apache-2.0, MIT or BSD), and it must support the targets of the module
  that uses it.
- Declare `api` exactly when the dependency's types appear in a public signature, otherwise
  `implementation` ([code style](docs/code-style.md#dependencies-and-api)).
- Record it in [docs/third-party.md](docs/third-party.md) with its license.

## Documentation to update

A change is finished when its documentation is:

| Change | Update |
|---|---|
| anything a user can notice | [CHANGELOG.md](CHANGELOG.md), section "Unreleased", with a migration note for a breaking change |
| a requirement's status or evidence | [docs/checklist.md](docs/checklist.md) |
| RFC coverage | [docs/rfc-compliance.md](docs/rfc-compliance.md) |
| a module's behaviour | its README and the KDoc |
| a dependency or a vector source | [docs/third-party.md](docs/third-party.md) |
| an architectural decision | a new ADR in [docs/adr](docs/adr/README.md) |

## Commit messages

Follow the style of the repository's history:

- An imperative subject line under 72 characters that says what the commit does: "Add ...", "Fix ...",
  "Keep ...", "Record ...". No trailing period.
- A blank line, then a body wrapped at about 72 characters that says **why**: what was wrong or
  missing, and what changes for whom. Name the RFC section when one is involved.
- The trailers last, in one block: `Signed-off-by` (required, below), then a `Co-authored-by` line for
  each co-author.

A commit from the history, with the sign-off it needs:

```text
Test a negative C_R out of range and a 5.00 EDHOC error to the client

The Kover report showed two branches of the reworked glue untested: a
connection identifier sent as an integer below -24, and an EDHOC error
message in a 5.00 response (RFC 9528 Appendix A.2.3), which the client
hands to EDHOC like a 4.00.

Signed-off-by: Your Name <you@example.org>
```

Keep each commit one logical step that builds and passes its tests, so that history stays bisectable.

## Developer Certificate of Origin

kcoap uses the [Developer Certificate of Origin 1.1](DCO) (DCO) instead of a contributor license
agreement. By adding a `Signed-off-by` line to a commit you certify, in short, that you wrote the
contribution or otherwise have the right to submit it under kcoap's open source license, and that a
public record of it, your sign-off included, is kept. Read the [full text](DCO) once; it is short.

**Every commit needs a sign-off in the form `Name <email>` whose email is the commit's author email.**
The check compares the emails without regard to case; it does not compare names, so use the same name
as your commits' author.

```text
Signed-off-by: Your Name <you@example.org>
```

| Situation | Command |
|---|---|
| Set your identity once (it must match the sign-off) | `git config user.name "Your Name"` and `git config user.email you@example.org` |
| Sign off a new commit | `git commit -s` |
| Add a forgotten sign-off to the last commit | `git commit --amend -s --no-edit` |
| Add sign-offs to every commit of your branch | `git rebase --signoff <base>`, where `<base>` is the commit your branch starts from (for example `upstream/main`) |
| Update the pull request after rewriting commits | `git push --force-with-lease` |

Notes:

- Sign off with the name you use publicly. Anonymous contributions cannot be accepted.
- If you commit with GitHub's private `noreply` email, sign off with that same address.
- Commits made in GitHub's web editor need the line too: add it to the commit message.
- A merge commit needs a sign-off too when it resolves a conflict or brings in anything its parents do
  not have. A merge that git would make on its own is skipped. Rebasing instead of merging avoids the
  question ([pull requests](#pull-requests)).
- Commits from before the DCO was adopted are exempt (`.github/dco-baseline`).
- Check your branch before you push: `scripts/check-dco upstream/main HEAD`.

The **DCO** workflow runs the same check on every pull request and names each commit that fails. It
runs from the base branch, so changes to the workflow or to `scripts/check-dco` in a pull request take
effect only after it has merged.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change in logical, signed-off commits, with tests and documentation.
3. Run `scripts/test unit`, and `scripts/test interop` if protocol behaviour changed.
4. Open the pull request and fill in the template: what and why, the linked issue, how it was tested.
5. CI must pass: the **CI** workflow (unit tests on every target, build checks, coverage, interop,
   Apple targets, the browser and Android jobs, the API reference) and the **DCO** check.
6. The maintainer reviews every pull request. kcoap has one maintainer today, so a review can take a
   few days; a friendly reminder after a week is welcome.

Update your branch by rebasing it on `main` rather than merging `main` into it, and keep the
sign-offs when you do (`git rebase --signoff`).

## Licensing

kcoap is licensed under the [Apache License 2.0](LICENSE). Contributions come in under the same
license they go out under: by section 5 of the license, anything you submit for inclusion is licensed
under Apache-2.0, without extra terms. There is no contributor license agreement, and you keep the
copyright of your contribution. [NOTICE](NOTICE) names "Adrian Sołtys and the kcoap contributors", and
git history records who wrote what.

Do not copy code from other projects unless its license allows it under Apache-2.0. If you do, say so
in the pull request, keep the original copyright and license notices, and record it in
[docs/third-party.md](docs/third-party.md).

## Tool-assisted contributions

You may use code generators, AI assistants and other tools. The rules do not change:

- **You answer for every line**, generated code included, as if you had typed it: you have read it,
  you understand it, and you can explain and defend it in review.
- **You must be able to certify the DCO for it.** Do not submit output you have no right to submit
  under Apache-2.0, such as code a tool reproduced from a project under another license.
- **Tests and vectors prove it**, as for any other change; generated tests that only repeat what the
  code does do not.
- Mention substantial tool use in the pull request description. A `Co-authored-by` trailer for the
  tool is welcome; your `Signed-off-by` is still required.

## Trademarks

Contributing gives no rights to the kcoap and kcoap.dev trademarks. [TRADEMARKS.md](TRADEMARKS.md)
says how the name may be used.

## Code of conduct

Everyone who takes part follows the [code of conduct](CODE_OF_CONDUCT.md).

## Security issues

Never report a vulnerability in a public issue, pull request or comment. [SECURITY.md](SECURITY.md)
says how to report it privately.
