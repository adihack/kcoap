package dev.kcoap.sample.js

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.seconds

/**
 * Reads a `lights` resource with an OSCORE-protected GET (RFC 8613) over CoAP over WebSockets (RFC 8323 §4):
 * [coapUri] is `coap+ws://host:port/lights`, or `coaps+ws://...` from a page served over HTTPS, where the
 * browser runs the TLS. The browser code calls it; the JVM tests call the same code.
 *
 * The context is derived from [session] with an [EphemeralSequenceNumberStore], which is right only because the
 * session's Master Secret is new in this page ([OscoreSession]). The WebSocket connector runs on the runtime's
 * own WebSocket in browsers, and [crypto] on WebCrypto.
 *
 * @throws dev.kcoap.common.KcoapException when the request fails, and IllegalStateException when the server
 *   answers with an error code.
 */
suspend fun readLights(
    scope: CoroutineScope,
    coapUri: String,
    session: OscoreSession,
    crypto: CryptoBackend = DefaultCryptoBackend(),
): String {
    val context = SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = session.masterSecret,
            senderId = session.senderId,
            recipientId = session.recipientId,
        ),
        crypto,
        sequenceStore = EphemeralSequenceNumberStore(), // the keys are new in this page
    )
    val client = CoapClient(
        listOf(KtorWebSocketConnector()),
        scope,
        CoapClientConfig {
            security = OscoreClient(context)
            requestTimeout = 10.seconds
        },
    )
    try {
        val response = client.get(coapUri)
        check(response.isSuccess) { "GET $coapUri answered ${response.code}" }
        return response.payloadAsText()
    } finally {
        client.close()
    }
}
