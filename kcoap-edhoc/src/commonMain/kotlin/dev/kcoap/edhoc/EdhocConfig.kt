package dev.kcoap.edhoc

import dev.kcoap.crypto.CryptoBackend

/**
 * The application profile (RFC 9528 §3.9) of an [EdhocInitiator].
 *
 * @property method the authentication method; the Initiator decides it alone (RFC 9528 §3.2).
 * @property cipherSuites the supported suites in order of preference, most preferred first. Only those the
 *   crypto backend runs ([CipherSuite.isSupportedBy]) are offered, and the order is fixed: SUITES_I is
 *   always a prefix of them (RFC 9528 §5.2.2). [supportedBy] narrows the list to them up front.
 * @property credentials own credentials; the first one usable for the method and the selected suite is used.
 *   A suite without a usable credential can still be listed, but a session that selects it fails when
 *   message_3 is created.
 * @property credentialResolver resolves and authorizes ID_CRED_R.
 * @property connectionIdentifiers chooses C_I, once per message_1.
 * @property ephemeralKeys source of X; fresh random keys unless replaying test vectors.
 * @property initialCipherSuite the suite to select in the first message_1, for example one remembered
 *   from an earlier session with this Responder (RFC 9528 §6.3.1); by default the most preferred one the
 *   crypto backend runs.
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

    /**
     * This configuration with only the cipher suites [crypto] runs for [method], in the same order of
     * preference ([CipherSuite.isSupportedBy], docs/adr/0001-crypto-capabilities.md). [EdhocInitiator]
     * makes the same choice in every session; calling this once where the configuration is set up finds a
     * backend that cannot run it before any session starts, and shows what will be offered.
     *
     * @throws IllegalArgumentException naming the dropped suites and what each needs, when no suite with a
     *   usable credential is left, or when [initialCipherSuite] is dropped.
     */
    public suspend fun supportedBy(crypto: CryptoBackend): EdhocInitiatorConfig {
        val runnable = runnableSuites(crypto)
        problemWith(runnable)?.let { throw IllegalArgumentException(it) }
        return EdhocInitiatorConfig(
            method, runnable.suites, credentials, credentialResolver, connectionIdentifiers, eadHandler,
            ephemeralKeys, initialCipherSuite,
        )
    }

    internal suspend fun runnableSuites(crypto: CryptoBackend): RunnableSuites =
        RunnableSuites.of(cipherSuites, crypto, method)

    /** Why the Initiator cannot start with [runnable], or null when it can. */
    internal fun problemWith(runnable: RunnableSuites): String? = when {
        runnable.suites.none { credentialFor(it) != null } ->
            "No cipher suite with a credential runs on this crypto backend: ${runnable.describeDropped()}"
        initialCipherSuite != null && initialCipherSuite !in runnable.suites ->
            "The initial cipher suite ${initialCipherSuite.value} ${runnable.reasonFor(initialCipherSuite)}"
        else -> null
    }

    internal fun credentialFor(suite: CipherSuite): LocalCredential? =
        credentials.firstOrNull { it.isUsableFor(method.initiatorAuthentication, suite) }
}

/**
 * The application profile (RFC 9528 §3.9) of an [EdhocResponder].
 *
 * @property methods the methods the Responder accepts.
 * @property cipherSuites the supported suites. Only those the crypto backend runs for the received method
 *   ([CipherSuite.isSupportedBy]) are accepted and named in SUITES_R, where their order has no meaning
 *   (RFC 9528 §6.3). [supportedBy] narrows the list to them up front.
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

    /**
     * This configuration with only the cipher suites [crypto] runs for at least one of [methods] with a
     * credential ([CipherSuite.isSupportedBy], docs/adr/0001-crypto-capabilities.md). [EdhocResponder] makes
     * the same choice, per method, for every message_1, so SUITES_R names only runnable suites and a selected
     * suite it cannot run gets error code 2 (RFC 9528 §6.3). Calling this once where the configuration is set
     * up finds a backend that cannot run it before any session starts.
     *
     * @throws IllegalArgumentException naming the dropped suites and what each needs, when no method is left
     *   with a runnable suite.
     */
    public suspend fun supportedBy(crypto: CryptoBackend): EdhocResponderConfig {
        val runnable = methods.associateWith { runnableSuitesFor(it, crypto) }
        val kept = cipherSuites.filter { suite -> runnable.values.any { suite in it.suites } }
        require(kept.isNotEmpty()) {
            "No cipher suite with a credential runs on this crypto backend: " +
                runnable.entries.joinToString("; ") { (method, suites) ->
                    "method ${method.value}: ${suites.describeDropped().ifEmpty { "no suite with a credential" }}"
                }
        }
        return EdhocResponderConfig(
            methods, kept, credentials, credentialResolver, connectionIdentifiers, eadHandler, ephemeralKeys,
        )
    }

    /** The suites usable with [method] ([suitesFor]) that [crypto] runs. */
    internal suspend fun runnableSuitesFor(method: EdhocMethod, crypto: CryptoBackend): RunnableSuites =
        RunnableSuites.of(suitesFor(method), crypto, method)

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
