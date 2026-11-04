package dev.kcoap.edhoc

/**
 * The application profile (RFC 9528 §3.9) of an [EdhocInitiator].
 *
 * @property method the authentication method; the Initiator decides it alone (RFC 9528 §3.2).
 * @property cipherSuites the supported suites in order of preference, most preferred first. The order is
 *   fixed: SUITES_I is always a prefix of it (RFC 9528 §5.2.2).
 * @property credentials own credentials; the first one usable for the method and the selected suite is used.
 *   A suite without a usable credential can still be listed, but a session that selects it fails when
 *   message_3 is created.
 * @property credentialResolver resolves and authorizes ID_CRED_R.
 * @property connectionIdentifiers chooses C_I, once per message_1.
 * @property ephemeralKeys source of X; fresh random keys unless replaying test vectors.
 * @property initialCipherSuite the suite to select in the first message_1, for example one remembered
 *   from an earlier session with this Responder (RFC 9528 §6.3.1); by default the most preferred.
 */
public class EdhocInitiatorConfig(
    public val method: EdhocMethod,
    cipherSuites: List<CipherSuite>,
    credentials: List<LocalCredential>,
    public val credentialResolver: CredentialResolver,
    public val connectionIdentifiers: ConnectionIdentifierProvider = ConnectionIdentifierProvider.Random,
    public val eadHandler: EadHandler = EadHandler.None,
    public val ephemeralKeys: EphemeralKeySource = EphemeralKeySource.Random,
    public val initialCipherSuite: CipherSuite? = null,
) {
    public val cipherSuites: List<CipherSuite> = cipherSuites.toList()
    public val credentials: List<LocalCredential> = credentials.toList()

    init {
        validateSuites(this.cipherSuites)
        for (suite in this.cipherSuites) {
            require(method.isCompatibleWith(suite)) { "$method needs Diffie-Hellman, suite ${suite.value} has a KEM" }
        }
        // A suite without a matching credential may still be listed as a preference, as in RFC 9529 §3,
        // but at least one suite must be usable.
        require(this.cipherSuites.any { credentialFor(it) != null }) {
            "No credential for ${method.initiatorAuthentication} in any of the cipher suites"
        }
        require(initialCipherSuite == null || initialCipherSuite in this.cipherSuites) {
            "The initial cipher suite must be one of the supported suites"
        }
    }

    internal fun credentialFor(suite: CipherSuite): LocalCredential? =
        credentials.firstOrNull { it.isUsableFor(method.initiatorAuthentication, suite) }
}

/**
 * The application profile (RFC 9528 §3.9) of an [EdhocResponder].
 *
 * @property methods the methods the Responder accepts.
 * @property cipherSuites the supported suites. Their order has no meaning in SUITES_R (RFC 9528 §6.3).
 * @property credentials own credentials; the first one usable for the received method and suite is used.
 * @property credentialResolver resolves and authorizes ID_CRED_I.
 * @property connectionIdentifiers chooses C_R given C_I.
 * @property ephemeralKeys source of Y on Diffie-Hellman suites.
 */
public class EdhocResponderConfig(
    methods: Set<EdhocMethod>,
    cipherSuites: List<CipherSuite>,
    credentials: List<LocalCredential>,
    public val credentialResolver: CredentialResolver,
    public val connectionIdentifiers: ConnectionIdentifierProvider = ConnectionIdentifierProvider.Random,
    public val eadHandler: EadHandler = EadHandler.None,
    public val ephemeralKeys: EphemeralKeySource = EphemeralKeySource.Random,
) {
    public val methods: Set<EdhocMethod> = methods.toSet()
    public val cipherSuites: List<CipherSuite> = cipherSuites.toList()
    public val credentials: List<LocalCredential> = credentials.toList()

    init {
        require(this.methods.isNotEmpty()) { "At least one method is needed" }
        validateSuites(this.cipherSuites)
        require(this.credentials.isNotEmpty()) { "At least one credential is needed" }
    }

    /** The suites usable with [method]: compatible with it and with a credential to authenticate. */
    internal fun suitesFor(method: EdhocMethod): List<CipherSuite> =
        cipherSuites.filter { method.isCompatibleWith(it) && credentialFor(method, it) != null }

    internal fun credentialFor(method: EdhocMethod, suite: CipherSuite): LocalCredential? =
        credentials.firstOrNull { it.isUsableFor(method.responderAuthentication, suite) }
}

private fun validateSuites(suites: List<CipherSuite>) {
    require(suites.isNotEmpty()) { "At least one cipher suite is needed" }
    require(suites.map { it.value }.toSet().size == suites.size) { "Cipher suite values must be distinct" }
    suites.forEach { require(it.isImplemented) { "Cipher suite ${it.value} is not implemented" } }
}

/**
 * Where an EDHOC state machine stands, with the state names of RFC 9528 Appendix I.
 */
public enum class EdhocPhase {
    /** Initiator: ready to create message_1. Responder: waiting for message_1. */
    START,

    /** Initiator: message_1 sent, waiting for message_2. */
    WAIT_M2,

    /** Responder: message_1 verified, ready to create message_2. */
    VERIFIED_M1,

    /** Initiator: message_2 verified, ready to create message_3. */
    VERIFIED_M2,

    /** Responder: message_2 sent, waiting for message_3. */
    WAIT_M3,

    /**
     * The session is established ([EdhocInitiator.session], [EdhocResponder.session]). The Initiator may
     * still receive message_4; the Responder may still send it.
     */
    COMPLETED,

    /** message_4 was verified (Initiator) or created (Responder). */
    PERSISTED,

    /** The session failed; the state machine accepts no further calls. */
    ABORTED,
}
