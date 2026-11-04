package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapResponse
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
 */
public class EdhocResource(private val edhoc: EdhocServer, name: String = "edhoc") : CoapResource(name) {
    override val attributes: ResourceAttributes = ResourceAttributes(resourceTypes = listOf(EdhocOverCoap.RESOURCE_TYPE))

    override suspend fun handlePost(context: RequestContext): CoapResponse = CoapResponse.of(edhoc.handle(context.request))
}
