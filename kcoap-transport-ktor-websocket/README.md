# kcoap-transport-ktor-websocket

The WebSocket client transport for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor client. It is
common code only and builds for every kcoap target (the UNIVERSAL tier), browsers and Node.js included;
ktor picks the engine: CIO on the JVM and native targets (the default client), and the JS engine,
the runtime's own WebSocket, on `js` and `wasmJs`. WebSockets are the one transport a browser allows,
so this is how a browser reaches CoAP.

- `KtorWebSocketConnector`: a `Connector` for the CoAP client. The opening handshake asks for the
  subprotocol `coap` at `/.well-known/coap` of the URI's authority, and the connector fails unless the
  server selects it (§4.1, §8.3). `wss` for `coaps+ws` runs on the engine's TLS, which checks the
  certificate against the host. Construct it with no arguments for a client of its own, or pass an
  `HttpClient` that has the WebSockets plugin, preferably without a ping interval (§4.4).
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

Verified by `KtorWebSocketConnectionTest` (frames, fragmentation, text frames, on every target),
`KtorWebSocketTransportTest` (real loopback sockets: the subprotocol required both ways, mounting in an
application) and the libcoap interop tests over `coap+ws` in `kcoap-coap/src/jvmTest`.
