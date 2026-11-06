package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.HashAlgorithm
import dev.kcoap.crypto.Hkdf
import dev.kcoap.crypto.KemAlgorithm
import dev.kcoap.crypto.KemEncapsulation
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SignatureAlgorithm
import dev.kcoap.crypto.UnsupportedAlgorithmException
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Capability-aware cipher suite negotiation (RFC 9528 §6.3, docs/adr/0001-crypto-capabilities.md): EDHOC
 * offers and accepts only the suites its crypto backend runs, [CipherSuite.isSupportedBy] says exactly
 * which, and what a backend lacks is reported as this side's failure, by name.
 */
class SuiteNegotiationTest {
    private val default = DefaultCryptoBackend()

    /** A platform without ChaCha20/Poly1305, as WebCrypto is, and without ES256 signatures. */
    private val restricted = RestrictedBackend(
        default, aeads = setOf(AeadAlgorithm.CHACHA20_POLY1305), signatures = setOf(SignatureAlgorithm.ES256),
    )

    /** The default backend as WebCrypto has it, on every target. */
    private val noChaCha = RestrictedBackend(default, aeads = setOf(AeadAlgorithm.CHACHA20_POLY1305))
    private val method0 = EdhocMethod.SIGNATURE_SIGNATURE

    /**
     * For every suite and method, on whatever target this runs, [CipherSuite.isSupportedBy] is true exactly
     * when a loopback handshake on that backend succeeds, message_4 and the application algorithms of the
     * session included. The loopback asks the backend nothing in advance, so it tries the real operations.
     */
    @Test
    fun isSupportedByIsTrueExactlyWhenALoopbackHandshakeSucceeds() = runTest(timeout = 5.minutes) {
        val everySuite = CipherSuite.registered + CipherSuite.SUITE_PQC_1
        val cases = listOf(
            Triple("the default backend", default, everySuite),
            Triple("a backend without ChaCha20/Poly1305 and ES256", restricted, everySuite),
            Triple("the post-quantum test double", FakePostQuantumBackend(), listOf(CipherSuite.SUITE_PQC_1)),
        )
        val outcomes = mutableListOf<Boolean>()
        for ((name, crypto, suites) in cases) {
            for (suite in suites) {
                for (method in EdhocMethod.entries) {
                    val label = "$name, suite ${suite.value}, method ${method.value}"
                    val supported = suite.isSupportedBy(crypto, method)
                    assertEquals(supported, loopbackSucceeds(crypto, suite, method), label)
                    outcomes += supported
                }
            }
        }
        // Suites 2 and 3 run everywhere with every method; suite 25 and the KEM suite with methods 1-3 nowhere.
        assertTrue(outcomes.count { it } >= 8 && outcomes.count { !it } >= 8, "both answers were checked")
    }

    // --- Responder ---------------------------------------------------------------------------------------

