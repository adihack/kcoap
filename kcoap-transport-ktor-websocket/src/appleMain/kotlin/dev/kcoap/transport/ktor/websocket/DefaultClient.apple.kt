package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun defaultClient(): HttpClient = HttpClient(Darwin) { install(WebSockets) }

// URLSession's default trust evaluation checks the chain against the system's trust store and the host name.
internal actual val defaultClientAuthenticatesServers: Boolean = true
