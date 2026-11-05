package dev.kcoap.coap.server

import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.OptionSet

/** RFC 7252 §5.4.1, §5.4.3 and §5.4.5: which critical options in a request the server does not recognize. */
internal object OptionCheck {
    /**
     * The critical options the server itself processes: the URI options, Accept (4.06 when it cannot be
     * met), the Block options and the proxy options (answered with 5.05). Elective options never cause
     * 4.02 and are not listed.
     */
    val SERVER_RECOGNIZED: Set<Int> = setOf(
        CoapOptions.URI_HOST, CoapOptions.URI_PORT, CoapOptions.URI_PATH, CoapOptions.URI_QUERY, CoapOptions.ACCEPT,
        CoapOptions.BLOCK1, CoapOptions.BLOCK2, CoapOptions.PROXY_URI, CoapOptions.PROXY_SCHEME,
    ).map { it.number }.toSet()

    /**
     * Q-Block1 and Q-Block2, recognized together or not at all (RFC 9177 §4.1), for the requests a server with
     * Q-Block on takes them in ([QBlockServerConfig]); for every other request they stay unrecognized.
     */
    val Q_BLOCK_RECOGNIZED: Set<Int> = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    /**
     * The critical options that must be treated as unrecognized: unknown to [recognized], outside their
     * registered length (§5.4.3), or repeated although not repeatable (§5.4.5, every occurrence after
     * the first).
     */
    fun unrecognizedCritical(options: OptionSet, recognized: Set<Int>): List<CoapOption> {
        val seen = HashSet<Int>()
        val unrecognized = mutableListOf<CoapOption>()
        for (option in options) {
            val number = option.number
            val repeated = !seen.add(number)
            if (number % 2 == 0) continue
            val definition = CoapOptions.byNumber(number)
            val badLength = definition != null && option.length !in definition.minLength..definition.maxLength
            val badRepetition = definition != null && repeated && !definition.repeatable
            if (number !in recognized || badLength || badRepetition) unrecognized += option
        }
        return unrecognized
    }
}
