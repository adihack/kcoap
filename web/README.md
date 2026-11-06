# kcoap.dev

The website at https://kcoap.dev: plain HTML, CSS and a little JavaScript, served by a Cloudflare Worker
named `kcoap` that has static assets only. There is no npm package, no framework and no build step:
Cloudflare uploads `web/public` as it is.

## Structure

```
web/
├── README.md            this file
├── TEMPLATE.md          the head, header and footer every page copies, the rules, the components
├── wrangler.jsonc       the Worker: name, compatibility date, static assets settings
└── public/              everything that is served
    ├── index.html       /           home
    ├── start/           /start/     getting started, and one guide per platform:
    │   ├── jvm/  android/  apple/  linux/  browser/  node/
    ├── features/        /features/  the features in depth, protocol support, interop
    ├── releases/        /releases/  status, release freeze, versioning, the GitHub releases
    ├── sponsors/        /sponsors/  the sponsorship placeholder (linked from FUNDING.yml and SPONSORS.md)
    ├── 404.html         served for any path without a file, with status 404
    ├── _headers         security and cache headers (read by Cloudflare, not served)
    ├── robots.txt, sitemap.xml, favicon.ico, apple-touch-icon.png
    └── assets/
        ├── site.css     the design system every page links: tokens (light and dark), base, layout,
        │                header, footer, and the components /features/ uses too
        ├── figures.css  figures and diagrams: /, /features/, /releases/, 404
        ├── components.css  steps, meter, tabs, platform links, fact tiles, release feed: the other pages
        ├── guide.css    the guide layout: /start/ and its guides
        ├── home.css     the home page's sections: /
        ├── site.js      theme toggle, mobile menu, copy buttons, tabs, scroll regions
        ├── releases.js  /releases/ only: the latest releases from the GitHub API
        └── kcoap-logo.svg, og-image.png
```

Every page is a directory with an `index.html`, served with a trailing slash. Each page carries its own
copy of the head, header and footer; [TEMPLATE.md](TEMPLATE.md) has them, the rules every page follows
(no inline CSS or JavaScript, one `h1`, repository links with one fixed prefix), which stylesheets each kind
of page links, and every component class.

## Preview and check

```bash
# then open http://127.0.0.1:8080/; --bind keeps the preview off the local network
python3 -m http.server --bind 127.0.0.1 -d web/public 8080
scripts/check-site
```

The local server does not apply `_headers` and answers unknown paths with its own 404 page; Cloudflare
serves `404.html` instead. `scripts/check-site` (Python 3, standard library only) runs in CI in the
"Website" workflow (`.github/workflows/website.yml`), which runs only when `web/`, the script or the
workflow changes. It is not a job of `ci.yml`, because `release.yml` reuses `ci.yml` as its test step and a
website fault should not hold back a release of the library. Its docstring lists every check; in short it
fails on:

- broken internal links and `#anchors`, links that Cloudflare would redirect (`.html`, `/index`), relative
  links on `404.html`, and a page that is not an `index.html` in its directory;
- a page without its title, description, canonical URL, Open Graph and Twitter tags (the image an absolute
  kcoap.dev URL of a PNG of the stated size), `lang` or exactly one `h1`; an image without `alt`;
- anything the Content-Security-Policy would block or that sends the reader elsewhere: inline scripts,
  `<style>`, `style` and `on*` attributes, `javascript:` URLs, meta refresh, `<base>`, `<form>`, `ping`;
- a resource from anywhere but the site itself, in HTML, CSS (`url()`, `image-set()`, `@import`), JavaScript
  (github.com only in `releases.js`, network calls only there) and SVG files;
- a `<pre>` without `tabindex="0"`, a code line over 80 characters (one-line TOML tables aside), and a
  check-list item or meter block without its state in text;
