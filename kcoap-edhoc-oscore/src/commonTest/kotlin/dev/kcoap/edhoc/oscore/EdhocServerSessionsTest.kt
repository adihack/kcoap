package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.ConnectionIdentifierProvider
import dev.kcoap.edhoc.CredentialResolver
import dev.kcoap.edhoc.EadHandler
import dev.kcoap.edhoc.EadItem
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocMessage
import dev.kcoap.edhoc.EphemeralKeySource
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.testkit.Stress
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity
import dev.kcoap.transport.SessionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The pending sessions of [EdhocServer] (WP-SEC1): a C_R is never shared (RFC 9668 §4.1.2), a session
 * belongs to the peer that sent message_1 (RFC 9528 §9.7), and pending sessions are bounded in number and
 * in time, with their secrets wiped when dropped (RFC 9528 §9.8).
 */
class EdhocServerSessionsTest {
    private val crypto = DefaultCryptoBackend()
    private val alice = Endpoint.Udp("192.0.2.1", 5683)
    private val bob = Endpoint.Udp("192.0.2.2", 5683)
    private val carol = Endpoint.Udp("192.0.2.3", 5683)
    private val mallory = Endpoint.Udp("198.51.100.66", 5683)
    private val unknownSession = EdhocError.unspecified("No EDHOC session for C_R")
    private val bye = EdhocError.unspecified("bye").encode()

    // --- C_R allocation (RFC 9668 §4.1.2) ---------------------------------------------------------------

    /** The second session finds the first one's C_R reserved while message_2 is still being composed. */
    @Test
    fun aCrIsReservedBeforeMessage2IsComposed() = runTest {
        val peers = Peers.create(crypto)
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gatedMessage2 = object : EadHandler {
            override suspend fun produce(message: EdhocMessage): List<EadItem> {
                if (message == EdhocMessage.MESSAGE_2 && !reached.isCompleted) {
                    reached.complete(Unit)
                    gate.await()
                }
                return emptyList()
            }
        }
        val contexts = InMemoryContextRepository()
        val server = EdhocServer(peers.responderConfig(eadHandler = gatedMessage2), FixedShortIds(crypto), contexts)

        val first = async { handshake(peers, server, alice).run() }
        reached.await()
        val second = handshake(peers, server, bob).run()
        gate.complete(Unit)
        val firstSession = first.await()

        val firstId = firstSession.edhoc.responderConnectionIdentifier
        val secondId = second.edhoc.responderConnectionIdentifier
        assertNotEquals(firstId, secondId)
        assertContentEquals(byteArrayOf(FixedShortIds.BYTE), firstId.bytes)
        assertEquals(2, secondId.size, "every one-byte candidate was taken, so C_R grew")
        assertServerContextMatches(contexts, firstSession)
        assertServerContextMatches(contexts, second)
    }

    /**
     * Between taking a session for its message_3 and adding its OSCORE context, its C_R stays reserved: a
     * session started meanwhile must not get it as well, or both contexts would have one Recipient ID.
     */
    @Test
    fun aCrStaysReservedUntilItsContextIsInPlace() = runTest {
        val peers = Peers.create(crypto)
        val resolving = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val trusted = peers.trustingClient()
        val gatedResolver = CredentialResolver { identifier ->
            if (!resolving.isCompleted) {
                resolving.complete(Unit)
                gate.await()
            }
            trusted.resolve(identifier)
        }
        val contexts = InMemoryContextRepository()
        val config = peers.responderConfig(credentialResolver = gatedResolver)
        val server = EdhocServer(config, FixedShortIds(crypto), contexts)

        val first = async { handshake(peers, server, alice).run() }
        resolving.await()
        val second = handshake(peers, server, bob).run()
        gate.complete(Unit)
        val firstSession = first.await()

        assertNotEquals(firstSession.edhoc.responderConnectionIdentifier, second.edhoc.responderConnectionIdentifier)
        for (session in listOf(firstSession, second)) assertServerContextMatches(contexts, session)
    }

