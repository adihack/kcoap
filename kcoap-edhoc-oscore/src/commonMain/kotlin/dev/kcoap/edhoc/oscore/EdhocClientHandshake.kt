package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.common.KcoapException
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SequenceNumberStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The client (EDHOC Initiator) side of EDHOC over CoAP, forward message flow (RFC 9528 Appendix A.2.1).
 *
 * It speaks the application model of kcoap-coap-message and is independent of the endpoint: [exchange] sends
 * one [CoapRequest] to the EDHOC resource [path] of [server] and returns its [CoapResponse], so a
 * [CoapClient] (`client::request`, which [over] wires), another carrier or a test double can carry it.
 *
 * ```
 * val handshake = EdhocClientHandshake.over(client, CoapUri.parse("coap://192.0.2.1"), config, crypto)
 *
 * // Three messages, then OSCORE:
 * val session = handshake.run()
 * val security = OscoreClient(session.oscore)
 *
 * // Or two round trips in total, message_3 riding on the first OSCORE request (RFC 9668):
 * val (session, security) = handshake.runCombined()
 * ```
 *
 * Cipher suite negotiation (RFC 9528 §6.3) is handled: when the server answers message_1 with error
 * code 2 naming a suite the Initiator supports, message_1 is sent again with that suite. The suites of
 * [config] are narrowed to those [crypto] runs on the first run, and [config] keeps the answer for every
 * later one (docs/adr/0001-crypto-capabilities.md).
 *
 * Every run fails with an [dev.kcoap.edhoc.EdhocException]: an [EdhocPeerErrorException] when the server
 * answered with an EDHOC error, an [EdhocTransferException] when it answered with something that is not
 * EDHOC, and the exception itself when a step of this side failed. When the OSCORE context cannot be derived
 * at the end (RFC 9528 Appendix A.1) because [sequenceStore] or [crypto] threw, the run fails with an
 * `EdhocException` of error code 1 whose cause is that exception. Whatever [exchange] throws, such as
 * kcoap-coap's `CoapException` for a request that got no response, passes through as it is. A run that fails or
 * is cancelled at any step wipes what it holds (RFC 9528 §9.8): the Initiator's ephemeral key X while it waits
 * for message_2, PRK_4e3m and the session's PRK_out and PRK_exporter once message_3 is out.
 *
 * [run] and [runCombined] list [KcoapException], the root of both, and `CancellationException` in `@Throws`, so on
 * Apple platforms Swift catches a failed handshake as an `NSError`, with the Kotlin exception in its `userInfo`
 * under `"KotlinException"`.
 *
 * @param server the scheme, host and port of the server; its path and query are not used.
 * @param path the EDHOC resource on [server], `.well-known/edhoc` (RFC 9528 §10.7) unless the server
 *   mounts it elsewhere, as discovery with resource type `core.edhoc` tells.
 * @param loggerFactory where the handshake's logger comes from, under [LogTags.EDHOC].
 * @param sequenceStore holds the Sender Sequence Numbers of the contexts this handshake derives. By default
 *   (null) each context gets its own [EphemeralSequenceNumberStore], which fits EDHOC, whose keys are new
 *   with every session, and goes away with the context. Pass a durable store to resume a context after a
 *   restart, as [oscoreParameters] describes. The store keeps one limit per context name, "sid=C_R,rid=C_I"
 *   ([dev.kcoap.oscore.SecurityContextParameters.name]), and those names are not unique across servers:
 *   each server chooses its C_R on its own, and nothing on this side makes C_I unique. Two live contexts
 *   with one name in one store break its contract and can lead to AEAD nonce reuse after a restore
 *   (RFC 8613 Appendix B.1.1), so give every server its own durable store, or a store that puts a name
 *   of the server in front of the context names (and restore with the same one).
 * @param exchange sends one request and returns its response.
 */
