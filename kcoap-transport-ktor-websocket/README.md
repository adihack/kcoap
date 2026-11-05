# kcoap-transport-ktor-websocket

The WebSocket client transport for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor client. It is
common code only and builds for every kcoap target (the UNIVERSAL tier), browsers and Node.js included.
WebSockets are the one transport a browser allows, so this is how a browser reaches CoAP.

The default client (`KtorWebSocketConnector()`) runs on the engine this module brings for the platform:

| Platform | Engine | Why |
|---|---|---|
| JVM, Android | CIO | ktor's engine on plain sockets |
| iOS, macOS | Darwin (`ktor-client-darwin`) | URLSession's WebSocket task: it brings up the cellular radio and honours VPN On Demand, which BSD sockets do not |
| Linux | CIO | |
| `js`, `wasmJs` | JS (in ktor-client-core) | the browser's or Node.js's own WebSocket |

An application that wants another engine passes its own `HttpClient`.

- `KtorWebSocketConnector`: a `Connector` for the CoAP client. The opening handshake asks for the
  subprotocol `coap` at `/.well-known/coap` of the URI's authority, and the connector fails unless the
  server selects it (§4.1, §8.3). Every engine reports the selected subprotocol as the response's
  `Sec-WebSocket-Protocol`; the JS engine takes it from `WebSocket.protocol`, having asked through the
  WebSocket constructor's `protocols` argument. `wss` for `coaps+ws` runs on the engine's TLS, which
  checks the certificate against the host. Construct it with no arguments for a client of its own, or
  pass an `HttpClient` that has the WebSockets plugin, preferably without a ping interval (§4.4).
- `KtorWebSocketConnection`: any ktor `WebSocketSession` as a `MessageConnection`. One CoAP message per
  binary frame (§4.2), fragmented frames reassembled, and a text frame closes the WebSocket with 1003.

The adapter only moves frames; Len 0 framing, the CSM, signaling, block-wise and Observe are common code
in `kcoap-coap`.

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
  and refused by a server that selects no subprotocol. On macOS and the iOS simulator that is the Darwin
  engine, checked in CI's macOS job (`scripts/test apple`); on Linux it is CIO.
- `DefaultEngineTest`: the default client's engine on the JVM, Apple and Linux.
- `JsWebSocketSubprotocolTest` (js, on Node.js): an npm `ws` server whose `handleProtocols` records the
  offered subprotocols sees `coap` requested alone, and the connector reads `coap` back from the
  negotiated protocol and exchanges a message; a server that selects none is refused.
- `BrowserAgainstJvmListenerTest` (JVM, with `-Pkcoap.browser`): the js tests above in a headless
  browser against a `KtorWebSocketListener` on the JVM, which accepts only handshakes that ask for
  `coap`. CI's browser job runs them in Chrome; `scripts/test browser firefox` runs them locally. The
  page loads the compiled js tests with a small AMD loader and runs them with mocha, both served by the
  test; no bundler and no extra npm packages are involved.
- The libcoap interop tests over `coap+ws` in `kcoap-coap/src/jvmTest`.

Not covered: the `wasmJs` engine against a real server (only the in-memory tests run there), and
`coaps+ws` against a real TLS server.
