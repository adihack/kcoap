package dev.kcoap.coap.server

import dev.kcoap.coap.LinkFormat
import dev.kcoap.coap.WebLink

/**
 * What `/.well-known/core` says about a resource (RFC 6690 §3, RFC 7252 §7.2.1). The `obs` hint
 * (RFC 7641 §6) is added for observable resources automatically. Immutable: the lists given are copied.
 *
 * @property maximumSize `sz`, the maximum size estimate; RFC 6690 §3.3 says SHOULD for resources larger
 *   than one MTU.
 * @property title a human-readable `title`.
 */
public class ResourceAttributes(
    resourceTypes: List<String> = emptyList(),
    interfaces: List<String> = emptyList(),
    contentFormats: List<Int> = emptyList(),
    public val maximumSize: Long? = null,
    public val title: String? = null,
    extra: List<Pair<String, String?>> = emptyList(),
) {
    /** `rt`, application-specific semantic types. */
    public val resourceTypes: List<String> = resourceTypes.toList()

    /** `if`, interface descriptions. */
    public val interfaces: List<String> = interfaces.toList()

    /** `ct`, the Content-Formats the resource returns. */
    public val contentFormats: List<Int> = contentFormats.toList()

    /** Further link-extension attributes, in order; a null value makes a flag. */
    public val extra: List<Pair<String, String?>> = extra.toList()

    // After the copies, so the checks hold for the lists kept, whatever happens to the ones given.
    init {
        require(this.contentFormats.all { it in 0..0xFFFF }) { "Content-Formats are 16-bit: ${this.contentFormats}" }
        require(maximumSize == null || maximumSize >= 0) { "Size estimate must not be negative: $maximumSize" }
        require(this.extra.none { it.first == LinkFormat.HREF }) { "RFC 6690 §2 reserves 'href' for queries" }
    }

    /** The link for a resource at [path]. */
    public fun toLink(path: String, observable: Boolean): WebLink {
        val attributes = mutableListOf<Pair<String, String?>>()
        title?.let { attributes += LinkFormat.TITLE to it }
        if (resourceTypes.isNotEmpty()) attributes += LinkFormat.RESOURCE_TYPE to resourceTypes.joinToString(" ")
        if (interfaces.isNotEmpty()) attributes += LinkFormat.INTERFACE to interfaces.joinToString(" ")
        if (contentFormats.isNotEmpty()) attributes += LinkFormat.CONTENT_TYPE to contentFormats.joinToString(" ")
        maximumSize?.let { attributes += LinkFormat.SIZE to it.toString() }
        if (observable) attributes += LinkFormat.OBSERVABLE to null
        attributes += extra
        return WebLink(path, attributes)
    }

    override fun toString(): String = toLink("", observable = false).attributes.toString()

    public companion object {
        /** No attributes: the link is just the path. */
        public val NONE: ResourceAttributes = ResourceAttributes()
    }
}
