package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun defaultClient(): HttpClient = HttpClient(Js) { install(WebSockets) }

// The browser's wss, and Node.js's, validate the chain and the host name; kcoap cannot weaken either.
internal actual val defaultClientAuthenticatesServers: Boolean = true
