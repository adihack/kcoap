# Trademark policy

kcoap's code is open source under the [Apache License 2.0](LICENSE): anyone may use, change and share
it. The project's name is a different matter. It tells people that software, a service or a statement
comes from the kcoap project, and that only works if the name is used honestly. This policy says how
the name may be used. Most uses need no permission at all.

## Status

**kcoap™** and **kcoap.dev™** are trademarks of Adrian Sołtys. They are not registered: no application
or registration exists in any trademark office.

This section is the only place that states the marks' registration status. Anyone who shows a symbol
with the marks uses the one shown here, ™, until this section names a registration. The project shows
the symbol in two more places, which change together with this section: the [README](README.md) (its
introduction and License section) and the footer of the API reference (`KcoapProject.DOKKA_FOOTER` in
`build-logic`).

## The marks

This policy covers:

- the name **kcoap**, in any capitalization or styling;
- the domain name **kcoap.dev**, used as a name;
- the project's logos, and anything that resembles them.

The owner is Adrian Sołtys, the project's lead maintainer ([governance](GOVERNANCE.md)). In this
policy "the marks" means all of the above.

## The license grants no trademark rights

Section 6 of the Apache License 2.0 grants no permission to use the licensor's names or trademarks,
beyond what is needed to describe where the work comes from and to reproduce the [NOTICE](NOTICE) file.
This policy is where that permission comes from instead.

## Uses that need no permission

Nothing in this policy limits what the law allows without permission, such as nominative or
descriptive fair use; the table lists common examples. You may use the name **kcoap** without asking,
as long as you follow the [conditions below](#conditions-for-every-use):

| Use | Examples |
|---|---|
| Refer to the project | articles, books, blog posts, videos, talks, papers, benchmarks, comparisons, social media |
| Say that your work uses or works with kcoap | "built with kcoap", "for kcoap", "works with kcoap", "an EDHOC credential store for kcoap" |
| Redistribute kcoap unmodified | the released artifacts or the source as published, in a Linux distribution, a mirror or an internal repository, with LICENSE and NOTICE intact; packaging changes that do not change kcoap's behaviour are fine |
| Describe a fork's origin | "based on kcoap", "a fork of kcoap", next to the fork's own name ([forks](#forks)) |
| Name a community group | a user group, meetup or chat channel about kcoap, run by volunteers and not for profit, that says it is not run by the project |
| Link to the website | https://kcoap.dev |
| State facts about your use | that you use kcoap in a product, or that you have experience with it, when that is true |

### Conditions for every use

- Do not suggest that the kcoap project made, endorses, sponsors, certifies or supports your product,
  service, event or person, unless it does in writing.
- Write the name as **kcoap**. Do not change it, abbreviate it or merge it into another word or logo.
- When you name a product, service or event that uses or works with kcoap, keep your own name and
  branding more prominent than kcoap's, so that nobody takes one for the other.
- Say only true things: no claims about kcoap that are false or misleading.

## Uses that need written permission

Ask first ([how to ask](#how-to-ask)) for any of these:

- **Names of products and packages.** A product, service, company, merchandise, event, package,
  library or artifact name that starts with kcoap or something similar, such as "kcoap Cloud", "kcoapX"
  or `kcoap-extras`, or that presents the thing as a version of kcoap, such as "Example kcoap Edition";
  and any name under `dev.kcoap`, the Maven group and Kotlin package namespace that belong to the
  project. A name that starts with your own and says what your work is for, such as "Acme Bridge for
  kcoap" or `acme-kcoap-bridge`, needs no permission; nor does a package that redistributes kcoap
  unmodified under its usual name in that ecosystem, such as `kcoap` or `libkcoap-java` in a Linux
  distribution.
- **Official status.** Calling anything "official", "certified", "approved" or "endorsed" by kcoap, or
  saying that the project recommends a product, service or person.
- **Support and training under the name.** Selling or offering support, consulting, hosting, training,
  courses or exams named after kcoap, such as "kcoap Support" or "kcoap Academy". Saying in plain words
  that you offer such services for kcoap, among others, is a factual statement and needs no permission.
- **Domain names, app names and accounts.** A domain name, subdomain, app-store name, or an account
  or organization name on a social network, code host or package registry, that contains kcoap or
  something similar, other than a community group as described above.
- **Modified distributions.** Distributing a changed kcoap, beyond packaging changes, under the kcoap
  name. Rename it instead ([forks](#forks)).
- **Logos.** Using a kcoap logo, or a logo that resembles one.

## Programs reserved to the project

Official sponsorship, support, training and certification programs under the kcoap name are run only
by the project. If such programs exist, https://kcoap.dev lists them; anything not listed there is
not official. Sponsors use the marks as [SPONSORS.md](SPONSORS.md) and this policy describe.

## Forks

The license lets anyone fork kcoap. A fork on GitHub that exists to prepare contributions back to
kcoap, or a copy used privately inside an organization, may keep the name.

A fork that is published as a project of its own needs its own name:

1. Choose a name that does not contain kcoap and is not confusingly similar to it.
2. Publish under your own Maven group and package names: nothing under `dev.kcoap`, and no name
   that starts with kcoap, the same test as for
   [names of products and packages](#uses-that-need-written-permission).
3. Remove the kcoap logos and the links that present your fork as kcoap, such as the website and the
   sponsorship links.
4. Keep [LICENSE](LICENSE) and [NOTICE](NOTICE), as the license requires, and say where the fork comes
   from: "based on kcoap" is welcome.

## How to ask

Email Adrian Sołtys at adrian.soltys@seelso.net. Say who you are, which use you have in mind, where and
for how long, and include a draft or a sketch if you have one. Permission is given in writing, an
email is enough, for the use it describes; a different use needs a new request.

The same address takes reports of uses that break this policy.

## Changes to this policy

The policy changes through pull requests to this repository, and git keeps every earlier version.
