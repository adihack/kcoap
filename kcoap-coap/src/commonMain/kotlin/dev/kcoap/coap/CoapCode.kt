package dev.kcoap.coap

import kotlin.jvm.JvmInline

/**
 * A CoAP code: a 3-bit class and a 5-bit detail, written `c.dd` (RFC 7252 §3, §12.1).
 *
 * Class 0 holds the request methods (and 0.00 for Empty messages), classes 2, 4 and 5 hold responses,
 * and class 7 holds the signaling codes of CoAP over reliable transports (RFC 8323 §5).
 */
@JvmInline
public value class CoapCode(public val value: Int) {
    init {
        require(value in 0..255) { "CoAP code out of range: $value" }
    }

    public val codeClass: Int get() = value ushr 5
    public val detail: Int get() = value and 0x1F

    public val isEmpty: Boolean get() = value == 0
    public val isRequest: Boolean get() = codeClass == 0 && detail != 0
    public val isResponse: Boolean get() = codeClass in 2..5
    public val isSuccess: Boolean get() = codeClass == 2
    public val isClientError: Boolean get() = codeClass == 4
    public val isServerError: Boolean get() = codeClass == 5
    public val isSignaling: Boolean get() = codeClass == 7

    /** The registered name, such as "Content", or null for an unregistered code. */
    public val description: String? get() = DESCRIPTIONS[value]

    /** `2.05 Content`, or `2.17` for an unregistered code. */
    override fun toString(): String {
        val dotted = "$codeClass.${detail.toString().padStart(2, '0')}"
        return description?.let { "$dotted $it" } ?: dotted
    }

    public companion object {
        public fun of(codeClass: Int, detail: Int): CoapCode {
            require(codeClass in 0..7 && detail in 0..31) { "Invalid code $codeClass.$detail" }
            return CoapCode((codeClass shl 5) or detail)
        }

        public val EMPTY: CoapCode = CoapCode(0x00)

        // Methods: RFC 7252 §12.1.1, RFC 8132.
        public val GET: CoapCode = CoapCode(0x01)
        public val POST: CoapCode = CoapCode(0x02)
        public val PUT: CoapCode = CoapCode(0x03)
        public val DELETE: CoapCode = CoapCode(0x04)
        public val FETCH: CoapCode = CoapCode(0x05)
        public val PATCH: CoapCode = CoapCode(0x06)
        public val IPATCH: CoapCode = CoapCode(0x07)

        // Responses: RFC 7252 §12.1.2, RFC 7959, RFC 8132, RFC 8516.
        public val CREATED: CoapCode = of(2, 1)
        public val DELETED: CoapCode = of(2, 2)
        public val VALID: CoapCode = of(2, 3)
        public val CHANGED: CoapCode = of(2, 4)
        public val CONTENT: CoapCode = of(2, 5)
        public val CONTINUE: CoapCode = of(2, 31)
        public val BAD_REQUEST: CoapCode = of(4, 0)
        public val UNAUTHORIZED: CoapCode = of(4, 1)
        public val BAD_OPTION: CoapCode = of(4, 2)
        public val FORBIDDEN: CoapCode = of(4, 3)
        public val NOT_FOUND: CoapCode = of(4, 4)
        public val METHOD_NOT_ALLOWED: CoapCode = of(4, 5)
        public val NOT_ACCEPTABLE: CoapCode = of(4, 6)
        public val REQUEST_ENTITY_INCOMPLETE: CoapCode = of(4, 8)
        public val CONFLICT: CoapCode = of(4, 9)
        public val PRECONDITION_FAILED: CoapCode = of(4, 12)
        public val REQUEST_ENTITY_TOO_LARGE: CoapCode = of(4, 13)
        public val UNSUPPORTED_CONTENT_FORMAT: CoapCode = of(4, 15)
        public val UNPROCESSABLE_ENTITY: CoapCode = of(4, 22)
        public val TOO_MANY_REQUESTS: CoapCode = of(4, 29)
        public val INTERNAL_SERVER_ERROR: CoapCode = of(5, 0)
        public val NOT_IMPLEMENTED: CoapCode = of(5, 1)
        public val BAD_GATEWAY: CoapCode = of(5, 2)
        public val SERVICE_UNAVAILABLE: CoapCode = of(5, 3)
        public val GATEWAY_TIMEOUT: CoapCode = of(5, 4)
        public val PROXYING_NOT_SUPPORTED: CoapCode = of(5, 5)
        public val HOP_LIMIT_REACHED: CoapCode = of(5, 8)

        // Signaling: RFC 8323 §5.
        public val CSM: CoapCode = of(7, 1)
        public val PING: CoapCode = of(7, 2)
        public val PONG: CoapCode = of(7, 3)
        public val RELEASE: CoapCode = of(7, 4)
        public val ABORT: CoapCode = of(7, 5)

        private val DESCRIPTIONS: Map<Int, String> = mapOf(
            GET.value to "GET", POST.value to "POST", PUT.value to "PUT", DELETE.value to "DELETE",
            FETCH.value to "FETCH", PATCH.value to "PATCH", IPATCH.value to "iPATCH",
            CREATED.value to "Created", DELETED.value to "Deleted", VALID.value to "Valid",
            CHANGED.value to "Changed", CONTENT.value to "Content", CONTINUE.value to "Continue",
            BAD_REQUEST.value to "Bad Request", UNAUTHORIZED.value to "Unauthorized",
            BAD_OPTION.value to "Bad Option", FORBIDDEN.value to "Forbidden", NOT_FOUND.value to "Not Found",
            METHOD_NOT_ALLOWED.value to "Method Not Allowed", NOT_ACCEPTABLE.value to "Not Acceptable",
            REQUEST_ENTITY_INCOMPLETE.value to "Request Entity Incomplete", CONFLICT.value to "Conflict",
            PRECONDITION_FAILED.value to "Precondition Failed",
            REQUEST_ENTITY_TOO_LARGE.value to "Request Entity Too Large",
            UNSUPPORTED_CONTENT_FORMAT.value to "Unsupported Content-Format",
            UNPROCESSABLE_ENTITY.value to "Unprocessable Entity", TOO_MANY_REQUESTS.value to "Too Many Requests",
            INTERNAL_SERVER_ERROR.value to "Internal Server Error", NOT_IMPLEMENTED.value to "Not Implemented",
            BAD_GATEWAY.value to "Bad Gateway", SERVICE_UNAVAILABLE.value to "Service Unavailable",
            GATEWAY_TIMEOUT.value to "Gateway Timeout", PROXYING_NOT_SUPPORTED.value to "Proxying Not Supported",
            HOP_LIMIT_REACHED.value to "Hop Limit Reached",
            CSM.value to "CSM", PING.value to "Ping", PONG.value to "Pong", RELEASE.value to "Release",
            ABORT.value to "Abort",
        )
    }
}

/** Message types of CoAP over UDP (RFC 7252 §3). Reliable transports have no type (RFC 8323 §3.2). */
public enum class CoapMessageType(public val value: Int) {
    /** Confirmable: retransmitted until acknowledged. */
    CON(0),

    /** Non-confirmable. */
    NON(1),

    /** Acknowledgement of a Confirmable message, possibly carrying a piggybacked response. */
    ACK(2),

    /** Reset: the receiver could not process a message. */
    RST(3),
    ;

    public companion object {
        public fun fromValue(value: Int): CoapMessageType = entries[value]
    }
}
