package dev.kcoap.coap.server

import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.LinkFormat
import dev.kcoap.coap.WebLink

/**
 * `/.well-known/core` (RFC 6690 §4): links to every discoverable resource of the tree, in link format,
 * filtered by the query as RFC 6690 §4.1 describes. Large documents go block-wise like any response.
 */
internal class WellKnownCore(private val tree: CoapResource) : CoapResource(NAME) {
    override val isDiscoverable: Boolean get() = false

    override suspend fun handleGet(context: RequestContext): CoapResponse {
        val links = LinkFormat.filter(links(), context.query)
        return CoapResponse.content(LinkFormat.serialize(links).encodeToByteArray(), ContentFormat.LINK_FORMAT)
    }

    /** Depth first, in the order resources were added. */
    fun links(): List<WebLink> {
        val links = mutableListOf<WebLink>()
        fun visit(resource: CoapResource) {
            if (resource.isDiscoverable) links += resource.attributes.toLink(resource.path, resource.isObservable)
            resource.children.forEach(::visit)
        }
        visit(tree)
        return links
    }

    companion object {
        const val CONTAINER: String = ".well-known"
        const val NAME: String = "core"
    }
}
