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
