package dev.kcoap.edhoc

import dev.kcoap.cbor.CborWriter
import dev.kcoap.common.constantTimeEquals
import dev.kcoap.cose.CoseStructures
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.PrivateKey

/**
 * The inputs MAC_2 / MAC_3 and Signature_or_MAC_2 / Signature_or_MAC_3 are computed over (RFC 9528 §5.3.2,
 * §5.4.2). message_2 includes C_R; message_3 has no connection identifier.
 *
 * ```
 * context_2    = << C_R, ID_CRED_R, TH_2, CRED_R, ? EAD_2 >>
 * context_3    = << ID_CRED_I, TH_3, CRED_I, ? EAD_3 >>
 * external_aad = << TH_x, CRED_x, ? EAD_x >>      protected = << ID_CRED_x >>
 * ```
 */
internal class AuthenticationInput(
    /** MAC_2 or MAC_3 info_label. */
    val macLabel: Int,
    val connectionIdentifier: ConnectionIdentifier?,
    val credentialIdentifier: CredentialIdentifier,
    val transcriptHash: ByteArray,
    val credential: AuthenticationCredential,
    val ead: List<EadItem>,
) {
    fun context(): ByteArray {
        val writer = CborWriter()
        connectionIdentifier?.let { writer.writeEncoded(it.encode()) }
        return writer.writeEncoded(credentialIdentifier.encoded).writeEncoded(externalAad()).toByteArray()
    }

    fun externalAad(): ByteArray = CborWriter()
        .writeBytes(transcriptHash)
        .writeEncoded(credential.encoded)
        .writeEncoded(EadItem.encode(ead))
        .toByteArray()
}

/** The peer credential that verified, with the PRK and MAC it verified under. */
internal class VerifiedPeer(val credential: AuthenticationCredential, val prk: ByteArray, val mac: ByteArray)

/** Computes and verifies MAC_x and Signature_or_MAC_x for one cipher suite. */
internal class SignatureOrMac(private val keySchedule: KeySchedule, private val backend: CryptoBackend) {
    private val suite = keySchedule.suite

    /** mac_length_x: the EDHOC MAC length with static DH, hash_length with signatures. */
    fun macLength(kind: AuthenticationKind): Int =
        if (kind == AuthenticationKind.STATIC_DH) suite.macLength else keySchedule.hashLength

    /** The length Signature_or_MAC_x must have (RFC 9528 §9.8, RFC 9529 §4.2.5). */
    fun expectedLength(kind: AuthenticationKind): Int =
        if (kind == AuthenticationKind.STATIC_DH) suite.macLength else suite.signature.signatureLength

    /** MAC_x = EDHOC_KDF( PRK, label, context_x, mac_length_x ). */
    suspend fun mac(prk: ByteArray, input: AuthenticationInput, kind: AuthenticationKind): ByteArray =
        keySchedule.kdf(prk, input.macLabel, input.context(), macLength(kind))

    /** Signature_or_MAC_x: MAC_x itself, or the COSE_Sign1 signature over it. */
    suspend fun create(
        mac: ByteArray,
        input: AuthenticationInput,
        kind: AuthenticationKind,
        privateKey: PrivateKey,
    ): ByteArray = if (kind == AuthenticationKind.STATIC_DH) {
        mac.copyOf()
    } else {
        backend.sign(suite.signature, privateKey, toBeSigned(mac, input))
    }

    /** Whether [received] is the Signature_or_MAC_x for [mac]; MACs are compared in constant time. */
    suspend fun verify(
        mac: ByteArray,
        input: AuthenticationInput,
        kind: AuthenticationKind,
        received: ByteArray,
    ): Boolean = if (kind == AuthenticationKind.STATIC_DH) {
        constantTimeEquals(mac, received)
    } else {
        backend.verify(suite.signature, input.credential.publicKey, toBeSigned(mac, input), received)
    }

    /**
     * Tries the candidates in turn, since several credentials may share a 'kid' (RFC 9528 §3.5.3), and returns
     * the first whose Signature_or_MAC_x verifies; PRKs of the rejected candidates are wiped.
     *
     * @param received Signature_or_MAC_x, already checked to have [expectedLength].
     * @param prkFor PRK_3e2m or PRK_4e3m for a candidate (static DH depends on its public key).
     */
    suspend inline fun firstVerified(
        candidates: List<AuthenticationCredential>,
        kind: AuthenticationKind,
        received: ByteArray,
        prkFor: (AuthenticationCredential) -> ByteArray,
        inputFor: (AuthenticationCredential) -> AuthenticationInput,
    ): VerifiedPeer? {
        for (candidate in candidates) {
            val prk = prkFor(candidate)
            val input = inputFor(candidate)
            val mac = mac(prk, input, kind)
            if (verify(mac, input, kind, received)) return VerifiedPeer(candidate, prk, mac)
            wipeAll(prk, mac)
        }
        return null
    }

    /** `[ "Signature1", << ID_CRED_x >>, << TH_x, CRED_x, ? EAD_x >>, MAC_x ]` (RFC 9528 §5.3.2). */
    private fun toBeSigned(mac: ByteArray, input: AuthenticationInput): ByteArray =
        CoseStructures.sigStructure(
            protected = input.credentialIdentifier.encoded,
            externalAad = input.externalAad(),
            payload = mac,
        )
}
