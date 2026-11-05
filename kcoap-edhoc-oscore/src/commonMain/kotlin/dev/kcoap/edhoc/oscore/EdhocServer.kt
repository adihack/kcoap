package dev.kcoap.edhoc.oscore

import dev.kcoap.cbor.CborReader
import dev.kcoap.cbor.CborType
import dev.kcoap.coap.CoapMessage
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.ConnectionIdentifier
import dev.kcoap.edhoc.ConnectionIdentifierProvider
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocException
import dev.kcoap.edhoc.EdhocResponder
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.oscore.InMemoryContextRepository
import dev.kcoap.oscore.SecurityContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * C_R is chosen as RFC 9668 §4.1.2 requires: unused by pending sessions, different from C_I, and not
 * the Recipient ID of a context in [contexts]. At most [maxPendingSessions] sessions wait for message_3;
 * the oldest is dropped beyond that, which bounds the memory unauthenticated clients can claim.
 */
public class EdhocServer(
    private val config: EdhocResponderConfig,
    private val crypto: CryptoBackend,
    private val contexts: InMemoryContextRepository,
    private val sendMessage4: Boolean = false,
    private val maxPendingSessions: Int = 64,
    private val listener: EdhocSessionListener = EdhocSessionListener { _, _ -> },
    private val loggers: LoggerFactory = LoggerFactory.Global,
) {
    init {
        require(maxPendingSessions > 0) { "maxPendingSessions must be positive" }
    }

    private val log = loggers.logger(LogTags.EDHOC).child("server")
    private val lock = Mutex()
    private val pending = LinkedHashMap<String, EdhocResponder>()

    /** Sessions waiting for message_3. */
    public suspend fun pendingSessions(): Int = lock.withLock { pending.size }

    /**
     * Answers a POST to the EDHOC resource. Never throws for peer errors: every failure becomes the
     * EDHOC error response of RFC 9528 Appendix A.2.3.
     */
    public suspend fun handle(request: CoapMessage): CoapMessage {
        val parsed = try {
            EdhocOverCoap.parseRequest(request.payload)
        } catch (e: IllegalArgumentException) {
            return EdhocOverCoap.errorResponse(request, EdhocError.unspecified("Malformed EDHOC request").encode())
        }
        return when (parsed) {
            is EdhocOverCoap.ServerRequest.NewSession -> startSession(request, parsed.message1)
            is EdhocOverCoap.ServerRequest.Continuation -> continueSession(request, parsed.responderId, parsed.message)
        }
    }

    /**
     * Processes the message_3 of an RFC 9668 EDHOC + OSCORE request for the session [responderId] (the
     * 'kid' of the request) and returns the new OSCORE context.
     *
     * @throws EdhocException when there is no such session or message_3 does not verify; its
     *   [EdhocException.errorMessage] is the error to send.
     */
    public suspend fun completeCombined(responderId: ConnectionIdentifier, message3: ByteArray): SecurityContext {
        val responder = take(responderId) ?: throw EdhocException(EdhocError.unspecified("No EDHOC session for C_R"))
        // RFC 9668 §3.3.1 step 4: the combined request cannot carry message_4.
        if (sendMessage4) throw EdhocException(EdhocError.unspecified("This EDHOC profile requires message_4"))
        responder.processMessage3(message3)
        return establish(responder.session!!)
    }

    private suspend fun startSession(request: CoapMessage, message1: ByteArray): CoapMessage {
        var chosen: ConnectionIdentifier? = null
        val responder = EdhocResponder(
            config.withConnectionIdentifiers { backend, peer -> allocate(backend, peer).also { chosen = it } },
            crypto,
            loggers,
        )
        return try {
            responder.processMessage1(message1)
            val message2 = responder.createMessage2()
            remember(chosen!!, responder)
            log.debug { "Sent message_2 for C_R ${chosen.bytes.toHex()}" }
            EdhocOverCoap.messageResponse(request, message2)
        } catch (e: EdhocException) {
            log.warn { "Rejected message_1: ${e.message}" }
            errorResponse(request, e)
        }
    }

    private suspend fun continueSession(request: CoapMessage, responderId: ConnectionIdentifier, message: ByteArray): CoapMessage {
        val responder = take(responderId)
            ?: return EdhocOverCoap.errorResponse(request, EdhocError.unspecified("No EDHOC session for C_R").encode())
        if (isErrorMessage(message)) {
            // The client gave up (RFC 9528 §6); error messages are never answered with another one.
            log.info { "The client aborted session ${responderId.bytes.toHex()}" }
            return EdhocOverCoap.completedResponse(request)
        }
        return try {
            responder.processMessage3(message)
            val session = responder.session!!
            establish(session)
            if (sendMessage4) EdhocOverCoap.messageResponse(request, responder.createMessage4()) else EdhocOverCoap.completedResponse(request)
        } catch (e: EdhocException) {
            log.warn { "Rejected message_3: ${e.message}" }
            errorResponse(request, e)
        }
    }

    private suspend fun establish(session: EdhocSession): SecurityContext {
        val context = session.oscoreContext(crypto)
        contexts.add(context)
        log.info { "EDHOC session established: $context, suite ${session.cipherSuite.value}" }
        listener.onEstablished(session, context)
        return context
    }

    private fun errorResponse(request: CoapMessage, e: EdhocException): CoapMessage =
        EdhocOverCoap.errorResponse(request, e.errorMessage ?: EdhocError.unspecified("EDHOC failed").encode())

    private suspend fun remember(responderId: ConnectionIdentifier, responder: EdhocResponder) = lock.withLock {
        pending[responderId.bytes.toHex()] = responder
        while (pending.size > maxPendingSessions) {
            val oldest = pending.keys.first()
            pending.remove(oldest)
            log.warn { "Dropped pending EDHOC session $oldest: too many open sessions" }
        }
    }

    private suspend fun take(responderId: ConnectionIdentifier): EdhocResponder? =
        lock.withLock { pending.remove(responderId.bytes.toHex()) }

    /** RFC 9668 §4.1.2: a C_R free among pending sessions and OSCORE Recipient IDs, and not C_I. */
    private suspend fun allocate(backend: CryptoBackend, initiatorId: ConnectionIdentifier?): ConnectionIdentifier {
        for (length in 1..MAX_IDENTIFIER_LENGTH) {
            repeat(ATTEMPTS_PER_LENGTH) {
                val candidate = ConnectionIdentifier(backend.randomBytes(length))
                val inUse = lock.withLock { candidate.bytes.toHex() in pending } ||
                    candidate == initiatorId ||
                    contexts.find(candidate.bytes, null) != null
                if (!inUse) return candidate
            }
        }
        throw EdhocException(EdhocError.unspecified("No free connection identifier"))
    }

    private companion object {
        const val MAX_IDENTIFIER_LENGTH = 7
        const val ATTEMPTS_PER_LENGTH = 8

        /** An EDHOC error message starts with an integer ERR_CODE; message_3 is a byte string. */
        fun isErrorMessage(message: ByteArray): Boolean =
            try {
                CborReader(message).peekType().let { it == CborType.UNSIGNED || it == CborType.NEGATIVE }
            } catch (_: Exception) {
                false
            }

        fun EdhocResponderConfig.withConnectionIdentifiers(provider: ConnectionIdentifierProvider): EdhocResponderConfig =
            EdhocResponderConfig(
                methods = methods,
                cipherSuites = cipherSuites,
                credentials = credentials,
                credentialResolver = credentialResolver,
                connectionIdentifiers = provider,
                eadHandler = eadHandler,
                ephemeralKeys = ephemeralKeys,
            )
    }
}
