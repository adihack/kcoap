package dev.kcoap.edhoc

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * The EDHOC Initiator (RFC 9528 §5), as a sans-IO state machine: it takes and returns message bytes and
 * leaves transport, correlation and deduplication (RFC 9528 §3.4, §7) to the caller.
 *
 * ```
 * val initiator = EdhocInitiator(config, crypto)
 * val reply = transport.exchange(initiator.createMessage1())
 * initiator.processMessage2(reply)                 // throws EdhocException
 * val (message3, session) = initiator.createMessage3()
 * transport.send(message3)                         // session: PRK_out, exporter, peer credential
 * initiator.processMessage4(transport.receive())  // only if the application profile uses message_4
 * ```
 *
 * Only the configured suites that [crypto] runs for the method are offered ([CipherSuite.isSupportedBy]):
 * [createMessage1] asks the backend, whose answers are memoized, and SUITES_I is a prefix of those suites.
 * [EdhocInitiatorConfig.supportedBy] makes the same choice once, up front.
 *
 * Every failure is an [EdhocException]; its [EdhocException.errorMessage], when not null, is the error
 * message to send to the Responder, and the state machine is then [EdhocPhase.ABORTED]. One exception
 * is cipher suite negotiation (RFC 9528 §6.3): when the Responder answers message_1 with error code 2 and
 * names a suite this Initiator prefers and runs, [processMessage2] throws an [EdhocPeerErrorException]
 * whose [EdhocPeerErrorException.retryWith] is that suite, and the Initiator is back at [EdhocPhase.START],
 * so [createMessage1] can be called again:
 *
 * ```
 * try {
 *     initiator.processMessage2(reply)
 * } catch (e: EdhocPeerErrorException) {
 *     if (e.retryWith == null) throw e
 *     reply = transport.exchange(initiator.createMessage1())   // SUITES_I now ends with e.retryWith
 * }
 * ```
 *
 * Calling a method out of order throws [IllegalStateException] and leaves the state unchanged. Instances
 * are not thread-safe; drive one from a single coroutine at a time.
 */
