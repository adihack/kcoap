package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.server.CoapResource
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.server.ResourceAttributes

/**
 * The EDHOC resource of a CoAP server (RFC 9528 Appendix A.2): POST requests are handed to [edhoc].
 * Mount it under `.well-known` for the default path:
 *
 * ```
 * CoapServer(transport, scope, config) {
 *     resource(".well-known") { add(EdhocResource(edhoc)) }
 * }
 * ```
 *
 * It is listed in `/.well-known/core` with resource type `core.edhoc` (RFC 9528 §10.10), so clients can
 * discover it.
 *
 * It is [AccessPolicy.Public] of its own, whatever the server's default: EDHOC runs before any OSCORE context
 * exists, and authenticates the peer itself (RFC 9528 §3.5). With message security configured, the server's
 * other resources refuse requests without it (ADR 0008).
 */
public class EdhocResource(private val edhoc: EdhocServer, name: String = "edhoc") : CoapResource(name) {
    override val attributes: ResourceAttributes = ResourceAttributes(resourceTypes = listOf(EdhocOverCoap.RESOURCE_TYPE))

    override val access: AccessPolicy get() = AccessPolicy.Public

    override suspend fun handlePost(context: RequestContext): CoapResponse =
        CoapResponse.of(edhoc.handle(context.request, context.peer))
}
