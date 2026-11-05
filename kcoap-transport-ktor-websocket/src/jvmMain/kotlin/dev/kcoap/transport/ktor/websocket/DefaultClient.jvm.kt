package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun defaultClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }
