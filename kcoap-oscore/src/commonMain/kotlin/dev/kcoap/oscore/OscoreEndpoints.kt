package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.common.readUnsigned
import dev.kcoap.common.toHex
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Chooses the security context for an outgoing request, or null to send it unprotected. */
public fun interface ClientContextResolver {
    public suspend fun contextFor(peer: Endpoint, request: CoapMessage): SecurityContext?
}

/**
 * Finds the Recipient Context for an incoming request by its 'kid' and optional 'kid context'
 * (RFC 8613 §8.2 step 2). Implementations may derive a context on demand, as Appendix B.2 and EDHOC do.
 */
public fun interface SecurityContextRepository {
    public suspend fun find(recipientId: ByteArray, idContext: ByteArray?): SecurityContext?
}

/** A repository holding contexts in memory, keyed by Recipient ID and ID Context. */
public class InMemoryContextRepository : SecurityContextRepository {
    private val lock = Mutex()
    private val contexts = mutableMapOf<String, SecurityContext>()

    /** Adds [context], replacing any context with the same Recipient ID and ID Context. */
    public suspend fun add(context: SecurityContext) {
        lock.withLock { contexts[key(context.recipientId, context.idContext)] = context }
    }

    /**
     * Adds [context] unless a context with the same Recipient ID and ID Context is present, in one step, and
     * returns whether it did. A writer that must never replace another's context, such as an EDHOC server
     * sharing the repository with other writers, uses this instead of [add].
     */
    public suspend fun addIfAbsent(context: SecurityContext): Boolean = lock.withLock {
        val key = key(context.recipientId, context.idContext)
        if (key in contexts) return@withLock false
        contexts[key] = context
        true
    }

    public suspend fun remove(context: SecurityContext) {
        lock.withLock { contexts.remove(key(context.recipientId, context.idContext)) }
    }

    /**
     * Without a 'kid context' in the request, a context is found by Recipient ID alone when exactly one
     * context has it (RFC 8613 §3.3 recommends unique Recipient IDs).
     */
    override suspend fun find(recipientId: ByteArray, idContext: ByteArray?): SecurityContext? = lock.withLock {
        if (idContext != null) return@withLock contexts[key(recipientId, idContext)]
        contexts[key(recipientId, null)]
            ?: contexts.values.singleOrNull { it.recipientId.contentEquals(recipientId) }
    }

    private fun key(recipientId: ByteArray, idContext: ByteArray?): String = "${recipientId.toHex()}/${idContext?.toHex() ?: "-"}"
}

/** The peer behind an OSCORE request: its Sender ID (our Recipient ID) and the ID Context. */
public class OscoreIdentity(recipientId: ByteArray, idContext: ByteArray?) : SecurityIdentity {
    private val rid = recipientId.copyOf()
    private val idc = idContext?.copyOf()

    public val recipientId: ByteArray get() = rid.copyOf()
    public val idContext: ByteArray? get() = idc?.copyOf()

    override val description: String get() = "OSCORE rid=${rid.toHex()}" + (idc?.let { " idc=${it.toHex()}" } ?: "")

    override fun equals(other: Any?): Boolean = other is OscoreIdentity && rid.contentEquals(other.rid) && idc.contentEquals(other.idc)

    override fun hashCode(): Int = rid.contentHashCode()

    override fun toString(): String = description
}

