package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.common.KcoapException
import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.bc.BouncyCastleCryptoBackend
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.AuthenticationKind
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.CredentialIdentifier
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocMethod
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.LocalCredential
import dev.kcoap.edhoc.TrustedCredentials
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorTcpConnector
import dev.kcoap.transport.ktor.KtorTcpListener
import dev.kcoap.transport.ktor.KtorTlsConnector
import dev.kcoap.transport.ktor.KtorUdpTransport
import dev.kcoap.transport.ktor.websocket.KtorWebSocketConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The examples of `docs/getting-started.md`, compiled and run over real UDP sockets on the loopback interface,
 * with the platform's cryptography. Each function is the document's, with the port as a parameter where the
 * document writes 5683, and `println` collected for the assertions.
 */
class GettingStartedExamplesTest {
    private val output = mutableListOf<String>()
    private fun println(line: Any?) {
        output += line.toString()
    }

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    // ---------------------------------------------------------------- as in the document

    suspend fun helloServer(scope: CoroutineScope, port: Int): CoapServer =
        CoapServer(KtorUdpTransport.bind("0.0.0.0", port), scope) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }

    suspend fun helloClient(scope: CoroutineScope, port: Int) {
        val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { requestTimeout = 5.seconds })
        try {
            println(client.get("coap://127.0.0.1:$port/hello").payloadAsText())
        } catch (e: KcoapException) { // here a CoapException, whose reason says what failed
            println("Failed: ${e.message}")
        } finally {
            client.close()
        }
    }

    val crypto = DefaultCryptoBackend()

    suspend fun oscoreServer(
        scope: CoroutineScope,
        masterSecret: ByteArray,
        store: SequenceNumberStore,
        port: Int,
    ): CoapServer {
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
        return CoapServer(KtorUdpTransport.bind("0.0.0.0", port), scope, config) {
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

    suspend fun oscoreClient(scope: CoroutineScope, context: SecurityContext, port: Int) {
        val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { security = OscoreClient(context) })
        println(client.get("coap://127.0.0.1:$port/lights").payloadAsText())
        client.close()
    }

    suspend fun credential(subject: String, kid: Byte): LocalCredential {
        val keys = crypto.generateKeyPair(KeyType.ED25519)
        val key = CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, keys.publicKey, kid = byteArrayOf(kid))
        return LocalCredential(
            AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
            CredentialIdentifier.KeyId(byteArrayOf(kid)),
            keys.privateKey,
            AuthenticationKind.SIGNATURE,
        )
    }

    fun initiatorConfig(client: LocalCredential, server: LocalCredential) = EdhocInitiatorConfig(
        method = EdhocMethod.SIGNATURE_SIGNATURE,
        cipherSuites = listOf(CipherSuite.SUITE_0),
        credentials = listOf(client),
        credentialResolver = TrustedCredentials(crypto, listOf(server.credential)),
    )

    fun responderConfig(server: LocalCredential, client: LocalCredential) = EdhocResponderConfig(
        methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
        cipherSuites = listOf(CipherSuite.SUITE_0),
        credentials = listOf(server),
        credentialResolver = TrustedCredentials(crypto, listOf(client.credential)),
    )

    suspend fun edhocServer(scope: CoroutineScope, config: EdhocResponderConfig, port: Int): CoapServer {
        val contexts = InMemoryContextRepository() // one context per handshake: bound it in production
        val edhoc = EdhocServer(config, crypto, contexts)
        val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
        return CoapServer(KtorUdpTransport.bind("0.0.0.0", port), scope, CoapServerConfig(security = security)) {
            resource(".well-known") { add(EdhocResource(edhoc)) } // POST /.well-known/edhoc, public
            resource("lights") {
                get {
                    val peer = (securityIdentity as EdhocIdentity).peerCredential // who EDHOC authenticated
                    CoapResponse.content("on for ${peer.claimsSet?.subject}")
                }
            }
        }
    }

    suspend fun edhocClient(scope: CoroutineScope, config: EdhocInitiatorConfig, port: Int) {
        val security = PeerSecurity()
        val client = CoapClient(KtorUdpTransport.bind(), scope, CoapClientConfig { this.security = security })
        val server = CoapUri.parse("coap://127.0.0.1:$port")
        // message_1 and message_2 here; message_3 rides on the first OSCORE request (RFC 9668).
        val (_, protection) = EdhocClientHandshake.over(client, server, config, crypto).runCombined()
        security.install(client, server, protection)
        println(client.get("coap://127.0.0.1:$port/lights").payloadAsText())
        client.close()
    }

    // JVM and Android: ML-KEM and ML-DSA from Bouncy Castle, everything else from the platform.
    val postQuantum = BouncyCastleCryptoBackend(classical = DefaultCryptoBackend())

    fun reliableClient(scope: CoroutineScope): CoapClient =
        CoapClient(listOf(KtorTcpConnector(), KtorTlsConnector(), KtorWebSocketConnector()), scope)

    // ---------------------------------------------------------------- what the document says in words

    // "Pass postQuantum wherever crypto goes above, list CipherSuite.SUITE_PQC_1 in the configurations, give each
    // side an ML-DSA-44 credential", and "take run() rather than runCombined()": credential, the two
    // configurations, edhocServer and edhocClient so changed.

    suspend fun postQuantumCredential(subject: String, kid: Byte): LocalCredential {
        val keys = postQuantum.generateKeyPair(KeyType.ML_DSA_44)
        val key = CoseKey(
            CoseKeyType.AKP, null, keys.publicKey, kid = byteArrayOf(kid), algorithm = SignatureAlgorithm.ML_DSA_44.coseId,
        )
        return LocalCredential(
            AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
            CredentialIdentifier.KeyId(byteArrayOf(kid)),
            keys.privateKey,
            AuthenticationKind.SIGNATURE,
        )
    }

    fun postQuantumInitiatorConfig(client: LocalCredential, server: LocalCredential) = EdhocInitiatorConfig(
        method = EdhocMethod.SIGNATURE_SIGNATURE,
        cipherSuites = listOf(CipherSuite.SUITE_PQC_1),
        credentials = listOf(client),
        credentialResolver = TrustedCredentials(postQuantum, listOf(server.credential)),
    )

    fun postQuantumResponderConfig(server: LocalCredential, client: LocalCredential) = EdhocResponderConfig(
        methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
        cipherSuites = listOf(CipherSuite.SUITE_PQC_1),
        credentials = listOf(server),
        credentialResolver = TrustedCredentials(postQuantum, listOf(client.credential)),
    )

    suspend fun postQuantumServer(scope: CoroutineScope, config: EdhocResponderConfig, port: Int): CoapServer {
        val contexts = InMemoryContextRepository()
        val edhoc = EdhocServer(config, postQuantum, contexts)
        val security = CombinedRequestServer(edhoc, OscoreServer(contexts))
        return CoapServer(KtorUdpTransport.bind("0.0.0.0", port), scope, CoapServerConfig(security = security)) {
            resource(".well-known") { add(EdhocResource(edhoc)) }
            resource("lights") {
                get {
                    val peer = (securityIdentity as EdhocIdentity).peerCredential
                    CoapResponse.content("on for ${peer.claimsSet?.subject}")
                }
            }
        }
    }

    /** As edhocClient, with run(); [wire] only records what the client's endpoint sends and receives. */
    suspend fun postQuantumClient(scope: CoroutineScope, config: EdhocInitiatorConfig, port: Int, wire: MessageInterceptor) {
        val security = PeerSecurity()
        val client = CoapClient(
            KtorUdpTransport.bind(), scope, CoapClientConfig { this.security = security },
            CoapEndpointConfig { interceptors = listOf(wire) },
        )
        val server = CoapUri.parse("coap://127.0.0.1:$port")
        val session = EdhocClientHandshake.over(client, server, config, postQuantum).run()
        security.install(client, server, OscoreClient(session.oscore))
        println(client.get("coap://127.0.0.1:$port/lights").payloadAsText())
        client.close()
    }

    // ---------------------------------------------------------------- the checks

    @Test
    fun theFirstServerAndClientExchangeAGreeting() = onLoopback { scope ->
        val port = freePort()
        val server = helloServer(scope, port)
        helloClient(scope, port)
        server.close()

        assertEquals(listOf("Hello"), output)
    }

    @Test
    fun aRequestNobodyAnswersEndsInACoapExceptionCaughtAsKcoapException() = onLoopback { scope ->
        helloClient(scope, freePort()) // nothing listens there

        val line = output.single()
        assertTrue(line.startsWith("Failed: No response from "), line)
    }

    @Test
    fun anOscoreClientReachesTheServerThatSharesItsMasterSecret() = onLoopback { scope ->
        val port = freePort()
        val masterSecret = crypto.randomBytes(16)
        val server = oscoreServer(scope, masterSecret, EphemeralSequenceNumberStore(), port)
        oscoreClient(scope, clientContext(masterSecret, EphemeralSequenceNumberStore()), port)
        val plain = CoapClient(KtorUdpTransport.bind(), scope)
        val refused = plain.get("coap://127.0.0.1:$port/lights")
        val status = plain.get("coap://127.0.0.1:$port/status")
        plain.close()
        server.close()

        assertEquals(listOf("on"), output)
        assertEquals(CoapCode.UNAUTHORIZED, refused.code, "without OSCORE the resource is refused")
        assertEquals("up", status.payloadAsText())
    }

    @Test
    fun clientsThatShareOneDerivedContextKeepItsSequenceNumbersApart() = onLoopback { scope ->
        val port = freePort()
        val masterSecret = crypto.randomBytes(16)
        val server = oscoreServer(scope, masterSecret, EphemeralSequenceNumberStore(), port)
        val context = clientContext(masterSecret, EphemeralSequenceNumberStore())
        oscoreClient(scope, context, port)
        oscoreClient(scope, context, port) // a second screen, say: the same context, the next sequence number
        server.close()

        assertEquals(listOf("on", "on"), output, "the server's replay window accepts both")
    }

    @Test
    fun anEdhocHandshakeGivesTheClientOscoreAndTheServerTheClientsCredential() = onLoopback { scope ->
        val port = freePort()
        val clientCredential = credential("client", 0x0c)
        val serverCredential = credential("server", 0x05)
        val server = edhocServer(scope, responderConfig(serverCredential, clientCredential), port)
        edhocClient(scope, initiatorConfig(clientCredential, serverCredential), port)
        server.close()

        assertEquals(listOf("on for client"), output)
    }

    @Test
    fun thePostQuantumBackendRunsSuiteMinus24AndTheClassicalSuites() = runBlocking {
        val method = EdhocMethod.SIGNATURE_SIGNATURE
        assertTrue(postQuantum.supports(KemAlgorithm.ML_KEM_512))
        assertTrue(CipherSuite.SUITE_PQC_1.isSupportedBy(postQuantum, method))
        assertTrue(CipherSuite.SUITE_2.isSupportedBy(postQuantum, method))
        assertFalse(CipherSuite.SUITE_PQC_1.isSupportedBy(crypto, method), "not without Bouncy Castle")
    }

    @Test
    fun suiteMinus24RunsTheSequentialFlowOverCoapWithItsLargeMessagesBlockWise() = onLoopback { scope ->
        val port = freePort()
        val clientCredential = postQuantumCredential("client", 0x0c)
        val serverCredential = postQuantumCredential("server", 0x05)
        val block1Sent = AtomicInteger()
        val block2Received = AtomicInteger()
        val wire = object : MessageInterceptor {
            override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage {
                if (CoapOptions.BLOCK1.number in message.options) block1Sent.incrementAndGet()
                return message
            }

            override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage {
                if (CoapOptions.BLOCK2.number in message.options) block2Received.incrementAndGet()
                return message
            }
        }
        val server = postQuantumServer(scope, postQuantumResponderConfig(serverCredential, clientCredential), port)
        postQuantumClient(scope, postQuantumInitiatorConfig(clientCredential, serverCredential), port, wire)
        server.close()

        assertEquals(listOf("on for client"), output)
        assertTrue(block2Received.get() > 1, "message_2 (ML-KEM-512 ciphertext, ML-DSA-44 signature) came block-wise")
        assertTrue(block1Sent.get() > 1, "message_3 (an ML-DSA-44 signature) went block-wise")
    }

    @Test
    fun aClientOfTheReliableTransportsServesTheirSchemes() = onLoopback { scope ->
        val listener = KtorTcpListener.bind("127.0.0.1")
        val server = CoapServer(listOf(listener), scope) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
        val client = reliableClient(scope)
        try {
            val greeting = client.get("coap+tcp://127.0.0.1:${listener.localEndpoint.port}/hello").payloadAsText()
            val refused = assertFailsWith<CoapException> { client.get("coaps://127.0.0.1:1/x") }

            assertEquals("Hello", greeting)
            assertEquals(CoapException.Reason.TRANSPORT_UNAVAILABLE, refused.reason, "no DTLS yet: refused before sending")
        } finally {
            client.close()
            server.close()
        }
    }

    private fun onLoopback(block: suspend (CoroutineScope) -> Unit) = runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            withTimeout(60.seconds) { block(scope) }
        } finally {
            scope.cancel()
        }
    }
}
