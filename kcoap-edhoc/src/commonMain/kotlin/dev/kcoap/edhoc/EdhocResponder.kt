package dev.kcoap.edhoc

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * The EDHOC Responder (RFC 9528 §5), as a sans-IO state machine for one session.
 *
 * ```
 * val responder = EdhocResponder(config, crypto)
 * responder.processMessage1(message1)          // throws EdhocException, e.g. error code 2 with SUITES_R
 * transport.reply(responder.createMessage2())
 * val session = responder.processMessage3(transport.receive())
 * transport.reply(responder.createMessage4())  // only if the application profile uses message_4
 * ```
 *
 * Only the configured suites that [crypto] runs for the received method are accepted and named in SUITES_R
 * ([CipherSuite.isSupportedBy]), and a selected suite it cannot run is answered with error code 2 and the
 * suites it can (RFC 9528 §6.3). The configuration asks the backend once and remembers the answers, so
 * message_1 makes no backend probe, also on a backend that does not memoize its own answers.
 * [EdhocResponderConfig.supportedBy] makes the same choice up front.
 *
 * Every failure is an [EdhocException] whose [EdhocException.errorMessage], when not null, is the error
 * message to send to the Initiator; the state machine is then [EdhocPhase.ABORTED]. After
 * [processMessage1] the application can inspect [method], [cipherSuite] and [peerConnectionIdentifier]
 * before answering.
 *
 * Calling a method out of order throws [IllegalStateException] and leaves the state unchanged. Instances
 * are not thread-safe.
 */
