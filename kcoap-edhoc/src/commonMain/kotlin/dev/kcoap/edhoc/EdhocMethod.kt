package dev.kcoap.edhoc

/** How one party proves possession of its authentication key (RFC 9528 §3.2, §3.5.1). */
public enum class AuthenticationKind {
    /** Signature_or_MAC_x is a COSE_Sign1 signature over MAC_x. */
    SIGNATURE,

    /** Signature_or_MAC_x is MAC_x, keyed from an ephemeral-static Diffie-Hellman secret. */
    STATIC_DH,
}

/**
 * The EDHOC authentication methods, RFC 9528 §3.2 Table 2. The name reads Initiator, then Responder.
 *
 * Methods 1–3 need a Diffie-Hellman key exchange; they cannot run on a KEM cipher suite
 * (draft-ietf-lake-pqsuites §6.3, "Requires DH/NIKE").
 */
public enum class EdhocMethod(
    /** METHOD in message_1. */
    public val value: Int,
    /** How the Initiator authenticates (Signature_or_MAC_3). */
    public val initiatorAuthentication: AuthenticationKind,
    /** How the Responder authenticates (Signature_or_MAC_2). */
    public val responderAuthentication: AuthenticationKind,
) {
    /** Method 0: both parties sign. */
    SIGNATURE_SIGNATURE(0, AuthenticationKind.SIGNATURE, AuthenticationKind.SIGNATURE),

    /** Method 1: the Initiator signs, the Responder uses a static DH key. */
    SIGNATURE_STATIC_DH(1, AuthenticationKind.SIGNATURE, AuthenticationKind.STATIC_DH),

    /** Method 2: the Initiator uses a static DH key, the Responder signs. */
    STATIC_DH_SIGNATURE(2, AuthenticationKind.STATIC_DH, AuthenticationKind.SIGNATURE),

    /** Method 3: both parties use static DH keys. */
    STATIC_DH_STATIC_DH(3, AuthenticationKind.STATIC_DH, AuthenticationKind.STATIC_DH),
    ;

    /** Whether either party authenticates with static Diffie-Hellman. */
    public val requiresDiffieHellman: Boolean
        get() = initiatorAuthentication == AuthenticationKind.STATIC_DH ||
            responderAuthentication == AuthenticationKind.STATIC_DH

    /** Whether this method can run on [suite]: static-DH methods need a Diffie-Hellman key exchange. */
    public fun isCompatibleWith(suite: CipherSuite): Boolean =
        !requiresDiffieHellman || suite.keyExchange.supportsStaticDiffieHellman

    /** The authentication kind of [role]. */
    public fun authenticationOf(role: EdhocRole): AuthenticationKind =
        if (role == EdhocRole.INITIATOR) initiatorAuthentication else responderAuthentication

    public companion object {
        /** The method with METHOD = [value], or null for an unknown method. */
        public fun fromValue(value: Int): EdhocMethod? = entries.firstOrNull { it.value == value }
    }
}

/** The two EDHOC roles (RFC 9528 §3.1): the Initiator sends message_1, the Responder answers it. */
public enum class EdhocRole {
    INITIATOR,
    RESPONDER,
}
