package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
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

/**
 * Chooses the security context for an outgoing request. Null means no context: [OscoreClient] then fails the
 * request with [MessageSecurityException] unless it was built with `allowUnprotected`, when null sends the
 * request in plaintext (ADR 0008).
 */
public fun interface ClientContextResolver {
    public suspend fun contextFor(peer: Endpoint, request: CoapMessage): SecurityContext?
}

/**
 * The peer behind an OSCORE request: its Sender ID (our Recipient ID) and the ID Context. The default
 * [SecurityContext.identity]; a context EDHOC established carries kcoap-edhoc-oscore's `EdhocIdentity`, which
 * holds this one and the peer's credential.
 */
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
 * - An Observe registration takes notifications: at most one without a Partial IV, which counts as the oldest
 *   (§4.1.3.5.2), and the others with a Partial IV above the Notification Number (§7.4.1, §8.4.2). A response
 *   without an Inner Observe option is verified as a non-Observe response (§4.1.3.5.2), so it takes one of
 *   those, which ends the observation.
 * - A request carrying an Inner Q-Block1 or Q-Block2 option (RFC 9177 §4) takes the responses of its body:
 *   the blocks of a Q-Block2 set and the sets that follow on its token, or a 2.31, 4.08s and the final
 *   response on the token of a Q-Block1 block (§4.3, §4.4, §10). At most one of them may reuse the request's
 *   nonce, without a Partial IV (§8.3 step 3); every other one must carry a Partial IV the request has not
 *   seen, within [SecurityContextParameters.replayWindowSize] (32 by default, §3.2.2) of the highest it has
 *   seen. That is an RFC 6347 sliding window per request, like the server's per context, so the blocks of a
 *   set may arrive in any order. A replayed or too old block is dropped like a response that fails
 *   verification (§8.4 step 8), and its body asks for it again (RFC 9177 §4.4).
 *
 * **Notification bodies** (RFC 9177 §4.5). A registration that carries Inner Q-Block gets its notifications as
 * Q-Block2 bodies, and every block of a body is a notification with a Partial IV of its own (§4.1.3.5.2), so a
 * block overtaken by a later one arrives below the Notification Number. §7.4.1 requires only that a Partial IV
 * received before is refused; refusing everything at or below the Notification Number is a rule it lets an
 * application choose, and for such a registration it would drop every block that arrives out of order. Its
 * notifications are therefore checked against the request's window, like the other responses of a Q-Block
 * body, and freshness, which §4.1.3.5.2 takes from the highest Partial IV whatever the order of arrival, is
 * decided per body: the blocks of one body carry one ETag (RFC 9177 §4.4) and one Observe value (§4.5), which
 * makes them one notification. A notification below the Notification Number passes only as a block of the
 * body whose block holds the highest Partial IV so far; a block of an older body, an older notification of one
 * message, or one without a Partial IV once a later one came, is stale and dropped. So the stack gets only
 * notifications that are the freshest or part of the freshest body, as [ProtectedRequest.unprotectResponse]
 * asks. A registration without Inner Q-Block keeps the strict order.
 *
 * Several responses to one request fall outside RFC 8613's single-response model, so the CoAP stack uses
 * Q-Block under OSCORE only where both sides opt in (`QBlockClientConfig.withMessageSecurity` and
 * `QBlockServerConfig.withMessageSecurity` of kcoap-coap); otherwise protected bodies use Inner Block1 and
 * Block2 (RFC 7959; RFC 8613 §4.1.3.4.1), one response per block.
 *
 * **Unprotected responses** are not authenticated ([UnprotectedResponses]). The unprotected 4.xx and 5.xx
 * errors of OSCORE and of intermediaries (§8.2) are handed on as [ResponseVerification.Unverified], to every
 * request alike, and the stack acts on nothing in them: it ends a request that has no verified response yet,
 * and an established observation ignores them (ADR 0008). Any other unprotected response is dropped like one
 * that fails verification.
 *
 * **No context.** A request for which [contexts] returns null fails with [MessageSecurityException] before
 * anything is sent, so a missing context never sends plaintext silently; kcoap-coap's client reports it as
 * `CoapException.Reason.SECURITY` with it as cause, as it does any failure here. With [allowUnprotected], null sends
 * the request unprotected instead, for a client that talks OSCORE to some servers and plain CoAP to others.
 *
 * @param allowUnprotected whether a request [contexts] finds no context for goes out unprotected; false by
 *   default, which fails it.
 */
