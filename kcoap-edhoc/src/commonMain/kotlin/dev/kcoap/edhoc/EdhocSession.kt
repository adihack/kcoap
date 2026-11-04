package dev.kcoap.edhoc

import dev.kcoap.common.wipe
import dev.kcoap.crypto.CryptoBackend

/**
 * The output of a completed EDHOC session (RFC 9528 §4.1.3, §4.2): PRK_out, the EDHOC_Exporter for
 * application keys, EDHOC_KeyUpdate, and what was negotiated and authenticated.
 *
 * The Initiator gets its session after creating message_3, the Responder after verifying it. RFC 9528
 * §5.4.2 advises the Initiator not to persist the keys until message_4 or a protected message from the
 * Responder has been verified.
 *
 * Not thread-safe: [keyUpdate] replaces the keys in place, so callers serialize access. `toString()` never
 * shows key material.
 */
public class EdhocSession internal constructor(
    public val role: EdhocRole,
    public val method: EdhocMethod,
    public val cipherSuite: CipherSuite,
    /** C_I, chosen by the Initiator. */
    public val initiatorConnectionIdentifier: ConnectionIdentifier,
    /** C_R, chosen by the Responder. */
    public val responderConnectionIdentifier: ConnectionIdentifier,
    /** The peer's ID_CRED_x as received. */
    public val peerCredentialIdentifier: CredentialIdentifier,
    /** The peer's credential, which the session authenticated. */
    public val peerCredential: AuthenticationCredential,
    prkOut: ByteArray,
    prkExporter: ByteArray,
    backend: CryptoBackend,
) {
    private val keySchedule = KeySchedule(cipherSuite, backend)
    private var prkOutBytes = prkOut.copyOf()
    private var prkExporterBytes = prkExporter.copyOf()

    /** The connection identifier this endpoint chose, which with OSCORE is its Recipient ID (RFC 9528 §3.3.3). */
    public val ownConnectionIdentifier: ConnectionIdentifier
        get() = if (role == EdhocRole.INITIATOR) initiatorConnectionIdentifier else responderConnectionIdentifier

    /** The connection identifier the peer chose, which with OSCORE is this endpoint's Sender ID. */
    public val peerConnectionIdentifier: ConnectionIdentifier
        get() = if (role == EdhocRole.INITIATOR) responderConnectionIdentifier else initiatorConnectionIdentifier

    /** A copy of PRK_out, the session key (RFC 9528 §4.1.3). Secret: store it only where keys belong. */
    public fun prkOut(): ByteArray = prkOutBytes.copyOf()

    /** A copy of PRK_exporter = EDHOC_KDF( PRK_out, 10, h'', hash_length ) (RFC 9528 §4.1.3). Secret. */
    public fun prkExporter(): ByteArray = prkExporterBytes.copyOf()

    /**
     * `EDHOC_Exporter( exporter_label, context, length ) = EDHOC_KDF( PRK_exporter, exporter_label, context,
     * length )` (RFC 9528 §4.2.1). Each (label, context) pair must serve a single purpose.
     */
    public suspend fun exporter(label: Int, context: ByteArray, length: Int): ByteArray {
        require(label >= 0) { "Exporter labels are unsigned, got $label" }
        require(length > 0) { "Exporter output must not be empty" }
        return keySchedule.kdf(prkExporterBytes, label, context, length)
    }

    /**
     * The OSCORE Master Secret, `EDHOC_Exporter( 0, h'', oscore_key_length )` (RFC 9528 Appendix A.1). The
     * default length is the key length of the application AEAD.
     */
    public suspend fun oscoreMasterSecret(length: Int = cipherSuite.appAead.keyLength): ByteArray =
        exporter(EXPORTER_OSCORE_MASTER_SECRET, ByteArray(0), length)

    /**
     * The OSCORE Master Salt, `EDHOC_Exporter( 1, h'', oscore_salt_length )`, 8 bytes by default
     * (RFC 9528 Appendix A.1).
     */
    public suspend fun oscoreMasterSalt(length: Int = DEFAULT_OSCORE_SALT_LENGTH): ByteArray =
        exporter(EXPORTER_OSCORE_MASTER_SALT, ByteArray(0), length)

    /**
     * EDHOC_KeyUpdate (RFC 9528 Appendix H): PRK_out becomes `EDHOC_KDF( PRK_out, 11, context, hash_length )`
     * and PRK_exporter is derived anew. The old keys are wiped, which is what gives forward secrecy; keys
     * already exported from them are the caller's to delete. Both parties must use the same [context].
     */
    public suspend fun keyUpdate(context: ByteArray) {
        val newPrkOut = keySchedule.keyUpdate(prkOutBytes, context)
        val newPrkExporter = keySchedule.prkExporter(newPrkOut)
        wipeAll(prkOutBytes, prkExporterBytes)
        prkOutBytes = newPrkOut
        prkExporterBytes = newPrkExporter
    }

    /** Wipes PRK_out and PRK_exporter; the session cannot derive keys afterwards. */
    public fun destroy() {
        prkOutBytes.wipe()
        prkExporterBytes.wipe()
    }

    override fun toString(): String =
        "EdhocSession($role, method ${method.value}, suite ${cipherSuite.value}, " +
            "C_I=$initiatorConnectionIdentifier, C_R=$responderConnectionIdentifier, peer=$peerCredential)"

    public companion object {
        /** Exporter label of the OSCORE Master Secret (RFC 9528 §10.1). */
        public const val EXPORTER_OSCORE_MASTER_SECRET: Int = 0

        /** Exporter label of the OSCORE Master Salt (RFC 9528 §10.1). */
        public const val EXPORTER_OSCORE_MASTER_SALT: Int = 1

        /** oscore_salt_length by default (RFC 9528 Appendix A.1). */
        public const val DEFAULT_OSCORE_SALT_LENGTH: Int = 8
    }
}
