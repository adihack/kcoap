package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborReader
import dev.kcoap.cbor.CborType
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.edhoc.EdhocResponder
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.MutableSecurityContextRepository
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The server (EDHOC Responder) side of EDHOC over CoAP: runs one [EdhocResponder] per session, keyed by
 * the C_R it chose, and turns every completed session into an OSCORE context in [contexts].
 *
 * - [handle] is the logic of the EDHOC resource (RFC 9528 Appendix A.2): message_1 starts a session and
 *   gets message_2, message_3 completes it (with message_4 when [sendMessage4] is set). [EdhocResource]
 *   mounts it in a CoAP server.
 * - [completeCombined] completes a session from the message_3 of an EDHOC + OSCORE request (RFC 9668), for
 *   [CombinedRequestServer].
 *
 * [contexts] is any [MutableSecurityContextRepository], usually the one the server's `OscoreServer` finds
 * contexts in. Contexts from EDHOC accumulate there, one per completed handshake, and this server never removes
 * them: a production server gives it a repository with a bound, which costs an evicted client only a new
 * handshake. The default [InMemoryContextRepository][dev.kcoap.oscore.InMemoryContextRepository] has none.
 *
 * C_R is chosen as RFC 9668 §4.1.2 requires: unused by other sessions, different from C_I, and not the
 * Recipient ID of a context in [contexts]. It is reserved in the same locked step that checks it, before
 * message_2 is composed, and stays reserved until the session's OSCORE context is in [contexts], so
 * concurrent handshakes of this server never share a C_R or collide with a context it is adding. The
 * context is added only if its Recipient ID is still free ([MutableSecurityContextRepository.addIfAbsent]):
 * when another writer of [contexts], such as a second EdhocServer for another application profile (RFC 9668
 * §5) or the application, took it meanwhile, the handshake fails with an EDHOC error instead of replacing
 * that context.
 *
 * Until message_3 is verified, nothing about the client is authenticated, so the state it can make the
 * server keep is bounded (RFC 9528 §9.7):
 * - A session waits for message_3 only from the peer that sent message_1. A message_3, an error message
 *   or an EDHOC + OSCORE request from any other peer is answered as for an unknown C_R, costs no
 *   cryptography and leaves the session waiting. Only this unauthenticated state is bound to the peer's
 *   address; OSCORE contexts are not.
 * - At most [maxPendingSessions] sessions are in progress, at most [maxPendingSessionsPerPeer] of them for
 *   one peer. A peer is its address and port (for DTLS, its session), so a host sending from many ports
 *   counts as many peers. A message_1 beyond the per-peer bound is refused before any processing, with an
 *   EDHOC error in a 5.00 response, and reported as [EdhocServerEvent.LimitReached].
 * - When all [maxPendingSessions] places are taken, the session that has waited longest for message_3 is
 *   dropped for the new one if it has waited longer than [pendingSessionGracePeriod], its secrets wiped
 *   and [EdhocServerEvent.SessionEvicted] reported. Only when no session has waited that long is the new
 *   one refused as above. A client whose message_3 arrives within the grace period is never evicted.
 * - A session that has not received message_3 within [pendingSessionLifetime] expires; its secrets are
 *   wiped and [EdhocServerEvent.SessionExpired] reported.
 *
 * These bounds do not stop a flood of message_1 from spoofed addresses: each costs a key generation, a
 * Diffie-Hellman and a signature, and [maxPendingSessions] of them per [pendingSessionGracePeriod]
 * (6.4 per second by default) keep new clients out while the flood lasts. That takes an Echo
 * reachability check before message_2 (RFC 9528 Appendix A.2, RFC 9175), which belongs to the CoAP server.
 *
 * The cipher suites [config] names are narrowed once to those [crypto] runs, on first use, and the answer is
 * kept with [config] for every later session (docs/adr/0001-crypto-capabilities.md).
 *
 * @param contexts where the OSCORE context of every completed session goes, and where C_R is checked
 *   against the Recipient IDs in use.
 * @param pendingSessionLifetime how long a session waits for message_3: by default MAX_TRANSMIT_WAIT
 *   (93 s, RFC 7252 §4.8.2), about as long as a client retransmits message_3 and waits for its answer.
 * @param pendingSessionGracePeriod how long a session waiting for message_3 is safe from eviction: by
 *   default 10 s, a round trip and the client's processing of message_2 with time to spare. Give it at least
 *   [pendingSessionLifetime] to never evict, so a full server only refuses.
 * @param sequenceStore holds the Sender Sequence Numbers of the contexts this server derives, under their
 *   names "sid=C_I,rid=C_R" ([dev.kcoap.oscore.SecurityContextParameters.name]). By default (null) each
 *   context gets its own [EphemeralSequenceNumberStore], which fits EDHOC, whose keys are new with every
 *   session, and goes away with the context. Pass a durable store to resume contexts after a restart, as
 *   [oscoreParameters] describes. A name is unique only among the contexts in [contexts], so do not share
 *   a durable store with contexts kept anywhere else.
 * @param timeSource the clock of [pendingSessionLifetime] and [pendingSessionGracePeriod].
 * @param listener told of every session that completes, with its context.
 * @param eventListener receives the [EdhocServerEvent]s.
 * @param loggerFactory where the server's logger comes from, under [LogTags.EDHOC].
 */