public class OscoreClient(
    private val contexts: ClientContextResolver,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
    private val allowUnprotected: Boolean = false,
) : ClientMessageSecurity {
    private val log = loggerFactory.logger(LogTags.OSCORE).child("client")

    /** Protects every request with [context]. */
    public constructor(context: SecurityContext, loggerFactory: LoggerFactory = LoggerFactory.Global) :
        this(ClientContextResolver { _, _ -> context }, loggerFactory)

    override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        val context = contexts.contextFor(peer, request) ?: return noContext(request, peer)
        val (protected, binding) = try {
            OscoreProtection.protectRequest(context, request)
        } catch (e: OscoreException) {
            throw e.toMessageSecurityException()
        }
        log.debug { "Protected ${request.code} for $peer with $context, piv=${binding.requestPiv.toHex()}" }
        return ClientExchange(protected, binding)
    }

    /** No context for [request]: plaintext when [allowUnprotected] says so, otherwise a failure (fail closed). */
    private fun noContext(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
        if (allowUnprotected) {
            log.debug { "No OSCORE context for ${request.code} ${request.options.path} to $peer; sent unprotected" }
            return null
        }
        log.warn { "No OSCORE context for ${request.code} ${request.options.path} to $peer; not sent" }
        throw MessageSecurityException("No OSCORE context for $peer")
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

        /** The highest Partial IV of the notifications (§4.1.3.5.2, §7.4.1), -1 before the first one with one. */
        private var notificationNumber: Long = -1

        /**
         * The Partial IVs of the responses to a request that takes several, notifications included, from the
         * first such response on.
         */
        private var responseWindow: ReplayWindow? = null

        /**
         * For a registration carrying Inner Q-Block: the ETag of the notification body whose block holds the
         * [notificationNumber], or null when that notification is not a Q-Block2 block (RFC 9177 §4.5).
         */
        private var freshestBody: ByteArray? = null

        override suspend fun unprotectResponse(response: CoapMessage): ResponseVerification {
            if (response.options.oscore == null) {
                // OSCORE errors are sent unprotected (RFC 8613 §8.2) and are not authenticated: the stack is told so
                // and acts on nothing in them (UnprotectedResponses).
                val unverified = UnprotectedResponses.classify(response)
                if (unverified == null) {
                    log.warn { "Dropped an unprotected ${response.code} to an OSCORE request" }
                    throw MessageSecurityException("Unprotected ${response.code} to an OSCORE request")
                }
                log.debug { "Unverified ${response.code}: ${unverified.reason}" }
                return unverified
            }
            val (decrypted, piv) = try {
                OscoreProtection.unprotectResponse(binding, response)
            } catch (e: OscoreException) {
                log.warn { "Dropped a response: ${e.message}" }
                throw e.toMessageSecurityException()
            }
            return ResponseVerification.Verified(lock.withLock { checkFreshness(decrypted, piv) })
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
            if (!window().accept(piv.readUnsigned())) throw MessageSecurityException("Replayed or too old response")
        }

        private fun window(): ReplayWindow =
            responseWindow ?: ReplayWindow(binding.context.parameters.replayWindowSize).also { responseWindow = it }

        /**
         * §8.4.2 and §7.4.1: one notification without a Partial IV at most, the oldest (§4.1.3.5.2), then strictly
         * newer ones, as §7.4.1 lets an application require. A registration carrying Inner Q-Block takes the
         * blocks of its freshest notification body in any order instead ([checkBodyNotification]).
         */
        private fun checkNotification(decrypted: CoapMessage, piv: ByteArray?): CoapMessage {
            if (!binding.isObserveRegistration) throw MessageSecurityException("Notification for a request that did not register")
            if (binding.allowsSeveralResponses) return checkBodyNotification(decrypted, piv)
            if (piv == null) {
                takeRequestNonceForNotification(isOldest = notificationNumber < 0)
                return withObserveValue(decrypted, 0)
            }
            val sequence = piv.readUnsigned()
            if (sequence <= notificationNumber) throw MessageSecurityException("Replayed or stale notification")
            notificationNumber = sequence
            return withObserveValue(decrypted, observeValue(sequence))
        }

        /**
         * A notification to a registration that carried Inner Q-Block (RFC 9177 §4.5; see [OscoreClient]): its
         * Partial IV must be new in the request's window (§7.4.1), and one below the Notification Number must
         * belong to the freshest body, whose block holds the Notification Number (§4.1.3.5.2). The Partial IV is
         * recorded as received before freshness is decided, so a stale notification sent again is a replay even
         * once its body became the freshest ("MUST stop processing notifications with a Partial IV that has been
         * previously received", §7.4.1).
         */
        private fun checkBodyNotification(decrypted: CoapMessage, piv: ByteArray?): CoapMessage {
            val body = bodyOf(decrypted)
            val ofFreshestBody = body != null && freshestBody?.contentEquals(body) == true
            if (piv == null) {
                takeRequestNonceForNotification(isOldest = notificationNumber < 0 || ofFreshestBody)
                if (notificationNumber < 0) freshestBody = body
                return withObserveValue(decrypted, 0)
            }
            val sequence = piv.readUnsigned()
            if (!window().accept(sequence)) throw MessageSecurityException("Replayed or stale notification")
            val isNewest = sequence > notificationNumber
            if (!isNewest && !ofFreshestBody) throw MessageSecurityException("Stale notification of an older body")
            if (isNewest) {
                notificationNumber = sequence
                freshestBody = body
            }
            return withObserveValue(decrypted, observeValue(sequence))
        }

        /**
         * The notification without a Partial IV reuses the request's nonce, which serves one response (§8.3 step
         * 3, §7.4.1), and counts as the oldest notification (§4.1.3.5.2): unless [isOldest] says it still can be,
         * a later one came already and it is stale. Either way the nonce counts as received, so the notification
         * is not taken when it comes again.
         */
        private fun takeRequestNonceForNotification(isOldest: Boolean) {
            if (usedRequestNonce) throw MessageSecurityException("Second notification without a Partial IV")
            usedRequestNonce = true
            if (!isOldest) throw MessageSecurityException("Stale notification without a Partial IV")
        }

        /** The ETag of the Q-Block2 body that [message] is a block of (RFC 9177 §4.4), or null for any other message. */
        private fun bodyOf(message: CoapMessage): ByteArray? =
            if (message.options.qBlock2 == null) null else message.options.etags.singleOrNull()

        /**
         * §4.1.3.5.2: the client MAY set Observe from the low three bytes of the Partial IV, so the stack's
         * ordering by Observe value matches OSCORE's ordering by Partial IV. Adding one keeps the notification
         * without a Partial IV (value 0) the oldest, as the RFC requires.
         */
        private fun observeValue(sequence: Long): Long = (sequence + 1) and 0xFFFFFF

        private fun withObserveValue(message: CoapMessage, value: Long): CoapMessage =
            message.toBuilder().options { observe(value) }.build()
    }
}

/**
 * The OSCORE server side for the CoAP stack: verifies requests carrying the OSCORE option against the
 * contexts in [contexts] and protects the responses. Each verified request carries its context's
 * [SecurityContext.identity]. Requests without the option are left to the stack (null). On a server with
 * message security its default access policy refuses them, so only the resources the application makes
 * `AccessPolicy.Public` (kcoap-coap's server) serve them (ADR 0008).
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

        override val identity: SecurityIdentity = binding.context.identity

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
