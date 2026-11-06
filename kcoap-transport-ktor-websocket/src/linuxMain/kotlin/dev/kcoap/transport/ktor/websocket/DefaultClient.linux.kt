package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun defaultClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

// CIO has no TLS on Linux native: ktor-network-tls's handshake is a stub that throws (LinuxSecureWebSocketTest).
internal actual val defaultClientAuthenticatesServers: Boolean = false
