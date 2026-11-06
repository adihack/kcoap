package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.edhoc.oscore.CombinedRequestServer
import dev.kcoap.edhoc.oscore.EdhocIdentity
import dev.kcoap.edhoc.oscore.EdhocResource
import dev.kcoap.edhoc.oscore.EdhocServer
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreIdentity
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorUdpTransport
import java.util.Collections
import kotlinx.coroutines.CoroutineScope

/**
 * The device behind the gateway: a kcoap [CoapServer] on UDP at 127.0.0.1, by default on [PORT], the default CoAP
 * port, which is where libcoap 4.3.5b's direct proxy sends every request ([LibcoapProxy]). Its resources:
 *
 * - `/.well-known/edhoc`, the EDHOC resource (RFC 9528 Appendix A.2), with the RFC 9668 combined request for
 *   every protected one ([CombinedRequestServer]); [established] lists the sessions EDHOC completed.
 * - `/lights`, authorized on who the client is: a provisioned OSCORE context's [OscoreIdentity] must be among
 *   the admitted ones, an EDHOC context's [EdhocIdentity] must carry an admitted credential. A request that OSCORE
 *   verified but the policy refuses gets a protected 4.03, one without OSCORE a 4.01 (ADR 0008). [lights]
 *   records who got through, and from which peer.
 * - `/firmware`, a [firmware] body larger than one block, for any OSCORE client to GET; a PUT body goes to
 *   [uploads].
 * - `/hello`, public, answering the device's greeting; [helloRequests] keeps the options of each request.
 *
 * [security] records every request as it arrived, before verification.
 */
internal class UdpDevice private constructor(
    private val server: CoapServer,
    val security: RecordingServerSecurity,
    /** Who `/lights` answered: the verified identity and the peer the request came from. */
    val lights: List<Visit>,
    /** The EDHOC sessions completed so far. */
    val established: List<EdhocSession>,
    /** The options of every request `/hello` answered, as they arrived. */
    val helloRequests: List<OptionSet>,
    /** The bodies PUT to `/firmware`, whole. */
    val uploads: List<ByteArray>,
) {
    /** A request `/lights` answered: who OSCORE verified, and the peer it came from. */
    class Visit(val identity: SecurityIdentity?, val peer: Endpoint)

    suspend fun close() {
        server.close()
    }

    companion object {
        /** RFC 7252 §6.1: the default port of `coap`. */
        const val PORT: Int = 5683

        /** The body of `/firmware`, three 1024-byte blocks. */
        val firmware: ByteArray = ByteArray(3000) { (it % 251).toByte() }

        /**
         * Starts a device on 127.0.0.1:[port] (by default [PORT], which must be free), answering `/hello` with
         * [greeting]. EDHOC runs with [responder]; [provisioned] are OSCORE contexts set up without EDHOC.
         * `/lights` admits the clients of the [admitted] provisioned contexts and those EDHOC authenticated with
         * one of [admittedCredentials].
         */
        suspend fun start(
            scope: CoroutineScope,
            crypto: CryptoBackend,
            responder: EdhocResponderConfig,
            provisioned: List<SecurityContext> = emptyList(),
            admitted: Set<OscoreIdentity> = emptySet(),
            admittedCredentials: Set<AuthenticationCredential> = emptySet(),
            port: Int = PORT,
            greeting: String = "device",
        ): UdpDevice {
            val transport = try {
                KtorUdpTransport.bind("127.0.0.1", port)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "UDP 127.0.0.1:$port is taken; the device behind libcoap's proxy needs it " +
                        "(docs/adr/0009-browser-topology-interop.md)",
                    e,
                )
            }
            val contexts = InMemoryContextRepository()
            provisioned.forEach { contexts.add(it) }
            val sessions = Collections.synchronizedList(mutableListOf<EdhocSession>())
            val edhoc = EdhocServer(responder, crypto, contexts, listener = { session, _ -> sessions += session })
            val security = RecordingServerSecurity(CombinedRequestServer(edhoc, OscoreServer(contexts)))
            val visits = Collections.synchronizedList(mutableListOf<Visit>())
            val hellos = Collections.synchronizedList(mutableListOf<OptionSet>())
            val uploads = Collections.synchronizedList(mutableListOf<ByteArray>())
            val server = CoapServer(transport, scope, CoapServerConfig(security = security)) {
                resource(".well-known") { add(EdhocResource(edhoc)) }
                resource("lights") {
                    access = AccessPolicy { request ->
                        when (val identity = request.securityIdentity) {
                            is OscoreIdentity -> identity in admitted
                            is EdhocIdentity -> identity.peerCredential in admittedCredentials
                            else -> false
                        }
                    }
                    get {
                        visits += Visit(securityIdentity, peer)
                        CoapResponse.content("on")
                    }
                }
                resource("firmware") {
                    get { CoapResponse.content(firmware, ContentFormat.OCTET_STREAM) }
                    put {
                        uploads += payload
                        CoapResponse.changed()
                    }
                }
                resource("hello") {
                    access = AccessPolicy.Public
                    get {
                        hellos += options
                        CoapResponse.content(greeting)
                    }
                }
            }
            return UdpDevice(server, security, visits, sessions, hellos, uploads)
        }
    }
}
