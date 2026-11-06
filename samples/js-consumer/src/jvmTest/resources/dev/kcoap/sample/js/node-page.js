// The browser sample's production bundle on Node.js, for NodePageTest: a stand-in for the few page objects the
// bundle touches (window.location, fetch relative to the page, document.getElementById, and the script element
// webpack's runtime reads its public path from), then the bundle itself, unchanged. Node.js has the browsers'
// fetch, WebSocket and WebCrypto. It also has `process`, which browsers do not, and by which ktor's Js engine
// would take the ws package instead of the WebSocket: the bundle runs without it, so it takes the browser's path.
//
//   node node-page.js <path of js-consumer.js> <origin of the sample server>
const nodeProcess = process;
const [bundle, origin] = nodeProcess.argv.slice(2);
const page = new URL(origin + "/");
const lights = { textContent: "" };

globalThis.window = {
  location: { protocol: page.protocol, host: page.host },
  fetch: (path, init) => fetch(new URL(path, page), init),
};
globalThis.document = {
  getElementById: (id) => (id === "lights" ? lights : null),
  currentScript: { src: new URL("js-consumer.js", page).href, tagName: "SCRIPT" },
  getElementsByTagName: () => [],
};
delete globalThis.process;

require(bundle);

// The page reports to the server by itself; this only ends the process once it has shown an outcome.
const started = Date.now();
const timer = setInterval(() => {
  if (lights.textContent !== "" || Date.now() - started > 60000) {
    clearInterval(timer);
    console.log("page shows: " + (lights.textContent || "nothing within 60 s"));
    setTimeout(() => nodeProcess.exit(lights.textContent === "on" ? 0 : 1), 1000);
  }
}, 100);
