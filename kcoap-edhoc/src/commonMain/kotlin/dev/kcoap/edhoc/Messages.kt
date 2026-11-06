package dev.kcoap.edhoc

import dev.kcoap.cbor.CborType
import dev.kcoap.cbor.CborWriter

/**
 * message_1 = ( METHOD : int, SUITES_I : suites, G_X : bstr, C_I : bstr / -24..23, ? EAD_1 )
 * (RFC 9528 §5.2.1).
 */
internal class Message1(
    val method: Int,
    /** SUITES_I in the Initiator's order of preference; the last one is the selected suite. */
    val suites: List<Int>,
    val gX: ByteArray,
    val connectionIdentifier: ConnectionIdentifier,
    val ead: List<EadItem>,
) {
    val selectedSuite: Int get() = suites.last()

    fun encode(): ByteArray = CborWriter()
        .writeInt(method)
        .write(EdhocError.encodeSuites(suites))
        .writeBytes(gX)
        .writeEncoded(connectionIdentifier.encode())
        .writeEncoded(EadItem.encode(ead))
        .toByteArray()

    companion object {
        fun decode(bytes: ByteArray): Message1 {
            val reader = MessageReader(bytes, "message_1")
            val method = reader.readInt()
            // A one-element array is not a valid encoding of a single suite (RFC 9529 §4.1.3).
            val suites = EdhocError.suitesOf(reader.readValue()) ?: throw reader.malformed("invalid SUITES_I")
            val gX = reader.readBytes()
            if (reader.isAtEnd) throw reader.malformed("missing C_I")
            val connectionIdentifier = ConnectionIdentifier.decode(reader.readValue(), "message_1")
            return Message1(method, suites, gX, connectionIdentifier, EadItem.readAll(reader))
        }
    }
}

/** A message that is a single byte string: message_2, message_3 and message_4 (RFC 9528 §5.3.1, §5.4.1, §5.5.1). */
internal object SingleByteStringMessage {
    fun encode(content: ByteArray): ByteArray = CborWriter().writeBytes(content).toByteArray()

    /** The content; anything but exactly one byte string is malformed (RFC 9529 §4.1.5). */
    fun decode(bytes: ByteArray, what: String): ByteArray {
        val reader = MessageReader(bytes, what)
        if (reader.peekType() != CborType.BYTES) throw reader.malformed("expected a byte string")
        val content = reader.readBytes()
        reader.expectEnd()
        return content
    }
}

/**
 * PLAINTEXT_2 = ( C_R, ID_CRED_R / bstr / -24..23, Signature_or_MAC_2, ? EAD_2 ) and
 * PLAINTEXT_3 = ( ID_CRED_I / bstr / -24..23, Signature_or_MAC_3, ? EAD_3 ) (RFC 9528 §5.3.2, §5.4.2).
 * [connectionIdentifier] is C_R in PLAINTEXT_2 and null in PLAINTEXT_3.
 */
internal class AuthenticatedPlaintext(
    val connectionIdentifier: ConnectionIdentifier?,
    val credentialIdentifier: CredentialIdentifier,
    val signatureOrMac: ByteArray,
    val ead: List<EadItem>,
) {
    fun encode(): ByteArray {
        val writer = CborWriter()
        connectionIdentifier?.let { writer.writeEncoded(it.encode()) }
        return writer
            .writeEncoded(credentialIdentifier.encodeForPlaintext())
            .writeBytes(signatureOrMac)
            .writeEncoded(EadItem.encode(ead))
            .toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray, withConnectionIdentifier: Boolean, what: String): AuthenticatedPlaintext {
            val reader = MessageReader(bytes, what)
            val connectionIdentifier =
                if (withConnectionIdentifier) ConnectionIdentifier.decode(reader.readValue(), what) else null
            val credentialIdentifier = CredentialIdentifier.decodeFromPlaintext(reader.readEncoded(), what)
            val signatureOrMac = reader.readBytes()
            val ead = EadItem.readAll(reader)
            return AuthenticatedPlaintext(connectionIdentifier, credentialIdentifier, signatureOrMac, ead)
        }
    }
}

/** PLAINTEXT_4 = ( ? EAD_4 ) (RFC 9528 §5.5.2). */
internal object Plaintext4 {
    fun encode(ead: List<EadItem>): ByteArray = EadItem.encode(ead)

    fun decode(bytes: ByteArray): List<EadItem> = EadItem.readAll(MessageReader(bytes, "PLAINTEXT_4"))
}
