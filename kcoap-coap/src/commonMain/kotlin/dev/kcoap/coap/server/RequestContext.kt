package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.PeerIdentity

/**
 * A request as a resource handler sees it: verified by message security, reassembled from Block1
 * transfers, and with who sent it.
 *
 * Authorize on either identity (architecture rule 4): [peerIdentity] is what DTLS authenticated for the
 * hop, [securityIdentity] what OSCORE authenticated end to end.
 */
public class RequestContext internal constructor(
    /** The request: inner (decrypted) for OSCORE, with the whole body for Block1 transfers. */
    public val request: CoapMessage,
    /** Where the request came from; responses and notifications go back there. */
    public val peer: Endpoint,
    /** The message-security identity, or null for an unprotected request. */
    public val securityIdentity: SecurityIdentity?,
    /** The resource the request is for. */
    public val resource: CoapResource,
    private val inbound: InboundRequest?,
) {
    public val method: CoapCode get() = request.code

    public val options: OptionSet get() = request.options

    public val payload: ByteArray get() = request.payload

    public val payloadSize: Int get() = request.payloadSize

    public fun payloadAsText(): String = request.payloadAsText()

    /** Uri-Query values, decoded. */
    public val query: List<String> get() = request.options.uriQuery

    /** The DTLS identity for [Endpoint.Secure] peers, [PeerIdentity.None] for plain ones. */
    public val peerIdentity: PeerIdentity get() = (peer as? Endpoint.Secure)?.identity ?: PeerIdentity.None

    /** True when the handler runs to produce an Observe notification rather than to answer a request. */
    public val isNotification: Boolean get() = inbound == null

    /**
     * Sends an Empty ACK now, so a slow handler's response goes separately (RFC 7252 §5.2.2). The server
     * does this by itself after [dev.kcoap.coap.stack.CoapEndpointConfig.separateResponseDelay]; call it
     * when the handler knows in advance that it will take long. No effect for Non-confirmable requests
     * and notifications.
     */
    public suspend fun acknowledge() {
        inbound?.acknowledge()
    }

    /**
     * Evaluates If-Match and If-None-Match (RFC 7252 §5.10.8) against the resource's current state. A
     * handler that returns 4.12 Precondition Failed when this is false, and lists both options in
     * [CoapResource.recognizedOptions], implements conditional requests.
     *
     * @param currentETag the ETag of the current representation, if any.
     * @param exists whether the target resource currently has a representation.
     */
    public fun preconditionsHold(currentETag: ByteArray?, exists: Boolean = currentETag != null): Boolean {
        val ifMatch = options.ifMatch
        if (ifMatch.isNotEmpty()) {
            val matched = ifMatch.any { tag ->
                if (tag.isEmpty()) exists else currentETag != null && tag.contentEquals(currentETag)
            }
            if (!matched) return false
        }
        return !(options.ifNoneMatch && exists)
    }

    override fun toString(): String = "RequestContext($method ${resource.path} from $peer)"
}
