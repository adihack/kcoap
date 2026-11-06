package dev.kcoap.sample.js

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.transport.ktor.websocket.server.KtorWebSocketListener
import dev.kcoap.transport.ktor.websocket.server.coapOverWebSockets
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server the browser sample talks to: one ktor application on CIO that serves
 * - the page and its bundle, from a site directory;
 * - `POST /oscore-session`: a new [OscoreSession] for each page load, whose context it adds to its own;
 * - CoAP over WebSockets at `/.well-known/coap` (RFC 8323 §4), where a [CoapServer] with OSCORE (RFC 8613)
 *   serves `lights` and refuses whatever OSCORE did not protect;
 * - `POST /report`: the outcome the page saw, handed to the server's report callback, for the headless-browser
 *   check.
 *
 * Page and CoAP share one origin, so the page needs no CORS and the WebSocket goes where the page came from. In
 * production the same application runs behind TLS, and the page then speaks `coaps+ws`.
 */
class SampleServer private constructor(
    private val http: EmbeddedServer<*, *>,
    private val coap: CoapServer,
    /** The port the page, the session endpoint and CoAP over WebSockets share. */
    val port: Int,
) {
    /** Stops serving CoAP, then the HTTP server. */
    suspend fun close() {
        coap.close()
        http.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1000)
    }

    companion object {
        /** The server's own Sender ID in every session: empty, as RFC 8613 §3.3 allows. */
        private val SERVER_ID = byteArrayOf()

        /**
         * Starts the server on [host] and [port] (0 picks a free one), serving the files of [site] (the bundle's
         * distribution directory) at `/` when it is given. [onReport] receives every outcome a page reports.
         */
        suspend fun start(
            scope: CoroutineScope,
            site: File?,
            host: String = "127.0.0.1",
            port: Int = 0,
            onReport: (String) -> Unit = {},
        ): SampleServer {
            val crypto = DefaultCryptoBackend()
            val contexts = InMemoryContextRepository() // one context per page load: bound it in production
            val clients = AtomicInteger()
            val listener = KtorWebSocketListener()
            val coap = CoapServer(listOf(listener), scope, CoapServerConfig(security = OscoreServer(contexts))) {
                resource("lights") { get { CoapResponse.content("on") } } // OSCORE only: the default with security
            }
            val http = embeddedServer(CIO, host = host, port = port) {
                install(WebSockets) {
                    pingPeriodMillis = 0 // RFC 8323 §4.4: CoAP pings test the connection, not WebSocket pings
                }
                routing {
                    coapOverWebSockets(listener)
                    post("/oscore-session") {
                        // A Sender ID that no other page has (RFC 8613 §3.3) and a fresh Master Secret.
                        val clientId = clients.incrementAndGet().let { n -> ByteArray(4) { i -> (n ushr (24 - 8 * i)).toByte() } }
                        val masterSecret = crypto.randomBytes(16)
                        contexts.add(
                            SecurityContext.derive(
                                SecurityContextParameters(masterSecret = masterSecret, senderId = SERVER_ID, recipientId = clientId),
                                crypto,
                                sequenceStore = EphemeralSequenceNumberStore(), // new keys, never used before
                            ),
                        )
                        call.respondText(OscoreSession(masterSecret, senderId = clientId, recipientId = SERVER_ID).encode())
                    }
                    post("/report") {
                        onReport(call.receiveText())
                        call.respond(HttpStatusCode.NoContent)
                    }
                    if (site != null) staticFiles("/", site)
                }
            }
            http.startSuspend(wait = false)
            return SampleServer(http, coap, http.engine.resolvedConnectors().first().port)
        }
    }
}
