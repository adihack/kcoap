package dev.kcoap.coap

/**
 * One link of the CoRE Link Format (RFC 6690 §2): a target URI-reference and its attributes in order.
 * An attribute without a value, such as `obs` (RFC 7641 §6), has a null value. Values are unquoted.
 *
 * The [target] stays the text the document had: a link a server sent is peer input, so to use an absolute
 * target as a URI, parse it with [CoapUri.parseOrNull].
 */
public class WebLink(
    public val target: String,
    attributes: List<Pair<String, String?>> = emptyList(),
) {
    /** The attributes in document order; a copy of the list given. */
    public val attributes: List<Pair<String, String?>> = attributes.toList()

    /** The first value of [name], or null if absent or a flag. */
    public operator fun get(name: String): String? = attributes.firstOrNull { it.first == name }?.second

    /** Whether the link has the attribute [name], with or without a value. */
    public fun has(name: String): Boolean = attributes.any { it.first == name }

    /** The space-separated values of a relation-type attribute such as `rt` or `if` (RFC 6690 §2, §3). */
    public fun values(name: String): List<String> =
        attributes.filter { it.first == name }.flatMap { (_, value) -> value?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList() }

    /** `rt` (RFC 6690 §3.1). */
    public val resourceTypes: List<String> get() = values(LinkFormat.RESOURCE_TYPE)

    /** `if` (RFC 6690 §3.2). */
    public val interfaces: List<String> get() = values(LinkFormat.INTERFACE)

    /** `ct` (RFC 7252 §7.2.1); malformed entries are skipped. */
    public val contentFormats: List<Int> get() = values(LinkFormat.CONTENT_TYPE).mapNotNull { it.toIntOrNull() }

    /** `sz` (RFC 6690 §3.3), or null when absent or too big for a Long. */
    public val maximumSize: Long? get() = get(LinkFormat.SIZE)?.toLongOrNull()

    /** `title` (RFC 8288), human readable. */
    public val title: String? get() = get(LinkFormat.TITLE)

    /** The `obs` hint (RFC 7641 §6): the target is useful to observe. */
    public val isObservable: Boolean get() = has(LinkFormat.OBSERVABLE)

    override fun equals(other: Any?): Boolean = other is WebLink && target == other.target && attributes == other.attributes

    override fun hashCode(): Int = 31 * target.hashCode() + attributes.hashCode()

    /** The link in link format. */
    override fun toString(): String = LinkFormat.serialize(listOf(this))
}

/**
 * The CoRE Link Format of RFC 6690, `application/link-format` ([ContentFormat.LINK_FORMAT]), as served by
 * `/.well-known/core`.
 */
public object LinkFormat {
    /** `rt`, RFC 6690 §3.1. */
    public const val RESOURCE_TYPE: String = "rt"
    /** `if`, RFC 6690 §3.2. */
    public const val INTERFACE: String = "if"
    /** `ct`, RFC 7252 §7.2.1. */
    public const val CONTENT_TYPE: String = "ct"
    /** `sz`, RFC 6690 §3.3. */
    public const val SIZE: String = "sz"
    /** `title`, RFC 8288. */
    public const val TITLE: String = "title"
    /** `obs`, RFC 7641 §6. */
    public const val OBSERVABLE: String = "obs"
    /** `osc`, RFC 8613 §9: a hint that the target is reachable with OSCORE only. */
    public const val OSCORE: String = "osc"

    /** RFC 6690 §4.1: the query name that matches the link target. */
    public const val HREF: String = "href"

    /** Attributes whose value is a space-separated list, each matched on its own (RFC 6690 §4.1, RFC 7252 §7.2.1). */
    private val MULTI_VALUED = setOf("rel", "rev", RESOURCE_TYPE, INTERFACE, CONTENT_TYPE)