/**
 * The OSCORE client side for the CoAP stack: protects requests with the context [contexts] picks and
 * verifies the responses, including Observe notifications with their replay protection
 * (RFC 8613 §4.1.3.5.2, §7.4.1).
 *
 * **Replay of responses** (§7.4). A response is bound to its request, and a request takes one response, so a
 * second response to the same request is rejected, with or without a Partial IV. Two kinds of request take
 * several:
 * - An Observe registration takes notifications: at most one without a Partial IV, the others with a Partial
 *   IV above the Notification Number (§7.4.1, §8.4.2). A response without an Inner Observe option is verified
 *   as a non-Observe response (§4.1.3.5.2), so it takes one of those, which ends the observation.
 * - A request carrying an Inner Q-Block1 or Q-Block2 option (RFC 9177 §4) takes the responses of its body:
 *   the blocks of a Q-Block2 set and the sets that follow on its token, or a 2.31, 4.08s and the final
 *   response on the token of a Q-Block1 block (§4.3, §4.4, §10). At most one of them may reuse the request's
 *   nonce, without a Partial IV (§8.3 step 3); every other one must carry a Partial IV the request has not
 *   seen, within [SecurityContextParameters.replayWindowSize] (32 by default, §3.2.2) of the highest it has
 *   seen. That is an RFC 6347 sliding window per request, like the server's per context, so the blocks of a
 *   set may arrive in any order. A replayed or too old block is dropped like a response that fails
 *   verification (§8.4 step 8), and its body asks for it again (RFC 9177 §4.4).
 *
 * Several responses to one request fall outside RFC 8613's single-response model, so the CoAP stack uses
 * Q-Block under OSCORE only where both sides opt in (`QBlockClientConfig.withMessageSecurity` and
 * `QBlockServerConfig.withMessageSecurity` of kcoap-coap); otherwise protected bodies use Inner Block1 and
 * Block2 (RFC 7959; RFC 8613 §4.1.3.4.1), one response per block.
 *
 * **Unprotected responses** are not authenticated. The unprotected 4.xx and 5.xx errors of OSCORE and of
 * intermediaries (§8.2) are passed on to a request that takes one response. To a request carrying an Inner
 * Q-Block option only 4.00, 4.01 and 5.xx are, without Echo or a block option: those end the body, whereas an
 * unprotected 4.08, 4.13, 4.02, Echo challenge or block would steer it from outside OSCORE (RFC 9177 §11), so
 * they are dropped like a response that fails verification ([UnprotectedResponses]).
 */
public class OscoreClient(
    private val contexts: ClientContextResolver,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) : ClientMessageSecurity {
    private val log = loggerFactory.logger(LogTags.OSCORE).child("client")

    /** Protects every request with [context]. */
    public constructor(context: SecurityContext, loggerFactory: LoggerFactory = LoggerFactory.Global) :
        this(ClientContextResolver { _, _ -> context }, loggerFactory)

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        val context = contexts.contextFor(peer, request) ?: return null
        val (protected, binding) = try {
            OscoreProtection.protectRequest(context, request)
        } catch (e: OscoreException) {
            throw e.toMessageSecurityException()
        }
        log.debug { "Protected ${request.code} for $peer with $context, piv=${binding.requestPiv.toHex()}" }
        return ClientExchange(protected, binding)
    }

    private inner class ClientExchange(
        override val message: CoapMessage,
        private val binding: RequestBinding,
    ) : ProtectedRequest {
        private val lock = Mutex()

        /** Whether a response without a Partial IV used the request's nonce (§8.3 step 3), which one may. */
        private var usedRequestNonce = false

        /** How many responses that are not notifications were accepted. */
        private var responses = 0
        private var notificationNumber: Long = -1

        /** The Partial IVs of the responses to a request that takes several, from the first such response on. */
        private var responseWindow: ReplayWindow? = null

        override suspend fun unprotectResponse(response: CoapMessage): CoapMessage {
            if (response.options.oscore == null) {
                // OSCORE errors are sent unprotected (RFC 8613 §8.2) and are not authenticated: only those that
                // can do no more than end the request are passed on (UnprotectedResponses).
                val rejection = UnprotectedResponses.rejection(response, takesSeveral = binding.allowsSeveralResponses)
                    ?: return response
                log.warn { "Dropped a response: $rejection" }
                throw MessageSecurityException(rejection)
            }
            val (decrypted, piv) = try {
                OscoreProtection.unprotectResponse(binding, response)
            } catch (e: OscoreException) {
                log.warn { "Dropped a response: ${e.message}" }
                throw e.toMessageSecurityException()
            }
            return lock.withLock { checkFreshness(decrypted, piv) }
        }

        /**
         * §7.4: one response per request, except for the requests that take several (see [OscoreClient]).
         * Called under [lock] once the response verified, so a forged response changes nothing.
         */
        private fun checkFreshness(decrypted: CoapMessage, piv: ByteArray?): CoapMessage {
            if (decrypted.options.observe != null) return checkNotification(decrypted, piv)
            if (binding.allowsSeveralResponses) {
                admitOneOfSeveral(piv)
            } else if (responses > 0) {
                throw MessageSecurityException("Second response to a request that takes one")
            }
            responses++
            if (piv == null) usedRequestNonce = true
            return decrypted
        }

        /**
         * A response to a request that takes several: at most one reuses the request's nonce, and every other
         * one has a Partial IV that is new in the request's window (RFC 6347 §4.1.2.6).
         */
        private fun admitOneOfSeveral(piv: ByteArray?) {
            if (piv == null) {
                if (usedRequestNonce) throw MessageSecurityException("Second response without a Partial IV")
                return
            }
            val window = responseWindow
                ?: ReplayWindow(binding.context.parameters.replayWindowSize).also { responseWindow = it }
            if (!window.accept(piv.readUnsigned())) throw MessageSecurityException("Replayed or too old response")
        }

        /** §8.4.2 and §7.4.1: one notification without a Partial IV at most, then strictly newer ones. */
        private fun checkNotification(decrypted: CoapMessage, piv: ByteArray?): CoapMessage {
            if (!binding.isObserveRegistration) throw MessageSecurityException("Notification for a request that did not register")
            if (piv == null) {
                if (usedRequestNonce) throw MessageSecurityException("Second notification without a Partial IV")
                usedRequestNonce = true
                return withObserveValue(decrypted, 0)
            }
            val sequence = piv.readUnsigned()
            if (sequence <= notificationNumber) throw MessageSecurityException("Replayed or stale notification")
            notificationNumber = sequence
            // §4.1.3.5.2: the client MAY set Observe from the low three bytes of the Partial IV, so the
            // stack's ordering by Observe value matches OSCORE's ordering by Partial IV. Adding one keeps
            // the notification without a Partial IV (value 0) the oldest, as the RFC requires.
            return withObserveValue(decrypted, (sequence + 1) and 0xFFFFFF)
        }

        private fun withObserveValue(message: CoapMessage, value: Long): CoapMessage =
            message.toBuilder().options { observe(value) }.build()
    }
}

