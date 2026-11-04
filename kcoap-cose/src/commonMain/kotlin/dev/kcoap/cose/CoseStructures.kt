package dev.kcoap.cose

import dev.kcoap.cbor.CborWriter

/** COSE header parameter labels (RFC 9052 §3.1, RFC 9360, RFC 9528 §3.5.3). */
public object CoseHeader {
    public const val ALG: Long = 1
    public const val CRIT: Long = 2
    public const val CONTENT_TYPE: Long = 3
    public const val KID: Long = 4
    public const val IV: Long = 5
    public const val PARTIAL_IV: Long = 6

    /** A CWT carried by value (RFC 9528 §3.5.3.1). */
    public const val KCWT: Long = 13

    /** A CWT Claims Set carried by value (RFC 9528 §3.5.3.1). */
    public const val KCCS: Long = 14

    /** An X.509 certificate chain (RFC 9360). */
    public const val X5CHAIN: Long = 33

    /** An X.509 certificate thumbprint (RFC 9360). */
    public const val X5T: Long = 34
}

/**
 * The to-be-authenticated structures of COSE. OSCORE and EDHOC never send full COSE messages; they
 * build these structures and feed them to the AEAD or signature algorithm.
 */
public object CoseStructures {
    /**
     * `Enc_structure = [ context, protected, external_aad ]` (RFC 9052 §5.3), the AEAD associated data
     * for COSE_Encrypt0. OSCORE (RFC 8613 §5.4) and EDHOC (RFC 9528 §5.3.2) use it with context
     * "Encrypt0".
     */
    public fun encStructure(protected: ByteArray, externalAad: ByteArray, context: String = "Encrypt0"): ByteArray =
        CborWriter()
            .writeArrayHeader(3)
            .writeText(context)
            .writeBytes(protected)
            .writeBytes(externalAad)
            .toByteArray()

    /**
     * `Sig_structure = [ context, body_protected, external_aad, payload ]` (RFC 9052 §4.4) for
     * COSE_Sign1, as EDHOC's signature methods use it (RFC 9528 §5.3.2).
     */
    public fun sigStructure(
        protected: ByteArray,
        externalAad: ByteArray,
        payload: ByteArray,
        context: String = "Signature1",
    ): ByteArray =
        CborWriter()
            .writeArrayHeader(4)
            .writeText(context)
            .writeBytes(protected)
            .writeBytes(externalAad)
            .writeBytes(payload)
            .toByteArray()
}
