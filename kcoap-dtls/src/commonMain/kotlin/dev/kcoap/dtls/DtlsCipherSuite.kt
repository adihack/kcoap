package dev.kcoap.dtls

import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.transport.SecurityMode

public enum class KeyExchange { PSK, ECDHE_ECDSA }

/**
 * The DTLS 1.2 cipher suites in scope: the ones RFC 7252 §9.1.3 and RFC 7925 make mandatory.
 *
 * Both use AES-128-CCM with an 8-byte tag and a 12-byte nonce made of a 4-byte implicit part from the
 * key block and an 8-byte explicit part sent in each record (RFC 6655 §3), which kcoap's common-code
 * [dev.kcoap.crypto.AesCcm] supports. The PRF is the TLS 1.2 PRF with SHA-256.
 */
public enum class DtlsCipherSuite(
    /** IANA TLS Cipher Suite value. */
    public val code: Int,
    public val keyExchange: KeyExchange,
    /** The security modes this suite serves; for each it is the RFC 7252 mandatory-to-implement suite. */
    public val modes: Set<SecurityMode>,
    public val reference: String,
) {
    TLS_PSK_WITH_AES_128_CCM_8(
        0xC0A8,
        KeyExchange.PSK,
        setOf(SecurityMode.PRE_SHARED_KEY),
        "RFC 6655",
    ),
    TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8(
        0xC0AE,
        KeyExchange.ECDHE_ECDSA,
        setOf(SecurityMode.RAW_PUBLIC_KEY, SecurityMode.CERTIFICATE),
        "RFC 7251",
    ),
    ;

    public val keyLength: Int get() = 16
    public val tagLength: Int get() = 8
    public val fixedIvLength: Int get() = 4
    public val recordIvLength: Int get() = 8
    public val nonceLength: Int get() = fixedIvLength + recordIvLength
    public val prfHash: HashAlgorithm get() = HashAlgorithm.SHA_256

    /** Bytes the AEAD adds to each record's plaintext: explicit nonce and tag. */
    public val recordExpansion: Int get() = recordIvLength + tagLength

    /**
     * Largest CoAP message that fits one datagram of [datagramSize] bytes. A DTLS secured transport
     * reports this as its [dev.kcoap.transport.DatagramTransport.maxDatagramSize].
     *
     * @param connectionIdLength length of the CID in records sent to the peer (RFC 9146), 0 for none.
     *   A CID record carries the CID in the header and one extra content-type byte inside the
     *   encrypted part (RFC 9146 §4); padding is not used.
     */
    public fun maxPlaintextLength(datagramSize: Int, connectionIdLength: Int = 0): Int {
        require(connectionIdLength in 0..255) { "CID length out of range: $connectionIdLength" }
        val cidOverhead = if (connectionIdLength > 0) connectionIdLength + 1 else 0
        return datagramSize - RECORD_HEADER_LENGTH - cidOverhead - recordExpansion
    }

    public companion object {
        /** DTLS 1.2 record header: type, version, epoch, sequence number, length (RFC 6347 §4.1). */
        public const val RECORD_HEADER_LENGTH: Int = 13

        public fun fromCode(code: Int): DtlsCipherSuite? = entries.firstOrNull { it.code == code }

        /** The suite RFC 7252 makes mandatory for [mode]; null for [SecurityMode.NO_SEC]. */
        public fun mandatoryFor(mode: SecurityMode): DtlsCipherSuite? = entries.firstOrNull { mode in it.modes }
    }
}