public class EdhocServer(
    private val config: EdhocResponderConfig,
    private val crypto: CryptoBackend,
    private val contexts: MutableSecurityContextRepository,
    private val sendMessage4: Boolean = false,
    private val maxPendingSessions: Int = 64,
    private val maxPendingSessionsPerPeer: Int = 8,
    private val pendingSessionLifetime: Duration = TransmissionParameters.Default.maxTransmitWait,
    private val pendingSessionGracePeriod: Duration = 10.seconds,
    private val sequenceStore: SequenceNumberStore? = null,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val listener: EdhocSessionListener = EdhocSessionListener { _, _ -> },
    eventListener: EdhocServerEventListener = EdhocServerEventListener.None,
    private val loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    init {
        require(maxPendingSessions > 0) { "maxPendingSessions must be positive" }
        require(maxPendingSessionsPerPeer > 0) { "maxPendingSessionsPerPeer must be positive" }
        require(pendingSessionLifetime.isPositive()) { "pendingSessionLifetime must be positive" }
        require(!pendingSessionGracePeriod.isNegative()) { "pendingSessionGracePeriod must not be negative" }
    }

    private val log = loggerFactory.logger(LogTags.EDHOC).child("server")
    private val pending = PendingSessions(
        maxPendingSessions,
        maxPendingSessionsPerPeer,
        pendingSessionLifetime,
        pendingSessionGracePeriod,
        timeSource,
        eventListener,
        log,
    )

    /** Sessions waiting for message_3, after dropping the expired ones. */
    public suspend fun pendingSessions(): Int = pending.waitingCount()

    /**
     * Answers a POST from [peer] to the EDHOC resource whose body is [payload] (RFC 9528 Appendix A.2.1):
     * a 2.04 with message_2 for `true, message_1`, a 2.04 with message_4 or without payload for
     * `C_R, message_3`, and a 2.04 for `C_R, error message`, which ends the session.
     *
     * It never throws for anything the peer sent: a malformed payload ([EdhocOverCoap.parseRequest]) and every
     * failed EDHOC step become the EDHOC error response of RFC 9528 Appendix A.2.3, a 4.00, or a 5.00 for a
     * message_1 refused over the bounds on pending sessions.
     */
    public suspend fun handle(payload: ByteArray, peer: Endpoint): CoapResponse {
        val parsed = try {
            EdhocOverCoap.parseRequest(payload)
        } catch (e: EdhocException) {
            log.warn { "Rejected a request from $peer: ${e.message}" }
            return errorResponse(e)
        }
        return when (parsed) {
            is EdhocOverCoap.ServerRequest.NewSession -> startSession(parsed.message1, peer)
            is EdhocOverCoap.ServerRequest.Continuation -> continueSession(parsed.responderId, parsed.message, peer)
        }
    }

    /**
     * Processes the message_3 of an RFC 9668 EDHOC + OSCORE request from [peer] for the session
     * [responderId] (the 'kid' of the request) and returns the new OSCORE context.
     *
     * @throws EdhocException when [peer] has no such session or message_3 does not verify; its
     *   [EdhocException.errorMessage] is the error to send.
     */
    public suspend fun completeCombined(
        responderId: ConnectionIdentifier,
        message3: ByteArray,
        peer: Endpoint,
    ): SecurityContext {
        val taken = pending.take(responderId, peer) ?: throw EdhocException(UNKNOWN_SESSION)
        try {
            if (sendMessage4) {
                // RFC 9668 §3.3.1 step 4: the combined request cannot carry message_4.
                taken.responder.abort(MESSAGE_4_REQUIRED)
                throw EdhocException(EdhocError.unspecified(MESSAGE_4_REQUIRED))
            }
            return establish(taken.responder, taken.responder.processMessage3(message3))
        } finally {
            withContext(NonCancellable) { pending.release(taken.session) }
        }
    }

    private suspend fun startSession(message1: ByteArray, peer: Endpoint): CoapResponse {
        val session = pending.admit(peer)
            ?: return EdhocOverCoap.errorResponse(TOO_MANY_SESSIONS.encode(), isServerError = true)
        var chosen: ConnectionIdentifier? = null
        val responder = EdhocResponder(
            config.withConnectionIdentifiers { backend, initiatorId ->
                allocate(session, backend, initiatorId).also { chosen = it }
            },
            crypto,
            loggerFactory,
        )
        var isWaiting = false
        try {
            responder.processMessage1(message1)
            val message2 = responder.createMessage2()
            pending.await(session, responder)
            isWaiting = true
            log.debug { "Sent message_2 for C_R ${chosen?.bytes?.toHex()} to $peer" }
            return EdhocOverCoap.messageResponse(message2)
        } catch (e: EdhocException) {
            log.warn { "Rejected message_1 from $peer: ${e.message}" }
            return errorResponse(e)
        } finally {
            if (!isWaiting) {
                // A failed step has aborted already; this wipes Y and PRK_3e2m when cancellation came after it.
                responder.abort("The session did not start")
                withContext(NonCancellable) { pending.release(session) }
            }
        }
    }

    private suspend fun continueSession(responderId: ConnectionIdentifier, message: ByteArray, peer: Endpoint): CoapResponse {
        val taken = pending.take(responderId, peer)
            ?: return EdhocOverCoap.errorResponse(UNKNOWN_SESSION.encode())
        try {
            if (isErrorMessage(message)) {
                // The client gave up (RFC 9528 §6); error messages are never answered with another one.
                taken.responder.abort("The client aborted the session")
                log.info { "The client $peer aborted session ${responderId.bytes.toHex()}" }
                return EdhocOverCoap.completedResponse()
            }
            establish(taken.responder, taken.responder.processMessage3(message))
            return if (sendMessage4) {
                EdhocOverCoap.messageResponse(taken.responder.createMessage4())
            } else {
                EdhocOverCoap.completedResponse()
            }
        } catch (e: EdhocException) {
            log.warn { "Rejected message_3 from $peer: ${e.message}" }
            return errorResponse(e)
        } finally {
            withContext(NonCancellable) { pending.release(taken.session) }
        }
    }

    private suspend fun establish(responder: EdhocResponder, session: EdhocSession): SecurityContext {
        val context = session.oscoreContext(crypto, sequenceStore ?: EphemeralSequenceNumberStore())
        if (!contexts.addIfAbsent(context)) {
            // Another writer of the repository took this Recipient ID after allocate() found it free: its
            // context stays, this session ends (RFC 9668 §4.1.2).
            log.warn { "Context $context not added: its Recipient ID is in use" }
            responder.abort(IDENTIFIER_IN_USE)
            session.destroy()
            throw EdhocException(EdhocError.unspecified(IDENTIFIER_IN_USE))
        }
        log.info { "EDHOC session established: $context, suite ${session.cipherSuite.value}" }
        listener.onEstablished(session, context)
        return context
    }

    private fun errorResponse(e: EdhocException): CoapResponse =
        EdhocOverCoap.errorResponse(e.errorMessage ?: EdhocError.unspecified("EDHOC failed").encode())

    /**
     * RFC 9668 §4.1.2: a C_R that is not C_I, not reserved by another session and not the Recipient ID of a
     * context. The reservation comes first and the repository is asked outside the lock: a session keeps its
     * C_R reserved until its context is in [contexts], so no context of this server can appear under a C_R
     * between the two steps.
     */
    private suspend fun allocate(
        session: PendingSessions.Session,
        backend: CryptoBackend,
        initiatorId: ConnectionIdentifier?,
    ): ConnectionIdentifier {
        for (length in 1..MAX_IDENTIFIER_LENGTH) {
            repeat(ATTEMPTS_PER_LENGTH) {
                val candidate = ConnectionIdentifier(backend.randomBytes(length))
                if (candidate != initiatorId && pending.reserve(session, candidate)) {
                    if (contexts.find(candidate.bytes, null) == null) return candidate
                    pending.unreserve(session)
                }
            }
        }
        throw EdhocException(EdhocError.unspecified("No free connection identifier"))
    }

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 7
        const val ATTEMPTS_PER_LENGTH = 8
        const val MESSAGE_4_REQUIRED = "This EDHOC profile requires message_4"
        const val IDENTIFIER_IN_USE = "Connection identifier in use"

        /** Also the answer to a continuation from a peer other than the session's (RFC 9528 §9.7). */
        val UNKNOWN_SESSION = EdhocError.unspecified("No EDHOC session for C_R")
        val TOO_MANY_SESSIONS = EdhocError.unspecified("Too many pending EDHOC sessions")

        /** An EDHOC error message starts with an integer ERR_CODE; message_3 is a byte string. */
        fun isErrorMessage(message: ByteArray): Boolean =
            try {
                CborReader(message).peekType().let { it == CborType.UNSIGNED || it == CborType.NEGATIVE }
            } catch (_: Exception) {
                false
            }
    }
}