public class EdhocClientHandshake(
    private val config: EdhocInitiatorConfig,
    private val crypto: CryptoBackend,
    server: CoapUri,
    path: String = EdhocOverCoap.WELL_KNOWN_PATH,
    private val loggerFactory: LoggerFactory = LoggerFactory.Global,
    private val sequenceStore: SequenceNumberStore? = null,
    private val exchange: suspend (CoapRequest) -> CoapResponse,
) {
    private val log = loggerFactory.logger(LogTags.EDHOC).child("client")

    /** The EDHOC resource: [server] with [path] for its own. */
    private val resource = CoapUri(server.scheme, server.host, server.port, path.split('/').filter { it.isNotEmpty() })

    /** Test hook: sees the Initiator of every run as it is created. */
    internal var onInitiator: (EdhocInitiator) -> Unit = {}

    /** The sequential flow: message_1 to message_3 (and message_4 when [expectMessage4]). */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun run(expectMessage4: Boolean = false): EdhocOscoreSession = wipingOnFailure { initiator ->
        exchangeMessages1And2(initiator)
        val (message3, session) = initiator.createMessage3()
        val response = exchange(EdhocOverCoap.continuationRequest(resource, session.peerConnectionIdentifier, message3))
        checkResponse(response, "message_3")
        if (expectMessage4) {
            initiator.processMessage4(response.payload)
        } else if (response.code != CoapCode.CHANGED) {
            // The server rejected message_3 with an EDHOC error message (RFC 9528 Appendix A.2.3).
            throw EdhocPeerErrorException(EdhocError.decode(response.payload))
        }
        EdhocOscoreSession(session, deriveContext(session))
    }

    /**
     * The RFC 9668 flow: message_1 and message_2 are exchanged here; message_3 is held back and sent
     * with the first request protected by the returned [CombinedHandshake.security].
     */
    @Throws(KcoapException::class, CancellationException::class)
    public suspend fun runCombined(): CombinedHandshake = wipingOnFailure { initiator ->
        exchangeMessages1And2(initiator)
        val (message3, session) = initiator.createMessage3()
        val context = deriveContext(session)
        val security = CombinedRequestSecurity(OscoreClient(context, loggerFactory), message3)
        CombinedHandshake(EdhocOscoreSession(session, context), security)
    }

    /**
     * Runs [block] with a new Initiator. When it fails or is cancelled at any step, the Initiator is aborted,
     * which wipes X or PRK_4e3m, whichever it holds, and its session is destroyed (RFC 9528 §9.8).
     */
    private inline fun <T> wipingOnFailure(block: (EdhocInitiator) -> T): T {
        val initiator = EdhocInitiator(config, crypto, loggerFactory).also(onInitiator)
        return try {
            block(initiator)
        } catch (e: Throwable) {
            val session: EdhocSession? = initiator.session
            initiator.abort(HANDSHAKE_FAILED)
            session?.destroy()
            throw e
        }
    }

    /**
     * The session's OSCORE context. A failure that is not kcoap's, of [sequenceStore] or of [crypto], becomes an
     * [EdhocClientFailure] with it as cause, as the server's becomes an [EdhocServerFailure]; so does a
     * cancellation of their own, such as a store's `withTimeout` expiring, while the caller is still active. The
     * caller's cancellation passes unchanged.
     */
    private suspend fun deriveContext(session: EdhocSession): SecurityContext =
        try {
            session.oscoreContext(crypto, sequenceStore ?: EphemeralSequenceNumberStore())
        } catch (e: KcoapException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException && !currentCoroutineContext().isActive) throw e
            log.warn(e) { "The OSCORE context of session ${session.ownConnectionIdentifier} was not derived" }
            throw EdhocClientFailure("${EdhocClientFailure.DIAGNOSTIC}: ${e.message}", e)
        }

    private suspend fun exchangeMessages1And2(initiator: EdhocInitiator) {
        repeat(MAX_NEGOTIATION_ROUNDS) {
            val response = exchange(EdhocOverCoap.message1Request(resource, initiator.createMessage1()))
            checkResponse(response, "message_1")
            try {
                initiator.processMessage2(response.payload)
                return
            } catch (e: EdhocPeerErrorException) {
                // §6.3: the Initiator is back at START with the server's preferred suite selected.
                val next = e.retryWith ?: throw e
                log.debug { "Retrying message_1 with suite ${next.value}" }
            }
        }
        throw EdhocTransferException("Cipher suite negotiation did not converge")
    }

    /** A 2.04 or an EDHOC error response; the latter is handed to EDHOC, which raises the error. */
    private fun checkResponse(response: CoapResponse, after: String) {
        val isEdhocError = response.code.isClientError || response.code.isServerError
        if (isEdhocError && response.contentFormat == ContentFormat.EDHOC_CBOR_SEQ) return
        if (response.code != CoapCode.CHANGED) {
            throw EdhocTransferException("Unexpected ${response.code} after $after", response)
        }
    }

    public companion object {
        /** One retry per suite the server can name is plenty; more means the peers cannot agree. */
        private const val MAX_NEGOTIATION_ROUNDS = 4

        private const val HANDSHAKE_FAILED = "Handshake failed"

        /**
         * A handshake that [client] carries to the EDHOC resource [path] of [server] (scheme, host and port;
         * the path and query of [server] are not used), with [CoapClient.request]. The requests go through the
         * client's message security: with [PeerSecurity] they go unprotected, as its `unprotectedPaths` hold
         * the EDHOC resource.
         *
         * @param sequenceStore as for the constructor: null gives each context its own ephemeral store, and
         *   a durable store must hold the contexts of this one server only.
         */
        public fun over(
            client: CoapClient,
            server: CoapUri,
            config: EdhocInitiatorConfig,
            crypto: CryptoBackend,
            path: String = EdhocOverCoap.WELL_KNOWN_PATH,
            loggerFactory: LoggerFactory = LoggerFactory.Global,
            sequenceStore: SequenceNumberStore? = null,
        ): EdhocClientHandshake =
            EdhocClientHandshake(config, crypto, server, path, loggerFactory, sequenceStore, client::request)
    }
}