- a page over 150 KB with every stylesheet, script and image it loads, the tab icon included
  ([Page weight](#page-weight)); it prints every page's weight and gzip size either way;
- a class in a page, or set by `site.js` or `releases.js`, that no stylesheet the page links names; a class
  a stylesheet names that no page linking it uses (dead CSS); a page that links a sheet it uses nothing
  from, or links the sheets in another order than `site.css`'s header gives;
- `_headers` beyond Cloudflare's limits, with a splat inside a path, without the security headers or with any
  other CSP, or giving one path the same header twice; a `robots.txt` that keeps crawlers out; a sitemap that
  does not match the pages.

## Page weight

Each page, with every stylesheet, script and image it loads, each file counted once, stays within 150 KB
(150 × 1024 bytes) before compression. That includes the tab icon: a browser that does not take the SVG logo
for its tab fetches `favicon.ico` (4.4 KB) on a first visit, so both count, the logo once, as the header's
`<img>` loads it too. Only `apple-touch-icon.png` is left out: a browser fetches it for a home screen or a
bookmark, not to show a page. `scripts/check-site` fails on a page over the limit and prints every page's
weight and its gzip size, so the numbers show in CI and in the Cloudflare build log.

The budget counts the stylesheets and scripts, not only the HTML and its images, because a first visit
downloads all of them before the page looks and works as it should: a stylesheet that every page links is
part of every page's weight. It is not about hosting limits. Cloudflare compresses text, and gzip brings the
heaviest page, `/features/`, to about 33 KB plus the favicon, whose PNG images are compressed already (36 KB
before the stylesheets were split); the home page, whose four sheets compress one by one, comes to 2 KB more
than with one sheet. It is about discipline and headroom: hand-written pages grow a little with every change,
and a fixed budget shows each addition before they add up.

So the styles are split by the pages that use them, and each page links only the sheets it uses:

| Sheet | Holds | Linked by |
|---|---|---|
| `site.css` | tokens, base, icons, layout, header, footer; buttons, badges, cards, callouts, tables, facts, checks, code, the `/features/` sections | every page |
| `figures.css` | figures and diagrams, the 404 page | `/`, `/features/`, `/releases/`, 404 |
| `components.css` | steps, the release meter, tabs, platform links, the status card, fact tiles, the release feed | every page but `/features/` and 404 |
| `guide.css` | the guide layout: contents list, long-form text, questions and answers, pager, matrix | `/start/` and its guides |
| `home.css` | the home page's sections | `/` |

`/features/` sets the shape: it has the most HTML (91 KB, 37 KB of it the inline SVG of eight diagrams and the
icons), so `site.css` holds what it needs, and the components it does not use, a few small ones aside, sit in
the sheets it does not link. The line icons take their stroke from the `icon` class instead of five attributes
each ([TEMPLATE.md](TEMPLATE.md#icons)), which saves 3.2 KB on `/features/` and 3.8 KB on `/`. On 6 November
2026, the favicon included, `/features/` weighs 148 KB, `/` 146 KB and the guides 85 to 99 KB; with the one
stylesheet and the icons' attributes they weighed 172 KB, 152 KB and 99 to 113 KB.

Pages link the sheets in a fixed order, `site.css` first ([TEMPLATE.md](TEMPLATE.md#1-the-head)); each sheet
keeps its rules in the order they had when `site.css` was the only stylesheet, so the cascade gives the same
result as before. Before moving a rule to another sheet, check what it competes with: between two rules of
equal specificity, the one in the later sheet wins. `scripts/check-site` checks the order, that every class a
page uses is in a sheet it links, and that no sheet names a class none of its pages uses.

## Cloudflare settings

The Worker `kcoap` is connected to the GitHub repository with Workers Builds (Workers & Pages → kcoap →
Settings → Builds):

| Setting | Value |
|---|---|
| Git repository | `adihack/kcoap` |
| Git branch (production) | `feat/protocol-core` until pull request #1 merges, then `main` |
| Root directory | `web` |
| Build command | `python3 ../scripts/check-site` (it builds nothing; a failed check stops the deploy) |
| Deploy command | `npx wrangler deploy`; pinned: `npx wrangler@4.152.0 deploy` (below) |
| Non-production branch command | `npx wrangler versions upload`; pinned: `npx wrangler@4.152.0 versions upload` |
| Build watch paths (optional) | include `web/*`, so only changes to the site start a build |

**Wrangler's version.** There is no `package.json`, so a bare `npx wrangler` installs whatever Wrangler is
newest on every build (Workers Builds sets `CI=true`, and npx then installs without asking), and a new
major could change how the site deploys without a change in the repository. The pinned forms in the
table fix 4.152.0, published on 22 October 2026; with them, raise the version on purpose, after reading
Wrangler's changelog. Which form to use is the owner's choice; the site's brief named the unpinned one.

**Watch paths.** Cloudflare's documentation does not say whether `web/*` matches nested files or whether it
is read from the repository root or from the root directory. After setting it, push a change to a nested
file such as `web/public/start/jvm/index.html` and check that a build starts; if none does, use `*`.

**What deploys.** Workers Builds deploys every push to the production branch, and it does not wait for
the GitHub "Website" check. The build command therefore runs the same check first:
`python3 ../scripts/check-site` (the build clones the whole repository, the root directory is `web`, and the
build image has Python, 3.14 by default). It builds nothing, so the site keeps no build step, but a commit
that fails the check is not deployed and the build shows the check's errors. Run the script before pushing a
change to `web/` anyway. Once pull request #1 has merged, the "Website" check also becomes required on
`main` (below), so a failing change cannot merge either.

The Worker's name in the dashboard must stay `kcoap`, the `name` in `wrangler.jsonc`, or the build fails.
Current Cloudflare documentation calls the non-production setting **Preview command** and gives it the
default `npx wrangler preview`, which creates a Preview; `npx wrangler versions upload` creates a version
with its own URL instead. Neither touches production.

`wrangler.jsonc` sets `name`, `compatibility_date`, `workers_dev`, `preview_urls` and `assets`
(`directory`, `not_found_handling`, `html_handling`). It has no `main`, because the Worker serves assets
only, and no `routes`: the custom domain is attached in the dashboard (Settings → Domains & Routes), which
is what Cloudflare's documentation describes for routes managed in the dashboard ("remove the `route` and
`routes` keys from your config file and add `workers_dev = false`"). Without `routes`, `workers_dev` would
default to true and the site would also answer on the account's `workers.dev` subdomain, so it is set to
`false`; Wrangler then logs that the Worker has no deploy targets of its own, and kcoap.dev keeps serving
the new version. `"preview_urls": false` also turns off the version and preview URLs, which live on
`workers.dev` too; left out, Wrangler would keep whatever the Worker had. Both apply on every deploy, so
the dashboard cannot turn them back on for long. A build for another branch still uploads a version; it
only has no URL to open. If the preview command fails because of that, set it to
`npx wrangler versions upload`, or turn off builds for non-production branches.

**www.kcoap.dev** redirects to kcoap.dev with a zone rule; the Worker never sees it. In the dashboard
(kcoap.dev → Rules → Overview → Create rule → Redirect Rule): match the wildcard pattern
`https://www.kcoap.dev/*`, target URL `https://kcoap.dev/${1}`, status code 301, preserve the query string.
The rule needs a proxied DNS record for `www`; Cloudflare offers to create one when the rule is saved, or
add it under DNS → Records as type `AAAA`, name `www`, IPv6 address `100::`, proxied (a placeholder: no
request ever reaches it). The pattern matches HTTPS only, which is all a browser sends to a `.dev` name,
since the whole `.dev` domain is on the HSTS preload list.

**DNS.** The custom domain needs no DNS record of your own, TXT or otherwise: on a zone that Cloudflare
hosts, attaching a Custom Domain creates the DNS record and the certificate. The only TXT record the
project needs on kcoap.dev is the one through which the Central Portal verified the `dev.kcoap` namespace
on 6 November 2026 ([releasing](../docs/releasing.md#verifying-the-devkcoap-namespace)); keep it. It is
unrelated to the website, and neither gets in the way of the other.

### Behaviour that follows from the settings

- `html_handling: "auto-trailing-slash"`: `/start/` serves `start/index.html`, and `/start` redirects
  there with a 307. The repository links `https://kcoap.dev/sponsors` without a slash (README, SUPPORT.md,
  SPONSORS.md, FUNDING.yml, the issue template configuration), which therefore lands on `/sponsors/`;
  check it once after the first deploy.
- `not_found_handling: "404-page"`: a path with no file gets the nearest `404.html` up the tree, here
  always `/404.html`, with status 404. Its links are absolute, so it works at any depth.
- `_headers` sets the Content-Security-Policy and the other security headers on every path, and a
  one-day cache on each image by its exact path. A header that two rules both set would be sent once with
  the values joined by a comma, so no path matches two rules that set the same header, and no rule has a
  splat inside the path, which the documentation does not describe; `scripts/check-site` checks both. The
  comment at the top of `_headers` says why one CSP covers every page.
- Cache: the pages, the stylesheets and the scripts keep Cloudflare's default
  `Cache-Control: public, max-age=0, must-revalidate` with an ETag, since they keep their names between
  deploys and must match; the images may be cached for a day.
- Zone features that rewrite HTML at the edge would change what `scripts/check-site` checked. Email
  Address Obfuscation is on by default for a new zone; the two e-mail links sit between
  `<!--email_off-->` comments, and the feature can be turned off (Scrape Shield). Keep Bot Fight Mode, the
  JavaScript detections and Web Analytics' automatic setup off: each injects a script that the CSP refuses.

### After the first deploy

Check these once, and after a change to `_headers` or the zone's settings:

```bash
curl -sI https://kcoap.dev/sponsors          # 307 to /sponsors/
curl -sI https://kcoap.dev/no/such/page/     # 404, with the Content-Security-Policy and the other headers
curl -sI https://kcoap.dev/assets/site.css   # Cache-Control: public, max-age=0, must-revalidate
curl -sI https://kcoap.dev/assets/og-image.png   # Cache-Control: public, max-age=86400
curl -s https://kcoap.dev/sponsors/ | grep -c cdn-cgi   # 0: nothing injected
```

Then open `/` and `/releases/` in Chrome and Safari with the developer tools' console open: no
Content-Security-Policy violation, and the releases feed answers. The pages were checked in Firefox and
Chromium only.

### The documentation behind each setting

Read on 6 November 2026:

- Static assets, binding and keys: https://developers.cloudflare.com/workers/static-assets/binding/
- `not_found_handling: "404-page"`, `html_handling`, and assets-only Workers without `main`:
  https://developers.cloudflare.com/workers/static-assets/routing/static-site-generation/
- What `auto-trailing-slash` serves and redirects (307):
  https://developers.cloudflare.com/workers/static-assets/routing/advanced/html-handling/
- `_headers`: syntax, splats, joined values, `!` to remove, 100 rules, 2,000 characters a line, and the
  default `Cache-Control` and `ETag`: https://developers.cloudflare.com/workers/static-assets/headers/
- Wrangler configuration (`name`, `compatibility_date`, `assets` and its allowed values, `main` optional
  for assets-only Workers, `workers_dev` and its default, `preview_urls`, routes managed in the dashboard):
  https://developers.cloudflare.com/workers/wrangler/configuration/
- The build image and its Python version: https://developers.cloudflare.com/workers/ci-cd/builds/build-image/
- The www redirect (wildcard pattern, `${1}`, 301, query string kept; HTTPS only):
  https://developers.cloudflare.com/rules/url-forwarding/examples/redirect-www-to-root/ and
  https://developers.cloudflare.com/rules/url-forwarding/single-redirects/create-dashboard/
- Workers Builds, and the Worker name that must match: https://developers.cloudflare.com/workers/ci-cd/builds/
- Build settings (root directory, build command, deploy command, preview command):
  https://developers.cloudflare.com/workers/ci-cd/builds/configuration/
- Production branch and builds for other branches:
  https://developers.cloudflare.com/workers/ci-cd/builds/build-branches/
- Build watch paths: https://developers.cloudflare.com/workers/ci-cd/builds/build-watch-paths/
- Custom Domains, DNS records and certificates, and why `www` needs a redirect rule and a proxied placeholder
  record: https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

## When pull request #1 merges

1. In Cloudflare, set the production branch (Settings → Builds → Git branch) to `main`.
2. Point the site's repository links at `main`. Every link to a file uses the prefix
   `https://github.com/adihack/kcoap/blob/feat/protocol-core/` (or `tree/`), so one command changes them,
   together with the clone commands that name the branch:

   ```bash
   grep -rl 'feat/protocol-core' web/public | xargs sed -i -e 's#/blob/feat/protocol-core/#/blob/main/#g' -e 's#/tree/feat/protocol-core/#/tree/main/#g' -e 's#git clone --branch feat/protocol-core #git clone #g'
   ```

3. `grep -rn 'feat/protocol-core' web/public` then lists the sentences that explain the branch (the
   install steps on `/start/` and the platform guides); rewrite or remove them, run `scripts/check-site`
   and commit.
4. On GitHub, make the "Website" check required on `main` (Settings → Rules → Rulesets, or the branch
   protection rule), beside the DCO check, so a change that fails `scripts/check-site` cannot merge.

## Facts with a date

Some pages state the project's state on a given day. Update them when it changes:

- The release-freeze meter on the home page and on `/releases/`, with its legend, the table of work
  packages on `/releases/` and the date beside them (TEMPLATE.md, section 7).
- The "Before 0.1.0 is tagged" list and the static "No release yet" note on `/releases/`.
- The state of the work packages that the pages describe: TLS, the browser topology through libcoap's proxy,
  the Swift smoke test (passing in CI since its first run on 6 November 2026), post-quantum EDHOC on Android (not run on a device).
- The `lastmod` dates in `sitemap.xml`.