    /**
     * Writes [links] separated by commas. Values consisting only of digits are written as tokens (`ct=40`,
     * `sz=262144`), all others as quoted strings (`rt="temperature-c"`), flags without a value (`obs`).
     */
    public fun serialize(links: List<WebLink>): String = links.joinToString(",") { link ->
        buildString {
            append('<').append(link.target).append('>')
            for ((name, value) in link.attributes) {
                append(';').append(name)
                if (value != null) {
                    append('=')
                    if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                        append(value)
                    } else {
                        append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                    }
                }
            }
        }
    }

    /**
     * Parses a link-format document the application supplies; a document from a peer, such as a
     * `/.well-known/core` response, goes through [parseOrNull]. Whitespace between links and parameters is
     * tolerated, as RFC 6690 §2 converts HTTP Link headers by removing it.
     *
     * @throws IllegalArgumentException when [text] is not link format.
     */
    public fun parse(text: String): List<WebLink> = read(text)

    /**
     * Parses [text] like [parse], or returns null where [parse] throws: for documents a peer sent, whose
     * malformation is the peer's, not the application's. `CoapClient.discover` reads `/.well-known/core` with it.
     */
    public fun parseOrNull(text: String): List<WebLink>? = try {
        read(text)
    } catch (_: MalformedLinkFormat) {
        null
    }

    /** The links of [text]; throws only [MalformedLinkFormat]. */
    private fun read(text: String): List<WebLink> {
        val reader = Reader(text)
        val links = mutableListOf<WebLink>()
        reader.skipWhitespace()
        if (reader.atEnd) return links
        while (true) {
            links += reader.link()
            reader.skipWhitespace()
            if (reader.atEnd) return links
            reader.expect(',')
            reader.skipWhitespace()
        }
    }

    /**
     * Applies the query filter of RFC 6690 §4.1: each `name=value` must match an attribute of the link
     * (`href` matches the target); a value ending in `*` matches as a prefix, and the values of
     * relation-type attributes match one by one. Queries without `=` match links that have the attribute.
     */
    public fun filter(links: List<WebLink>, query: List<String>): List<WebLink> {
        if (query.isEmpty()) return links
        val conditions = query.map { item ->
            val separator = item.indexOf('=')
            if (separator < 0) item to "*" else item.substring(0, separator) to item.substring(separator + 1)
        }
        return links.filter { link -> conditions.all { (name, value) -> matches(link, name, value) } }
    }

    private fun matches(link: WebLink, name: String, pattern: String): Boolean {
        val candidates = when {
            name == HREF -> listOf(link.target)
            name in MULTI_VALUED -> link.values(name)
            else -> link.attributes.filter { it.first == name }.map { it.second ?: "" }
        }
        if (name != HREF && !link.has(name)) return false
        return if (pattern.endsWith('*')) {
            val prefix = pattern.dropLast(1)
            candidates.any { it.startsWith(prefix) } || (prefix.isEmpty() && link.has(name))
        } else {
            candidates.any { it == pattern }
        }
    }

    private class Reader(private val text: String) {
        private var position = 0

        val atEnd: Boolean get() = position >= text.length

        fun skipWhitespace() {
            while (!atEnd && text[position].isWhitespace()) position++
        }

        fun expect(char: Char) {
            ensure(!atEnd && text[position] == char) { "Expected '$char' at $position in link format" }
            position++
        }

        /** Fails the document unless [condition] holds. */
        private inline fun ensure(condition: Boolean, message: () -> String) {
            if (!condition) throw MalformedLinkFormat(message())
        }

        fun link(): WebLink {
            expect('<')
            val end = text.indexOf('>', position)
            ensure(end >= 0) { "Unterminated link target at $position" }
            val target = text.substring(position, end)
            position = end + 1
            val attributes = mutableListOf<Pair<String, String?>>()
            while (true) {
                skipWhitespace()
                if (atEnd || text[position] != ';') return WebLink(target, attributes)
                position++
                skipWhitespace()
                val name = token()
                ensure(name.isNotEmpty()) { "Empty parameter name at $position" }
                skipWhitespace()
                if (!atEnd && text[position] == '=') {
                    position++
                    skipWhitespace()
                    attributes += name to if (!atEnd && text[position] == '"') quoted() else token()
                } else {
                    attributes += name to null
                }
            }
        }

        /** A parameter name or ptoken (RFC 6690 §2): up to the next separator. */
        private fun token(): String {
            val start = position
            while (!atEnd && text[position] !in ";,=\" \t\r\n") position++
            return text.substring(start, position)
        }

        /** An RFC 2616 quoted-string with backslash escapes. */
        private fun quoted(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                ensure(!atEnd) { "Unterminated quoted string in link format" }
                when (val c = text[position++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        ensure(!atEnd) { "Dangling escape in link format" }
                        out.append(text[position++])
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}

/** What [LinkFormat.parse] throws, and the only failure [LinkFormat.parseOrNull] turns into null. */
private class MalformedLinkFormat(message: String) : IllegalArgumentException(message)
