package dev.kcoap.sample.android

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.transport.ktor.KtorUdpTransport
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds

/**
 * A CoAP GET (RFC 7252) and an OSCORE-protected GET (RFC 8613) over UDP on the loopback interface, from a
 * [CoapClient] to a [CoapServer] in the same app, each on its own [KtorUdpTransport]. The server answers
 * both on one resource and says whether OSCORE protected the request.
 */
internal suspend fun loopbackGets(scope: CoroutineScope, contexts: OscoreContexts): String {
    val repository = InMemoryContextRepository().apply { add(contexts.server) }
    val serverTransport = KtorUdpTransport.bind("127.0.0.1", 0)
    val server = CoapServer(serverTransport, scope, CoapServerConfig(security = OscoreServer(repository))) {
        resource("hello") {
            get { CoapResponse.content(if (securityIdentity == null) "Hello" else "Hello under OSCORE") }
        }
    }
    val uri = "coap://127.0.0.1:${serverTransport.localEndpoint.port}/hello"
    val config = CoapClientConfig(requestTimeout = TIMEOUT)
    val plain = CoapClient(KtorUdpTransport.bind("127.0.0.1", 0), scope, config)
    val protected = CoapClient(
        KtorUdpTransport.bind("127.0.0.1", 0),
        scope,
        CoapClientConfig(security = OscoreClient(contexts.client), requestTimeout = TIMEOUT),
    )
    return try {
        val answer = plain.get(uri)
        val protectedAnswer = protected.get(uri)
        "GET $uri: ${answer.code} ${answer.payloadAsText()}\n" +
            "OSCORE GET $uri: ${protectedAnswer.code} ${protectedAnswer.payloadAsText()}\n"
    } finally {
        protected.close()
        plain.close()
        server.close()
    }
}

private val TIMEOUT = 10.seconds
