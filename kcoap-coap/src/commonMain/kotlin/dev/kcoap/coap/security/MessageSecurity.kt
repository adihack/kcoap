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
 */
public interface ClientMessageSecurity {
    /** Protects [request] for [peer], or returns null to send it unprotected. */
    public suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest?
}

/** A request on its way out, with what is needed to read the answers to it. */
public interface ProtectedRequest {
    /** The message to send in place of the original request. */
    public val message: CoapMessage

    /**
     * Verifies and decrypts a response to this request. Called once per response, so also for every
     * Observe notification.
     *
     * @throws MessageSecurityException when the response does not verify; the stack drops it.
     */
    public suspend fun unprotectResponse(response: CoapMessage): CoapMessage
}

public interface ServerMessageSecurity {
    /**
     * Verifies and decrypts [request] from [peer]. Returns null when the request does not use this kind
     * of protection, so the stack processes it as a plain request (and resources decide whether that is
     * acceptable).
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

    /** Who sent it, for authorization. */
    public val identity: SecurityIdentity

    /**
     * Protects a response to this request. The stack keeps this object for an observation and calls it
     * for every notification.
     */
    public suspend fun protectResponse(response: CoapMessage): CoapMessage
}

/** The authenticated identity behind a protected request, such as an OSCORE recipient. */
public interface SecurityIdentity {
    /** A short human-readable form for logs, never containing keys. */
    public val description: String
}

/**
 * A protected message that failed verification. On the server side, [responseCode] and [diagnostic]
 * form the error response (RFC 8613 §8.2: sent unprotected, with Max-Age 0).
 */
public class MessageSecurityException(
    message: String,
    public val responseCode: CoapCode = CoapCode.BAD_REQUEST,
    public val diagnostic: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
