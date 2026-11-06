package dev.kcoap.transport.ktor.websocket

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun defaultClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }

// ktor-network-tls 3.6.1 accepts a certificate without dNSName entries for any host name
// (upstreamKtorCioAcceptsACertificateWithoutDnsNamesForAnyHost in kcoap-transport-ktor), so CIO does not
// authenticate a coaps+ws server here.
internal actual val defaultClientAuthenticatesServers: Boolean = false