public class EdhocInitiator(
    private val config: EdhocInitiatorConfig,
    private val crypto: CryptoBackend,
    loggerFactory: LoggerFactory = LoggerFactory.Global,
) {
    private val log = loggerFactory.logger(LogTags.EDHOC).with("role" to "initiator")

    /** Test hook for the intermediate values of RFC 9529; never set in production. */
    internal var intermediateValues: IntermediateValues? = null

    private var state: State = State.Start(config.initialCipherSuite ?: config.cipherSuites.first(), runnable = null)

    /** Where the handshake stands. */
    public val phase: EdhocPhase get() = state.phase

    /**
     * The suite selected for the current or next message_1. Until the first [createMessage1] asks the
     * crypto backend, this is the configured choice; that call replaces a default the backend cannot run
     * with the most preferred suite it can.
     */
    public val cipherSuite: CipherSuite get() = state.suite

    /** The authenticated Responder credential, once message_2 has been verified. */
    public val peerCredential: AuthenticationCredential?
        get() = when (val current = state) {
            is State.VerifiedMessage2 -> current.peerCredential
            else -> session?.peerCredential
        }

    /** The session, once message_3 has been created. */
    public val session: EdhocSession?
        get() = when (val current = state) {
            is State.Completed -> current.session
            is State.Persisted -> current.session
            else -> null
        }

    /** Why the state machine aborted, or null. */
    public val failure: EdhocException? get() = (state as? State.Aborted)?.failure

    /**
     * Composes message_1 (RFC 9528 §5.2.2): SUITES_I up to the selected suite, a fresh ephemeral key pair,
     * and C_I. SUITES_I holds only suites the crypto backend runs for the method; the first call asks the
     * backend which ones those are, and fails with an [EdhocException] naming what is missing when no suite
     * with a credential, or not the configured initial suite, is among them.
     */
    public suspend fun createMessage1(): ByteArray {
        val start = expect<State.Start>("createMessage1")
        return step("message_1", isReply = false) {
            val (suite, runnable) = start.runnable?.let { start.suite to it } ?: firstSelection()
            val suites = runnable.take(runnable.indexOf(suite) + 1).map { it.value }
            val keySchedule = KeySchedule(suite, crypto)
            val exchange = EphemeralKeyExchange.forSuite(suite, crypto, config.ephemeralKeys)
            val share = exchange.initiatorShare()
            val connectionIdentifier = config.connectionIdentifiers.choose(crypto, null)
            val ead = config.eadHandler.produce(EdhocMessage.MESSAGE_1)
            val message1 = Message1(config.method.value, suites, share.gX, connectionIdentifier, ead).encode()
            val hashMessage1 = keySchedule.hash(message1)
            record("G_X", share.gX)
            record("H(message_1)", hashMessage1)
            log.debug {
                "message_1: method ${config.method.value}, SUITES_I $suites, C_I $connectionIdentifier, " +
                    "EAD_1 ${ead.describe()}"
            }
            log.trace { "message_1 ${log.payload(message1)}" }
            state = State.WaitingForMessage2(
                suite, runnable, keySchedule, exchange, share, connectionIdentifier, hashMessage1,
            )
            message1
        }
    }

    /**
     * The suites the crypto backend runs for the method, asked once per Initiator, and the suite the first
     * message_1 selects.
     */
    private suspend fun firstSelection(): Pair<CipherSuite, List<CipherSuite>> {
        val runnable = config.runnableSuites(crypto)
        if (runnable.hasDropped) log.debug { "Not offered on this crypto backend: ${runnable.describeDropped()}" }
        config.problemWith(runnable)?.let { throw EdhocException.unspecified(it) }
        return (config.initialCipherSuite ?: runnable.suites.first()) to runnable.suites
    }

    /**
     * Processes message_2 (RFC 9528 §5.3.3): decrypts PLAINTEXT_2, hands EAD_2 to the [EadHandler], resolves
     * ID_CRED_R through the [CredentialResolver] and verifies Signature_or_MAC_2.
     *
     * An error message in reply to message_1 throws [EdhocPeerErrorException]. With error code 2 and a
     * SUITES_R naming a suite this Initiator prefers and runs, its [EdhocPeerErrorException.retryWith] is
     * that suite and the Initiator is back at [EdhocPhase.START] (RFC 9528 §6.3.1); otherwise the session
     * is aborted.
     */
    public suspend fun processMessage2(message2: ByteArray) {
        val waiting = expect<State.WaitingForMessage2>("processMessage2")
        log.trace { "message_2 ${log.payload(message2)}" }
        if (MessageReader.startsWithInteger(message2)) retryOrAbort(waiting, Handshake.peerError(message2))
        step("message_2") {
            val keySchedule = waiting.keySchedule
            val content = SingleByteStringMessage.decode(message2, "message_2")
            val gYLength = waiting.suite.keyExchange.responderShareLength
            if (content.size <= gYLength) throw EdhocException.malformed("message_2: G_Y_CIPHERTEXT_2 too short")
            val gY = content.copyOfRange(0, gYLength)
            val ciphertext2 = content.copyOfRange(gYLength, content.size)

            val gXY = waiting.exchange.complete(waiting.share.privateKey, gY)
            val th2 = keySchedule.th2(gY, waiting.hashMessage1)
            val prk2e = keySchedule.prk2e(th2, gXY)
            val keystream2 = keySchedule.keystream2(prk2e, th2, ciphertext2.size)
            val plaintext2 = Handshake.xor(ciphertext2, keystream2)
            record("G_Y", gY)
            record("G_XY", gXY)
            record("TH_2", th2)
            record("PRK_2e", prk2e)
            record("KEYSTREAM_2", keystream2)
            record("PLAINTEXT_2", plaintext2)
            wipeAll(gXY, keystream2)

            val received = AuthenticatedPlaintext.decode(plaintext2, withConnectionIdentifier = true, "PLAINTEXT_2")
            record("Signature_or_MAC_2", received.signatureOrMac)
            config.eadHandler.receive(EdhocMessage.MESSAGE_2, received.ead)
            val verified = verifyMessage2(waiting, received, th2, prk2e)
            wipeAll(prk2e)
            val th3 = keySchedule.nextTranscriptHash(th2, plaintext2, verified.credential.encoded)
            record("PRK_3e2m", verified.prk)
            record("MAC_2", verified.mac)
            record("TH_3", th3)
            log.debug {
                "message_2 verified: C_R ${received.connectionIdentifier}, " +
                    "ID_CRED_R ${received.credentialIdentifier}, EAD_2 ${received.ead.describe()}"
            }
            waiting.share.privateKey.destroyIfSoftware()
            state = State.VerifiedMessage2(waiting, gY, received, verified.credential, verified.prk, th3)
        }
    }

    /** Steps 5 and 6 of RFC 9528 §5.3.3: obtain CRED_R and verify Signature_or_MAC_2. */
    private suspend fun verifyMessage2(
        waiting: State.WaitingForMessage2,
        received: AuthenticatedPlaintext,
        th2: ByteArray,
        prk2e: ByteArray,
    ): VerifiedPeer {
        val suite = waiting.suite
        val keySchedule = waiting.keySchedule
        val kind = config.method.responderAuthentication
        val authenticator = SignatureOrMac(keySchedule, crypto)
        if (received.signatureOrMac.size != authenticator.expectedLength(kind)) {
            throw EdhocException.unspecified("Signature_or_MAC_2 has the wrong length")
        }
        val candidates = Handshake.resolvePeer(config.credentialResolver, received.credentialIdentifier, kind, suite)
        // PRK_3e2m = EDHOC_Extract( SALT_3e2m, G_RX ) when the Responder uses static DH (RFC 9528 §4.1.1.2).
        val salt3e2m = if (kind == AuthenticationKind.STATIC_DH) keySchedule.salt3e2m(prk2e, th2) else null
        val verified = authenticator.firstVerified(
            candidates,
            kind,
            received.signatureOrMac,
            prkFor = { candidate ->
                if (salt3e2m == null) {
                    prk2e.copyOf()
                } else {
                    val gRX = waiting.exchange.staticAgreement(waiting.share.privateKey, candidate.publicKey)
                    record("SALT_3e2m", salt3e2m)
                    record("G_RX", gRX)
                    keySchedule.extract(salt3e2m, gRX).also { wipeAll(gRX) }
                }
            },
            inputFor = { candidate ->
                AuthenticationInput(
                    KeySchedule.MAC_2, received.connectionIdentifier, received.credentialIdentifier, th2, candidate,
                    received.ead,
                )
            },
        )
        wipeAll(salt3e2m)
        return verified ?: throw EdhocException.unspecified("Verification of Signature_or_MAC_2 failed")
    }

    /**
     * Composes message_3 (RFC 9528 §5.4.2) and completes the session, which comes with it; [session]
     * returns the same session afterwards.
     */
    public suspend fun createMessage3(): Message3 {
        val verified = expect<State.VerifiedMessage2>("createMessage3")
        return step("message_3") {
            val suite = verified.suite
            val keySchedule = verified.keySchedule
            val own = config.credentialFor(suite)
                ?: throw EdhocException.unspecified("No credential for cipher suite ${suite.value}")
            val kind = config.method.initiatorAuthentication
            val th3 = verified.th3
            // PRK_4e3m = EDHOC_Extract( SALT_4e3m, G_IY ) when the Initiator uses static DH (RFC 9528 §4.1.1.3).
            val prk4e3m = if (kind == AuthenticationKind.STATIC_DH) {
                val salt4e3m = keySchedule.salt4e3m(verified.prk3e2m, th3)
                val gIY = verified.exchange.staticAgreementWithShare(own.privateKey, verified.gY)
                record("SALT_4e3m", salt4e3m)
                record("G_IY", gIY)
                keySchedule.extract(salt4e3m, gIY).also { wipeAll(salt4e3m, gIY) }
            } else {
                verified.prk3e2m.copyOf()
            }
            val ead = config.eadHandler.produce(EdhocMessage.MESSAGE_3)
            val authenticator = SignatureOrMac(keySchedule, crypto)
            val input = AuthenticationInput(KeySchedule.MAC_3, null, own.identifier, th3, own.credential, ead)
            val mac3 = authenticator.mac(prk4e3m, input, kind)
            val signatureOrMac3 = authenticator.create(mac3, input, kind, own.privateKey)
            val plaintext3 = AuthenticatedPlaintext(null, own.identifier, signatureOrMac3, ead).encode()
            val k3 = keySchedule.k3(verified.prk3e2m, th3)
            val iv3 = keySchedule.iv3(verified.prk3e2m, th3)
            val ciphertext3 = Handshake.encrypt(crypto, suite, k3, iv3, plaintext3, Handshake.encrypt0Aad(th3))
            val message3 = SingleByteStringMessage.encode(ciphertext3)
            val th4 = keySchedule.nextTranscriptHash(th3, plaintext3, own.credential.encoded)
            val prkOut = keySchedule.prkOut(prk4e3m, th4)
            val prkExporter = keySchedule.prkExporter(prkOut)
            record("PRK_4e3m", prk4e3m)
            record("MAC_3", mac3)
            record("Signature_or_MAC_3", signatureOrMac3)
            record("PLAINTEXT_3", plaintext3)
            record("K_3", k3)
            record("IV_3", iv3)
            record("TH_4", th4)
            record("PRK_out", prkOut)
            record("PRK_exporter", prkExporter)
            val session = EdhocSession(
                EdhocRole.INITIATOR, config.method, suite, verified.initiatorConnectionIdentifier,
                verified.responderConnectionIdentifier, verified.peerIdentifier, verified.peerCredential,
                prkOut, prkExporter, crypto,
            )
            wipeAll(verified.prk3e2m, k3, iv3, prkOut, prkExporter)
            log.debug { "message_3: ID_CRED_I ${own.identifier}, EAD_3 ${ead.describe()}; session completed" }
            log.trace { "message_3 ${log.payload(message3)}" }
            state = State.Completed(session, keySchedule, prk4e3m, th4)
            Message3(message3, session)
        }
    }

    /**
     * Processes message_4 (RFC 9528 §5.5.3), which confirms that the Responder derived the same PRK_out.
     * Only valid after [createMessage3], and only in application profiles that use message_4.
     */
    public suspend fun processMessage4(message4: ByteArray) {
        val completed = expect<State.Completed>("processMessage4")
        step("message_4") {
            log.trace { "message_4 ${log.payload(message4)}" }
            if (MessageReader.startsWithInteger(message4)) throw EdhocPeerErrorException(Handshake.peerError(message4))
            val ciphertext4 = SingleByteStringMessage.decode(message4, "message_4")
            val keySchedule = completed.keySchedule
            val k4 = keySchedule.k4(completed.prk4e3m, completed.th4)
            val iv4 = keySchedule.iv4(completed.prk4e3m, completed.th4)
            record("K_4", k4)
            record("IV_4", iv4)
            val aad4 = Handshake.encrypt0Aad(completed.th4)
            val plaintext4 = Handshake.decrypt(crypto, keySchedule.suite, k4, iv4, ciphertext4, aad4, "message_4")
            wipeAll(k4, iv4)
            val ead = Plaintext4.decode(plaintext4)
            config.eadHandler.receive(EdhocMessage.MESSAGE_4, ead)
            log.debug { "message_4 verified: EAD_4 ${ead.describe()}" }
            completed.destroy()
            state = State.Persisted(completed.session)
        }
    }

    /**
     * Aborts the session on the application's initiative, for example when ID_CRED_R or EAD_2 is not
     * acceptable after all. Returns the error message to send (code 1 with [diagnostic]) if a received
     * message_2 is waiting for an answer, otherwise null.
     */
    public fun abort(diagnostic: String): ByteArray? {
        val answersMessage2 = state is State.VerifiedMessage2
        if (state !is State.Aborted) abortWith(EdhocException.unspecified(diagnostic))
        return if (answersMessage2) EdhocError.unspecified(diagnostic).encode() else null
    }

    /**
     * An error message in reply to message_1. RFC 9528 §6.3.1: on error code 2, retry with the most
     * preferred suite in SUITES_R that this Initiator runs, unless that is the suite just rejected; abort
     * otherwise.
     */
    private fun retryOrAbort(waiting: State.WaitingForMessage2, error: EdhocError): Nothing {
        val suitesR = error.cipherSuites
        val next = suitesR?.let { offered -> waiting.runnable.firstOrNull { it.value in offered } }
        if (next != null && next != waiting.suite) {
            log.debug { "Responder supports $suitesR; selecting suite ${next.value} for the next message_1" }
            waiting.destroy()
            state = State.Start(next, waiting.runnable)
            throw EdhocPeerErrorException(error, retryWith = next)
        }
        val failure = EdhocPeerErrorException(error)
        abortWith(failure)
        log.warn { "message_2 failed: ${failure.message}" }
        throw failure
    }

    private inline fun <reified T : State> expect(operation: String): T =
        state as? T ?: throw IllegalStateException("$operation is not allowed in phase $phase")

    /**
     * Runs one protocol step; any failure aborts the session and surfaces as an [EdhocException]. A missing
     * algorithm is this side's failure, and the diagnostic says so instead of blaming the peer's input.
     */
    private inline fun <R> step(what: String, isReply: Boolean = true, block: () -> R): R = try {
        block()
    } catch (e: EdhocException) {
        if (!isReply) e.isReply = false
        abortWith(e)
        log.warn { "$what failed: ${e.message}" }
        throw e
    } catch (e: CancellationException) {
        abortWith(EdhocException.unspecified("Cancelled while processing $what", e))
        throw e
    } catch (e: UnsupportedAlgorithmException) {
        val failure = EdhocException.unavailable(e.algorithm, e).also { it.isReply = isReply }
        abortWith(failure)
        log.warn { "$what failed: ${failure.message}" }
        throw failure
    } catch (e: Exception) {
        val failure = EdhocException.unspecified("Processing $what failed", e).also { it.isReply = isReply }
        abortWith(failure)
        log.warn(e) { "$what failed" }
        throw failure
    }

    private fun abortWith(failure: EdhocException) {
        state.destroy()
        state = State.Aborted(failure, state.suite)
    }

    private fun record(name: String, value: ByteArray) {
        intermediateValues?.record(name, value.copyOf())
    }

    private sealed class State(val phase: EdhocPhase, val suite: CipherSuite) {
        /** Overwrites the secrets this state holds. */
        open fun destroy() {}

        /**
         * Ready for message_1. [runnable] is null until the crypto backend has been asked which suites it
         * runs; until then [suite] is only the configured choice.
         */
        class Start(suite: CipherSuite, val runnable: List<CipherSuite>?) : State(EdhocPhase.START, suite)

        class WaitingForMessage2(
            suite: CipherSuite,
            /** The suites SUITES_I was taken from, which a retry chooses from (RFC 9528 §6.3.1). */
            val runnable: List<CipherSuite>,
            val keySchedule: KeySchedule,
            val exchange: EphemeralKeyExchange,
            val share: InitiatorShare,
            val connectionIdentifier: ConnectionIdentifier,
            val hashMessage1: ByteArray,
        ) : State(EdhocPhase.WAIT_M2, suite) {
            override fun destroy() = share.privateKey.destroyIfSoftware()
        }

        class VerifiedMessage2(
            previous: WaitingForMessage2,
            val gY: ByteArray,
            plaintext2: AuthenticatedPlaintext,
            val peerCredential: AuthenticationCredential,
            val prk3e2m: ByteArray,
            val th3: ByteArray,
        ) : State(EdhocPhase.VERIFIED_M2, previous.suite) {
            val keySchedule: KeySchedule = previous.keySchedule
            val exchange: EphemeralKeyExchange = previous.exchange
            val initiatorConnectionIdentifier: ConnectionIdentifier = previous.connectionIdentifier
            val responderConnectionIdentifier: ConnectionIdentifier = plaintext2.connectionIdentifier!!
            val peerIdentifier: CredentialIdentifier = plaintext2.credentialIdentifier

            override fun destroy() = wipeAll(prk3e2m)
        }

        class Completed(
            val session: EdhocSession,
            val keySchedule: KeySchedule,
            val prk4e3m: ByteArray,
            val th4: ByteArray,
        ) : State(EdhocPhase.COMPLETED, session.cipherSuite) {
            override fun destroy() = wipeAll(prk4e3m)
        }

        class Persisted(val session: EdhocSession) : State(EdhocPhase.PERSISTED, session.cipherSuite)

        class Aborted(val failure: EdhocException, suite: CipherSuite) : State(EdhocPhase.ABORTED, suite)
    }
}
