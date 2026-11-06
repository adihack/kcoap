package dev.kcoap.sample.jvm

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.ktor.KtorUdpTransport
import kotlinx.coroutines.CoroutineScope

// docs/getting-started.md, "OSCORE with pre-shared keys" (RFC 8613): client and server derive their contexts from
// the same Master Secret, each with the other's Sender ID as its Recipient ID (§3).

/** The platform's cryptography: the JDK's providers through cryptography-kotlin. */
val crypto = DefaultCryptoBackend()

suspend fun oscoreServer(scope: CoroutineScope, masterSecret: ByteArray, store: SequenceNumberStore, port: Int): CoapServer {
    val context = SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = byteArrayOf(0x01),
            recipientId = byteArrayOf(0x00),
        ),
        crypto,
        sequenceStore = store, // durable for a pre-shared key
    )
    val contexts = InMemoryContextRepository().apply { add(context) }
    val config = CoapServerConfig(security = OscoreServer(contexts))
    return CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope, config) {
        resource("lights") { get { CoapResponse.content("on") } } // OSCORE only: the default with security
        resource("status") {
            access = AccessPolicy.Public // anyone, with or without OSCORE
            get { CoapResponse.content("up") }
        }
    }
}

// Once per process, shared by every client that talks to that server (below).
suspend fun clientContext(masterSecret: ByteArray, store: SequenceNumberStore): SecurityContext =
    SecurityContext.derive(
        SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = byteArrayOf(0x00),
            recipientId = byteArrayOf(0x01),
        ),
        crypto,
        sequenceStore = store, // durable for a pre-shared key
    )

suspend fun oscoreClient(scope: CoroutineScope, context: SecurityContext, port: Int): String {
    val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { security = OscoreClient(context) })
    try {
        return client.get("coap://127.0.0.1:$port/lights").payloadAsText()
    } finally {
        client.close()
    }
}
