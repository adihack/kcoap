package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborReader
import dev.kcoap.cbor.CborType
import dev.kcoap.coap.CoapMessage
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
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SequenceNumberStore
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Called when an EDHOC session completes and its OSCORE context is in place. */
public fun interface EdhocSessionListener {
    public suspend fun onEstablished(session: EdhocSession, context: SecurityContext)
}

/**
 * The server (EDHOC Responder) side of EDHOC over CoAP: runs one [EdhocResponder] per session, keyed by
 * the C_R it chose, and turns every completed session into an OSCORE context in [contexts].
 *
 * - [handle] is the logic of the EDHOC resource (RFC 9528 Appendix A.2): message_1 starts a session and
 *   gets message_2, message_3 completes it (with message_4 when [sendMessage4] is set).
 * - [completeCombined] completes a session from the message_3 of an EDHOC + OSCORE request (RFC 9668).
 *
 * C_R is chosen as RFC 9668 §4.1.2 requires: unused by other sessions, different from C_I, and not the
 * Recipient ID of a context in [contexts]. It is reserved in the same locked step that checks it, before
 * message_2 is composed, and stays reserved until the session's OSCORE context is in [contexts], so
 * concurrent handshakes never share a C_R or collide with a context being added.
 *
 * Until message_3 is verified, nothing about the client is authenticated, so the state it can make the
 * server keep is bounded (RFC 9528 §9.7):
 * - A session waits for message_3 only from the peer that sent message_1. A message_3, an error message
 *   or an EDHOC + OSCORE request from any other peer is answered as for an unknown C_R, costs no
 *   cryptography and leaves the session waiting. Only this unauthenticated state is bound to the peer's
 *   address; OSCORE contexts are not.
 * - At most [maxPendingSessions] sessions are in progress, at most [maxPendingSessionsPerPeer] of them for
 *   one peer. A message_1 beyond either bound is refused before any processing, with an EDHOC error in a
 *   5.00 response, and reported as [EdhocServerEvent.LimitReached]; sessions in progress are never
 *   dropped for a new one.
 * - A session that has not received message_3 within [pendingSessionLifetime] (EXCHANGE_LIFETIME by
 *   default) expires; its secrets are wiped and [EdhocServerEvent.SessionExpired] reported.
 *
 * Flooding with message_1 from spoofed addresses is not stopped by these bounds: that takes an Echo
 * reachability check before message_2 (RFC 9528 Appendix A.2, RFC 9175), which belongs to the CoAP server.
 *
 * @param sequenceStore holds the Sender Sequence Numbers of every context this server derives, each under
 *   its own name. The ephemeral default fits EDHOC, whose keys are new with every session; pass a durable
 *   store to resume contexts after a restart, as [oscoreParameters] describes.
 * @param timeSource the clock of [pendingSessionLifetime].
 * @param eventListener receives the [EdhocServerEvent]s.
 */