    /** SUITES_R names only suites the Responder runs, however many more it is configured with. */
    @Test
    fun suitesRNamesOnlySuitesTheResponderRuns() = runTest {
        val ed = credential(KeyType.ED25519, 1)
        val p256 = credential(KeyType.P256, 2)
        val p384 = credential(KeyType.P384, 3)
        val configured = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_5, CipherSuite.SUITE_2, CipherSuite.SUITE_0)
        val responderConfig = EdhocResponderConfig(setOf(method0), configured, listOf(ed, p256), nobody)
        val responder = EdhocResponder(responderConfig, noChaCha)
        val offering24 = EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_24), listOf(p384), nobody)

        val message1 = EdhocInitiator(offering24, default).createMessage1()

        val failure = assertFailsWith<EdhocException> { responder.processMessage1(message1) }

        assertEquals(EdhocError.WRONG_SELECTED_CIPHER_SUITE, failure.errorCode)
        assertEquals(listOf(2, 0), failure.error.cipherSuites, "not 4 and 5, which need ChaCha20/Poly1305")
        for (value in failure.error.cipherSuites!!) {
            assertTrue(CipherSuite.fromValue(value)!!.isSupportedBy(noChaCha, method0), "suite $value")
        }
    }

    /**
     * A selected suite the Responder is configured with but cannot run is not supported (RFC 9528 §6.3.2):
     * error code 2 with the suites it runs, and the Initiator's retry with one of them succeeds.
     */
    @Test
    fun aSelectedSuiteTheResponderCannotRunGetsErrorCode2() = runTest {
        val ed = credential(KeyType.ED25519, 1)
        val suites = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_0)
        val responderConfig = EdhocResponderConfig(setOf(method0), suites, listOf(ed), trusting(ed))
        // An Initiator that offers suite 4 on every target, also where the platform lacks ChaCha20/Poly1305:
        // message_1 needs only X25519.
        val initiatorConfig = EdhocInitiatorConfig(method0, suites, listOf(ed), trusting(ed))
        val initiator = EdhocInitiator(initiatorConfig, Unfiltered(default))

        val rejected = assertFailsWith<EdhocException> {
            EdhocResponder(responderConfig, restricted).processMessage1(initiator.createMessage1())
        }
        assertEquals(EdhocError.WRONG_SELECTED_CIPHER_SUITE, rejected.errorCode)
        assertEquals(listOf(0), rejected.error.cipherSuites)

        val retry = assertFailsWith<EdhocPeerErrorException> { initiator.processMessage2(rejected.errorMessage!!) }
        assertEquals(CipherSuite.SUITE_0, retry.retryWith)
        val responder = EdhocResponder(responderConfig, restricted)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        val (message3, session) = initiator.createMessage3()
        assertEquals(CipherSuite.SUITE_0, responder.processMessage3(message3).cipherSuite)
        assertEquals(CipherSuite.SUITE_0, session.cipherSuite)
    }

    /** The Initiator learns only that the method is not supported; the Responder's own message says why. */
    @Test
    fun aResponderThatRunsNoSuiteOfTheMethodSaysWhy() = runTest {
        val p256 = credential(KeyType.P256, 2)
        val responder = EdhocResponder(
            EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), nobody),
            restricted,
        )
        val initiatorConfig = EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), nobody)
        val message1 = EdhocInitiator(initiatorConfig, default).createMessage1()

        val failure = assertFailsWith<EdhocException> { responder.processMessage1(message1) }

        assertEquals("Method 0 not supported", failure.error.diagnostic)
        assertEquals("Method 0 not supported: suite 2 needs ES256", failure.message)
    }

    // --- Initiator ---------------------------------------------------------------------------------------

    /**
     * SUITES_I is a prefix of the suites the Initiator runs, by default ending with the most preferred, and a
     * retry picks the most preferred suite of SUITES_R that it runs (RFC 9528 §5.2.2, §6.3.1).
     */
    @Test
    fun theInitiatorOffersAndRetriesWithOnlySuitesItRuns() = runTest {
        val preferences = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_6, CipherSuite.SUITE_0)
        val config = EdhocInitiatorConfig(method0, preferences, listOf(credential(KeyType.ED25519, 1)), nobody)
        val initiator = EdhocInitiator(config, noChaCha)
        assertEquals(CipherSuite.SUITE_4, initiator.cipherSuite, "the configured choice, before the backend is asked")

        assertTrue(initiator.createMessage1().toHex().startsWith("00" + "06"), "SUITES_I = 6")
        assertEquals(CipherSuite.SUITE_6, initiator.cipherSuite)
        val retry = assertFailsWith<EdhocPeerErrorException> {
            initiator.processMessage2(EdhocError.wrongSelectedCipherSuite(listOf(4, 0)).encode())
        }
        assertEquals(CipherSuite.SUITE_0, retry.retryWith, "suite 4 is preferred but does not run")
        assertEquals(EdhocPhase.START, initiator.phase)
        assertTrue(initiator.createMessage1().toHex().startsWith("00" + "820600"), "SUITES_I = [6, 0]")

        val stuck = EdhocInitiator(config, noChaCha)
        stuck.createMessage1()
        val aborted = assertFailsWith<EdhocPeerErrorException> {
            stuck.processMessage2(EdhocError.wrongSelectedCipherSuite(listOf(4, 5)).encode())
        }
        assertNull(aborted.retryWith)
        assertEquals(EdhocPhase.ABORTED, stuck.phase)
    }

    @Test
    fun anInitiatorThatRunsNoneOfItsSuitesFailsBeforeSendingAnything() = runTest {
        val ed = listOf(credential(KeyType.ED25519, 1))
        val chacha = EdhocInitiator(EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_4), ed, nobody), restricted)
        val failure = assertFailsWith<EdhocException> { chacha.createMessage1() }
        assertEquals(
            "No cipher suite with a credential runs on this crypto backend: suite 4 needs CHACHA20_POLY1305",
            failure.error.diagnostic,
        )
        assertNull(failure.errorMessage, "nothing to answer before message_1")
        assertEquals(EdhocPhase.ABORTED, chacha.phase)

        val suites = listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_4)
        val initial = EdhocInitiatorConfig(method0, suites, ed, nobody, initialCipherSuite = CipherSuite.SUITE_4)
        val remembered = assertFailsWith<EdhocException> { EdhocInitiator(initial, restricted).createMessage1() }
        assertEquals("The initial cipher suite 4 needs CHACHA20_POLY1305", remembered.error.diagnostic)
    }

    // --- naming what is missing --------------------------------------------------------------------------

    /** A backend without P-256 is named as such, not as a peer that sent an invalid key. */
    @Test
    fun aBackendLackingACurveIsNamedInTheFailure() = runTest {
        val p256 = credential(KeyType.P256, 2)
        val config = EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), nobody)
        val withoutP256 = RestrictedBackend(default, keyTypes = setOf(KeyType.P256))
        val honest = assertFailsWith<EdhocException> { EdhocInitiator(config, withoutP256).createMessage1() }
        assertContains(honest.message!!, "suite 2 needs P_256")

        // A backend that claims P-256 and then lacks it fails just the same, by name.
        val noKeyGeneration = object : CryptoBackend by default {
            override suspend fun generateKeyPair(type: KeyType): KeyPair =
                if (type == KeyType.P256) throw UnsupportedAlgorithmException("EC") else default.generateKeyPair(type)
        }
        val generating = assertFailsWith<EdhocException> { EdhocInitiator(config, noKeyGeneration).createMessage1() }
        assertEquals("P_256 is not available in this crypto backend", generating.error.diagnostic)

        val noKeyAgreement = object : CryptoBackend by default {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
                throw UnsupportedAlgorithmException("ECDH")
        }
        val responderConfig = EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), nobody)
        val responder = EdhocResponder(responderConfig, noKeyAgreement)
        responder.processMessage1(EdhocInitiator(config, default).createMessage1())
        val agreeing = assertFailsWith<EdhocException> { responder.createMessage2() }
        assertEquals("P_256 is not available in this crypto backend", agreeing.error.diagnostic)
        assertNotNull(agreeing.errorMessage, "message_1 is answered with the error")
    }

    /** Any other algorithm a backend turns out to lack mid-handshake is named too. */
    @Test
    fun anAlgorithmMissingMidHandshakeIsNamed() = runTest {
        val p256 = credential(KeyType.P256, 2)
        val noSigning = object : CryptoBackend by default {
            override suspend fun sign(algorithm: SignatureAlgorithm, privateKey: PrivateKey, data: ByteArray) =
                throw UnsupportedAlgorithmException(algorithm.name)
        }
        val responder = EdhocResponder(
            EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), nobody), noSigning,
        )
        responder.processMessage1(
            EdhocInitiator(EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), nobody), default)
                .createMessage1(),
        )
        val failure = assertFailsWith<EdhocException> { responder.createMessage2() }
        assertEquals("ES256 is not available in this crypto backend", failure.error.diagnostic)
        assertTrue(failure.cause is UnsupportedAlgorithmException)

        val noEcdh = object : CryptoBackend by default {
            override suspend fun keyAgreement(privateKey: PrivateKey, peerPublicKey: ByteArray): ByteArray =
                throw UnsupportedAlgorithmException("ECDH")
        }
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), trusting(p256)), noEcdh,
        )
        val honest = EdhocResponder(
            EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), trusting(p256)), default,
        )
        honest.processMessage1(initiator.createMessage1())
        val completing = assertFailsWith<EdhocException> { initiator.processMessage2(honest.createMessage2()) }
        assertEquals("P_256 is not available in this crypto backend", completing.error.diagnostic)

        val unsupportedStep = object : CryptoBackend by default {
            override suspend fun digest(algorithm: HashAlgorithm, data: ByteArray): ByteArray =
                throw UnsupportedAlgorithmException(algorithm.name)
        }
        val hashing = EdhocInitiator(
            EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), nobody), unsupportedStep,
        )
        val early = assertFailsWith<EdhocException> { hashing.createMessage1() }
        assertEquals("SHA_256 is not available in this crypto backend", early.error.diagnostic)
        assertNull(early.errorMessage)
    }

    /** The KEM, when the backend turns out to lack it, is named as the curve is. */
    @Test
    fun aMissingKemIsNamed() = runTest {
        val pq = FakePostQuantumBackend()
        val (initiatorConfig, responderConfig) = Fixtures.configs(pq, method0, CipherSuite.SUITE_PQC_1)
        val noEncapsulation = object : CryptoBackend by pq {
            override suspend fun encapsulate(algorithm: KemAlgorithm, publicKey: ByteArray): KemEncapsulation =
                throw UnsupportedAlgorithmException(algorithm.name)

            override suspend fun supports(algorithm: KemAlgorithm): Boolean = true
        }
        val responder = EdhocResponder(responderConfig, noEncapsulation)
        responder.processMessage1(EdhocInitiator(initiatorConfig, pq).createMessage1())
        val failure = assertFailsWith<EdhocException> { responder.createMessage2() }
        assertEquals("ML_KEM_512 is not available in this crypto backend", failure.error.diagnostic)
    }

    // --- supportedBy -------------------------------------------------------------------------------------

    @Test
    fun anInitiatorConfigurationNarrowsToTheSuitesTheBackendRuns() = runTest {
        val ed = credential(KeyType.ED25519, 1)
        val p256 = credential(KeyType.P256, 2)
        val preferences = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_6, CipherSuite.SUITE_0, CipherSuite.SUITE_2)
        val config = EdhocInitiatorConfig(
            method0, preferences, listOf(ed, p256), nobody, ephemeralKeys = EphemeralKeySource.Random,
            initialCipherSuite = CipherSuite.SUITE_0,
        )
        val narrowed = config.supportedBy(restricted)
        assertEquals(listOf(CipherSuite.SUITE_0), narrowed.cipherSuites, "no ChaCha20 (4), no ES256 (6, 2)")
        assertEquals(config.credentials, narrowed.credentials)
        assertSame(config.credentialResolver, narrowed.credentialResolver)
        assertSame(config.ephemeralKeys, narrowed.ephemeralKeys)
        assertEquals(CipherSuite.SUITE_0, narrowed.initialCipherSuite)
        assertEquals(preferences - CipherSuite.SUITE_4, config.supportedBy(noChaCha).cipherSuites, "the order is kept")

        val nothing = assertFailsWith<IllegalArgumentException> {
            EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_2), listOf(ed, p256), nobody)
                .supportedBy(restricted)
        }
        assertEquals(
            "No cipher suite with a credential runs on this crypto backend: " +
                "suite 4 needs CHACHA20_POLY1305, suite 2 needs ES256",
            nothing.message,
        )
        val initial = assertFailsWith<IllegalArgumentException> {
            EdhocInitiatorConfig(method0, preferences, listOf(ed), nobody, initialCipherSuite = CipherSuite.SUITE_6)
                .supportedBy(restricted)
        }
        assertEquals("The initial cipher suite 6 needs ES256", initial.message)
        // Suite 0 runs, but the only credential is for suite 2, which does not.
        assertFailsWith<IllegalArgumentException> {
            EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2, CipherSuite.SUITE_0), listOf(p256), nobody)
                .supportedBy(restricted)
        }
    }

    @Test
    fun aResponderConfigurationNarrowsToTheSuitesTheBackendRunsForAMethod() = runTest {
        val ed = credential(KeyType.ED25519, 1)
        val p256 = credential(KeyType.P256, 2)
        val staticP256 = credential(KeyType.P256, 3, AuthenticationKind.STATIC_DH)
        val methods = setOf(method0, EdhocMethod.STATIC_DH_STATIC_DH)
        val suites = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_2, CipherSuite.SUITE_0, CipherSuite.SUITE_24)
        val config = EdhocResponderConfig(methods, suites, listOf(ed, p256, staticP256), nobody)

        val narrowed = config.supportedBy(restricted)

        assertEquals(listOf(CipherSuite.SUITE_2, CipherSuite.SUITE_0), narrowed.cipherSuites)
        assertEquals(methods, narrowed.methods)
        assertEquals(config.credentials, narrowed.credentials)

        val nothing = assertFailsWith<IllegalArgumentException> {
            val chachaOrEs256 = listOf(CipherSuite.SUITE_4, CipherSuite.SUITE_2)
            EdhocResponderConfig(setOf(method0), chachaOrEs256, listOf(ed, p256), nobody).supportedBy(restricted)
        }
        assertEquals(
            "No cipher suite with a credential runs on this crypto backend: " +
                "method 0: suite 4 needs CHACHA20_POLY1305, suite 2 needs ES256",
            nothing.message,
        )
        val noCredential = assertFailsWith<IllegalArgumentException> {
            EdhocResponderConfig(setOf(method0), listOf(CipherSuite.SUITE_2), listOf(ed), nobody).supportedBy(default)
        }
        assertContains(noCredential.message!!, "method 0: no suite with a credential")
    }

    // --- asking the backend once ------------------------------------------------------------------------

    /**
     * A backend that keeps the probing defaults of `supports` generates keys, signs and agrees on a secret
     * for every question, and the Responder filters before it has checked anything of message_1. So the
     * configuration asks once per backend, and the copies [EdhocResponderConfig.supportedBy] and
     * [EdhocResponderConfig.withConnectionIdentifiers] make keep the answers: no message_1 probes the
     * backend again, accepted or rejected.
     */
    @Test
    fun aResponderProbesABackendThatDoesNotMemoizeOncePerConfiguration() = runTest {
        val p256 = credential(KeyType.P256, 2)
        val suites = listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2)
        val config = EdhocResponderConfig(setOf(method0), suites, listOf(credential(KeyType.ED25519, 1), p256), nobody)
        val accepted = EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_2), listOf(p256), nobody)
        val p384 = credential(KeyType.P384, 3)
        val rejected = EdhocInitiatorConfig(method0, listOf(CipherSuite.SUITE_24), listOf(p384), nobody)
        suspend fun message1(from: EdhocInitiatorConfig) = EdhocInitiator(from, default).createMessage1()

        val probing = ProbingBackend(default)
        val narrowed = config.supportedBy(probing)
        val probes = probing.probes
        assertTrue(probes > 0, "supportedBy asks the backend")
        repeat(2) { EdhocResponder(narrowed, probing).processMessage1(message1(accepted)) }
        assertFailsWith<EdhocException> { EdhocResponder(narrowed, probing).processMessage1(message1(rejected)) }
        val perSession = narrowed.withConnectionIdentifiers(ConnectionIdentifierProvider.Random)
        EdhocResponder(perSession, probing).processMessage1(message1(accepted))
        assertEquals(probes, probing.probes, "no message_1 probes the backend again")

        // Without supportedBy, the first message_1 asks what supportedBy would have, even when it is rejected.
        val lazily = ProbingBackend(default)
        assertFailsWith<EdhocException> { EdhocResponder(config, lazily).processMessage1(message1(rejected)) }
        assertEquals(probes, lazily.probes)
        repeat(2) { EdhocResponder(config, lazily).processMessage1(message1(accepted)) }
        assertEquals(probes, lazily.probes, "only the first message_1 probes the backend")

        val other = ProbingBackend(default)
        EdhocResponder(narrowed, other).processMessage1(message1(accepted))
        assertEquals(probes, other.probes, "another backend is asked for itself")
    }

    /** The Initiator too asks a backend that does not memoize once per configuration, not per session. */
    @Test
    fun anInitiatorProbesABackendThatDoesNotMemoizeOncePerConfiguration() = runTest {
        val credentials = listOf(credential(KeyType.ED25519, 1), credential(KeyType.P256, 2))
        val suites = listOf(CipherSuite.SUITE_0, CipherSuite.SUITE_2)
        val config = EdhocInitiatorConfig(method0, suites, credentials, nobody)
        val probing = ProbingBackend(default)

        EdhocInitiator(config, probing).createMessage1()
        val probes = probing.probes
        assertTrue(probes > 0, "the first message_1 asks the backend")
        EdhocInitiator(config, probing).createMessage1()
        val narrowed = config.supportedBy(probing)
        repeat(2) { EdhocInitiator(narrowed, probing).createMessage1() }
        assertEquals(probes, probing.probes, "later sessions and the narrowed configuration do not probe")

        val other = ProbingBackend(default)
        EdhocInitiator(narrowed, other).createMessage1()
        assertEquals(probes, other.probes, "another backend is asked for itself")
    }

    /** The copy takes the new C_R provider and keeps the rest of the configuration. */
    @Test
    fun withConnectionIdentifiersReplacesOnlyTheProvider() = runTest {
        val p256 = credential(KeyType.P256, 2)
        val ead = RecordingEadHandler()
        val keys = EphemeralKeySource { crypto, type -> crypto.generateKeyPair(type) }
        val config = EdhocResponderConfig(
            setOf(method0), listOf(CipherSuite.SUITE_2), listOf(p256), trusting(p256), eadHandler = ead,
            ephemeralKeys = keys,
        )
        val responderId = ConnectionIdentifier.ofInt(7)

        val copy = config.withConnectionIdentifiers(ConnectionIdentifierProvider.fixed(responderId))

        assertEquals(config.methods, copy.methods)
        assertEquals(config.cipherSuites, copy.cipherSuites)
        assertEquals(config.credentials, copy.credentials)
        assertSame(config.credentialResolver, copy.credentialResolver)
        assertSame(ead, copy.eadHandler)
        assertSame(keys, copy.ephemeralKeys)
        assertSame(ConnectionIdentifierProvider.Random, config.connectionIdentifiers, "the original is unchanged")
        val initiator = EdhocInitiator(
            EdhocInitiatorConfig(
                method0, listOf(CipherSuite.SUITE_2), listOf(p256), trusting(p256),
                connectionIdentifiers = ConnectionIdentifierProvider.fixed(ConnectionIdentifier.ofInt(1)),
            ),
            default,
        )
        val responder = EdhocResponder(copy, default)
        responder.processMessage1(initiator.createMessage1())
        initiator.processMessage2(responder.createMessage2())
        assertEquals(responderId, initiator.createMessage3().session.peerConnectionIdentifier)
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private val nobody = TrustedCredentials(default, emptyList())

    private fun trusting(vararg credentials: LocalCredential): CredentialResolver =
        TrustedCredentials(default, credentials.map { it.credential })

    private suspend fun credential(
        type: KeyType,
        kid: Int,
        kind: AuthenticationKind = AuthenticationKind.SIGNATURE,
    ): LocalCredential = Fixtures.ccsCredential(default, kind, type, byteArrayOf(kid.toByte()))

    /**
     * A complete handshake between an Initiator and a Responder on [crypto], with message_4, followed by what
     * OSCORE does with the session (RFC 9528 Appendix A.1, RFC 8613 §3.2): HKDF with the application hash and
     * the application AEAD. Both state machines see a backend that answers yes to every `supports`
     * question, so nothing is refused in advance and the operations themselves decide.
     */
    private suspend fun loopbackSucceeds(crypto: CryptoBackend, suite: CipherSuite, method: EdhocMethod): Boolean {
        val unfiltered = Unfiltered(crypto)
        return try {
            val configs = Fixtures.configs(unfiltered, method, suite)
            val session = Fixtures.handshake(unfiltered, configs, withMessage4 = true).initiatorSession
            val secret = session.oscoreMasterSecret()
            val salt = session.oscoreMasterSalt()
            val appAead = suite.appAead
            val key = if (suite.appHash.isXof) {
                unfiltered.digest(suite.appHash, secret).copyOf(appAead.keyLength)
            } else {
                Hkdf(unfiltered, suite.appHash).derive(salt, secret, ByteArray(0), appAead.keyLength)
            }
            unfiltered.aead(appAead, key).encrypt(ByteArray(appAead.nonceLength), ByteArray(1), ByteArray(0))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** [crypto] answering yes to every capability question. */
    private class Unfiltered(crypto: CryptoBackend) : CryptoBackend by crypto {
        override suspend fun supports(algorithm: AeadAlgorithm): Boolean = true
        override suspend fun supports(type: KeyType): Boolean = true
        override suspend fun supports(algorithm: SignatureAlgorithm): Boolean = true
        override suspend fun supports(algorithm: KemAlgorithm): Boolean = true
        override suspend fun supports(algorithm: HashAlgorithm): Boolean = true
    }
}
