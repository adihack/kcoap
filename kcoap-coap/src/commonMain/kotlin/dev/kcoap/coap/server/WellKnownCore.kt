package dev.kcoap.coap.server

import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.LinkFormat
import dev.kcoap.coap.WebLink

/**
 * `/.well-known/core` (RFC 6690 §4): links to the discoverable resources of [owner]'s tree that the caller may
 * use, in link format, filtered by the query as RFC 6690 §4.1 describes. Large documents go block-wise like any
 * response.
 *
 * It is [AccessPolicy.Public] whatever the server's default, so clients find the EDHOC resource and anything
 * else offered in the clear. It lists a resource only when the resource's policy permits a GET by the caller
 * (RFC 6690 §4: which links to include is up to the server), so a caller without message security does not see
 * the protected ones (ADR 0008). The resources only OSCORE reaches ([AccessPolicy.MessageSecurityRequired]) carry
 * the `osc` attribute (RFC 8613 §9).
 */
internal class WellKnownCore(private val owner: CoapServer) : CoapResource(NAME) {
    override val isDiscoverable: Boolean get() = false

    override val access: AccessPolicy get() = AccessPolicy.Public

    override suspend fun handleGet(context: RequestContext): CoapResponse {
        val links = LinkFormat.filter(links(context), context.query)
        return CoapResponse.content(LinkFormat.serialize(links).encodeToByteArray(), ContentFormat.LINK_FORMAT)
    }

    /**
     * Depth first, in the order resources were added: those the caller of [context] may use. Each resource is
     * governed by its own policy, else the one it inherits on the way down, else the server's default; a policy
     * that throws leaves its link out and no other.
     */
    suspend fun links(context: RequestContext): List<WebLink> {
        val links = mutableListOf<WebLink>()
        suspend fun visit(resource: CoapResource, inherited: AccessPolicy?) {
            val governing = resource.access ?: inherited
            if (resource.isDiscoverable) {
                val policy = governing ?: owner.config.defaultAccess
                if (owner.permitsDiscovery(policy, resource, context)) links += link(resource, policy)
            }
            resource.children.forEach { visit(it, governing) }
        }
        visit(owner.root, null)
        return links
    }

    private fun link(resource: CoapResource, policy: AccessPolicy): WebLink {
        val link = resource.attributes.toLink(resource.path, resource.isObservable)
        if (policy != AccessPolicy.MessageSecurityRequired || link.has(LinkFormat.OSCORE)) return link
        return WebLink(link.target, link.attributes + (LinkFormat.OSCORE to null))
    }

    companion object {
        const val CONTAINER: String = ".well-known"
        const val NAME: String = "core"
    }
}
