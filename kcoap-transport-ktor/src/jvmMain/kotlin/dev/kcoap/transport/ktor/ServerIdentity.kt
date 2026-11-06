package dev.kcoap.transport.ktor

import dev.kcoap.transport.IpLiteral
import java.net.IDN
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate

/**
 * Whether a server certificate names the host a client asked for, by RFC 9525 §6: the subjectAltName entries
 * only, since §2 rules out the subject's Common Name. ktor-network-tls checks names too, but accepts any
 * certificate without a DNS name and checks IP addresses not at all when no server name is set, so the
 * connector does it here, before its pinning hook.
 */
internal object ServerIdentity {
    private const val DNS_NAME = 2
    private const val IP_ADDRESS = 7

    /**
     * Whether [certificate] names [host]: an IP literal by an equal iPAddress entry (RFC 9525 §6.4, exact), any
     * other host by a dNSName entry (§6.3).
     */
    fun isNamedBy(certificate: X509Certificate, host: String): Boolean {
        val names = try {
            certificate.subjectAlternativeNames ?: return false
        } catch (e: CertificateParsingException) {
            return false
        }
        val address = IpLiteral.canonical(host.removeSurrounding("[", "]"))
        return names.any { entry ->
            val type = entry.getOrNull(0) as? Int
            val value = entry.getOrNull(1) as? String ?: return@any false
            when {
                address != null -> type == IP_ADDRESS && IpLiteral.canonical(value) == address
                else -> type == DNS_NAME && dnsNameMatches(value, host)
            }
        }
    }

    /**
     * RFC 9525 §6.3: labels compared as case-insensitive ASCII after U-labels became A-labels; a wildcard only as
     * the whole left-most label of the presented name, matching exactly one label, and never directly below the
     * top-level domain.
     */
    fun dnsNameMatches(presented: String, host: String): Boolean {
        val reference = asciiName(host) ?: return false
        val name = asciiName(presented) ?: return false
        if (reference.isEmpty() || name.isEmpty()) return false
        if ('*' !in name) return name == reference
        if (!name.startsWith("*.") || '*' in name.substring(1)) return false
        val parent = name.substring(2)
        if ('.' !in parent) return false
        val firstDot = reference.indexOf('.')
        return firstDot > 0 && reference.substring(firstDot + 1) == parent
    }

    /** The name in lower-case ASCII, its U-labels as A-labels, without a trailing dot; null if it is no valid name. */
    private fun asciiName(name: String): String? = try {
        IDN.toASCII(name.removeSuffix(".")).lowercase()
    } catch (e: IllegalArgumentException) {
        null
    }
}