    /**
     * Concurrent handshakes on real threads, all drawing the same one-byte C_R candidate: each gets its own
     * C_R and completes. Without the reservation under the lock, two sessions pick the same C_R, the later
     * one replaces the earlier, and a message_3 meets the wrong session.
     */
    @Test
    fun concurrentHandshakesNeverShareACr() = runTest {
        val peers = Peers.create(crypto)
        val clients = (1..4).map { Endpoint.Udp("192.0.2.${10 + it}", 5683) }
        Stress.run(iterations = 25) {
            val contexts = InMemoryContextRepository()
            val server = EdhocServer(peers.responderConfig(), FixedShortIds(crypto), contexts)
            val handshakes = clients.map { client -> suspend { handshake(peers, server, client).run() } }
            val sessions = Stress.race(*handshakes.toTypedArray()).map { it.getOrThrow() }
            val identifiers = sessions.map { it.edhoc.responderConnectionIdentifier }
            assertEquals(identifiers.size, identifiers.toSet().size, "C_Rs $identifiers")
            for (session in sessions) assertServerContextMatches(contexts, session)
            assertEquals(0, server.pendingSessions())
        }
    }

    @Test
    fun aCrIsNeitherCiNorTheRecipientIdOfAContext() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        contexts.add(contextWithRecipientId(byteArrayOf(FixedShortIds.BYTE)))
        val server = EdhocServer(peers.responderConfig(), FixedShortIds(crypto), contexts)
        val session = handshake(peers, server, alice).run()
        assertEquals(2, session.edhoc.responderConnectionIdentifier.size, "0x2a is a Recipient ID already")

