# kcoap-transport-ktor-websocket-server

The WebSocket server transport for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor server, in
common code. It runs on the JVM and the native targets (the SOCKETS tier): a browser cannot accept
connections, and reaches a CoAP server as a client through `kcoap-transport-ktor-websocket`.

- `KtorWebSocketListener`: a `ConnectionListener` for the CoAP server. It serves `/.well-known/coap`
  and answers only clients that ask for the subprotocol `coap` (§4.1, §8.3). Each peer is an
  `Endpoint.WebSocket` named after the scheme and the client's address.
  - `KtorWebSocketListener.start(host, port)` runs a standalone server on ktor's CIO engine, with
    WebSocket pings off (§4.4).
  - In an application that runs its own ktor server, create a `KtorWebSocketListener()` and mount it
    with `routing { coapOverWebSockets(listener) }`. TLS (`coaps+ws`) is then the application's
    connector; pass `CoapScheme.COAPS_WS` so peers are named `wss://`.

```kotlin
val server = CoapServer(listOf(KtorWebSocketListener.start(port = 8080)), scope) {
    resource("hello") { get { CoapResponse.content("Hello") } }
}
```

It is a module of its own so that WebSocket clients, browsers above all, never depend on ktor-server.

Verified by `KtorWebSocketTransportTest` in `kcoap-transport-ktor-websocket` (real loopback sockets,
standalone and mounted), `ReliableLoopbackTest` and the libcoap interop tests, where `coap-client`
reaches it over `coap+ws`.
