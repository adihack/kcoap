/*
 * kcoap.dev /releases/: lists kcoap's latest releases from the GitHub REST API.
 *
 * Progressive enhancement: without this script the page shows its static note on 0.1.0, the first release.
 * With it, a status line under that note says GitHub is being asked, then that GitHub listed no release
 * (wrong since 0.1.0, so the line points at the releases page), or why GitHub could not be asked (network,
 * the rate limit of 60 requests an hour per visitor, a timeout, an unexpected answer). Only when GitHub
 * lists releases does the list replace the note. The note and the status line keep their place, so the
 * page does not jump.
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

  function status(feed, parts) {
    var line = feed.querySelector("[data-release-status]");
    if (!line) {
      line = el("p", "release-status");
      line.setAttribute("data-release-status", "");
      line.setAttribute("role", "status");
      feed.appendChild(line);
    }
    while (line.firstChild) line.removeChild(line.firstChild);
    parts.forEach(function (part) {
      line.appendChild(typeof part === "string" ? document.createTextNode(part) : part);
    });
  }

  function showLoading(feed) {
    var loader = el("span", "block-loader");
    loader.setAttribute("aria-hidden", "true");
    loader.appendChild(el("span"));
    loader.appendChild(el("span"));
    loader.appendChild(el("span"));
    status(feed, [loader, "Asking GitHub for kcoap’s releases…"]);
  }

  function showEmpty(feed) {
    status(feed, ["GitHub’s API listed no release just now. ", link(RELEASES, "GitHub Releases"),
      " has the current list."]);
  }

  function showError(feed, rateLimited) {
    var why = rateLimited
      ? "GitHub’s API answers each visitor 60 times an hour, and that limit is used up for now."
      : "GitHub’s API could not be reached in time, or did not answer with a list of releases.";
    status(feed, [why + " ", link(RELEASES, "GitHub Releases"), " has the current list."]);
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

  /* The first few list items of a release body, as plain text: those of the changelog summary, then GitHub’s
     generated "* Title by @user in https://…". */
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
    var more = el("p", "muted");
    more.appendChild(link(RELEASES, "Every release, with its notes, on GitHub", "link-arrow"));
    while (feed.firstChild) feed.removeChild(feed.firstChild);
    feed.appendChild(list);
    feed.appendChild(more);
    status(feed, ["The latest releases, as GitHub listed them when the page loaded."]);
  }

  function init() {
    var feed = document.querySelector("[data-release-feed]");
    if (!feed || !window.fetch || !window.Promise) return;
    showLoading(feed);
    /* The first outcome wins: the answer, a failure, or the timeout, which needs no AbortController. */
    var settled = false;
    function settle(show) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      show();
    }
    var controller = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () {
      if (controller) controller.abort();
      settle(function () { showError(feed, false); });
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
        settle(function () {
          if (releases.length) showList(feed, releases);
          else showEmpty(feed);
        });
      })
      .catch(function (error) {
        settle(function () { showError(feed, error && error.message === "rate-limited"); });
      });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
