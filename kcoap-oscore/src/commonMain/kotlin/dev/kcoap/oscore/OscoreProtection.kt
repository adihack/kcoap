package dev.kcoap.oscore

import dev.kcoap.cbor.CborWriter
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.OscoreClass
import dev.kcoap.coap.codec.CoapFormatException
import dev.kcoap.coap.codec.OptionCodec
import dev.kcoap.common.ByteArrayBuilder
import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.common.readUnsigned
import dev.kcoap.cose.CoseStructures
import dev.kcoap.crypto.AeadAuthenticationException

/** What a protected request leaves behind for the responses to it (RFC 8613 §5.4, §8.4). */
internal class RequestBinding(
    val context: SecurityContext,
    /** 'kid' of the request: the client's Sender ID. */
    val requestKid: ByteArray,
    /** 'Partial IV' of the request. */
    val requestPiv: ByteArray,
    /** The request's AEAD nonce, reused by the first response without a Partial IV. */
    val requestNonce: ByteArray,
    /** Whether the request registered an observation, which allows several responses. */
    val isObserveRegistration: Boolean,
)

/** A verified request, and the Echo challenge to answer it with when freshness is not yet proven. */
internal class VerifiedRequest(val message: CoapMessage, val binding: RequestBinding, val challengeEcho: ByteArray?)

/**
 * The OSCORE message transformations of RFC 8613 §8, independent of how messages are sent. The client
 * and server SPI implementations ([OscoreClient], [OscoreServer]) wrap these four operations.
 */
internal object OscoreProtection {

    /** RFC 8613 §8.1. */
    suspend fun protectRequest(context: SecurityContext, request: CoapMessage): Pair<CoapMessage, RequestBinding> {
        val split = splitRequestOptions(request.options)
        val sequence = context.takeSequenceNumber()
        val piv = encodePartialIv(sequence)
        val senderId = context.senderId
        val nonce = context.nonce(senderId, piv)
        val aad = additionalData(context, requestKid = senderId, requestPiv = piv)
        val ciphertext = context.senderAead.encrypt(nonce, plaintext(request.code, split.inner, request.payload), aad)

        val optionValue = OscoreOptionValue(
            partialIv = piv,
            kid = senderId,
            kidContext = if (context.parameters.includeIdContext) context.idContext else null,
        )
        val isObserve = request.options.observe != null
        val protected = request.toBuilder().apply {
            // §4.2 and §4.1.3.5: the Outer Code is POST, or FETCH when Observe is present.
            code = if (isObserve) CoapCode.FETCH else CoapCode.POST
            replaceOptions(split.outer.toBuilder().oscore(optionValue.encode()).build())
            payload = ciphertext
        }.build()
        val binding = RequestBinding(context, senderId, piv, nonce, isObserveRegistration = request.options.observe == 0L)
        return protected to binding
    }

    /** RFC 8613 §8.2. [findContext] looks up the Recipient Context by 'kid' and 'kid context'. */
    suspend fun unprotectRequest(
        request: CoapMessage,
        findContext: suspend (kid: ByteArray, kidContext: ByteArray?) -> SecurityContext?,
    ): VerifiedRequest {
        val optionValue = OscoreOptionValue.decode(request.options.oscore ?: throw OscoreException(OscoreError.DECODE_FAILED, "no OSCORE option"))
        val kid = optionValue.kid ?: throw OscoreException(OscoreError.DECODE_FAILED, "request without kid")
        val piv = optionValue.partialIv ?: throw OscoreException(OscoreError.DECODE_FAILED, "request without Partial IV")
        if (request.payloadSize == 0) throw OscoreException(OscoreError.DECODE_FAILED, "request without ciphertext")

        val context = findContext(kid, optionValue.kidContext) ?: throw OscoreException(OscoreError.CONTEXT_NOT_FOUND)
        val sequence = piv.readUnsigned()
        if (context.isReplay(sequence)) throw OscoreException(OscoreError.REPLAY)

        val nonce = context.nonce(kid, piv)
        val aad = additionalData(context, requestKid = kid, requestPiv = piv)
        val plaintext = decrypt(context, nonce, request.payload, aad)
        val decrypted = merge(request, parsePlaintext(plaintext))
        val binding = RequestBinding(context, kid, piv, nonce, isObserveRegistration = decrypted.options.observe == 0L)

        return when (val admission = context.admit(sequence, decrypted.options.echo)) {
            SecurityContext.Admission.Accepted -> VerifiedRequest(decrypted, binding, challengeEcho = null)
            SecurityContext.Admission.Replay -> throw OscoreException(OscoreError.REPLAY)
            is SecurityContext.Admission.Challenge -> VerifiedRequest(decrypted, binding, admission.echo)
        }
    }

