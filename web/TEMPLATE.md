# Page template for kcoap.dev

Every page of the site is a plain HTML file in `web/public/`, served as a directory (`/start/jvm/` is
`web/public/start/jvm/index.html`). There is no build step and no include mechanism, so each page carries its
own copy of the head, the header and the footer below. Copy them exactly; change only the parts this file marks.
`web/public/index.html` is the reference implementation of everything here.

## Rules every page follows

- **No inline CSS or JavaScript.** The Content-Security-Policy forbids `<style>`, `style="..."`, inline
  `<script>` and `on*=` attributes. All CSS is in `/assets/site.css`, all JavaScript in `/assets/site.js`.
  SVG presentation attributes (`fill`, `stroke`, `transform`, `viewBox`) are fine.
- **One `h1` per page**, `lang="en"`, the skip link, `<main id="main">`.
- **Repository links** use exactly `https://github.com/adihack/kcoap/blob/feat/protocol-core/<path>` (or
  `tree/` for folders), so one replacement switches them to `main` after PR #1 merges.
- **The API reference** (https://adihack.github.io/kcoap/) is not online yet; say so wherever it is linked.
- **Wording** follows the brief: plain sentences, no superlatives, no "simply", "just" or "easy", nothing
  claimed that the code, tests or interop do not show, and unfinished work named as unfinished.
- **Size:** keep each page with its images under about 150 KB before compression.
- **Assets are not fingerprinted.** `site.css`, `site.js` and the images keep their names between deploys, so
  cache them with revalidation (for example `Cache-Control: public, max-age=0, must-revalidate` or a short
  `max-age`), not as immutable.

## 1. The head

Change the five marked values: title, description, canonical path, and the same path and texts in the Open
Graph and Twitter tags. The title is "Page name · kcoap" on every page except the home page.

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Getting started · kcoap</title>                                  <!-- CHANGE -->
<meta name="description" content="One or two plain sentences.">         <!-- CHANGE -->
<link rel="canonical" href="https://kcoap.dev/start/">                  <!-- CHANGE -->
<meta name="color-scheme" content="light dark">
<meta name="theme-color" content="#ffffff" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#0a1517" media="(prefers-color-scheme: dark)">
<link rel="icon" href="/favicon.ico" sizes="48x48">
<link rel="icon" href="/assets/kcoap-logo.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="stylesheet" href="/assets/site.css">
<script src="/assets/site.js"></script>
<meta property="og:type" content="website">
<meta property="og:site_name" content="kcoap">
<meta property="og:title" content="Getting started · kcoap">            <!-- CHANGE -->
<meta property="og:description" content="One or two plain sentences."> <!-- CHANGE -->
<meta property="og:url" content="https://kcoap.dev/start/">             <!-- CHANGE -->
<meta property="og:image" content="https://kcoap.dev/assets/og-image.png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="The kcoap logo and the words: CoAP, OSCORE and EDHOC for Kotlin Multiplatform.">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:title" content="Getting started · kcoap">            <!-- CHANGE -->
<meta name="twitter:description" content="One or two plain sentences."> <!-- CHANGE -->
<meta name="twitter:image" content="https://kcoap.dev/assets/og-image.png">
<meta name="twitter:image:alt" content="The kcoap logo and the words: CoAP, OSCORE and EDHOC for Kotlin Multiplatform.">
</head>
<body>
<a class="skip-link" href="#main">Skip to content</a>
```

`site.js` is loaded in the head without `defer` on purpose: it applies a remembered theme before the first
paint and adds the `js` class that the mobile menu and tabs rely on. Drop the `<!-- CHANGE -->` comments.

A page with a script of its own loads it right after `site.js`, with `defer` (only `/releases/` has one:
`<script src="/assets/releases.js" defer></script>`). `404.html` is served at every missing path, so it has
no canonical link and no `og:url`, and adds `<meta name="robots" content="noindex">` instead.

## 2. The header

Copy it as is. On the page the navigation item belongs to, add `aria-current="page"` to its link (every
`/start/...` page marks "Get started"; the home page marks none).

```html
<header class="site-header">
  <div class="container header-inner">
    <a class="brand" href="/" aria-label="kcoap home">
      <img src="/assets/kcoap-logo.svg" alt="" width="32" height="32">
      <span>kcoap</span>
    </a>
    <button class="icon-button nav-toggle" type="button" data-nav-toggle aria-controls="site-nav" aria-expanded="false" aria-label="Open menu" hidden><svg class="icon-menu" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M4 7h16M4 12h16M4 17h16"/></svg><svg class="icon-close" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M6 6l12 12M18 6 6 18"/></svg></button>
    <nav class="site-nav" id="site-nav" aria-label="Main">
      <ul>
        <li><a href="/start/">Get started</a></li>
        <li><a href="/features/">Features</a></li>
        <li><a href="/releases/">Releases</a></li>
        <li><a href="/sponsors/">Sponsors</a></li>
        <li><a class="nav-external" href="https://github.com/adihack/kcoap/blob/feat/protocol-core/docs/README.md">Docs <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M8 16 16 8M10 8h6v6"/></svg><span class="visually-hidden"> (on GitHub)</span></a></li>
      </ul>
    </nav>
    <div class="header-actions">
      <a class="icon-button header-github" href="https://github.com/adihack/kcoap"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="6" cy="5.5" r="2.25"/><circle cx="6" cy="18.5" r="2.25"/><circle cx="18" cy="8" r="2.25"/><path d="M6 7.75v8.5M18 10.25c0 4-5 3.5-10.5 7"/></svg><span class="header-github-label">GitHub</span><span class="visually-hidden"> repository</span></a>
      <button class="icon-button theme-toggle" type="button" data-theme-toggle hidden aria-label="Switch theme"><svg class="icon-moon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M20 14.25A8.25 8.25 0 0 1 9.75 4 8.25 8.25 0 1 0 20 14.25z"/></svg><svg class="icon-sun" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/></svg></button>
    </div>
  </div>
</header>
```

## 3. The page body

Pages other than the home page open with a page head, then sections. A typical page:

```html
<main id="main">
  <section class="page-head">
    <div class="container">
      <nav aria-label="Breadcrumb"><ol class="breadcrumb">
        <li><a href="/">Home</a></li>
        <li><a href="/start/">Get started</a></li>
        <li aria-current="page">JVM</li>
      </ol></nav>
      <p class="eyebrow">Get started</p>
      <h1>JVM servers and desktop</h1>
      <p class="lede">One or two sentences that say what this page gives the reader.</p>
    </div>
  </section>

  <section class="section">
    <div class="container">
      <div class="prose">
        <h2 id="install">Install</h2>
        <p>...</p>
      </div>
    </div>
  </section>

  <section class="section section-soft">
    <div class="container">...</div>
  </section>
</main>
```

Sections alternate between `.section` and `.section .section-soft`. Long text sits in a `.prose` block
(760 px measure) inside the `.container`, so it lines up with the page head on the left; do not put both
classes on one element. Wide content (grids, tables, figures) sits in `.container` directly.
`.container-narrow` keeps the page's left edge and limits its children to 820 px, for text-heavy sections.

## 4. The footer

Copy it as is, followed by `</body></html>`.

```html
<footer class="site-footer">
  <div class="container">
    <div class="footer-grid">
      <div class="footer-brand">
        <a class="brand" href="/"><img src="/assets/kcoap-logo.svg" alt="" width="32" height="32"><span>kcoap</span></a>
        <p>CoAP, OSCORE and EDHOC for Kotlin Multiplatform. In development: version <code>0.1.0-SNAPSHOT</code>, nothing released yet.</p>
      </div>
      <div>
        <p class="footer-heading">Site</p>
        <ul class="footer-links">
          <li><a href="/start/">Get started</a></li>
          <li><a href="/features/">Features</a></li>
          <li><a href="/releases/">Releases</a></li>
          <li><a href="/sponsors/">Sponsors</a></li>
        </ul>
      </div>
      <div>
        <p class="footer-heading">Platforms</p>
        <ul class="footer-links">
          <li><a href="/start/jvm/">JVM</a></li>
          <li><a href="/start/android/">Android</a></li>
          <li><a href="/start/apple/">iOS and macOS</a></li>
          <li><a href="/start/linux/">Linux</a></li>
          <li><a href="/start/browser/">Browsers</a></li>
          <li><a href="/start/node/">Node.js</a></li>
        </ul>
      </div>
      <div>
        <p class="footer-heading">Project</p>
        <ul class="footer-links">
          <li><a href="https://github.com/adihack/kcoap">GitHub repository</a></li>
          <li><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/docs/README.md">Documentation</a></li>
          <li><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/CHANGELOG.md">Changelog</a></li>
          <li><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/CONTRIBUTING.md">Contributing</a></li>
          <li><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/SECURITY.md">Security policy</a></li>
          <li><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/CODE_OF_CONDUCT.md">Code of Conduct</a></li>
        </ul>
      </div>
    </div>
    <div class="footer-legal">
      <p>© 2026 Adrian Sołtys and the kcoap contributors</p>
      <p><a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/LICENSE">Apache License 2.0</a></p>
      <p>kcoap™ is a trademark of Adrian Sołtys (<a href="https://github.com/adihack/kcoap/blob/feat/protocol-core/TRADEMARKS.md">trademark policy</a>)</p>
    </div>
  </div>
</footer>
</body>
</html>
```

## 5. Components

All classes are defined in `site.css`, whose header lists its sections.

### Layout

| Class | Use |
|---|---|
| `.container`, `.container-narrow` | page width with the side gutter (16 px on phones) |
| `.section`, `.section-soft`, `.section-tight` | vertical rhythm; soft has the tinted background |
| `.section-head`, `.section-head-center` | eyebrow + h2 + lede above a section's content |
| `.eyebrow` | small uppercase label with the amber block |
| `.lede`, `.muted` | larger intro text; secondary text |
| `.grid .grid-2` / `.grid-3` / `.grid-4` | responsive card grids |
| `.split`, `.split-reverse` | text beside a figure from 960 px; the reverse puts the figure first |
| `.prose` | long-form text with styled lists and headings |
| `.page-head`, `.breadcrumb` | the top of every page but the home page |

### Buttons and links

`<a class="btn btn-primary">`, `.btn-secondary`, `.btn-ghost`, size `.btn-lg`, in a `.btn-row`. An icon
SVG inside a button sits before or after the text. `.link-arrow` adds a trailing arrow to a text link.

### Badges for states

Use the checklist's words: `<span class="badge badge-done">done</span>`, `.badge-partial`, `.badge-todo`
("to do"), `.badge-deferred`. Add `.badge-dot` for a leading dot. A plain `.badge` is teal for neutral
labels.

### Cards

```html
<article class="card card-link">
  <div class="card-icon"><!-- 24x24 icon SVG --></div>
  <h3><a href="/features/#q-block">Q-Block, RFC 9177</a></h3>
  <p>Two or three sentences.</p>
</article>
```

`.card-link` makes the whole card clickable through the heading's link (one link per card). Without it a
card is static. `.card-foot` is a bottom line (for a `.link-arrow`).

### Callouts

```html
<div class="callout">                 <!-- teal: a note -->
  <span class="callout-title">Note</span>
  <p>...</p>
</div>
<div class="callout callout-status">  <!-- amber: not done yet, status, future -->
<div class="callout callout-warning"> <!-- red edge: security or data loss -->
```

### Tables

Wrap every table so it scrolls inside itself on phones. The wrapper is a named region that can take focus,
so a keyboard user can scroll it (site.js keeps only the wrappers that overflow in the tab order), and the
table is named by the same heading:

```html
<h2 id="protocols">The state of every protocol</h2>
<div class="table-wrap" role="region" tabindex="0" aria-labelledby="protocols">
  <table aria-labelledby="protocols">
    <thead><tr><th scope="col">Protocol</th><th scope="col">RFC</th><th scope="col">State</th></tr></thead>
    <tbody>
      <tr><th scope="row">Observe</th><td>7641</td><td><span class="badge badge-partial">partial</span> ...</td></tr>
    </tbody>
  </table>
</div>
```

### Code blocks

Give every block a label (a file name or "Terminal"): the copy button then sits in the label bar and never
covers code. An unlabelled block still works; it gets extra top padding for the button.

```html
<div class="code-block">
  <p class="code-block-label">build.gradle.kts</p>
  <pre tabindex="0"><code>...escaped code with token spans...</code></pre>
</div>
```

Every `<pre>` has `tabindex="0"`, so its code scrolls from the keyboard; site.js takes the ones that do not
overflow out of the tab order. Keep code lines to 80 characters: put a trailing comment above its line,
split long calls, and write version-catalog arrays one entry per line. Only a one-line TOML table such as
`kcoap-coap = { module = "...", version.ref = "kcoap" }` may be longer, since TOML 1.0 cannot break it.
`scripts/check-site` checks both. The copy button is named after the block's label ("Copy build.gradle.kts").

`site.js` adds the copy button to every `.code-block` (skip one with `data-no-copy`). Escape `<`, `>` and
`&` in code. Syntax colouring is optional and hand-written: wrap tokens in `<span class="tok-k">` (keyword),
`tok-s` (string), `tok-c` (comment), `tok-f` (function call), `tok-t` (type), `tok-n` (number). Shell
commands carry no `$` prompt, so a copy pastes cleanly.

### Tabs

```html
<div class="tabs" data-tabs data-tab-group="gradle" data-tabs-label="Build file format">
  <div class="tab-panel" data-tab-label="Kotlin DSL">
    <p class="tab-panel-title">Kotlin DSL</p>
    <div class="code-block">...</div>
  </div>
  <div class="tab-panel" data-tab-label="Version catalog">
    <p class="tab-panel-title">Version catalog</p>
    <div class="code-block">...</div>
  </div>
</div>
```

Without JavaScript every panel shows under its `.tab-panel-title`. With it, `site.js` builds an accessible
tab list (arrow keys, Home, End). Tab sets with the same `data-tab-group` switch together on a page and
remember the choice per visitor; use `gradle` for "Kotlin DSL" / "Version catalog" everywhere so a reader's
choice follows them from page to page. The labels must match exactly across a group.

### Steps, facts, checks, meter

- `<ol class="steps">` with an `<h3>` in each `<li>`: numbered setup steps (the number badge is CSS).
- `<dl class="facts">`: term and value pairs (minimum versions, coordinates).
- `<ul class="checks">`: a list with a teal check per item; `<li class="is-open">` gets an amber ring for an
  open item. The state is also text, for screen readers and forced colours: every item starts with
  `<span class="visually-hidden">Done: </span>` or, when open, `<span class="visually-hidden">Open: </span>`.
- `<ol class="meter">` of `<li class="meter-block is-done|is-active">`: progress drawn as blocks, with a
  `.meter-legend` list under it (the home page's release-freeze meter). Each block ends with its state in
  text, `A1<span class="visually-hidden">, merged</span>` (or `, in progress`, `, not started`); no `title`.

### Platform links

`<ul class="platform-strip">` of `<li><a class="platform-link" href="/start/jvm/">ICON<span><strong>JVM</strong><span>Servers and desktop</span></span></a></li>`:
one, two or three columns by width. The six icons (server, phone, apple, gateway, browser, terminal) are in
the home page's strip; copy them from there.

### Figures and diagrams

```html
<figure class="figure">
  <svg class="diagram diagram-narrow" viewBox="0 0 440 388" role="img"
       aria-labelledby="fig-name-title" aria-describedby="fig-name-desc">
    <title id="fig-name-title">Short title</title>
    <desc id="fig-name-desc">A full text version of what the diagram shows.</desc>
    ...
  </svg>
  <details class="diagram-text"><summary>Read the diagram as text</summary><p>The same text as the desc.</p></details>
  <figcaption>One or two sentences.</figcaption>
</figure>
```

- Draw diagrams 440 units wide: on phones the figure runs edge to edge and the diagram scales to about
  0.85. Below 600 px the small text classes grow (`d-sub` and `d-mono` to 14 units, the smallest to
  12.5-13.5) so labels stay near 11 px or more, so leave room for that in boxes and between lifelines, and
  check a new diagram at 360 px. Desktop caps `.diagram-narrow` at 520 px.
- Use `d-arrow-amber`, `d-head-amber` and `d-box-amber-soft` for amber lines: they draw with
  `--c-amber-line`, which keeps 3:1 against the light background; `--c-amber` is for fills under dark text.
- Colour comes only from classes, so diagrams follow the theme: boxes `d-box`, `d-box-2`, `d-box-soft`,
  `d-box-accent`, `d-box-amber`, `d-box-amber-soft`, `d-box-dashed`, `d-box-lost`; lines `d-line`,
  `d-line-soft`, `d-lifeline`, `d-arrow`, `d-arrow-accent`, `d-arrow-amber`, `d-arrow-lost`; arrow heads
  in `<marker>`s with `d-head`, `d-head-accent`, `d-head-amber`; text `d-title`, `d-big`, `d-sub`,
  `d-small`, `d-mono`, `d-mono-small`, `d-caps`, `d-block-num`, `d-on-accent`, `d-on-accent-2`, `d-on-amber`,
  `d-accent-text`, `d-amber-text`, `d-lost-text`; fills `d-fill-accent`, `d-fill-amber`, `d-fill-on-accent`,
  `d-fill-lost`, `d-row-alt`; icons `d-icon-accent`, `d-icon-on-accent`; marks `d-check`, `d-stroke-lost`.
- Prefix every id inside an SVG with `fig-<name>-` (titles, descriptions, markers) so ids stay unique on the
  page. The five home-page diagrams (`fig-stack`, `fig-edhoc`, `fig-qblock`, `fig-platforms`,
  `fig-interop`) can be copied as they are to `/features/`; rename their ids if two copies share a page.
- Each figure gets a text alternative twice: the `<desc>`, which `aria-describedby` reads out (the name is the
  title alone), and a visible `<details class="diagram-text">` with the same text, or a table where the
  diagram is one, as for the interop matrix.

### Icons

Icons are drawn for this site: 24×24 viewBox, `fill="none" stroke="currentColor" stroke-width="1.75"
stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"`. Copy them from
`index.html`. Do not use third-party icon sets or brand logos (no GitHub, Android, Apple or Kotlin marks):
the GitHub link uses the repository glyph from the header.

### Inner pages

| Class | Use |
|---|---|
| `ul.fact-tiles` > `li.fact-tile` > `.fact-tile-label` + `.fact-tile-value` | four key facts under a page head’s lede |
| `.container.doc-layout` > `nav.toc` + `div.doc-body.prose` | a guide: the text with an “On this page” list (`p.toc-title` and an `ol` of `#id` links to its `h2`s), beside it from 1100 px and above it below that |
| `p.code-note` with the check-circle icon, or `p.code-note.code-note-names` with the info icon | under a code block: how the sample was checked (compiled in CI, or names checked only) |
| `.code-block.code-block-output` with `data-no-copy` | expected output of a sample, labelled “Output” |
| `nav.pager` > `a.pager-prev` / `a.pager-next` > `.pager-label` + `.pager-title` | previous and next guide at the end of a guide |
| `ul.jump-links` | chips that jump to the sections of a long page |
| `table.matrix` | a comparison table whose row headers stick while it scrolls |
| `table.table-stack` with `data-label` on cells | a table whose rows become stacked blocks on phones; each cell’s label comes from `data-label` |
| `.state-col`, `.row-ids` | a narrow column of state badges; the checklist row ids under a cell, as a link |
| `dl.qa-list` | questions and answers, such as troubleshooting |
| `.feature-icon`, `.feature-code`, `.evidence` (`.evidence-title`) | a feature section on `/features/`: its icon, a code sample under the split, the box of tests and open items |
| `[data-release-feed]`, `.release-list`, `.release-item`, `.release-meta`, `.release-notes`, `.release-loading`, `.block-loader` | the GitHub releases on `/releases/`, rendered by `releases.js` |
| `.notfound`, `.notfound-code`, `.diagram-lost` | the 404 page |

Diagrams added for these pages: `fig-vectors`, `fig-failclosed`, `fig-sansio`, `fig-crypto` (on `/features/`)
and `fig-release` (on `/releases/`); `d-head-lost` colours an arrow head in the “lost” red.

## 6. Anchors the home page links to

`/features/` must have these ids, one per highlight card: `vectors`, `interop`, `post-quantum`, `q-block`,
`fail-closed`, `sans-io`, `platforms`, `crypto`, `build`.

## 7. Dated facts to update

The home page's release-freeze meter shows the work packages as of 6 November 2026 (12 merged; C3 not
started; `docs/releasing.md` "Release freeze"). Update the blocks, their hidden state text, the legend and
the date when C3 starts or merges, and `/releases/` with them.
On `/releases/`, that means the meter, the table of work packages, the fact tile “12 of 13 merged”, the
“Before 0.1.0 is tagged” list and the static “No release yet” note. `web/README.md` lists the same places
and the other states the pages describe.
