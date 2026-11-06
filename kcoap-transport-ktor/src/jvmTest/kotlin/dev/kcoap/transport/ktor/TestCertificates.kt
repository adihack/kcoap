package dev.kcoap.transport.ktor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Throwaway X.509 certificates for the TLS tests, written in DER here (RFC 5280 §4.1) and signed with
 * ECDSA P-256 and SHA-256, so the tests need no openssl and no key files.
 */
object TestCertificates {
    /** A key pair and the certificate for it. */
    class Issued(val keys: KeyPair, val certificate: X509Certificate) {
        override fun toString(): String = certificate.subjectX500Principal.toString()
    }

    private val serials = AtomicLong(1)

    fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** A self-signed certificate authority. */
    fun authority(name: String): Issued {
        val keys = keyPair()
        return Issued(keys, certificate(name, keys, name, keys, isAuthority = true))
    }

    /** A certificate issued by [issuer]: an intermediate authority, or a TLS server with these names. */
    fun issue(
        issuer: Issued,
        name: String,
        dnsNames: List<String> = emptyList(),
        ipAddresses: List<String> = emptyList(),
        isAuthority: Boolean = false,
        validDays: Long = 1,
    ): Issued {
        val keys = keyPair()
        val issuerName = issuer.certificate.subjectX500Principal.name.removePrefix("CN=")
        return Issued(keys, certificate(name, keys, issuerName, issuer.keys, isAuthority, dnsNames, ipAddresses, validDays))
    }

    /** A server certificate signed by its own key. */
    fun selfSigned(name: String, dnsNames: List<String> = emptyList(), ipAddresses: List<String> = emptyList()): Issued {
        val keys = keyPair()
        return Issued(keys, certificate(name, keys, name, keys, isAuthority = false, dnsNames, ipAddresses))
    }

    private fun certificate(
        subject: String,
        subjectKeys: KeyPair,
        issuer: String,
        issuerKeys: KeyPair,
        isAuthority: Boolean,
        dnsNames: List<String> = emptyList(),
        ipAddresses: List<String> = emptyList(),
        validDays: Long = 1,
    ): X509Certificate {
        val ecdsaWithSha256 = sequence(oid("1.2.840.10045.4.3.2"))
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val extensions = buildList {
            add(extension("2.5.29.19", critical = true, if (isAuthority) sequence(boolean(true)) else sequence()))
            // keyUsage: keyCertSign and cRLSign for an authority, digitalSignature for an ECDHE_ECDSA server.
            add(extension("2.5.29.15", critical = true, if (isAuthority) bitString(byteArrayOf(0x06), unused = 1) else bitString(byteArrayOf(0x80.toByte()), unused = 7)))
            if (!isAuthority) add(extension("2.5.29.37", critical = false, sequence(oid("1.3.6.1.5.5.7.3.1"))))
            val names = dnsNames.map { tlv(0x82, it.encodeToByteArray()) } +
                ipAddresses.map { tlv(0x87, java.net.InetAddress.getByName(it).address) }
            if (names.isNotEmpty()) add(extension("2.5.29.17", critical = false, sequence(*names.toTypedArray())))
        }
        val tbs = sequence(
            tlv(0xA0, integer(BigInteger.TWO)),
            integer(BigInteger.valueOf(serials.getAndIncrement())),
            ecdsaWithSha256,
            name(issuer),
            sequence(time(now.minusMinutes(5)), time(now.plusDays(validDays))),
            name(subject),
            subjectKeys.public.encoded,
            tlv(0xA3, sequence(*extensions.toTypedArray())),
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(issuerKeys.private)
            update(tbs)
            sign()
        }
        val der = sequence(tbs, ecdsaWithSha256, bitString(signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    private fun extension(id: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) sequence(oid(id), boolean(true), octetString(value)) else sequence(oid(id), octetString(value))

    private fun name(commonName: String): ByteArray = sequence(set(sequence(oid("2.5.4.3"), tlv(0x0C, commonName.encodeToByteArray()))))

    private fun time(at: ZonedDateTime): ByteArray = tlv(0x17, at.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).encodeToByteArray())

    private fun integer(value: BigInteger): ByteArray = tlv(0x02, value.toByteArray())

    private fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))

    private fun octetString(value: ByteArray): ByteArray = tlv(0x04, value)

    private fun bitString(value: ByteArray, unused: Int = 0): ByteArray = tlv(0x03, byteArrayOf(unused.toByte()) + value)

    private fun sequence(vararg parts: ByteArray): ByteArray = tlv(0x30, concat(parts))

    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, concat(parts))

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            val groups = mutableListOf<Int>()
            var rest = arc
            do {
                groups.add(0, (rest and 0x7F).toInt())
                rest = rest shr 7
            } while (rest > 0)
            groups.forEachIndexed { i, group -> out.write(if (i < groups.size - 1) group or 0x80 else group) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            content.size < 0x80 -> out.write(content.size)
            content.size < 0x100 -> {
                out.write(0x81)
                out.write(content.size)
            }
            else -> {
                out.write(0x82)
                out.write(content.size shr 8)
                out.write(content.size and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(parts: Array<out ByteArray>): ByteArray = parts.fold(ByteArray(0)) { acc, part -> acc + part }
}
