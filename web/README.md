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
        ├── site.js      theme toggle, mobile menu, copy buttons, tabs
        ├── releases.js  /releases/ only: the latest releases from the GitHub API
        └── kcoap-logo.svg, og-image.png
```

Every page is a directory with an `index.html`, served with a trailing slash. Each page carries its own
copy of the head, header and footer; [TEMPLATE.md](TEMPLATE.md) has them, the rules every page follows
(no inline CSS or JavaScript, one `h1`, repository links with one fixed prefix) and every component class.

## Preview and check

```bash
python3 -m http.server -d web/public 8080   # then open http://localhost:8080/
scripts/check-site                          # links, anchors, head tags, inline code, size, sitemap
```

The local server does not apply `_headers` and answers unknown paths with its own 404 page; Cloudflare
serves `404.html` instead. `scripts/check-site` (Python 3, standard library only) runs in CI as the
"Website" job. It fails on a broken internal link or `#anchor`; a page without its title, description,
canonical URL, Open Graph tags, `lang` or exactly one `h1`; an image without `alt`; inline scripts,
`<style>` elements, `style` attributes or `on*` handlers; a resource loaded from anywhere but the site
itself (and the GitHub API from `releases.js`); a page heavier than 150 KB with its images; and a sitemap
that does not match the pages.

## Cloudflare settings

The Worker `kcoap` is connected to the GitHub repository with Workers Builds (Workers & Pages → kcoap →
Settings → Builds):

| Setting | Value |
|---|---|
| Git repository | `adihack/kcoap` |
| Git branch (production) | `feat/protocol-core` until pull request #1 merges, then `main` |
| Root directory | `web` |
| Build command | empty |
| Deploy command | `npx wrangler deploy` (the default) |
| Non-production branch command | `npx wrangler versions upload` |
| Build watch paths (optional) | include `web/*`, so only changes to the site start a build |

The Worker's name in the dashboard must stay `kcoap`, the `name` in `wrangler.jsonc`, or the build fails.
Current Cloudflare documentation calls the non-production setting **Preview command** and gives it the
default `npx wrangler preview`, which creates a Preview; `npx wrangler versions upload` creates a version
with its own URL instead. Neither touches production.

`wrangler.jsonc` sets only `name`, `compatibility_date` and `assets` (`directory`, `not_found_handling`,
`html_handling`). It has no `main`, because the Worker serves assets only, and no `routes`: the custom
domain is attached in the dashboard (Settings → Domains & Routes). Without `routes`, Wrangler's
`workers_dev` setting defaults to true, so the site also answers on the account's `workers.dev`
subdomain; the canonical links in every page point search engines at kcoap.dev. Add `"workers_dev": false`
to turn that off.

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
- `_headers` sets the Content-Security-Policy and the other security headers on every path, and the cache
  rules per file type. A header that two rules both set would be sent once with the values joined by a
  comma, so the rules never overlap; the comment at the top of `_headers` says why one CSP covers every
  page. Whether Cloudflare applies `_headers` to the 404 page as well is not stated in its documentation;
  check the headers of a missing path once after deploy.
- Cache: HTML keeps Cloudflare's default `Cache-Control: public, max-age=0, must-revalidate` with an
  ETag. The assets are not fingerprinted, so CSS and JavaScript revalidate on every load as well; images
  may be cached for a day.

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
- Custom Domains, DNS records and certificates:
  https://developers.cloudflare.com/workers/configuration/routing/custom-domains/

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
- The "Before 0.1.0 is tagged" list and the "No release yet" note on `/releases/`, and the date in the
  error message of `assets/releases.js`.
- The `lastmod` dates in `sitemap.xml`.