    /**
     * RFC 8613 §8.3. A response without [newPartialIv] reuses the request nonce; notifications after the
     * first must use a new Partial IV (§8.3.1).
     */
    suspend fun protectResponse(binding: RequestBinding, response: CoapMessage, newPartialIv: Boolean): CoapMessage {
        val context = binding.context
        val isNotification = response.options.observe != null
        val split = splitResponseOptions(response.options, isNotification)

        val piv: ByteArray?
        val nonce: ByteArray
        if (newPartialIv) {
            piv = encodePartialIv(context.takeSequenceNumber())
            nonce = context.nonce(context.senderId, piv)
        } else {
            piv = null
            nonce = binding.requestNonce
        }
        val aad = additionalData(context, binding.requestKid, binding.requestPiv)
        val ciphertext = context.senderAead.encrypt(nonce, plaintext(response.code, split.inner, response.payload), aad)
        return response.toBuilder().apply {
            // §4.2 and §4.1.3.5: the Outer Code is 2.04 Changed, or 2.05 Content for notifications.
            code = if (isNotification) CoapCode.CONTENT else CoapCode.CHANGED
            replaceOptions(split.outer.toBuilder().oscore(OscoreOptionValue(partialIv = piv).encode()).build())
            payload = ciphertext
        }.build()
    }

    /** RFC 8613 §8.4: returns the decrypted response and its Partial IV, if it had one. */
    suspend fun unprotectResponse(binding: RequestBinding, response: CoapMessage): Pair<CoapMessage, ByteArray?> {
        val optionValue = OscoreOptionValue.decode(response.options.oscore ?: throw OscoreException(OscoreError.DECODE_FAILED, "no OSCORE option"))
        val context = binding.context
        val piv = optionValue.partialIv
        // The server's Sender ID is our Recipient ID; it chose any Partial IV in the response (§5.2).
        val nonce = if (piv != null) context.nonce(context.recipientId, piv) else binding.requestNonce
        val aad = additionalData(context, binding.requestKid, binding.requestPiv)
        val plaintext = decrypt(context, nonce, response.payload, aad)
        val decrypted = merge(response, parsePlaintext(plaintext))
        return decrypted to piv
    }

