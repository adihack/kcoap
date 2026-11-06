package dev.kcoap.transport

/**
 * Channel-security modes of RFC 7252 §9. RFC 8323 §9.1 uses the same three secured modes for TLS.
 *
 * These describe DTLS and TLS only. OSCORE protects messages end to end regardless of the mode.
 */
public enum class SecurityMode(
    /** RFC 7252 §9: NoSec and RawPublicKey are mandatory to implement. */
    public val isMandatoryToImplement: Boolean,
) {
    /** No DTLS or TLS. */
    NO_SEC(true),

    /** Pre-shared keys, `TLS_PSK_WITH_AES_128_CCM_8` (RFC 7252 §9.1.3.1). */
    PRE_SHARED_KEY(false),

    /** Raw public keys (RFC 7250), `TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8` (RFC 7252 §9.1.3.2). */
    RAW_PUBLIC_KEY(true),

    /** X.509 certificates, same cipher suite as raw public keys (RFC 7252 §9.1.3.3). */
    CERTIFICATE(false),
}
