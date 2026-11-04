package com.example.kcoap.coap

/** Option value formats, RFC 7252 §3.2. */
public enum class OptionFormat { EMPTY, OPAQUE, UINT, STRING }

/** How OSCORE protects an option, RFC 8613 §4.1. Some options may appear both as Inner and Outer. */
public enum class OscoreClass {
    /** Encrypted and integrity protected (Inner). */
    E,

    /** Unprotected (Outer). */
    U,
}

/**
 * A registered CoAP option. Critical / Unsafe / NoCacheKey are derived from the option number
 * (RFC 7252 §5.4.6), so they cannot drift from the registry.
 */
public class CoapOption(
    public val number: Int,
    public val name: String,
    public val format: OptionFormat,
    public val minLength: Int,
    public val maxLength: Int,
    public val repeatable: Boolean,
    public val oscoreClasses: Set<OscoreClass>,
    public val reference: String,
) {
    public val isCritical: Boolean get() = (number and 1) != 0
    public val isUnsafe: Boolean get() = (number and 2) != 0
    public val isNoCacheKey: Boolean get() = (number and 0x1e) == 0x1c

    override fun toString(): String = "$name($number)"
}

/** Options from RFC 7252 and the extensions this library targets. */
public object CoapOptions {
    private val E = setOf(OscoreClass.E)
    private val U = setOf(OscoreClass.U)
    private val EU = setOf(OscoreClass.E, OscoreClass.U)

    public val IF_MATCH: CoapOption = CoapOption(1, "If-Match", OptionFormat.OPAQUE, 0, 8, true, E, "RFC 7252")
    public val URI_HOST: CoapOption = CoapOption(3, "Uri-Host", OptionFormat.STRING, 1, 255, false, U, "RFC 7252")
    public val ETAG: CoapOption = CoapOption(4, "ETag", OptionFormat.OPAQUE, 1, 8, true, E, "RFC 7252")
    public val IF_NONE_MATCH: CoapOption = CoapOption(5, "If-None-Match", OptionFormat.EMPTY, 0, 0, false, E, "RFC 7252")
    public val OBSERVE: CoapOption = CoapOption(6, "Observe", OptionFormat.UINT, 0, 3, false, EU, "RFC 7641")
    public val URI_PORT: CoapOption = CoapOption(7, "Uri-Port", OptionFormat.UINT, 0, 2, false, U, "RFC 7252")
    public val LOCATION_PATH: CoapOption = CoapOption(8, "Location-Path", OptionFormat.STRING, 0, 255, true, E, "RFC 7252")
    public val OSCORE: CoapOption = CoapOption(9, "OSCORE", OptionFormat.OPAQUE, 0, 255, false, U, "RFC 8613")
    public val URI_PATH: CoapOption = CoapOption(11, "Uri-Path", OptionFormat.STRING, 0, 255, true, E, "RFC 7252")
    public val CONTENT_FORMAT: CoapOption = CoapOption(12, "Content-Format", OptionFormat.UINT, 0, 2, false, E, "RFC 7252")
    public val MAX_AGE: CoapOption = CoapOption(14, "Max-Age", OptionFormat.UINT, 0, 4, false, EU, "RFC 7252")
    public val URI_QUERY: CoapOption = CoapOption(15, "Uri-Query", OptionFormat.STRING, 0, 255, true, E, "RFC 7252")

    /**
     * Class E under the published RFCs: RFC 8768 defines no OSCORE class, so RFC 8613 §4.1 applies.
     * draft-ietf-core-oscore-capable-proxies updates RFC 8768 to make it Class U, and libcoap (whose
     * maintainer co-authored RFC 8768) already treats it as Outer-only. Revisit when the draft is published.
     */
    public val HOP_LIMIT: CoapOption = CoapOption(16, "Hop-Limit", OptionFormat.UINT, 1, 1, false, E, "RFC 8768")
    public val ACCEPT: CoapOption = CoapOption(17, "Accept", OptionFormat.UINT, 0, 2, false, E, "RFC 7252")
    public val Q_BLOCK1: CoapOption = CoapOption(19, "Q-Block1", OptionFormat.UINT, 0, 3, false, EU, "RFC 9177")
    public val LOCATION_QUERY: CoapOption = CoapOption(20, "Location-Query", OptionFormat.STRING, 0, 255, true, E, "RFC 7252")
    public val EDHOC: CoapOption = CoapOption(21, "EDHOC", OptionFormat.EMPTY, 0, 0, false, U, "RFC 9668")
    public val BLOCK2: CoapOption = CoapOption(23, "Block2", OptionFormat.UINT, 0, 3, false, EU, "RFC 7959")
    public val BLOCK1: CoapOption = CoapOption(27, "Block1", OptionFormat.UINT, 0, 3, false, EU, "RFC 7959")
    public val SIZE2: CoapOption = CoapOption(28, "Size2", OptionFormat.UINT, 0, 4, false, EU, "RFC 7959")
    public val Q_BLOCK2: CoapOption = CoapOption(31, "Q-Block2", OptionFormat.UINT, 0, 3, true, EU, "RFC 9177")

    /** Class U, but OSCORE decomposes it into Proxy-Scheme/Uri-Host/Uri-Port (U) and Uri-Path/Uri-Query (E). */
    public val PROXY_URI: CoapOption = CoapOption(35, "Proxy-Uri", OptionFormat.STRING, 1, 1034, false, U, "RFC 7252")
    public val PROXY_SCHEME: CoapOption = CoapOption(39, "Proxy-Scheme", OptionFormat.STRING, 1, 255, false, U, "RFC 7252")
    public val SIZE1: CoapOption = CoapOption(60, "Size1", OptionFormat.UINT, 0, 4, false, EU, "RFC 7252")
    public val ECHO: CoapOption = CoapOption(252, "Echo", OptionFormat.OPAQUE, 1, 40, false, EU, "RFC 9175")
    public val NO_RESPONSE: CoapOption = CoapOption(258, "No-Response", OptionFormat.UINT, 0, 1, false, EU, "RFC 7967")
    public val REQUEST_TAG: CoapOption = CoapOption(292, "Request-Tag", OptionFormat.OPAQUE, 0, 8, true, EU, "RFC 9175")

    public val all: List<CoapOption> = listOf(
        IF_MATCH, URI_HOST, ETAG, IF_NONE_MATCH, OBSERVE, URI_PORT, LOCATION_PATH, OSCORE, URI_PATH,
        CONTENT_FORMAT, MAX_AGE, URI_QUERY, HOP_LIMIT, ACCEPT, Q_BLOCK1, LOCATION_QUERY, EDHOC, BLOCK2, BLOCK1,
        SIZE2, Q_BLOCK2, PROXY_URI, PROXY_SCHEME, SIZE1, ECHO, NO_RESPONSE, REQUEST_TAG,
    )

    private val index: Map<Int, CoapOption> = all.associateBy { it.number }

    public fun byNumber(number: Int): CoapOption? = index[number]

    /** Unknown options are processed as Class E (RFC 8613 §4.1). */
    public fun oscoreClasses(number: Int): Set<OscoreClass> = index[number]?.oscoreClasses ?: E
}