public class EdhocServer(
    private val config: EdhocResponderConfig,
    private val crypto: CryptoBackend,
    private val contexts: InMemoryContextRepository,
    private val sendMessage4: Boolean = false,
    private val maxPendingSessions: Int = 64,
    private val maxPendingSessionsPerPeer: Int = 8,
    private val pendingSessionLifetime: Duration = TransmissionParameters.Default.exchangeLifetime,
    private val sequenceStore: SequenceNumberStore = EphemeralSequenceNumberStore(),
    timeSource: TimeSource = TimeSource.Monotonic,
    private val listener: EdhocSessionListener = EdhocSessionListener { _, _ -> },
    eventListener: EdhocServerEventListener = EdhocServerEventListener.None,
    private val loggers: LoggerFactory = LoggerFactory.Global,
) {
    init {
        require(maxPendingSessions > 0) { "maxPendingSessions must be positive" }
        require(maxPendingSessionsPerPeer > 0) { "maxPendingSessionsPerPeer must be positive" }
        require(pendingSessionLifetime.isPositive()) { "pendingSessionLifetime must be positive" }
    }

    private val log = loggers.logger(LogTags.EDHOC).child("server")
    private val pending = PendingSessions(
        maxPendingSessions, maxPendingSessionsPerPeer, pendingSessionLifetime, timeSource, eventListener, log,
    )

    /** Sessions waiting for message_3, after dropping the expired ones. */
    public suspend fun pendingSessions(): Int = pending.waitingCount()

    /**
     * Answers a POST from [peer] to the EDHOC resource. Never throws for peer errors: every failure becomes
     * the EDHOC error response of RFC 9528 Appendix A.2.3.
     */
    public suspend fun handle(request: CoapMessage, peer: Endpoint): CoapMessage {
        val parsed = try {
            EdhocOverCoap.parseRequest(request.payload)
        } catch (e: IllegalArgumentException) {
            return EdhocOverCoap.errorResponse(request, EdhocError.unspecified("Malformed EDHOC request").encode())
        }
        return when (parsed) {
            is EdhocOverCoap.ServerRequest.NewSession -> startSession(request, parsed.message1, peer)
            is EdhocOverCoap.ServerRequest.Continuation ->
                continueSession(request, parsed.responderId, parsed.message, peer)
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
            return establish(taken.responder.processMessage3(message3))
        } finally {
            withContext(NonCancellable) { pending.release(taken.session) }
        }
    }

    private suspend fun startSession(request: CoapMessage, message1: ByteArray, peer: Endpoint): CoapMessage {
        val session = pending.admit(peer)
            ?: return EdhocOverCoap.errorResponse(request, TOO_MANY_SESSIONS.encode(), isServerError = true)
        var chosen: ConnectionIdentifier? = null
        val responder = EdhocResponder(
            config.withConnectionIdentifiers { backend, initiatorId ->
                allocate(session, backend, initiatorId).also { chosen = it }
            },
            crypto,
            loggers,
        )
        var isWaiting = false
        try {
            responder.processMessage1(message1)
            val message2 = responder.createMessage2()
            pending.await(session, responder)
            isWaiting = true
            log.debug { "Sent message_2 for C_R ${chosen?.bytes?.toHex()} to $peer" }
            return EdhocOverCoap.messageResponse(request, message2)
        } catch (e: EdhocException) {
            log.warn { "Rejected message_1 from $peer: ${e.message}" }
            return errorResponse(request, e)
        } finally {
            if (!isWaiting) {
                // A failed step has aborted already; this wipes Y and PRK_3e2m when cancellation came after it.
                responder.abort("The session did not start")
                withContext(NonCancellable) { pending.release(session) }
            }
        }
    }

    private suspend fun continueSession(
        request: CoapMessage,
        responderId: ConnectionIdentifier,
        message: ByteArray,
        peer: Endpoint,
    ): CoapMessage {
        val taken = pending.take(responderId, peer)
            ?: return EdhocOverCoap.errorResponse(request, UNKNOWN_SESSION.encode())
        try {
            if (isErrorMessage(message)) {
                // The client gave up (RFC 9528 §6); error messages are never answered with another one.
                taken.responder.abort("The client aborted the session")
                log.info { "The client $peer aborted session ${responderId.bytes.toHex()}" }
                return EdhocOverCoap.completedResponse(request)
            }
            establish(taken.responder.processMessage3(message))
            return if (sendMessage4) {
                EdhocOverCoap.messageResponse(request, taken.responder.createMessage4())
            } else {
                EdhocOverCoap.completedResponse(request)
            }
        } catch (e: EdhocException) {
            log.warn { "Rejected message_3 from $peer: ${e.message}" }
            return errorResponse(request, e)
        } finally {
            withContext(NonCancellable) { pending.release(taken.session) }
        }
    }

    private suspend fun establish(session: EdhocSession): SecurityContext {
        val context = session.oscoreContext(crypto, sequenceStore)
        contexts.add(context)
        log.info { "EDHOC session established: $context, suite ${session.cipherSuite.value}" }
        listener.onEstablished(session, context)
        return context
    }

    private fun errorResponse(request: CoapMessage, e: EdhocException): CoapMessage =
        EdhocOverCoap.errorResponse(request, e.errorMessage ?: EdhocError.unspecified("EDHOC failed").encode())

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