public class EdhocResponder(
    private val config: EdhocResponderConfig,
    private val crypto: CryptoBackend,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    private val log = loggerFactory.logger(LogTags.EDHOC).with("role" to "responder")

    /** Test hook for the intermediate values of RFC 9529; never set in production. */
    internal var intermediateValues: IntermediateValues? = null

    private var state: State = State.Start

    /** Where the handshake stands. */
    public val phase: EdhocPhase get() = state.phase

    /** METHOD of the accepted message_1. */
    public val method: EdhocMethod? get() = state.negotiation?.method

    /** The selected cipher suite of the accepted message_1. */
    public val cipherSuite: CipherSuite? get() = state.negotiation?.suite

    /** C_I of the accepted message_1. */
    public val peerConnectionIdentifier: ConnectionIdentifier? get() = state.negotiation?.initiatorConnectionIdentifier

    /** The session, once message_3 has been verified. */
    public val session: EdhocSession?
        get() = when (val current = state) {
            is State.Completed -> current.session
            is State.Persisted -> current.session
            else -> null
        }

    /** Why the state machine aborted, or null. */
    public val failure: EdhocException? get() = (state as? State.Aborted)?.failure

    /**
     * Processes message_1 (RFC 9528 §5.2.3): checks the method, the cipher suite negotiation of §6.3 and
     * G_X, and hands EAD_1 to the [EadHandler]. Supported suites are those configured for the method that
     * the crypto backend runs. A selected suite that is not supported, or preceded in SUITES_I by a
     * supported one, fails with error code 2 and a SUITES_R of supported suites.
     */
    public suspend fun processMessage1(message1: ByteArray) {
        expect<State.Start>("processMessage1")
        step("message_1") {
            log.trace { "message_1 ${log.payload(message1)}" }
            val received = Message1.decode(message1)
            val method = EdhocMethod.fromValue(received.method)?.takeIf { it in config.methods }
                ?: throw EdhocException.unspecified("Method ${received.method} not supported")
            val runnable = config.runnableSuitesFor(method, crypto)
            if (runnable.hasDropped) log.debug { "Not accepted on this crypto backend: ${runnable.describeDropped()}" }
            val supported = runnable.suites
            if (supported.isEmpty()) {
                // The peer learns only that the method is not supported; the local message says why.
                val error = EdhocError.unspecified("Method ${method.value} not supported")
                val why = if (runnable.hasDropped) ": ${runnable.describeDropped()}" else ""
                throw EdhocException(error, "${error.diagnostic}$why")
            }
            val supportedValues = supported.map { it.value }
            val firstSupported = received.suites.firstOrNull { it in supportedValues }
            if (firstSupported != received.selectedSuite) {
                // RFC 9528 §6.3: name the Initiator's most preferred supported suite, or else all supported ones.
                val suitesR = if (firstSupported != null) listOf(firstSupported) else supportedValues
                log.debug { "message_1 selects ${received.selectedSuite} of ${received.suites}; SUITES_R $suitesR" }
                throw EdhocException(EdhocError.wrongSelectedCipherSuite(suitesR), "Wrong selected cipher suite")
            }
            val suite = supported.first { it.value == firstSupported }
            if (received.gX.size != suite.keyExchange.initiatorShareLength) {
                throw EdhocException.unspecified("G_X has the wrong length for cipher suite ${suite.value}")
            }
            val keySchedule = KeySchedule(suite, crypto)
            val hashMessage1 = keySchedule.hash(message1)
            record("H(message_1)", hashMessage1)
            config.eadHandler.receive(EdhocMessage.MESSAGE_1, received.ead)
            log.debug {
                "message_1 accepted: method ${method.value}, suite ${suite.value}, " +
                    "C_I ${received.connectionIdentifier}, EAD_1 ${received.ead.describe()}"
            }
            state = State.VerifiedMessage1(
                Negotiation(method, suite, received.connectionIdentifier), keySchedule, received.gX, hashMessage1,
            )
        }
    }

    /**
     * Composes message_2 (RFC 9528 §5.3.2): completes the ephemeral exchange (ECDH, or KEM encapsulation),
     * chooses C_R, and authenticates with MAC_2 and Signature_or_MAC_2.
     *
     * When it fails or is cancelled, the ephemeral key Y and the PRKs derived so far are wiped (RFC 9528
     * §9.8), like the secrets of every later state when the session aborts.
     */
    public suspend fun createMessage2(): ByteArray {
        val verified = expect<State.VerifiedMessage1>("createMessage2")
        return step("message_2") {
            val (method, suite, initiatorConnectionIdentifier) = verified.negotiation
            val keySchedule = verified.keySchedule
            val own = config.credentialFor(method, suite)!!
            val exchange = EphemeralKeyExchange.forSuite(suite, crypto, config.ephemeralKeys)
            val share = exchange.respond(verified.gX)
            // Until the WaitingForMessage3 state holds them, the finally block below owns these secrets.
            var prk2e: ByteArray? = null
            var prk3e2m: ByteArray? = null
            var isComposed = false
            try {
                val connectionIdentifier = config.connectionIdentifiers.choose(crypto, initiatorConnectionIdentifier)
                if (connectionIdentifier == initiatorConnectionIdentifier) {
                    throw EdhocException.unspecified("C_R must differ from C_I")
                }
                val th2 = keySchedule.th2(share.gY, verified.hashMessage1)
                prk2e = keySchedule.prk2e(th2, share.sharedSecret)
                record("G_Y", share.gY)
                record("G_XY", share.sharedSecret)
                record("TH_2", th2)
                record("PRK_2e", prk2e)
                wipeAll(share.sharedSecret)

                val kind = method.responderAuthentication
                // PRK_3e2m = EDHOC_Extract( SALT_3e2m, G_RX ) with static DH (RFC 9528 §4.1.1.2).
                prk3e2m = if (kind == AuthenticationKind.STATIC_DH) {
                    val salt3e2m = keySchedule.salt3e2m(prk2e, th2)
                    val gRX = exchange.staticAgreementWithShare(own.privateKey, verified.gX)
                    record("SALT_3e2m", salt3e2m)
                    record("G_RX", gRX)
                    keySchedule.extract(salt3e2m, gRX).also { wipeAll(salt3e2m, gRX) }
                } else {
                    prk2e.copyOf()
                }
                val ead = config.eadHandler.produce(EdhocMessage.MESSAGE_2)
                val authenticator = SignatureOrMac(keySchedule, crypto)
                val input = AuthenticationInput(
                    KeySchedule.MAC_2, connectionIdentifier, own.identifier, th2, own.credential, ead,
                )
                val mac2 = authenticator.mac(prk3e2m, input, kind)
                val signatureOrMac2 = authenticator.create(mac2, input, kind, own.privateKey)
                val plaintext2 =
                    AuthenticatedPlaintext(connectionIdentifier, own.identifier, signatureOrMac2, ead).encode()
                val keystream2 = keySchedule.keystream2(prk2e, th2, plaintext2.size)
                val message2 = SingleByteStringMessage.encode(share.gY + Handshake.xor(plaintext2, keystream2))
                val th3 = keySchedule.nextTranscriptHash(th2, plaintext2, own.credential.encoded)
                record("PRK_3e2m", prk3e2m)
                record("MAC_2", mac2)
                record("Signature_or_MAC_2", signatureOrMac2)
                record("PLAINTEXT_2", plaintext2)
                record("KEYSTREAM_2", keystream2)
                record("TH_3", th3)
                wipeAll(keystream2)

                // Y is still needed for G_IY when the Initiator authenticates with static DH (RFC 9528 §4.1.1.3).
                val keepY = method.initiatorAuthentication == AuthenticationKind.STATIC_DH
                val ephemeralKey = share.privateKey.takeIf { keepY }
                if (!keepY) share.privateKey?.destroyIfSoftware()
                log.debug {
                    "message_2: C_R $connectionIdentifier, ID_CRED_R ${own.identifier}, EAD_2 ${ead.describe()}"
                }
                log.trace { "message_2 ${log.payload(message2)}" }
                state = State.WaitingForMessage3(
                    verified.negotiation, keySchedule, exchange, connectionIdentifier, ephemeralKey, prk3e2m, th3,
                )
                isComposed = true
                message2
            } finally {
                wipeAll(prk2e)
                if (!isComposed) {
                    share.privateKey?.destroyIfSoftware()
                    wipeAll(share.sharedSecret, prk3e2m)
                }
            }
        }
    }

    /**
     * Processes message_3 (RFC 9528 §5.4.3): decrypts PLAINTEXT_3, hands EAD_3 to the [EadHandler], resolves
     * ID_CRED_I and verifies Signature_or_MAC_3. On success the state machine is [EdhocPhase.COMPLETED]
     * and returns the session, which [session] returns afterwards too.
     */
    public suspend fun processMessage3(message3: ByteArray): EdhocSession {
        val waiting = expect<State.WaitingForMessage3>("processMessage3")
        return step("message_3") {
            log.trace { "message_3 ${log.payload(message3)}" }
            if (MessageReader.startsWithInteger(message3)) throw EdhocPeerErrorException(Handshake.peerError(message3))
            val (method, suite, initiatorConnectionIdentifier) = waiting.negotiation
            val keySchedule = waiting.keySchedule
            val ciphertext3 = SingleByteStringMessage.decode(message3, "message_3")
            val th3 = waiting.th3
            val k3 = keySchedule.k3(waiting.prk3e2m, th3)
            val iv3 = keySchedule.iv3(waiting.prk3e2m, th3)
            record("K_3", k3)
            record("IV_3", iv3)
            val aad3 = Handshake.encrypt0Aad(th3)
            val plaintext3 = Handshake.decrypt(crypto, suite, k3, iv3, ciphertext3, aad3, "message_3")
            wipeAll(k3, iv3)
            record("PLAINTEXT_3", plaintext3)
            val received = AuthenticatedPlaintext.decode(plaintext3, withConnectionIdentifier = false, "PLAINTEXT_3")
            config.eadHandler.receive(EdhocMessage.MESSAGE_3, received.ead)
            val verified = verifyMessage3(waiting, received)
            val th4 = keySchedule.nextTranscriptHash(th3, plaintext3, verified.credential.encoded)
            val prkOut = keySchedule.prkOut(verified.prk, th4)
            val prkExporter = keySchedule.prkExporter(prkOut)
            record("PRK_4e3m", verified.prk)
            record("MAC_3", verified.mac)
            record("TH_4", th4)
            record("PRK_out", prkOut)
            record("PRK_exporter", prkExporter)
            val session = EdhocSession(
                EdhocRole.RESPONDER, method, suite, initiatorConnectionIdentifier,
                waiting.responderConnectionIdentifier, received.credentialIdentifier, verified.credential,
                prkOut, prkExporter, crypto,
            )
            wipeAll(prkOut, prkExporter)
            waiting.destroy()
            log.debug {
                "message_3 verified: ID_CRED_I ${received.credentialIdentifier}, EAD_3 ${received.ead.describe()}; " +
                    "session completed"
            }
            state = State.Completed(waiting.negotiation, session, keySchedule, verified.prk, th4)
            session
        }
    }

    /** Steps 5 and 6 of RFC 9528 §5.4.3: obtain CRED_I and verify Signature_or_MAC_3. */
    private suspend fun verifyMessage3(
        waiting: State.WaitingForMessage3,
        received: AuthenticatedPlaintext,
    ): VerifiedPeer {
        val (method, suite) = waiting.negotiation
        val keySchedule = waiting.keySchedule
        val kind = method.initiatorAuthentication
        val authenticator = SignatureOrMac(keySchedule, crypto)
        if (received.signatureOrMac.size != authenticator.expectedLength(kind)) {
            throw EdhocException.unspecified("Signature_or_MAC_3 has the wrong length")
        }
        val candidates = Handshake.resolvePeer(config.credentialResolver, received.credentialIdentifier, kind, suite)
        // PRK_4e3m = EDHOC_Extract( SALT_4e3m, G_IY ) when the Initiator uses static DH (RFC 9528 §4.1.1.3).
        val salt4e3m =
            if (kind == AuthenticationKind.STATIC_DH) keySchedule.salt4e3m(waiting.prk3e2m, waiting.th3) else null
        val verified = authenticator.firstVerified(
            candidates,
            kind,
            received.signatureOrMac,
            prkFor = { candidate ->
                if (salt4e3m == null) {
                    waiting.prk3e2m.copyOf()
                } else {
                    val gIY = waiting.exchange.staticAgreement(waiting.ephemeralKey!!, candidate.publicKey)
                    record("SALT_4e3m", salt4e3m)
                    record("G_IY", gIY)
                    keySchedule.extract(salt4e3m, gIY).also { wipeAll(gIY) }
                }
            },
            inputFor = { candidate ->
                AuthenticationInput(
                    KeySchedule.MAC_3, null, received.credentialIdentifier, waiting.th3, candidate, received.ead,
                )
            },
        )
        wipeAll(salt4e3m)
        return verified ?: throw EdhocException.authenticationFailed("Verification of Signature_or_MAC_3 failed")
    }

    /**
     * Composes message_4 (RFC 9528 §5.5.2), for application profiles that use it: it gives the Initiator
     * key confirmation when no protected application message flows from the Responder (RFC 9528 §5.5).
     */
    public suspend fun createMessage4(): ByteArray {
        val completed = expect<State.Completed>("createMessage4")
        return step("message_4") {
            val keySchedule = completed.keySchedule
            val ead = config.eadHandler.produce(EdhocMessage.MESSAGE_4)
            val k4 = keySchedule.k4(completed.prk4e3m, completed.th4)
            val iv4 = keySchedule.iv4(completed.prk4e3m, completed.th4)
            record("K_4", k4)
            record("IV_4", iv4)
            val ciphertext4 = Handshake.encrypt(
                crypto, keySchedule.suite, k4, iv4, Plaintext4.encode(ead), Handshake.encrypt0Aad(completed.th4),
            )
            wipeAll(k4, iv4)
            val message4 = SingleByteStringMessage.encode(ciphertext4)
            log.debug { "message_4: EAD_4 ${ead.describe()}" }
            log.trace { "message_4 ${log.payload(message4)}" }
            completed.destroy()
            state = State.Persisted(completed.negotiation, completed.session)
            message4
        }
    }

    /**
     * Aborts the session on the application's initiative, for example when C_I or EAD_1 is not acceptable.
     * Returns the error message to send (code 1 with [diagnostic]) if a received message is waiting for an
     * answer, otherwise null.
     */
    public fun abort(diagnostic: String): ByteArray? {
        val answersMessage1 = state is State.VerifiedMessage1
        if (state !is State.Aborted) abortWith(EdhocException.unspecified(diagnostic))
        return if (answersMessage1) EdhocError.unspecified(diagnostic).encode() else null
    }

    private inline fun <reified T : State> expect(operation: String): T =
        state as? T ?: throw IllegalStateException("$operation is not allowed in phase $phase")

    /**
     * Runs one protocol step; any failure aborts the session and surfaces as an [EdhocException]. A missing
     * algorithm is this side's failure, and the diagnostic says so instead of blaming the peer's input.
     */
    private inline fun <R> step(what: String, block: () -> R): R = try {
        block()
    } catch (e: EdhocException) {
        abortWith(e)
        log.warn { "$what failed: ${e.message}" }
        throw e
    } catch (e: CancellationException) {
        abortWith(EdhocException.unspecified("Cancelled while processing $what", e))
        throw e
    } catch (e: UnsupportedAlgorithmException) {
        val failure = EdhocException.unavailable(e.algorithm, e)
        abortWith(failure)
        log.warn { "$what failed: ${failure.message}" }
        throw failure
    } catch (e: Exception) {
        val failure = EdhocException.unspecified("Processing $what failed", e)
        abortWith(failure)
        log.warn(e) { "$what failed" }
        throw failure
    }

    private fun abortWith(failure: EdhocException) {
        state.destroy()
        state = State.Aborted(failure, state.negotiation)
    }

    private fun record(name: String, value: ByteArray) {
        intermediateValues?.record(name, value.copyOf())
    }

    /** What message_1 settled. */
    private data class Negotiation(
        val method: EdhocMethod,
        val suite: CipherSuite,
        val initiatorConnectionIdentifier: ConnectionIdentifier,
    )

    private sealed class State(val phase: EdhocPhase) {
        /** What message_1 settled, from [EdhocPhase.VERIFIED_M1] on. */
        abstract val negotiation: Negotiation?

        /** Overwrites the secrets this state holds. */
        open fun destroy() {}

        data object Start : State(EdhocPhase.START) {
            override val negotiation: Negotiation? get() = null
        }

        class VerifiedMessage1(
            override val negotiation: Negotiation,
            val keySchedule: KeySchedule,
            val gX: ByteArray,
            val hashMessage1: ByteArray,
        ) : State(EdhocPhase.VERIFIED_M1)

        class WaitingForMessage3(
            override val negotiation: Negotiation,
            val keySchedule: KeySchedule,
            val exchange: EphemeralKeyExchange,
            val responderConnectionIdentifier: ConnectionIdentifier,
            /** Y, kept only while G_IY still needs it. */
            val ephemeralKey: PrivateKey?,
            val prk3e2m: ByteArray,
            val th3: ByteArray,
        ) : State(EdhocPhase.WAIT_M3) {
            override fun destroy() {
                ephemeralKey?.destroyIfSoftware()
                wipeAll(prk3e2m)
            }
        }

        class Completed(
            override val negotiation: Negotiation,
            val session: EdhocSession,
            val keySchedule: KeySchedule,
            val prk4e3m: ByteArray,
            val th4: ByteArray,
        ) : State(EdhocPhase.COMPLETED) {
            override fun destroy() = wipeAll(prk4e3m)
        }

        class Persisted(override val negotiation: Negotiation, val session: EdhocSession) : State(EdhocPhase.PERSISTED)

        class Aborted(val failure: EdhocException, override val negotiation: Negotiation?) : State(EdhocPhase.ABORTED)
    }
}
