package com.example.kcoap.dtls

import com.example.kcoap.transport.SecurityMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * DTLS 1.2 settings for one endpoint, acting as client towards peers it contacts and as server towards
 * peers that contact it. Defaults follow RFC 7925.
 */
public class DtlsConfig(
    public val modes: Set<SecurityMode>,
    public val credentials: DtlsCredentials,
    public val cipherSuites: List<DtlsCipherSuite> = DtlsCipherSuite.entries,
    /** RFC 7925 §11 recommends 9 s instead of RFC 6347's 1 s, because constrained peers are slow. */
    public val initialRetransmissionTimeout: Duration = 9.seconds,
    /** RFC 7925 §11: exponential back-off up to no less than 60 s. */
    public val maxRetransmissionTimeout: Duration = 60.seconds,
    /**
     * Connection ID this endpoint asks the peer to put in records (RFC 9146): null sends no CID
     * extension, 0 means "I will send a CID but need none", 1..255 is the length to request.
     */
    public val connectionIdLength: Int? = null,
    /** RFC 6066 `max_fragment_length` to request: 512, 1024, 2048 or 4096, or null for none. */
    public val maxFragmentLength: Int? = null,
    /** Refuse peers that do not negotiate the extended master secret (RFC 7627); it is always offered. */
    public val requireExtendedMasterSecret: Boolean = false,
    /** Server side: demand a cookie (HelloVerifyRequest, RFC 6347 §4.2.1) before keeping any state. */
    public val cookieExchange: Boolean = true,
) {
    init {
        require(modes.isNotEmpty()) { "At least one security mode is needed" }
        require(SecurityMode.NO_SEC !in modes) { "NoSec is the absence of DTLS, not a DTLS mode" }
        require(cipherSuites.isNotEmpty()) { "At least one cipher suite is needed" }
        for (mode in modes) {
            require(cipherSuites.any { mode in it.modes }) { "No configured cipher suite serves $mode" }
        }
        require(initialRetransmissionTimeout.isPositive()) { "Initial retransmission timeout must be positive" }
        require(maxRetransmissionTimeout >= initialRetransmissionTimeout) {
            "Maximum retransmission timeout is below the initial one"
        }
        require(connectionIdLength == null || connectionIdLength in 0..255) {
            "CID length out of range: $connectionIdLength"
        }
        require(maxFragmentLength == null || maxFragmentLength in MAX_FRAGMENT_LENGTHS) {
            "max_fragment_length must be one of $MAX_FRAGMENT_LENGTHS"
        }
        if (SecurityMode.PRE_SHARED_KEY in modes) {
            require(credentials.preSharedKeys != null) { "PreSharedKey mode needs a PskStore" }
        }
        if (SecurityMode.RAW_PUBLIC_KEY in modes) {
            require(credentials.signer != null) { "RawPublicKey mode needs a signer" }
            require(credentials.rawPublicKeyTrust != null) { "RawPublicKey mode needs a RawPublicKeyTrust" }
        }
        if (SecurityMode.CERTIFICATE in modes) {
            val signer = credentials.signer
            require(signer != null && signer.certificateChain.isNotEmpty()) {
                "Certificate mode needs a signer with a certificate chain"
            }
            require(credentials.certificateValidator != null) { "Certificate mode needs a CertificateValidator" }
        }
    }

    public companion object {
        /** RFC 6066 §4. */
        public val MAX_FRAGMENT_LENGTHS: Set<Int> = setOf(512, 1024, 2048, 4096)
    }
}
