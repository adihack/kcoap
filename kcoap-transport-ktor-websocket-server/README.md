# kcoap-transport-ktor-websocket-server

The WebSocket server transport for `coap+ws` and `coaps+ws` (RFC 8323 §4) on the ktor server, in
common code. It runs on the JVM and the native targets (the SOCKETS tier): a browser cannot accept
connections, and reaches a CoAP server as a client through `kcoap-transport-ktor-websocket`.

- `KtorWebSocketListener`: a `ConnectionListener` for the CoAP server. It serves `/.well-known/coap`
  and answers only clients that ask for the subprotocol `coap` (§4.1, §8.3). Each peer is an
  `Endpoint.WebSocket` named after the scheme and the client's address.
  - `KtorWebSocketListener.start(engineFactory, host, port)` runs a standalone server on the ktor engine
    you pass, with WebSocket pings off (§4.4).
  - In an application that runs its own ktor server, create a `KtorWebSocketListener()` and mount it
    with `routing { coapOverWebSockets(listener) }`. TLS (`coaps+ws`) is then the application's
    connector; pass `CoapScheme.COAPS_WS` so peers are named `wss://`.

The module brings no server engine: the application depends on the one it runs. CIO
(`io.ktor:ktor-server-cio`, `io.ktor.server.cio.CIO`) runs on the JVM and natively and is the one the
tests use; another engine with WebSocket support, such as Netty on the JVM, is passed the same way. The
build's `verifyNoServerEngine` check fails if an engine appears among the published dependencies.

```kotlin
// build.gradle.kts: implementation("io.ktor:ktor-server-cio:<ktor version>")
val server = CoapServer(listOf(KtorWebSocketListener.start(CIO, port = 8080)), scope) {
    resource("hello") { get { CoapResponse.content("Hello") } }
}
```

It is a module of its own so that WebSocket clients, browsers above all, never depend on ktor-server.

Verified by `KtorWebSocketTransportTest` (JVM) and `NativeWebSocketLoopbackTest` (Linux; macOS and the
iOS simulator pending the first apple-job run) in `kcoap-transport-ktor-websocket` (real loopback
sockets, standalone and mounted), `BrowserAgainstJvmListenerTest` there (a browser's WebSocket against
the listener on the JVM), `ReliableLoopbackTest` and the libcoap interop tests, where `coap-client`
reaches it over `coap+ws`.
