package dev.kcoap.coap.security

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.transport.Endpoint

/**
 * Message-level (object) security between the request/response layer and the messaging layer, where
 * RFC 8613 Figure 1 places OSCORE. Block-wise transfer and Observe sit above it, so they work on
 * protected exchanges unchanged; retransmission and deduplication sit below it and handle the
 * protected bytes.
 *
 * kcoap-oscore implements these interfaces. EDHOC + OSCORE (RFC 9668) decorates them.
 *
 * The CoAP stack assigns the token and Message ID before protecting a request, and keeps them: OSCORE
 * does not protect either, and responses are matched by token.
 *
 * **Concurrency and cancellation.** The stack calls every method here and on [ProtectedRequest],
 * [ServerMessageSecurity] and [UnprotectedRequest] from many coroutines at once, on any dispatcher: one
 * per request, one per notification, and the receive loops. Implementations must be safe for that, as
 * kcoap-oscore is with a `Mutex` for the sender and one for the recipient state of each security context.
 * Any call may be cancelled, when its request is cancelled, its server stops or its endpoint closes, and a
 * cancelled call must leave replay and sequence-number state consistent: a sequence number it took stays
 * used and is never handed out again (RFC 8613 §7.2.1), and a replay window either accepted the message or
 * is unchanged.
 */
public interface ClientMessageSecurity {
    /**
     * Protects [request] for [peer], or returns null to send it unprotected.
     *
     * Null is plaintext on the wire, so an implementation returns it only where its configuration says so
     * explicitly, such as the EDHOC resource or an opt-in like kcoap-oscore's `allowUnprotected`. One that is
     * meant to protect [peer] and cannot, because no context or protection is installed for it, throws
     * [MessageSecurityException] instead, which fails the request before anything is sent (ADR 0008).
     *
     * kcoap-coap's client reports any exception from here, this one or another, as a `CoapException` with
     * reason `SECURITY` and the exception as cause, so an implementation may let the failures of what it calls,
     * such as a sequence-number store, pass.
     *
     * @throws MessageSecurityException when the request must be protected and cannot be.
     */
    public suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest?
}

/** A request on its way out, with what is needed to read the answers to it. */
public interface ProtectedRequest {
    /** The message to send in place of the original request. */
    public val message: CoapMessage

    /**
     * Verifies and decrypts a response to this request, and says whether it verified. Called once per response,
     * so also for every Observe notification and for every response of a Q-Block body (RFC 9177 §4.3, §4.4) when
     * `QBlockClientConfig.withMessageSecurity` lets protected requests use Q-Block, possibly from different
     * coroutines (see [ClientMessageSecurity]).
     *
     * The stack acts only on [ResponseVerification.Verified]: an Echo retry, a block-wise or Q-Block step, a
     * Q-Block capability, an Observe registration or its end. A [ResponseVerification.Unverified] response, an
     * error that came without message security (RFC 8613 §8.2), ends a request that has no verified response
     * yet with `CoapException.Reason.UNVERIFIED_RESPONSE` and is dropped by an established observation, so a
     * party on the path cannot make the client send a request again, which would run a non-idempotent request
     * twice under a fresh Partial IV.
     *
     * Ordering and replay protection of the notifications of a protected observation are this layer's job
     * (RFC 8613 §4.1.3.5.2, §7.4.1): the stack does not order them again, so what this returns as verified must
     * be fresher than every notification before it or, for a registration carrying Q-Block, a block of the
     * freshest notification body, whose blocks may arrive in any order (RFC 9177 §4.5). kcoap-oscore's
     * `OscoreClient` does both.
     *
     * @throws MessageSecurityException when the response is to be dropped: a protected response that does not
     *   verify, a replay, or an unprotected response that is not an error (RFC 8613 §8.4).
     */
    public suspend fun unprotectResponse(response: CoapMessage): ResponseVerification
}

public interface ServerMessageSecurity {
    /**
     * Verifies and decrypts [request] from [peer]. Returns null when the request does not use this kind
     * of protection, so the stack processes it as a plain request, which only resources whose `AccessPolicy`
     * (kcoap-coap's server) permits it reach (with message security configured, the default policy refuses it,
     * ADR 0008).
     *
     * @throws MessageSecurityException when the request is protected but invalid; the stack answers with
     *   [MessageSecurityException.responseCode], unprotected, as RFC 8613 §8.2 requires.
     */
    public suspend fun unprotectRequest(request: CoapMessage, peer: Endpoint): UnprotectedRequest?
}

/** A verified request, and the way to protect the answers to it. */
public interface UnprotectedRequest {
    /** The inner request, as the resource sees it. */
    public val message: CoapMessage

    /**
     * Who sent it, for authorization (kcoap-coap's `AccessPolicy`). Requests of one security context
     * should give equal identities: the server keys block-wise state and Observe registrations by it.
     */
    public val identity: SecurityIdentity

    /**
     * A response the security layer requires instead of processing the request, such as OSCORE's
     * freshness challenge after a reboot (RFC 8613 Appendix B.1.2: a protected 4.01 with Echo). When it
     * is set, the stack does not pass the request to a resource; it sends [protectResponse] of this
     * message, with the type and Message ID it would give any response.
     */
    public val challenge: CoapMessage? get() = null

    /**
     * Protects a response to this request. The stack keeps this object for an observation and calls it
     * for every notification, and for a Q-Block body (`QBlockServerConfig.withMessageSecurity`) for every block,
     * 2.31 and 4.08 it sends in answer to the request (RFC 9177 §4.3, §4.4), from the coroutine of each, so
     * calls may overlap (see [ClientMessageSecurity]).
     */
    public suspend fun protectResponse(response: CoapMessage): CoapMessage
}

/**
 * The authenticated identity behind a protected request, such as an OSCORE recipient or the peer credential
 * of the EDHOC session that established the context. Compared with `equals`.
 */
public interface SecurityIdentity {
    /** A short human-readable form for logs, never containing keys. */
    public val description: String
}

/**
 * A message that message security could not protect or verify.
 *
 * On the server side, from [ServerMessageSecurity.unprotectRequest], it describes the error response, which is
 * sent unprotected with Max-Age 0 (RFC 8613 §8.2): [responseCode], and as payload either [errorPayload] with
 * [contentFormat] (an EDHOC error message in RFC 9668 §3.3.1, for example) or the [diagnostic] text.
 *
 * On the client side the response fields are unused. From [ClientMessageSecurity.protectRequest] it says that a
 * request that must be protected cannot be, and kcoap-coap's client fails the request with a `CoapException` of
 * reason `SECURITY` with this as cause; from [ProtectedRequest.unprotectResponse] it drops the response.
 */
public class MessageSecurityException(
    message: String,
    public val responseCode: CoapCode = CoapCode.BAD_REQUEST,
    public val diagnostic: String? = null,
    cause: Throwable? = null,
    errorPayload: ByteArray? = null,
    public val contentFormat: Int? = null,
) : Exception(message, cause) {
    private val payloadBytes = errorPayload?.copyOf()

    /** A structured error body to send instead of [diagnostic], or null. */
    public val errorPayload: ByteArray? get() = payloadBytes?.copyOf()
}