/**
 * The OSCORE server side for the CoAP stack: verifies requests carrying the OSCORE option against the
 * contexts in [contexts] and protects the responses. Requests without the option are left to the
 * stack (null), so a server can host public and protected resources side by side.
 */
public class OscoreServer(
    private val contexts: SecurityContextRepository,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) : ServerMessageSecurity {
    private val log = loggerFactory.logger(LogTags.OSCORE).child("server")

    override suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest? {
        if (CoapOptions.OSCORE.number !in request.options) return null
        val verified = try {
            OscoreProtection.unprotectRequest(request) { kid, kidContext -> contexts.find(kid, kidContext) }
        } catch (e: OscoreException) {
            log.warn { "Rejected a request from $peer: ${e.message}" }
            throw e.toMessageSecurityException()
        }
        val decrypted = verified.message
        val challenge = verified.challengeEcho?.let { echo ->
            log.info { "Challenging $peer to prove freshness for ${verified.binding.context} (RFC 8613 B.1.2)" }
            // Appendix B.1.2: a protected 4.01 containing only the Echo option and no diagnostic payload.
            CoapMessage.response(decrypted, CoapCode.UNAUTHORIZED) { options { echo(echo) } }
        }
        if (challenge == null) log.debug { "Verified ${decrypted.code} ${decrypted.options.path} from $peer with ${verified.binding.context}" }
        return ServerExchange(decrypted, verified.binding, challenge)
    }

    private class ServerExchange(
        override val message: CoapMessage,
        private val binding: RequestBinding,
        override val challenge: CoapMessage?,
    ) : UnprotectedRequest {
        private val lock = Mutex()
        private var responses = 0

        override val identity: SecurityIdentity = OscoreIdentity(binding.context.recipientId, binding.context.idContext)

        /**
         * The first response reuses the request nonce (RFC 8613 §8.3); every later one takes a new Partial IV
         * (§8.3 step 3), as notifications must (§8.3.1) and as the further responses of a Q-Block body do
         * (RFC 9177 §4.3, §4.4: the blocks of a set, 4.08s on the timer), and so does a freshness challenge,
         * whose request may be a replay (Appendix B.1.2).
         */
        override suspend fun protectResponse(response: CoapMessage): CoapMessage {
            val first = lock.withLock { responses++ == 0 }
            return try {
                OscoreProtection.protectResponse(binding, response, newPartialIv = !first || challenge != null)
            } catch (e: OscoreException) {
                throw e.toMessageSecurityException()
            }
        }
    }
}
