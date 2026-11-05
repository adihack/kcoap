package dev.kcoap.coap.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Whether a [CoapClient] uses Q-Block (RFC 9177) instead of Block1 and Block2 (RFC 7959) for its requests.
 */
public enum class QBlockMode {
    /** Never: every request uses RFC 7959, and a response carrying Q-Block1 or Q-Block2 is rejected (§4.1). */
    OFF,

    /**
     * When the server supports it, which the client finds out once per server with a Confirmable
     * `GET /.well-known/core` carrying Q-Block2 0/0/16 (RFC 9177 §4.1 requires a Confirmable message for
     * this). Requests wait for that probe; servers that do not support Q-Block, or do not answer the probe,
     * get RFC 7959.
     */
    PROBE,

    /** Always, without a probe, for servers the application knows to support it. */
    ASSUME_SUPPORTED,
}

/**
 * How a [CoapClient] uses Q-Block (RFC 9177): bodies sent and received in bursts of MAX_PAYLOADS blocks
 * ([dev.kcoap.coap.stack.TransmissionParameters.maxPayloads]) that recover lost blocks without a round trip
 * per block. Off by default.
 *
 * A body follows the message type of its request (RFC 9177 §7): a Confirmable request (the default) sends
 * and receives Confirmable blocks in lock-step under NSTART (§7.1, Appendix A); a Non-confirmable one
 * (`CoapRequest.Builder.nonConfirmable()`) gets the bursts of §7.2. A request carrying No-Response, Observe,
 * or a Block or Q-Block option of the application's own always uses RFC 7959, as do observations.
 *
 * @property mode whether Q-Block is used, and how the client finds out that a server supports it.
 * @property probePath the resource the [QBlockMode.PROBE] probe asks for, with Q-Block2 0/0/16: block 0 of 16
 *   bytes and nothing more, the probe libcoap sends.
 * @property capabilityLifetime how long a probe's answer is remembered per server, by the endpoint for all its
 *   clients. A probe that got no answer is repeated after at most a minute; a server's DTLS session ending or
 *   its connection closing forgets the answer at once, and so do a 4.02 or a Reset that answers a Q-Block body
 *   before anything else, a Reset to a Q-Block1 block, and a Non-confirmable body that got no response at
 *   all.
 * @property onReliableTransports whether Q-Block is used over TCP and WebSockets (RFC 8323), where it only
 *   sends blocks back to back (RFC 9177 Appendix B); off by default, since BERT does the same with fewer
 *   messages.
 * @property withMessageSecurity whether Q-Block is used when the client protects its requests
 *   ([CoapClientConfig.security]); off by default, so protected bodies use Inner Block1 and Block2 (RFC 7959;
 *   RFC 8613 §4.1.3.4.1). Several responses to one request fall outside OSCORE's single-response replay model
 *   (RFC 8613 §7.4): turned on, Q-Block1 and Q-Block2 go inside OSCORE (RFC 9177 §4, Table 2), and the
 *   security layer must accept several responses to a request carrying them, as kcoap-oscore's `OscoreClient`
 *   does with a replay window per request. The probe and the capability it finds are kept apart from those of
 *   unprotected requests. The server has to opt in too (`QBlockServerConfig.withMessageSecurity`), or it
 *   answers 4.02, or a Reset to a Non-confirmable request, and the request is sent again with RFC 7959.
 * @property missingBlockMBit whether a request for missing blocks asks for the missing tail of a
 *   MAX_PAYLOADS set with one Q-Block2 option with M set (RFC 9177 §4.4), instead of one option per block.
 */
public class QBlockClientConfig(
    public val mode: QBlockMode = QBlockMode.OFF,
    public val probePath: String = "/.well-known/core",
    public val capabilityLifetime: Duration = 1.hours,
    public val onReliableTransports: Boolean = false,
    public val withMessageSecurity: Boolean = false,
    public val missingBlockMBit: Boolean = false,
) {
    init {
        require(probePath.startsWith("/")) { "The probe path must be absolute: $probePath" }
        require(capabilityLifetime.isPositive()) { "The capability lifetime must be positive: $capabilityLifetime" }
    }

    /** Whether the client uses Q-Block at all. */
    public val isEnabled: Boolean get() = mode != QBlockMode.OFF

    override fun toString(): String =
        "QBlockClientConfig(mode=$mode, probePath=$probePath, capabilityLifetime=$capabilityLifetime, " +
            "onReliableTransports=$onReliableTransports, withMessageSecurity=$withMessageSecurity, " +
            "missingBlockMBit=$missingBlockMBit)"
}
