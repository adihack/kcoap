/*
 * kcoap.dev /releases/: lists kcoap's latest releases from the GitHub REST API.
 *
 * Progressive enhancement: without this script the page shows its static "no release yet" note, which is
 * true until the first release. With it, the feed shows a loading state, then the releases GitHub lists,
 * the honest "no release yet" state when the list is empty, or an error state when GitHub cannot be asked
 * (network, rate limit of 60 requests an hour per visitor, or an unexpected answer).
 *
 * The request is anonymous: no credentials, no cookies, and only the Accept header, which needs no CORS
 * preflight. The Content-Security-Policy allows connect-src https://api.github.com and nothing else.
 * Everything from GitHub is inserted as text, never as HTML.
 */
(function () {
  "use strict";

  var API = "https://api.github.com/repos/adihack/kcoap/releases?per_page=10";
  var RELEASES = "https://github.com/adihack/kcoap/releases";
  var SHOWN = 5;
  var TIMEOUT_MS = 10000;

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function link(href, text, className) {
    var a = el("a", className, text);
    a.href = href;
    return a;
  }

  function callout(kind, title, paragraphs) {
    var box = el("div", "callout" + (kind ? " callout-" + kind : ""));
    box.appendChild(el("span", "callout-title", title));
    paragraphs.forEach(function (parts) {
      var p = el("p");
      parts.forEach(function (part) {
        p.appendChild(typeof part === "string" ? document.createTextNode(part) : part);
      });
      box.appendChild(p);
    });
    return box;
  }

  function replace(feed, node) {
    while (feed.firstChild) feed.removeChild(feed.firstChild);
    feed.appendChild(node);
  }

  function showLoading(feed) {
    var p = el("p", "release-loading");
    p.setAttribute("role", "status");
    var loader = el("span", "block-loader");
    loader.setAttribute("aria-hidden", "true");
    loader.appendChild(el("span"));
    loader.appendChild(el("span"));
    loader.appendChild(el("span"));
    p.appendChild(loader);
    p.appendChild(document.createTextNode("Asking GitHub for kcoap’s releases…"));
    feed.setAttribute("aria-busy", "true");
    replace(feed, p);
  }

  function showEmpty(feed) {
    replace(feed, callout("status", "No release yet", [
      ["GitHub lists no kcoap release yet; this was checked as the page loaded. The first release, 0.1.0, ",
        "follows the release freeze below. Until then, build kcoap from source as ",
        link("/start/", "Get started"), " shows."],
      [link(RELEASES, "GitHub Releases"), " will list it."]
    ]));
  }

  function showError(feed, rateLimited) {
    var why = rateLimited
      ? "GitHub’s API answers each visitor 60 times an hour, and that limit is used up for now."
      : "GitHub’s API could not be reached, or did not answer with a list of releases.";
    replace(feed, callout("", "Could not ask GitHub", [
      [why + " As of 6 November 2026 kcoap had no release; ", link(RELEASES, "GitHub Releases"),
        " has the current list."]
    ]));
  }

  function formatDate(iso) {
    var date = new Date(iso);
    if (!iso || isNaN(date.getTime())) return "";
    try {
      return date.toLocaleDateString("en-GB", { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" });
    } catch (e) {
      return iso.slice(0, 10);
    }
  }

  /* The first few entries of generated release notes, as plain text: "* Title by @user in https://…". */
  function noteLines(body) {
    if (typeof body !== "string") return [];
    var lines = [];
    body.split(/\r?\n/).forEach(function (line) {
      var m = /^\s*[*-]\s+(.*)$/.exec(line);
      if (!m || lines.length >= 3) return;
      var text = m[1].replace(/\s+by @\S+\s+in\s+https?:\/\/\S+\s*$/, "").replace(/[*_`]/g, "").trim();
      if (text) lines.push(text.length > 140 ? text.slice(0, 139) + "…" : text);
    });
    return lines;
  }

  function safeUrl(url) {
    return typeof url === "string" && url.indexOf("https://github.com/adihack/kcoap/") === 0 ? url : RELEASES;
  }

  function showList(feed, releases) {
    var list = el("ol", "release-list");
    releases.slice(0, SHOWN).forEach(function (release) {
      var item = el("li", "release-item");
      var title = el("h3");
      var name = release.name && String(release.name).trim() ? String(release.name) : release.tag_name;
      title.appendChild(link(safeUrl(release.html_url), name));
      if (release.prerelease) {
        title.appendChild(document.createTextNode(" "));
        title.appendChild(el("span", "badge badge-partial", "pre-release"));
      }
      item.appendChild(title);
      var meta = el("p", "release-meta");
      var when = formatDate(release.published_at || release.created_at);
      meta.textContent = release.tag_name + (when ? " · " + when : "");
      item.appendChild(meta);
      var lines = noteLines(release.body);
      if (lines.length) {
        var notes = el("ul", "release-notes");
        lines.forEach(function (line) {
          notes.appendChild(el("li", null, line));
        });
        item.appendChild(notes);
      }
      list.appendChild(item);
    });
    var wrap = el("div");
    wrap.appendChild(list);
    var more = el("p", "muted");
    more.appendChild(link(RELEASES, "Every release, with its notes, on GitHub", "link-arrow"));
    wrap.appendChild(more);
    replace(feed, wrap);
  }

  function init() {
    var feed = document.querySelector("[data-release-feed]");
    if (!feed || !window.fetch || !window.Promise) return;
    showLoading(feed);
    var controller = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () {
      if (controller) controller.abort();
    }, TIMEOUT_MS);
    var options = { headers: { Accept: "application/vnd.github+json" }, credentials: "omit", referrerPolicy: "no-referrer" };
    if (controller) options.signal = controller.signal;
    fetch(API, options)
      .then(function (response) {
        if (!response.ok) {
          var limited = response.status === 429 ||
            (response.status === 403 && response.headers.get("x-ratelimit-remaining") === "0");
          throw new Error(limited ? "rate-limited" : "HTTP " + response.status);
        }
        return response.json();
      })
      .then(function (data) {
        if (!Array.isArray(data)) throw new Error("unexpected answer");
        var releases = data.filter(function (r) {
          return r && !r.draft && typeof r.tag_name === "string" && r.tag_name;
        });
        if (releases.length) showList(feed, releases);
        else showEmpty(feed);
      })
      .catch(function (error) {
        showError(feed, error && error.message === "rate-limited");
      })
      .then(function () {
        clearTimeout(timer);
        feed.removeAttribute("aria-busy");
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
