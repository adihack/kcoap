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
        ├── site.css     the whole design system, light and dark
        ├── site.js      theme toggle, mobile menu, copy buttons, tabs, scroll regions
        ├── releases.js  /releases/ only: the latest releases from the GitHub API
        └── kcoap-logo.svg, og-image.png
```

Every page is a directory with an `index.html`, served with a trailing slash. Each page carries its own
copy of the head, header and footer; [TEMPLATE.md](TEMPLATE.md) has them, the rules every page follows
(no inline CSS or JavaScript, one `h1`, repository links with one fixed prefix) and every component class.

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
- a page over 150 KB with its images, or over 180 KB with everything it loads;
- `_headers` beyond Cloudflare's limits, with a splat inside a path, without the security headers or with any
  other CSP, or giving one path the same header twice; a `robots.txt` that keeps crawlers out; a sitemap that
  does not match the pages.

## Cloudflare settings

The Worker `kcoap` is connected to the GitHub repository with Workers Builds (Workers & Pages → kcoap →
Settings → Builds):

| Setting | Value |
|---|---|
| Git repository | `adihack/kcoap` |
| Git branch (production) | `feat/protocol-core` until pull request #1 merges, then `main` |
| Root directory | `web` |
| Build command | empty |
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

**What deploys.** Workers Builds deploys every push to the production branch, whether or not the GitHub
"Website" check passes: a commit that fails `scripts/check-site` still goes live. Run the script before
pushing a change to `web/`. To make Cloudflare refuse such a commit, set the build command to
`python3 ../scripts/check-site` (the build clones the whole repository, the root directory is `web`, and the
build image has Python 3); it builds nothing, so the site keeps no build step, but a failed check stops the
deploy. That is the owner's choice: the settings above leave the build command empty.

The Worker's name in the dashboard must stay `kcoap`, the `name` in `wrangler.jsonc`, or the build fails.
Current Cloudflare documentation calls the non-production setting **Preview command** and gives it the
default `npx wrangler preview`, which creates a Preview; `npx wrangler versions upload` creates a version
with its own URL instead. Neither touches production.

`wrangler.jsonc` sets only `name`, `compatibility_date` and `assets` (`directory`, `not_found_handling`,
`html_handling`). It has no `main`, because the Worker serves assets only, and no `routes`: the custom
domain is attached in the dashboard (Settings → Domains & Routes). Without `routes`, Wrangler's
`workers_dev` setting defaults to true, so the site also answers on the account's `workers.dev`
subdomain; the canonical links in every page point search engines at kcoap.dev. To turn that copy off,
declare the domain in `wrangler.jsonc` instead of adding `"workers_dev": false` alone:
`"routes": [{ "pattern": "kcoap.dev", "custom_domain": true }]` makes `workers_dev` default to false, and
`"preview_urls": true` keeps the URLs of non-production builds, since its initial value depends on
`workers_dev`.
Without `routes`, `"workers_dev": false` would leave the configuration with no deploy target of its own.

**www.kcoap.dev** does not resolve, and a Worker on kcoap.dev does not receive it. To send it to
kcoap.dev, add a proxied `AAAA` record `www` → `100::` and a Redirect Rule from `www.kcoap.dev/*` to
`https://kcoap.dev/${1}` (Custom Domains documentation, "www").

**DNS.** The custom domain needs no DNS record of your own, TXT or otherwise: on a zone that Cloudflare
hosts, attaching a Custom Domain creates the DNS record and the certificate. The only TXT record the
project needs on kcoap.dev is the one that verifies the `dev.kcoap` namespace for Maven Central
([releasing](../docs/releasing.md#verifying-the-devkcoap-namespace)); it is unrelated to the website.

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
- Cache: the pages, `site.css` and the scripts keep Cloudflare's default
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
  for assets-only Workers, `workers_dev`): https://developers.cloudflare.com/workers/wrangler/configuration/
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

## Facts with a date

Some pages state the project's state on a given day. Update them when it changes:

- The release-freeze meter on the home page and on `/releases/`, with its legend, the table of work
  packages on `/releases/` and the date beside them (TEMPLATE.md, section 7).
- The "Before 0.1.0 is tagged" list and the static "No release yet" note on `/releases/`.
- The state of the work packages that the pages describe: TLS, the browser topology through libcoap's proxy,
  the Swift smoke test (pending its first CI run), post-quantum EDHOC on Android (not run on a device).
- The `lastmod` dates in `sitemap.xml`.
