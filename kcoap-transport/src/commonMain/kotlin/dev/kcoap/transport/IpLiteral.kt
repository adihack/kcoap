package dev.kcoap.transport

/**
 * The one textual form of an IP address, which [Endpoint.Udp] and [Endpoint.Tcp] keep their host in, so that
 * one address is one endpoint: dotted decimal for IPv4, RFC 5952 canonical text for IPv6 (lower case, no
 * leading zeros, the longest run of two or more zero groups compressed to `::`). IPv4-mapped IPv6 addresses
 * are written as IPv4, as a dual-stack socket reports such peers.
 *
 * Transports format received addresses with [format]; endpoints and clients normalize literals with
 * [canonical].
 */
public object IpLiteral {
    /** The text of a 4- or 16-byte address. */
    public fun format(address: ByteArray): String {
        require(address.size == 4 || address.size == 16) { "Not an IP address: ${address.size} bytes" }
        if (address.size == 4) return address.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val mapped = (0 until 10).all { address[it] == 0.toByte() } && address[10] == (-1).toByte() && address[11] == (-1).toByte()
        if (mapped) return format(address.copyOfRange(12, 16))
        val groups = IntArray(8) { ((address[2 * it].toInt() and 0xFF) shl 8) or (address[2 * it + 1].toInt() and 0xFF) }
        // RFC 5952 §4.2: compress the longest run of at least two zero groups, the first one on a tie.
        var bestStart = -1
        var bestLength = 1
        var index = 0
        while (index < 8) {
            if (groups[index] != 0) {
                index++
                continue
            }
            val start = index
            while (index < 8 && groups[index] == 0) index++
            if (index - start > bestLength) {
                bestStart = start
                bestLength = index - start
            }
        }
        if (bestStart < 0) return groups.joinToString(":") { it.toString(16) }
        val head = groups.copyOfRange(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = groups.copyOfRange(bestStart + bestLength, 8).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }

    /**
     * The canonical text of an IPv4 or IPv6 literal (without brackets or zone), or null if [text] is none, by
     * the grammar of [parse].
     */
    public fun canonical(text: String): String? = parse(text)?.let(::format)

    /**
     * The bytes of an IPv4 or IPv6 literal, or null if [text] is none. The grammar is RFC 3986 §3.2.2's
     * `IPv4address` and `IPv6address`: a decimal octet has no leading zero and a group is one to four hex digits,
     * without a sign. Other text is a name, not an address: `010.0.0.1` is no literal, since RFC 3986 §7.4 warns
     * that resolvers may read it as octal, an address other than `10.0.0.1`.
     */
    public fun parse(text: String): ByteArray? = if (':' in text) parseIpv6(text) else parseIpv4(text)

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            // RFC 3986 §3.2.2 dec-octet: one to three digits, without a leading zero.
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun parseIpv6(text: String): ByteArray? {
        val halves = text.split("::")
        if (halves.size > 2) return null
        val head = halves[0].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList()
        val tail = if (halves.size == 2) halves[1].takeIf { it.isNotEmpty() }?.split(':') ?: emptyList() else emptyList()
        val groups = mutableListOf<Int>()
        fun addAll(parts: List<String>, last: Boolean): Boolean {
            for ((i, part) in parts.withIndex()) {
                if (last && i == parts.size - 1 && '.' in part) {
                    val ipv4 = parseIpv4(part) ?: return false
                    groups += ((ipv4[0].toInt() and 0xFF) shl 8) or (ipv4[1].toInt() and 0xFF)
                    groups += ((ipv4[2].toInt() and 0xFF) shl 8) or (ipv4[3].toInt() and 0xFF)
                    continue
                }
                // RFC 3986 §3.2.2 h16: one to four hex digits, which toInt(16) alone would also accept with a sign.
                if (part.isEmpty() || part.length > 4 || !part.all { it.isHexDigit() }) return false
                groups += part.toInt(16)
            }
            return true
        }
        val compressed = halves.size == 2
        if (!addAll(head, last = !compressed)) return null
        val headCount = groups.size
        if (!addAll(tail, last = true)) return null
        val missing = 8 - groups.size
        if (compressed && missing < 1 || !compressed && missing != 0) return null
        val all = groups.subList(0, headCount) + List(missing) { 0 } + groups.subList(headCount, groups.size)
        return ByteArray(16) { i -> (all[i / 2] ushr (if (i % 2 == 0) 8 else 0)).toByte() }
    }
}

/** RFC 3986 §2.1 HEXDIG, in either case. */
internal fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
