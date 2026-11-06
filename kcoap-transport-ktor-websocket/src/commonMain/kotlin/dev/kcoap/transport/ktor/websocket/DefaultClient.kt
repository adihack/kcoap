package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient

/**
 * The client behind `KtorWebSocketConnector()`: the WebSockets plugin with no ping interval (RFC 8323
 * §4.4), on an engine named for each platform rather than found by ktor's lookup. On native, ktor uses
 * whichever linked engine registered first, so an Apple app that also links ktor-client-cio for its own
 * HTTP calls could otherwise end up on BSD sockets instead of URLSession.
 *
 * | Platform | Engine |
 * |---|---|
 * | JVM, Linux | CIO |
 * | iOS, macOS | Darwin (URLSession: brings up the cellular radio, honours VPN On Demand) |
 * | `js`, `wasmJs` | Js (the browser's or Node.js's WebSocket) |
 */
internal expect fun defaultClient(): HttpClient

/**
 * Whether [defaultClient] authenticates a `wss` server as `coaps+ws` needs it (RFC 8323 §9.2, RFC 6455 §4.1):
 * a chain valid under the platform's trust store that names the URI's host (RFC 9525 §6). Only then does
 * `KtorWebSocketConnector()` list `coaps+ws`, the connector's word that it runs TLS for it.
 *
 * - JVM and Android: false. CIO's TLS is ktor-network-tls, whose name check accepts a certificate without DNS
 *   names for any host name; CIO checks an IP literal by the name its reverse DNS lookup gives.
 * - Linux: false. CIO has no TLS on Linux native.
 * - iOS and macOS: true. URLSession checks the chain against the system's trust store, and the host name.
 * - `js` and `wasmJs`: true. The browser's or Node.js's own `wss` does.
 */
internal expect val defaultClientAuthenticatesServers: Boolean
