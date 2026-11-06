<!--
Thank you for contributing. CONTRIBUTING.md explains each item below.
Security vulnerabilities do not belong in a pull request: see SECURITY.md.
-->

## What and why

<!-- What this pull request changes, and why: the problem, the RFC section if one is involved. -->

## Linked issue

<!-- "Closes #123". For a change without an issue, say why none is needed (a small fix, a typo). -->

## How it was tested

<!-- The commands you ran and on which platforms; the tests or RFC vectors you added. -->

## Checklist

- [ ] `scripts/test unit` passes.
- [ ] `scripts/test interop` passes, if protocol behaviour changed.
- [ ] New behaviour has tests, with RFC vectors where the RFC publishes them.
- [ ] ABI dumps are updated with `./gradlew updateKotlinAbi` and committed, if the public API changed.
- [ ] `docs/checklist.md` and the "Unreleased" section of `CHANGELOG.md` are updated, with a migration note for a breaking change.
- [ ] Other documentation is updated: KDoc, module READMEs, `docs/rfc-compliance.md`, `docs/third-party.md` for a new dependency.
- [ ] Every commit is signed off (`git commit -s`) by its author.
