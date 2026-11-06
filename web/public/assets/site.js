/*
 * kcoap.dev: theme toggle, mobile menu, copy buttons, tabs and scroll regions.
 *
 * Progressive enhancement: every page works without this file. It is loaded in <head> without `defer` so
 * that a remembered theme is applied before the first paint; everything that needs the DOM waits for
 * DOMContentLoaded. Browser storage is optional: every read and write is wrapped in try/catch.
 */
(function () {
  "use strict";

  var root = document.documentElement;
  var THEME_KEY = "kcoap-theme";
  var TAB_KEY_PREFIX = "kcoap-tab-";
  var THEME_COLORS = { light: "#ffffff", dark: "#0a1517" };

  root.classList.add("js");

  function readStorage(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (e) {
      return null;
    }
  }

  function writeStorage(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (e) {
      /* Storage blocked or full: the choice lasts for this page only. */
    }
  }

  /* ---- Theme ------------------------------------------------------------------------------------- */

  var darkQuery = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  var savedTheme = readStorage(THEME_KEY);
  if (savedTheme === "light" || savedTheme === "dark") {
    root.setAttribute("data-theme", savedTheme);
  }

  function currentTheme() {
    var chosen = root.getAttribute("data-theme");
    if (chosen === "light" || chosen === "dark") return chosen;
    return darkQuery && darkQuery.matches ? "dark" : "light";
  }

  function syncThemeColor() {
    var chosen = root.getAttribute("data-theme");
    var metas = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < metas.length; i++) {
      var meta = metas[i];
      if (!meta.hasAttribute("data-default")) meta.setAttribute("data-default", meta.getAttribute("content") || "");
      meta.setAttribute("content", chosen ? THEME_COLORS[chosen] : meta.getAttribute("data-default"));
    }
  }

  function setUpThemeToggle() {
    var buttons = document.querySelectorAll("[data-theme-toggle]");
    function label() {
      var next = currentTheme() === "dark" ? "light" : "dark";
      for (var i = 0; i < buttons.length; i++) {
        buttons[i].setAttribute("aria-label", "Switch to " + next + " theme");
        buttons[i].setAttribute("title", "Switch to " + next + " theme");
      }
    }
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].hidden = false;
      buttons[i].addEventListener("click", function () {
        var next = currentTheme() === "dark" ? "light" : "dark";
        root.setAttribute("data-theme", next);
        writeStorage(THEME_KEY, next);
        syncThemeColor();
        label();
      });
    }
    if (darkQuery && darkQuery.addEventListener) darkQuery.addEventListener("change", label);
    syncThemeColor();
    label();
  }

  /* ---- Mobile menu ------------------------------------------------------------------------------- */

  function setUpMenu() {
    var toggle = document.querySelector("[data-nav-toggle]");
    var nav = toggle && document.getElementById(toggle.getAttribute("aria-controls"));
    if (!toggle || !nav) return;
    var wide = window.matchMedia ? window.matchMedia("(min-width: 880px)") : null;

    function setOpen(open, returnFocus) {
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      toggle.setAttribute("aria-label", open ? "Close menu" : "Open menu");
      nav.classList.toggle("is-open", open);
      if (!open && returnFocus) toggle.focus();
    }

    toggle.hidden = false;
    setOpen(false, false);
    toggle.addEventListener("click", function () {
      var open = toggle.getAttribute("aria-expanded") !== "true";
      setOpen(open, false);
      if (open) {
        var first = nav.querySelector("a");
        if (first) first.focus();
      }
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && toggle.getAttribute("aria-expanded") === "true") setOpen(false, true);
    });
    nav.addEventListener("click", function (event) {
      if (event.target.closest && event.target.closest("a")) setOpen(false, false);
    });
    if (wide && wide.addEventListener) {
      wide.addEventListener("change", function (event) {
        if (event.matches) setOpen(false, false);
      });
    }
  }

  /* ---- Copy buttons ------------------------------------------------------------------------------ */

  var COPY_ICON =
    '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/>' +
    '<path d="M5 15V6a2 2 0 0 1 2-2h9"/></svg>';

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var area = document.createElement("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.className = "visually-hidden";
      document.body.appendChild(area);
      area.select();
      var ok = false;
      try {
        ok = document.execCommand("copy");
      } catch (e) {
        ok = false;
      }
      document.body.removeChild(area);
      if (ok) resolve();
      else reject(new Error("copy failed"));
    });
  }

  /* The name a copy button reads out: the block's file name, or its tab's label. */
  function blockName(block) {
    var label = block.querySelector(".code-block-label");
    if (label && label.textContent.trim()) return label.textContent.trim();
    var panel = block.closest ? block.closest(".tab-panel") : null;
    if (panel && panel.getAttribute("data-tab-label")) return panel.getAttribute("data-tab-label");
    return "code";
  }

  function setUpCopyButtons() {
    var live = document.createElement("div");
    live.className = "visually-hidden";
    live.setAttribute("aria-live", "polite");
    document.body.appendChild(live);

    var blocks = document.querySelectorAll(".code-block");
    for (var i = 0; i < blocks.length; i++) {
      (function (block) {
        var code = block.querySelector("pre code") || block.querySelector("pre");
        if (!code || block.hasAttribute("data-no-copy")) return;
        var button = document.createElement("button");
        button.type = "button";
        button.className = "copy-button";
        button.innerHTML = COPY_ICON + "<span>Copy</span>";
        button.setAttribute("aria-label", "Copy " + blockName(block));
        var text = button.querySelector("span");
        var timer = null;
        button.addEventListener("click", function () {
          copyText(code.textContent.replace(/\n$/, "")).then(
            function () {
              text.textContent = "Copied";
              button.classList.add("is-copied");
              live.textContent = "Code copied to clipboard";
            },
            function () {
              text.textContent = "Press Ctrl+C";
              live.textContent = "Copying failed; select the code and copy it by hand";
            }
          );
          clearTimeout(timer);
          timer = setTimeout(function () {
            text.textContent = "Copy";
            button.classList.remove("is-copied");
          }, 2000);
        });
        block.appendChild(button);
      })(blocks[i]);
    }
  }

  /* ---- Tabs -------------------------------------------------------------------------------------- */

  var tabCount = 0;
  var groups = {};

  function selectInGroup(group, label, origin) {
    var members = groups[group] || [];
    for (var i = 0; i < members.length; i++) {
      if (members[i] !== origin) members[i].select(label, false, false);
    }
  }

  function setUpTabs() {
    var sets = document.querySelectorAll("[data-tabs]");
    for (var s = 0; s < sets.length; s++) {
      (function (set) {
        var panels = [];
        for (var c = 0; c < set.children.length; c++) {
          if (set.children[c].classList.contains("tab-panel")) panels.push(set.children[c]);
        }
        if (panels.length < 2) return;
        var group = set.getAttribute("data-tab-group");
        var list = document.createElement("div");
        list.className = "tab-list";
        list.setAttribute("role", "tablist");
        list.setAttribute("aria-label", set.getAttribute("data-tabs-label") || "Variants");
        var tabs = [];

        panels.forEach(function (panel, index) {
          var id = ++tabCount;
          var tab = document.createElement("button");
          tab.type = "button";
          tab.className = "tab";
          tab.id = "tab-" + id;
          tab.setAttribute("role", "tab");
          tab.textContent = panel.getAttribute("data-tab-label") || "Tab " + (index + 1);
          if (!panel.id) panel.id = "tab-panel-" + id;
          tab.setAttribute("aria-controls", panel.id);
          panel.setAttribute("role", "tabpanel");
          panel.setAttribute("aria-labelledby", tab.id);
          panel.setAttribute("tabindex", "0");
          tab.addEventListener("click", function () {
            api.select(tab.textContent, true, true);
          });
          tab.addEventListener("keydown", function (event) {
            var next = null;
            if (event.key === "ArrowRight") next = tabs[(index + 1) % tabs.length];
            else if (event.key === "ArrowLeft") next = tabs[(index - 1 + tabs.length) % tabs.length];
            else if (event.key === "Home") next = tabs[0];
            else if (event.key === "End") next = tabs[tabs.length - 1];
            if (next) {
              event.preventDefault();
              api.select(next.textContent, true, true);
              next.focus();
            }
          });
          tabs.push(tab);
          list.appendChild(tab);
        });

        var api = {
          select: function (label, remember, propagate) {
            var found = false;
            for (var i = 0; i < tabs.length; i++) if (tabs[i].textContent === label) found = true;
            if (!found) return;
            for (var j = 0; j < tabs.length; j++) {
              var on = tabs[j].textContent === label;
              tabs[j].setAttribute("aria-selected", on ? "true" : "false");
              tabs[j].tabIndex = on ? 0 : -1;
              panels[j].hidden = !on;
            }
            if (group && remember) writeStorage(TAB_KEY_PREFIX + group, label);
            updateScrollRegions();
            if (group && propagate) selectInGroup(group, label, api);
          }
        };

        set.insertBefore(list, set.firstChild);
        set.classList.add("tabs-ready");
        if (group) (groups[group] = groups[group] || []).push(api);
        api.select(tabs[0].textContent, false, false);
        var remembered = group ? readStorage(TAB_KEY_PREFIX + group) : null;
        if (remembered) api.select(remembered, false, false);
      })(sets[s]);
    }
  }

  /* ---- Scroll regions ---------------------------------------------------------------------------- */

  /*
   * Code blocks and table wrappers carry tabindex="0" in the markup, so their content can be scrolled from
   * the keyboard without this file. Here only the ones that overflow stay in the tab order.
   */
  var scrollRegions = null;

  function updateScrollRegions() {
    if (!scrollRegions) return;
    for (var i = 0; i < scrollRegions.length; i++) {
      var region = scrollRegions[i];
      if (region === document.activeElement || !region.getClientRects().length) continue;
      region.tabIndex = region.scrollWidth > region.clientWidth + 1 ? 0 : -1;
    }
  }

  function setUpScrollRegions() {
    scrollRegions = document.querySelectorAll(".code-block pre[tabindex], .table-wrap[tabindex]");
    updateScrollRegions();
    var timer = null;
    window.addEventListener("resize", function () {
      clearTimeout(timer);
      timer = setTimeout(updateScrollRegions, 150);
    });
    document.addEventListener("toggle", updateScrollRegions, true);
  }

  function init() {
    setUpThemeToggle();
    setUpMenu();
    setUpTabs();
    setUpCopyButtons();
    setUpScrollRegions();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
