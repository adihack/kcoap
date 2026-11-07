# kcoap-transport-ktor-websocket

The WebSocket client transport for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor client. It
builds for every kcoap target (the UNIVERSAL tier), browsers and Node.js included, and is common code
except for one line per platform that names the default client's engine. WebSockets are the one
transport a browser allows, so this is how a browser reaches CoAP.

The default client (`KtorWebSocketConnector()`) runs on the engine this module brings for the platform.
It names that engine instead of leaving the choice to ktor's lookup, which on native takes whichever
linked engine registered first: an iOS app that also links ktor-client-cio for its own HTTP calls still
gets URLSession here.

| Platform | Engine | Why | `coaps+ws` |
|---|---|---|---|
| JVM, Android | CIO | ktor's engine on plain sockets | no: CIO's TLS, ktor-network-tls, accepts a certificate without DNS names for any host name, and CIO checks an IP literal by its reverse DNS name |
| iOS, macOS | Darwin (`ktor-client-darwin`) | URLSession's WebSocket task: it brings up the cellular radio and honours VPN On Demand, which BSD sockets do not | yes, on URLSession's TLS |
| Linux | CIO | | no: CIO has no TLS on Linux native |
| `js`, `wasmJs` | JS (in ktor-client-core) | the browser's or Node.js's own WebSocket | yes, on the runtime's `wss` |

An application that wants another engine passes its own `HttpClient`.

`coaps+ws` needs a client that authenticates the server (RFC 8323 §9.2): a chain its trust store accepts that
names the URI's host (RFC 9525 §6). The connector lists `coaps+ws` among its `schemes`, the connector's word that
it runs TLS for it, only where its default client does that (last column); elsewhere a `CoapClient` on it fails a
`coaps+ws` URI with `TRANSPORT_UNAVAILABLE` before resolving it, and the connector refuses a `wss` endpoint itself.
A connector on an `HttpClient` the application passes serves `coaps+ws` everywhere: passing one is the
application's word that its engine authenticates servers. A CIO client on the JVM or Android has the name check
above, so give it a trust manager for an authority that issues only DNS names for your servers, or use an engine
whose TLS checks names, such as one on the JDK's own TLS (not tested in kcoap).

- `KtorWebSocketConnector`: a `Connector` for the CoAP client. The opening handshake asks for the
  subprotocol `coap` at `/.well-known/coap` of the URI's authority, and the connector fails unless the
  server selects it (§4.1, §8.3). Every engine reports the selected subprotocol as the response's
  `Sec-WebSocket-Protocol`; the JS engine takes it from `WebSocket.protocol`, having asked through the
  WebSocket constructor's `protocols` argument. `wss` for `coaps+ws` runs on the engine's TLS, as above.
  Construct it with no arguments for a client of its own, or pass an `HttpClient` that has the WebSockets
  plugin, preferably without a ping interval (§4.4).
- `KtorWebSocketConnection`: any ktor `WebSocketSession` as a `MessageConnection`. One CoAP message per
  binary frame (§4.2), fragmented frames reassembled, and a text frame closes the WebSocket with 1003.

The adapter only moves frames; Len 0 framing is common code in `kcoap-coap-message`, and the CSM, signaling,
block-wise and Observe are in `kcoap-coap`.

```kotlin
val client = CoapClient(listOf(KtorWebSocketConnector()), scope)
println(client.get("coap+ws://example.org/sensors/temperature?u=Cel").payloadAsText())
```

The server side is `kcoap-transport-ktor-websocket-server`, a module of its own so that a browser
client never depends on ktor-server.

## Verified by

- `KtorWebSocketConnectionTest`: frames, fragmentation, text frames, on every target.
- `KtorWebSocketTransportTest` (JVM): real loopback sockets, the subprotocol required both ways,
  mounting in an application.
- `NativeWebSocketLoopbackTest` (native): the default client against the listener on loopback sockets,
  and refused by a server that selects no subprotocol. On Linux it is CIO. On macOS and the iOS
  simulator it is the Darwin engine, in CI's macOS job (`scripts/test apple`), which passed on pull
  request #1 and for the release. In release run 42298365400 the default client's negotiation timed out
  once on macOS, after 20 s, and passed when the job ran again.
- `DefaultEngineTest`: the default client's engine on the JVM, Linux, and on `js` and `wasmJs`
  (`webTest`, on Node.js), where an application's `HttpClient()` also finds the Js engine; on Apple, in
  a test binary that links ktor-client-cio too.
- `KtorWebSocketConnectorTest`: `KtorWebSocketConnector()` builds its client and refuses non-WebSocket
  endpoints, on every target; it lists `coaps+ws` exactly where its engine authenticates servers, and a
  connector on the application's client lists it everywhere.
- `DefaultConnectorCoapsWsTest` (JVM) and `LinuxSecureWebSocketTest` (Linux): a `CoapClient` on the default
  connector fails `coaps+ws` with `TRANSPORT_UNAVAILABLE` before resolving it, and the connector refuses a `wss`
  endpoint before opening a socket. `LinuxSecureWebSocketTest` also pins that CIO has no TLS on Linux native,
  and `CioWebSocketNameCheckTest` in kcoap-transport-ktor pins CIO's name check on the JVM; `DefaultEngineTest`
  checks that the default connector lists `coaps+ws` on `js` and `wasmJs`, and on Apple.
- `JsWebSocketSubprotocolTest` (js, on Node.js): an npm `ws` server whose `handleProtocols` records the
  offered subprotocols sees `coap` requested alone, and the connector reads `coap` back from the
  negotiated protocol and exchanges a message; a server that selects none is refused.
- `BrowserAgainstJvmListenerTest` (JVM, with `-Pkcoap.browser`): the js tests above in a headless
  browser against a `KtorWebSocketListener` on the JVM, which accepts only handshakes that ask for
  `coap`. CI's browser job runs them in headless Chrome; they have also passed in headless Firefox
  (`scripts/test browser firefox`). The
  page loads the compiled js tests with a small AMD loader and runs them with mocha, both served by the
  test; no bundler and no extra npm packages are involved.
- The libcoap interop tests over `coap+ws` in `kcoap-coap/src/jvmTest`, and over `coaps+ws` on a CIO client
  that trusts the test authority (`KcoapClientAgainstLibcoapTlsTest`): a GET, the platform store's refusal,
  and a certificate for another host name refused.

Not covered: the `wasmJs` engine against a real server (only the in-memory tests and the engine checks
run there), and `coaps+ws` on the Darwin and Js engines against a real TLS server.
