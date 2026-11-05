package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.common.readUnsigned
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocResponder
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.OscoreOptionValue
import dev.kcoap.oscore.OscoreServer
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 9528 Appendix A.1: the OSCORE parameters and context of an EDHOC session, and resuming them. */
class OscoreFromEdhocTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.9", 5683)

    /** A store that stands in for a durable one, such as a file: any store but the ephemeral one. */
    private class DurableStore : SequenceNumberStore {
        val values = mutableMapOf<String, Long>()
        override suspend fun load(contextName: String): Long? = values[contextName]
        override suspend fun store(contextName: String, limit: Long) {
            values[contextName] = limit
        }
    }

    /** A loopback handshake, message_1 to message_3, that derives no context: both sides' sessions. */
    private suspend fun handshake(): Pair<EdhocSession, EdhocSession> {
        val peers = Peers.create(crypto)
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        val responder = EdhocResponder(peers.responderConfig(), crypto)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        responder.processMessage3(initiator.createMessage3())
        return initiator.session!! to responder.session!!
    }

    private fun request() = CoapMessage.request(CoapCode.GET) {
        messageId = 3
        token = byteArrayOf(0x17)
        options { uriPath("temp") }
    }

    private fun partialIvOf(message: CoapMessage): Long =
        OscoreOptionValue.decode(message.options.oscore!!).partialIv!!.readUnsigned()

    private suspend fun serverFor(context: SecurityContext) =
        OscoreServer(InMemoryContextRepository().apply { add(context) })

    private fun ByteArray.isWiped() = all { it == 0.toByte() }

    /** RFC 9528 Appendix A.1, Table 14: exporter labels 0 and 1, the application algorithms, C_R and C_I as IDs. */
    @Test
    fun parametersComeFromTheExporterAndTheConnectionIdentifiers() = runTest {
        val (initiator, _) = handshake()
        val parameters = initiator.oscoreParameters()
        assertContentEquals(initiator.exporter(0, ByteArray(0), 16), parameters.masterSecret())
        assertContentEquals(initiator.exporter(1, ByteArray(0), 8), parameters.masterSalt())
        assertContentEquals(initiator.responderConnectionIdentifier.bytes, parameters.senderId)
        assertContentEquals(initiator.initiatorConnectionIdentifier.bytes, parameters.recipientId)
        assertEquals(initiator.cipherSuite.appAead, parameters.aeadAlgorithm)
        assertEquals(initiator.cipherSuite.appHash, parameters.hkdfAlgorithm)
        assertEquals(32, initiator.oscoreParameters(keyLength = 32, saltLength = 4).masterSecret().size)
    }

    @Test
    fun parametersWipeTheExporterOutputsAndKeepTheirOwnCopies() = runTest {
        val (initiator, _) = handshake()
        val exported = mutableListOf<ByteArray>()
        val parameters = initiator.oscoreParametersFrom(
            exportMasterSecret = { initiator.oscoreMasterSecret().also { exported += it } },
            exportMasterSalt = { initiator.oscoreMasterSalt().also { exported += it } },
        )
        assertEquals(listOf(16, 8), exported.map { it.size }, "the Master Secret, then the Master Salt")
        assertTrue(exported.all { it.isWiped() }, "both exporter outputs are wiped")
        assertContentEquals(initiator.oscoreMasterSecret(), parameters.masterSecret())
        assertContentEquals(initiator.oscoreMasterSalt(), parameters.masterSalt())
        assertFalse(parameters.masterSecret().isWiped())
    }

    @Test
    fun aFailedSaltExportStillWipesTheMasterSecret() = runTest {
        val (initiator, _) = handshake()
        lateinit var secret: ByteArray
        assertFailsWith<IllegalStateException> {
            initiator.oscoreParametersFrom(
                exportMasterSecret = { initiator.oscoreMasterSecret().also { secret = it } },
                exportMasterSalt = { throw IllegalStateException("export failed") },
            )
        }
        assertTrue(secret.isWiped(), "the exported Master Secret is wiped")
        // The public path: the exporter rejects an empty output (RFC 9528 §4.2.1).
        assertFailsWith<IllegalArgumentException> { initiator.oscoreParameters(saltLength = 0) }
    }

    /**
     * EDHOC keys are new, so the context trusts its replay window even with a durable store that already
     * knows the name. Under [dev.kcoap.oscore.FreshnessPolicy.AUTO] it would challenge the first request,
     * which breaks the combined request of RFC 9668.
     */
    @Test
    fun theContextTrustsItsWindowWithADurableStoreToo() = runTest {
        val (initiator, responder) = handshake()
        val store = DurableStore().apply { values[responder.oscoreParameters().name] = 640 }
        val serverContext = responder.oscoreContext(crypto, store)
        assertEquals(640, serverContext.peekSequenceNumber(), "resumes from the stored value (RFC 8613 Appendix B.1.1)")

        val request = OscoreClient(initiator.oscoreContext(crypto)).protectRequest(request(), peer)!!
        val verified = serverFor(serverContext).unprotectRequest(request.message, peer)!!
        assertNull(verified.challenge, "the first request is processed, not challenged")
        assertEquals(listOf("temp"), verified.message.options.uriPath)
    }

    /**
     * The documented way to resume an EDHOC-derived context after a restart (RFC 8613 Appendix B.1): it
     * used a durable store from its first derivation, and is derived again from the persisted parameters
     * and that store. The client resumes above every Partial IV it used; the restarted server challenges
     * the first request, because its replay window is gone, and accepts the answer.
     */
    @Test
    fun persistedParametersResumeFromTheStoreTheContextUsedFromTheStart() = runTest {
        val (initiator, responder) = handshake()
        val clientStore = DurableStore()
        val serverStore = DurableStore()
        val client = OscoreClient(initiator.oscoreContext(crypto, clientStore))
        val server = serverFor(responder.oscoreContext(crypto, serverStore))
        val usedBefore = List(70) {
            val protected = client.protectRequest(request(), peer)!!
            assertNull(server.unprotectRequest(protected.message, peer)!!.challenge)
            partialIvOf(protected.message)
        }
        val persistedClient: SecurityContextParameters = initiator.oscoreParameters()
        val persistedServer: SecurityContextParameters = responder.oscoreParameters()

        // Restart: the EDHOC sessions and the contexts are gone; the parameters and the stores remain.
        val resumedClient = OscoreClient(SecurityContext.derive(persistedClient, crypto, clientStore))
        val restartedServer = serverFor(SecurityContext.derive(persistedServer, crypto, serverStore))

        val first = resumedClient.protectRequest(request(), peer)!!
        val resumedAt = partialIvOf(first.message)
        assertTrue(resumedAt > usedBefore.max(), "$resumedAt is above every Partial IV used before the restart")
        val challenged = restartedServer.unprotectRequest(first.message, peer)!!
        val challenge = assertNotNull(challenged.challenge, "the restarted server requires the Echo proof (B.1.2)")
        val echo = first.unprotectResponse(challenged.protectResponse(challenge)).options.echo!!

        val answer = resumedClient.protectRequest(request().toBuilder().options { echo(echo) }.build(), peer)!!
        assertNull(restartedServer.unprotectRequest(answer.message, peer)!!.challenge, "the Echo proves freshness")
    }
}
