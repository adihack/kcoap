package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.ContentFormat
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.EdhocError
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocPeerErrorException
import dev.kcoap.edhoc.EdhocPhase
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.log.LogTags
import dev.kcoap.log.LoggerFactory
import dev.kcoap.oscore.OscoreClient
import dev.kcoap.oscore.SecurityContext

/** A transfer step failed outside EDHOC itself: the server answered with something that is not EDHOC. */
public class EdhocTransferException(message: String, public val response: CoapMessage? = null) : Exception(message)

/** An established EDHOC session and the OSCORE context derived from it. */
public class EdhocOscoreSession(public val edhoc: EdhocSession, public val oscore: SecurityContext)

/**
 * The client (EDHOC Initiator) side of EDHOC over CoAP, forward message flow (RFC 9528 Appendix A.2.1).
 *
 * It is independent of the CoAP endpoint: [exchange] sends one request to the server and returns its
 * response, so any CoAP client (or a test double) can carry the handshake.
 *
 * ```
 * val handshake = EdhocClientHandshake(config, crypto) { request -> coapClient.send(request) }
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
 * code 2 naming a suite the Initiator supports, message_1 is sent again with that suite.
 */
public class EdhocClientHandshake(
    private val config: EdhocInitiatorConfig,
    private val crypto: CryptoBackend,
    private val path: String = EdhocOverCoap.WELL_KNOWN_PATH,
    private val loggers: LoggerFactory = LoggerFactory.Global,
    private val exchange: suspend (CoapMessage) -> CoapMessage,
) {
    private val log = loggers.logger(LogTags.EDHOC).child("client")

    /** The sequential flow: message_1 to message_3 (and message_4 when [expectMessage4]). */
    public suspend fun run(expectMessage4: Boolean = false): EdhocOscoreSession {
        val initiator = EdhocInitiator(config, crypto, loggers)
        exchangeMessages1And2(initiator)
        val message3 = initiator.createMessage3()
        val responderId = initiator.session!!.peerConnectionIdentifier
        val response = exchange(EdhocOverCoap.continuationRequest(responderId, message3, path))
        checkResponse(response, "message_3")
        if (expectMessage4) {
            initiator.processMessage4(response.payload)
        } else if (response.code != CoapCode.CHANGED) {
            // The server rejected message_3 with an EDHOC error message (RFC 9528 Appendix A.2.3).
            throw EdhocPeerErrorException(EdhocError.decode(response.payload))
        }
        val session = initiator.session!!
        return EdhocOscoreSession(session, session.oscoreContext(crypto))
    }

    /**
     * The RFC 9668 flow: message_1 and message_2 are exchanged here; message_3 is held back and sent
     * with the first request protected by the returned [ClientMessageSecurity].
     */
    public suspend fun runCombined(): Pair<EdhocOscoreSession, CombinedRequestSecurity> {
        val initiator = EdhocInitiator(config, crypto, loggers)
        exchangeMessages1And2(initiator)
        val message3 = initiator.createMessage3()
        val session = initiator.session!!
        val context = session.oscoreContext(crypto)
        val security = CombinedRequestSecurity(OscoreClient(context, loggers), message3)
        return EdhocOscoreSession(session, context) to security
    }

    private suspend fun exchangeMessages1And2(initiator: EdhocInitiator) {
        repeat(MAX_NEGOTIATION_ROUNDS) {
            val response = exchange(EdhocOverCoap.message1Request(initiator.createMessage1(), path))
            checkResponse(response, "message_1")
            try {
                initiator.processMessage2(response.payload)
                return
            } catch (e: EdhocPeerErrorException) {
                // §6.3: the Initiator is back at START with the server's preferred suite selected.
                if (initiator.phase != EdhocPhase.START) throw e
                log.debug { "Retrying message_1 with suite ${initiator.cipherSuite.value}" }
            }
        }
        throw EdhocTransferException("Cipher suite negotiation did not converge")
    }

    /** A 2.04 or an EDHOC error response; the latter is handed to EDHOC, which raises the error. */
    private fun checkResponse(response: CoapMessage, after: String) {
        val isEdhocError = response.code.isClientError || response.code.isServerError
        if (isEdhocError && response.options.contentFormat == ContentFormat.EDHOC_CBOR_SEQ) return
        if (response.code != CoapCode.CHANGED) {
            throw EdhocTransferException("Unexpected ${response.code} after $after", response)
        }
    }

    private companion object {
        /** One retry per suite the server can name is plenty; more means the peers cannot agree. */
        const val MAX_NEGOTIATION_ROUNDS = 4
    }
}
