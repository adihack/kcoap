# Governance

kcoap is a maintainer-led open source project. This page says who decides what, how someone becomes a
maintainer, who holds the project's name and accounts, and how sponsorship money is handled.

## Maintainers

| Name | GitHub | Role |
|---|---|---|
| Adrian Sołtys | [@adihack](https://github.com/adihack) | Lead maintainer |

kcoap has one maintainer today. The lead maintainer is also the author of the project and the owner
of its trademarks.

## Roles

| Role | Who | Can |
|---|---|---|
| User | anyone who uses kcoap | report bugs, ask questions, propose features |
| Contributor | anyone whose pull request was merged | everything a user can, and review others' pull requests |
| Maintainer | listed above | merge pull requests, triage issues, accept ADRs, publish releases |
| Lead maintainer | one of the maintainers | decide when maintainers disagree, appoint maintainers, hold the project's assets |

## How decisions are made

- **Day to day** in issues and pull requests, in public. Anyone may comment; maintainers merge.
- **Architecture** through ADRs in [docs/adr](docs/adr/README.md). An ADR is proposed in a pull request,
  discussed there, and accepted when a maintainer merges it. Accepted ADRs are never edited; a later
  one supersedes them.
- **When there is no consensus**, the lead maintainer decides, and records the reasons in the issue,
  the pull request or the ADR.
- **Security issues** are handled in private, as [SECURITY.md](SECURITY.md) describes, and disclosed
  when fixed.

## Becoming a maintainer

Maintainers are contributors who have shown, over months rather than weeks, that they:

- make careful contributions: code, reviews, tests, interop reports, documentation;
- understand kcoap's security model and the RFCs behind the code they review;
- follow the [code of conduct](CODE_OF_CONDUCT.md) and help others do so.

The lead maintainer invites new maintainers. A new maintainer is added to the table above and to
[CODEOWNERS](.github/CODEOWNERS) in a pull request, and must use two-factor authentication on GitHub.
Maintainers who step back are thanked and listed as former maintainers.

## Releases

Maintainers publish releases as [docs/releasing.md](docs/releasing.md) describes: a release is a tag,
CI must pass first, and no tag is made before the release freeze list has merged.

## The project's assets

| Asset | Held by |
|---|---|
| The trademarks kcoap and kcoap.dev ([TRADEMARKS.md](TRADEMARKS.md)) | Adrian Sołtys |
| The domain kcoap.dev and the website https://kcoap.dev | Adrian Sołtys |
| The GitHub repository [adihack/kcoap](https://github.com/adihack/kcoap) | Adrian Sołtys |
| The Maven namespace `dev.kcoap` and the release signing keys | Adrian Sołtys |
| Sponsorship accounts, once they exist | Adrian Sołtys, for the project |

If the project moves to a foundation or another legal home, this table changes in the same pull
request as the move.

## Sponsorship money

Sponsorship will come through GitHub Sponsors or Open Collective; neither is set up yet
([SPONSORS.md](SPONSORS.md)). Money is handled in the open:

- **It pays for the project**: CI runners and the interop infrastructure, test hardware (phones,
  gateways, boards), independent security review, the domain and the website, and maintainers' time on
  kcoap.
- **It is accounted for publicly.** With Open Collective every income and expense is public. With
  GitHub Sponsors, a summary of income and spending is published at least once a year.
- **Sponsors buy no influence.** They get no say over technical decisions, the roadmap, the content of
  reviews, or how and when vulnerabilities are disclosed, and no early or private access to
  vulnerability information. Recognition is the only thing sponsorship brings.

## Changing this document

Changes to this document are proposed in a pull request, stay open for comment for at least seven
days, and are merged by the lead maintainer.