        val sameAsCi = ConnectionIdentifierProvider.fixed(ConnectionIdentifier(byteArrayOf(0x2b)))
        val server2 = EdhocServer(peers.responderConfig(), FixedShortIds(crypto, 0x2b), InMemoryContextRepository())
        val session2 = EdhocClientHandshake(peers.initiatorConfig(connectionIdentifiers = sameAsCi), crypto) {
            server2.handle(it, alice)
        }.run()
        assertEquals(2, session2.edhoc.responderConnectionIdentifier.size, "C_R never equals C_I")
    }

    /**
     * RFC 9668 §5: two EDHOC resources for two application profiles share the OSCORE contexts. Each server
     * reserves C_R only among its own sessions, so both can choose the same one; the second context to
     * arrive must not replace the first.
     */
    @Test
    fun aRecipientIdTakenByAnotherWriterOfTheRepositoryIsNeverReplaced() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val established = mutableListOf<SecurityContext>()
        fun server() = EdhocServer(
            peers.responderConfig(), FixedShortIds(crypto), contexts,
            listener = { _, context -> established += context },
        )
        val first = server()
        val second = server()
        val alices = initiator(peers)
        alices.processMessage2(first.start(alice, alices).payload)
        val bobs = initiator(peers)
        bobs.processMessage2(second.start(bob, bobs).payload)
        val (aliceMessage3, aliceSession) = alices.createMessage3()
        val (bobMessage3, bobSession) = bobs.createMessage3()
        val responderId = aliceSession.responderConnectionIdentifier
        assertEquals(responderId, bobSession.responderConnectionIdentifier, "neither context was in place yet")

        assertEquals(CoapCode.CHANGED, first.continueWith(alice, responderId, aliceMessage3).code)
        val refused = second.continueWith(bob, responderId, bobMessage3)
        assertEquals(CoapCode.BAD_REQUEST, refused.code)
        assertEquals("Connection identifier in use", EdhocError.decode(refused.payload).diagnostic)
        assertSame(established.single(), contexts.find(responderId.bytes, null), "Alice's context stays")
        assertEquals(0, second.pendingSessions())
    }

    @Test
    fun noFreeCrIsAnEdhocError() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        // Every candidate the backend draws, of every length, is the Recipient ID of a context.
        for (length in 1..7) contexts.add(contextWithRecipientId(ByteArray(length) { FixedShortIds.BYTE }))
        val exhausted = FixedShortIds(crypto, everyLength = true)
        val server = EdhocServer(peers.responderConfig(), exhausted, contexts, maxPendingSessions = 1)
        repeat(2) {
            val response = server.start(alice, EdhocInitiator(peers.initiatorConfig(), crypto))
            assertEquals(CoapCode.BAD_REQUEST, response.code, "the failed session gave its place back")
            assertEquals("No free connection identifier", EdhocError.decode(response.payload).diagnostic)
        }
        assertEquals(0, server.pendingSessions())
    }

    // --- binding to the peer (RFC 9528 §9.7) -------------------------------------------------------------

    @Test
    fun aContinuationFromAnotherPeerNeitherTakesNorAbortsTheSession() = runTest {
        val peers = Peers.create(crypto)
        val counting = CountingCrypto(crypto)
        val events = mutableListOf<EdhocServerEvent>()
        val contexts = InMemoryContextRepository()
        val server = EdhocServer(peers.responderConfig(), counting, contexts, eventListener = { events += it })
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        initiator.processMessage2(server.start(alice, initiator).payload)
        val (message3, session) = initiator.createMessage3()
        val responderId = session.responderConnectionIdentifier
        val operations = counting.operations

        // Mallory replays Alice's message_3, aborts with an error message, and tries the combined request.
        val replayed = server.continueWith(mallory, responderId, message3)
        assertEquals(CoapCode.BAD_REQUEST, replayed.code)
        assertEquals(unknownSession, EdhocError.decode(replayed.payload), "exactly the answer for an unknown C_R")
        val abort = server.continueWith(mallory, responderId, bye)
        assertEquals(CoapCode.BAD_REQUEST, abort.code, "not the 2.04 of an abort that took effect")
        val combined = assertFailsWith<EdhocException> { server.completeCombined(responderId, message3, mallory) }
        assertEquals(unknownSession, combined.error)
        assertEquals(operations, counting.operations, "a foreign continuation costs no cryptography")
        assertEquals(1, server.pendingSessions())
        val expected = "ContinuationFromAnotherPeer(peer=$mallory, C_R=$responderId)"
        assertEquals(List(3) { expected }, events.map { it.toString() })
        assertTrue(events.all { it.peer == mallory })

        // Alice's own message_3 still completes the session.
        assertEquals(CoapCode.CHANGED, server.continueWith(alice, responderId, message3).code)
        assertNotNull(contexts.find(responderId.bytes, null))
        assertEquals(0, server.pendingSessions())
    }

    /** A DTLS peer is its session: a new epoch is the same peer, another session is not. */
    @Test
    fun aDtlsPeerIsItsSessionNotItsEpoch() = runTest {
        val peers = Peers.create(crypto)
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        fun secure(session: Long, epoch: Int) = Endpoint.Secure(SessionId(session), epoch, alice, PeerIdentity.None)
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        initiator.processMessage2(server.start(secure(1, 1), initiator).payload)
        val (message3, session) = initiator.createMessage3()
        val responderId = session.responderConnectionIdentifier
        assertEquals(CoapCode.BAD_REQUEST, server.continueWith(secure(2, 1), responderId, message3).code)
        assertEquals(CoapCode.CHANGED, server.continueWith(secure(1, 2), responderId, message3).code)
    }

    @Test
    fun theCombinedRequestCompletesOnlyForItsOwnPeer() = runTest {
        val peers = Peers.create(crypto)
        val server = EdhocServer(peers.responderConfig(), crypto, InMemoryContextRepository())
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        initiator.processMessage2(server.start(alice, initiator).payload)
        val (message3, session) = initiator.createMessage3()
        val responderId = session.responderConnectionIdentifier
        assertFailsWith<EdhocException> { server.completeCombined(responderId, message3, bob) }
        val context = server.completeCombined(responderId, message3, alice)
        assertContentEquals(responderId.bytes, context.recipientId)
        val again = assertFailsWith<EdhocException> { server.completeCombined(responderId, message3, alice) }
        assertEquals(unknownSession, again.error, "a session completes once")
    }

    // --- bounds and expiry -------------------------------------------------------------------------------

    @Test
    fun boundsRefuseNewSessionsBeforeAnyCryptography() = runTest {
        val peers = Peers.create(crypto)
        val counting = CountingCrypto(crypto)
        val events = mutableListOf<EdhocServerEvent>()
        val server = EdhocServer(
            peers.responderConfig(), counting, InMemoryContextRepository(),
            maxPendingSessions = 3, maxPendingSessionsPerPeer = 2, timeSource = TestTimeSource(),
            eventListener = { events += it },
        )
        suspend fun start(peer: Endpoint): CoapMessage = server.start(peer, initiator(peers))

        val aliceFirst = EdhocInitiator(peers.initiatorConfig(), crypto)
        val accepted = server.start(alice, aliceFirst)
        assertEquals(CoapCode.CHANGED, accepted.code)
        assertEquals(CoapCode.CHANGED, start(alice).code)
        val operations = counting.operations
        val perPeer = start(alice)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, perPeer.code)
        assertEquals("Too many pending EDHOC sessions", EdhocError.decode(perPeer.payload).diagnostic)
        assertEquals(operations, counting.operations, "refused before message_1 is processed")
        assertEquals(CoapCode.CHANGED, start(bob).code)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, start(carol).code)
        assertEquals(
            listOf(
                "LimitReached(peer=$alice, limit=PENDING_SESSIONS_PER_PEER)",
                "LimitReached(peer=$carol, limit=PENDING_SESSIONS)",
            ),
            events.map { it.toString() },
        )
        assertEquals(3, server.pendingSessions(), "within the grace period, no session is evicted for a new one")

        // A completed session gives its place back.
        aliceFirst.processMessage2(accepted.payload)
        val (message3, session) = aliceFirst.createMessage3()
        assertEquals(CoapCode.CHANGED, server.continueWith(alice, session.responderConnectionIdentifier, message3).code)
        assertEquals(CoapCode.CHANGED, start(carol).code)
    }

    /**
     * A full server refuses only while every waiting session is within its grace period. Past it, the
     * oldest makes room for a new client and is wiped, so a few message_1 per lifetime cannot keep new
     * clients out (RFC 9528 §9.7). The per-peer bound never evicts.
     */
    @Test
    fun aFullServerEvictsTheOldestSessionPastTheGracePeriod() = runTest {
        val peers = Peers.staticDiffieHellman(crypto)
        val clock = TestTimeSource()
        val ephemeralKeys = mutableListOf<SoftwarePrivateKey>()
        val events = mutableListOf<EdhocServerEvent>()
        val server = EdhocServer(
            peers.responderConfig(ephemeralKeys = recording(ephemeralKeys)), crypto, InMemoryContextRepository(),
            maxPendingSessions = 3, maxPendingSessionsPerPeer = 2, pendingSessionLifetime = 60.seconds,
            pendingSessionGracePeriod = 5.seconds, timeSource = clock, eventListener = { events += it },
        )
        val oldest = initiator(peers)
        oldest.processMessage2(server.start(alice, oldest).payload)
        val (oldestMessage3, oldestSession) = oldest.createMessage3()
        clock += 1.seconds
        val younger = initiator(peers)
        younger.processMessage2(server.start(alice, younger).payload)
        assertEquals(CoapCode.CHANGED, server.start(bob, initiator(peers)).code)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, server.start(carol, initiator(peers)).code, "all within grace")

        clock += 4500.milliseconds
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, server.start(alice, initiator(peers)).code, "Alice has 2")
        assertEquals(3, server.pendingSessions())
        val newcomer = initiator(peers)
        val message2 = server.start(carol, newcomer)
        assertEquals(CoapCode.CHANGED, message2.code, "the oldest session waited 5.5 s and made room")
        assertEquals(3, server.pendingSessions())
        assertFailsWith<IllegalStateException>("Y is wiped") { ephemeralKeys[0].encoded() }
        ephemeralKeys[1].encoded() // the younger session keeps its Y
        val evictedId = oldestSession.responderConnectionIdentifier
        assertEquals(
            listOf(
                "LimitReached(peer=$carol, limit=PENDING_SESSIONS)",
                "LimitReached(peer=$alice, limit=PENDING_SESSIONS_PER_PEER)",
                "SessionEvicted(peer=$alice, C_R=$evictedId)",
            ),
            events.map { it.toString() },
        )
        assertEquals(unknownSession, EdhocError.decode(server.continueWith(alice, evictedId, oldestMessage3).payload))

        // The new client and the younger session complete.
        newcomer.processMessage2(message2.payload)
        val (newcomerMessage3, newcomerSession) = newcomer.createMessage3()
        val completed = server.continueWith(carol, newcomerSession.responderConnectionIdentifier, newcomerMessage3)
        assertEquals(CoapCode.CHANGED, completed.code)
        val (youngerMessage3, youngerSession) = younger.createMessage3()
        val youngerId = youngerSession.responderConnectionIdentifier
        assertEquals(CoapCode.CHANGED, server.continueWith(alice, youngerId, youngerMessage3).code)
    }

    @Test
    fun failedAndAbortedSessionsGiveTheirPlaceBack() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        val server = EdhocServer(peers.responderConfig(), crypto, contexts, maxPendingSessionsPerPeer = 1)
        for (aborts in listOf(false, true)) {
            val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
            initiator.processMessage2(server.start(alice, initiator).payload)
            val (message3, session) = initiator.createMessage3()
            val tampered = message3.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            val responderId = session.responderConnectionIdentifier
            val response = server.continueWith(alice, responderId, if (aborts) bye else tampered)
            assertEquals(if (aborts) CoapCode.CHANGED else CoapCode.BAD_REQUEST, response.code)
            assertEquals(0, server.pendingSessions())
        }
        assertEquals(CoapCode.CHANGED, server.start(alice, initiator(peers)).code)
        // A malformed message_1 gives its place back too.
        val malformed = EdhocOverCoap.message1Request(byteArrayOf(0))
        repeat(2) { assertEquals(CoapCode.BAD_REQUEST, server.handle(malformed, bob).code) }
    }

    @Test
    fun aFailingEventListenerChangesNothing() = runTest {
        val peers = Peers.create(crypto)
        val clock = TestTimeSource()
        val server = EdhocServer(
            peers.responderConfig(), crypto, InMemoryContextRepository(), maxPendingSessions = 1,
            pendingSessionLifetime = 1.seconds, timeSource = clock,
            eventListener = { throw IllegalStateException("boom") },
        )
        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        val message2 = server.start(alice, initiator)
        assertEquals(CoapCode.CHANGED, message2.code)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, server.start(bob, initiator(peers)).code)
        initiator.processMessage2(message2.payload)
        val (message3, session) = initiator.createMessage3()
        val responderId = session.responderConnectionIdentifier
        assertEquals(CoapCode.BAD_REQUEST, server.continueWith(bob, responderId, message3).code)
        clock += 2.seconds
        val next = server.start(bob, initiator(peers))
        assertEquals(CoapCode.CHANGED, next.code, "the expired session gave its place back")
    }

    /**
     * RFC 9528 §9.7 and §9.8: a session whose message_3 does not come within the lifetime is dropped, on the
     * injected clock, and its ephemeral key Y wiped. The client's error message wipes Y as well.
     */
    @Test
    fun pendingSessionsExpireAndAreWiped() = runTest {
        val peers = Peers.staticDiffieHellman(crypto)
        val clock = TestTimeSource()
        val ephemeralKeys = mutableListOf<PrivateKey>()
        val recording = EphemeralKeySource { backend, type ->
            backend.generateKeyPair(type).also { ephemeralKeys += it.privateKey }
        }
        val events = mutableListOf<EdhocServerEvent>()
        val server = EdhocServer(
            peers.responderConfig(ephemeralKeys = recording), crypto, InMemoryContextRepository(),
            pendingSessionLifetime = 10.seconds, timeSource = clock, eventListener = { events += it },
        )

        val initiator = EdhocInitiator(peers.initiatorConfig(), crypto)
        initiator.processMessage2(server.start(alice, initiator).payload)
        val (message3, session) = initiator.createMessage3()
        val y = ephemeralKeys.single() as SoftwarePrivateKey
        clock += 9.seconds
        assertEquals(1, server.pendingSessions())
        y.encoded() // still needed for G_IY
        clock += 2.seconds
        assertEquals(0, server.pendingSessions())
        assertFailsWith<IllegalStateException>("Y is wiped") { y.encoded() }
        val responderId = session.responderConnectionIdentifier
        assertEquals(listOf("SessionExpired(peer=$alice, C_R=$responderId)"), events.map { it.toString() })
        assertEquals(unknownSession, EdhocError.decode(server.continueWith(alice, responderId, message3).payload))

        // The client aborts with an error message instead of message_3.
        val second = EdhocInitiator(peers.initiatorConfig(), crypto)
        second.processMessage2(server.start(alice, second).payload)
        val secondId = second.createMessage3().session.responderConnectionIdentifier
        assertEquals(CoapCode.CHANGED, server.continueWith(alice, secondId, bye).code)
        assertFailsWith<IllegalStateException>("Y is wiped") { (ephemeralKeys.last() as SoftwarePrivateKey).encoded() }
    }

    @Test
    fun configurationIsValidated() = runTest {
        val peers = Peers.create(crypto)
        val contexts = InMemoryContextRepository()
        assertFailsWith<IllegalArgumentException> {
            EdhocServer(peers.responderConfig(), crypto, contexts, maxPendingSessionsPerPeer = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EdhocServer(peers.responderConfig(), crypto, contexts, pendingSessionLifetime = 0.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            EdhocServer(peers.responderConfig(), crypto, contexts, pendingSessionGracePeriod = (-1).seconds)
        }
    }

    // --- sequence numbers --------------------------------------------------------------------------------

    @Test
    fun bothSidesDeriveTheirContextsWithTheGivenSequenceStore() = runTest {
        val peers = Peers.create(crypto)
        val serverStore = RecordingStore()
        val clientStore = RecordingStore()
        val contexts = InMemoryContextRepository()
        val server = EdhocServer(peers.responderConfig(), crypto, contexts, sequenceStore = serverStore)
        val client = EdhocClientHandshake(peers.initiatorConfig(), crypto, sequenceStore = clientStore) {
            server.handle(it, alice)
        }

        val sequential = client.run()
        val serverContext = assertNotNull(contexts.find(sequential.edhoc.responderConnectionIdentifier.bytes, null))
        assertEquals(setOf(serverContext.name), serverStore.names)
        assertEquals(setOf(sequential.oscore.name), clientStore.names)

        val (combined, _) = client.runCombined()
        assertEquals(setOf(sequential.oscore.name, combined.oscore.name), clientStore.names)
    }

    // --- helpers ----------------------------------------------------------------------------------------

    /** Random C_I, never [FixedShortIds.BYTE], so that byte is the first C_R candidate of every session. */
    private val clientIds = ConnectionIdentifierProvider { backend, peer ->
        var identifier: ConnectionIdentifier
        do {
            identifier = ConnectionIdentifierProvider.Random.choose(backend, peer)
        } while (identifier.bytes.contentEquals(byteArrayOf(FixedShortIds.BYTE)))
        identifier
    }

    private fun handshake(peers: Peers, server: EdhocServer, peer: Endpoint) =
        EdhocClientHandshake(peers.initiatorConfig(connectionIdentifiers = clientIds), crypto) {
            server.handle(it, peer)
        }

    private fun initiator(peers: Peers) =
        EdhocInitiator(peers.initiatorConfig(connectionIdentifiers = clientIds), crypto)

    /** Records every ephemeral key the server generates; they are software keys. */
    private fun recording(keys: MutableList<SoftwarePrivateKey>) = EphemeralKeySource { backend, type ->
        backend.generateKeyPair(type).also { keys += it.privateKey as SoftwarePrivateKey }
    }

    private suspend fun EdhocServer.start(peer: Endpoint, initiator: EdhocInitiator): CoapMessage =
        handle(EdhocOverCoap.message1Request(initiator.createMessage1()), peer)

    private suspend fun EdhocServer.continueWith(
        peer: Endpoint,
        responderId: ConnectionIdentifier,
        message: ByteArray,
    ): CoapMessage = handle(EdhocOverCoap.continuationRequest(responderId, message), peer)

    /** The server's context for [session] is in [contexts], with the client's IDs swapped (RFC 9528 Table 14). */
    private suspend fun assertServerContextMatches(contexts: InMemoryContextRepository, session: EdhocOscoreSession) {
        val context = assertNotNull(contexts.find(session.edhoc.responderConnectionIdentifier.bytes, null))
        assertContentEquals(session.oscore.recipientId, context.senderId)
        assertContentEquals(session.oscore.senderId, context.recipientId)
    }

    private suspend fun contextWithRecipientId(recipientId: ByteArray): SecurityContext = SecurityContext.derive(
        SecurityContextParameters(ByteArray(16) { 1 }, senderId = byteArrayOf(0x77), recipientId = recipientId),
        crypto,
        EphemeralSequenceNumberStore(),
    )

    /**
     * [delegate] with every one-byte random value fixed (with [everyLength], every random value), so the
     * C_R candidates of all sessions collide.
     */
    private class FixedShortIds(
        private val delegate: CryptoBackend,
        private val byte: Byte = BYTE,
        private val everyLength: Boolean = false,
    ) : CryptoBackend by delegate {
        override suspend fun randomBytes(size: Int): ByteArray =
            if (size == 1 || everyLength) ByteArray(size) { byte } else delegate.randomBytes(size)

        companion object {
            const val BYTE: Byte = 0x2a
        }
    }

    /** [delegate], counting the operations a message_1 or message_3 would cost. */
    private class CountingCrypto(private val delegate: CryptoBackend) : CryptoBackend by delegate {
        var operations = 0
            private set

        override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
            delegate.digest(algorithm, data).also { operations++ }

        override suspend fun hmac(algorithm: HashAlgorithm, key: ByteArray, data: ByteArray): ByteArray =
            delegate.hmac(algorithm, key, data).also { operations++ }

        override suspend fun aead(algorithm: AeadAlgorithm, key: ByteArray): Aead =
            delegate.aead(algorithm, key).also { operations++ }

        override suspend fun verify(
            algorithm: SignatureAlgorithm,
            publicKey: ByteArray,
            data: ByteArray,
            signature: ByteArray,
        ): Boolean = delegate.verify(algorithm, publicKey, data, signature).also { operations++ }
    }
}
