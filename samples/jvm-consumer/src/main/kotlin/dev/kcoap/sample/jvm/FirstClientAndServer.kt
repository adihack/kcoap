package dev.kcoap.sample.jvm

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.transport.ktor.KtorUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds

// docs/getting-started.md, "A first client and server": a CoAP server (RFC 7252) on UDP with one resource, and a
// client that asks for it. The port is a parameter where the document writes 5683, and the client returns the
// answer instead of printing it, so that main can check it.

suspend fun helloServer(scope: CoroutineScope, port: Int): CoapServer =
    CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }

suspend fun helloClient(scope: CoroutineScope, port: Int): String {
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { requestTimeout = 5.seconds })
    try {
        return client.get("coap://127.0.0.1:$port/hello").payloadAsText()
    } finally {
        client.close()
    }
}