    private suspend fun decrypt(context: SecurityContext, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        try {
            context.recipientAead.decrypt(nonce, ciphertext, aad)
        } catch (e: AeadAuthenticationException) {
            throw OscoreException(OscoreError.DECRYPTION_FAILED, cause = e)
        }

    /** §5.3: the Code, the Inner options, and the payload behind a marker. */
    private fun plaintext(code: CoapCode, inner: OptionSet, payload: ByteArray): ByteArray {
        val out = ByteArrayBuilder(16 + payload.size).append(code.value)
        OptionCodec.write(inner, payload, out)
        return out.toByteArray()
    }

    private class Plaintext(val code: CoapCode, val options: OptionSet, val payload: ByteArray)

    private fun parsePlaintext(plaintext: ByteArray): Plaintext {
        if (plaintext.isEmpty()) throw OscoreException(OscoreError.DECODE_FAILED, "empty plaintext")
        val parsed = try {
            OptionCodec.read(plaintext, 1, plaintext.size) { CoapFormatException(it) }
        } catch (e: CoapFormatException) {
            throw OscoreException(OscoreError.DECODE_FAILED, "malformed plaintext: ${e.message}", e)
        }
        return Plaintext(CoapCode(plaintext[0].toInt() and 0xFF), parsed.options, parsed.payload)
    }

    /**
     * The decrypted message (§8.2 steps 1 and 7, §8.4 steps 1 and 6): the outer header, the Outer
     * options except Class E ones and the OSCORE option, plus the Inner options, Code and payload.
     */
    private fun merge(outer: CoapMessage, inner: Plaintext): CoapMessage {
        val options = OptionSet.Builder()
        for (option in outer.options) {
            if (option.number == CoapOptions.OSCORE.number) continue
            if (OscoreClass.E in CoapOptions.oscoreClasses(option.number)) continue
            options.add(option)
        }
        options.addAll(inner.options)
        return outer.toBuilder().apply {
            code = inner.code
            replaceOptions(options.build())
            payload = inner.payload
        }.build()
    }

    /** `Enc_structure = [ "Encrypt0", h'', bstr .cbor aad_array ]` (§5.4). Class I options: none defined. */
    internal fun additionalData(context: SecurityContext, requestKid: ByteArray, requestPiv: ByteArray): ByteArray {
        val aadArray = CborWriter()
            .writeArrayHeader(5)
            .writeInt(OSCORE_VERSION)
            .writeArrayHeader(1).writeInt(context.parameters.aeadAlgorithm.coseId)
            .writeBytes(requestKid)
            .writeBytes(requestPiv)
            .writeBytes(ByteArray(0))
            .toByteArray()
        return CoseStructures.encStructure(protected = ByteArray(0), externalAad = aadArray)
    }

    /** §5: the Sender Sequence Number without leading zero bytes; 0 encodes as 0x00. */
    internal fun encodePartialIv(sequence: Long): ByteArray =
        if (sequence == 0L) byteArrayOf(0) else minimalUnsignedBytes(sequence)

    private class SplitOptions(val inner: OptionSet, val outer: OptionSet)

    /**
     * §4.1: Class E options go inside, Class U options stay outside, options in both classes go inside
     * because this endpoint is not a proxy, and Observe is present on both sides with the same value
     * (§4.1.3.5.1). Proxy-Uri is decomposed (§4.1.3.3). Unknown options are Class E.
     */
    private fun splitRequestOptions(options: OptionSet): SplitOptions {
        val inner = OptionSet.Builder()
        val outer = OptionSet.Builder()
        for (option in options) {
            when {
                option.number == CoapOptions.PROXY_URI.number -> decomposeProxyUri(option.stringValue(), inner, outer)
                option.number == CoapOptions.OBSERVE.number -> {
                    inner.add(option)
                    outer.add(option)
                }
                else -> placeByClass(option, inner, outer)
            }
        }
        return SplitOptions(inner.build(), outer.build())
    }

    /** §4.1.3.5.2: in notifications the Inner Observe is empty and the Outer one keeps the value. */
    private fun splitResponseOptions(options: OptionSet, isNotification: Boolean): SplitOptions {
        val inner = OptionSet.Builder()
        val outer = OptionSet.Builder()
        for (option in options) {
            if (option.number == CoapOptions.OBSERVE.number && isNotification) {
                inner.add(CoapOption.empty(CoapOptions.OBSERVE.number))
                outer.add(option)
            } else {
                placeByClass(option, inner, outer)
            }
        }
        return SplitOptions(inner.build(), outer.build())
    }

    private fun placeByClass(option: CoapOption, inner: OptionSet.Builder, outer: OptionSet.Builder) {
        if (option.number == CoapOptions.OSCORE.number) return
        if (OscoreClass.E in CoapOptions.oscoreClasses(option.number)) inner.add(option) else outer.add(option)
    }

    /**
     * §4.1.3.3: Uri-Path and Uri-Query from the Proxy-Uri are Inner; the outer Proxy-Uri keeps scheme,
     * host and port only.
     */
    private fun decomposeProxyUri(value: String, inner: OptionSet.Builder, outer: OptionSet.Builder) {
        val uri = try {
            CoapUri.parse(value)
        } catch (e: IllegalArgumentException) {
            throw OscoreException(OscoreError.DECODE_FAILED, "unparsable Proxy-Uri", e)
        }
        uri.pathSegments.forEach { inner.add(CoapOption.string(CoapOptions.URI_PATH.number, it)) }
        uri.querySegments.forEach { inner.add(CoapOption.string(CoapOptions.URI_QUERY.number, it)) }
        val authority = CoapUri(uri.scheme, uri.host, uri.port).toString().removeSuffix("/")
        outer.add(CoapOption.string(CoapOptions.PROXY_URI.number, authority))
    }

    private const val OSCORE_VERSION = 1
}
