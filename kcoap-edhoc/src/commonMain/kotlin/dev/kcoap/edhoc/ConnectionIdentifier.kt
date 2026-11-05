package dev.kcoap.edhoc

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.CborWriter
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend

/**
 * An EDHOC connection identifier, C_I or C_R (RFC 9528 §3.3). Connection identifiers are byte strings;
 * on the wire, a one-byte string that equals the one-byte CBOR encoding of an integer in -24..23 is sent
 * as that integer, any other string as a CBOR byte string (RFC 9528 §3.3.2). With OSCORE the bytes
 * become a Recipient ID unchanged (RFC 9528 §3.3.3).
 *
 * ```
 * ConnectionIdentifier(byteArrayOf(0x2d)).encode()   // 2d   (the integer -14)
 * ConnectionIdentifier(byteArrayOf(0x18)).encode()   // 41 18
 * ```
 */
public class ConnectionIdentifier(bytes: ByteArray) {
    private val content = bytes.copyOf()

    /** The identifier itself, which is also the OSCORE Recipient ID it maps to. */
    public val bytes: ByteArray get() = content.copyOf()

    /** Length of the identifier in bytes. */
    public val size: Int get() = content.size

    /** The representation in EDHOC messages (RFC 9528 §3.3.2). */
    public fun encode(): ByteArray = ByteStringIdentifier.encode(content)

    override fun equals(other: Any?): Boolean = other is ConnectionIdentifier && content.contentEquals(other.content)

    override fun hashCode(): Int = content.contentHashCode()

    override fun toString(): String = "ConnectionIdentifier(${content.toHex()})"

    public companion object {
        /** The identifier sent as the one-byte integer [value], which must be in -24..23. */
        public fun ofInt(value: Int): ConnectionIdentifier {
            require(value in -24..23) { "Only -24..23 have a one-byte encoding, got $value" }
            return ConnectionIdentifier(CborInt(value.toLong()).encode())
        }

        /** Reads an identifier from its EDHOC representation. */
        internal fun decode(item: CborValue, what: String): ConnectionIdentifier =
            ConnectionIdentifier(ByteStringIdentifier.decode(item, what))
    }
}

/**
 * Chooses the connection identifier an endpoint asks its peer to use (RFC 9528 §3.3.1). The Initiator
 * calls it once per message_1 with `peer = null`; the Responder calls it with C_I and must not return
 * C_I, because with OSCORE both would become the same Recipient ID (RFC 9528 §3.3.3).
 */
public fun interface ConnectionIdentifierProvider {
    /** The identifier to send; [peer] is C_I when the Responder chooses C_R, null for the Initiator. */
    public suspend fun choose(crypto: CryptoBackend, peer: ConnectionIdentifier?): ConnectionIdentifier

    public companion object {
        /**
         * Random one-byte identifiers that encode as a single CBOR integer, the shortest possible, chosen
         * from the 48 values -24..23 and different from the peer's.
         */
        public val Random: ConnectionIdentifierProvider = ConnectionIdentifierProvider { crypto, peer ->
            var candidate: ConnectionIdentifier
            do {
                val random = crypto.randomBytes(1)[0].toInt() and 0xFF
                candidate = ConnectionIdentifier.ofInt(random % ONE_BYTE_INTEGERS - 24)
            } while (candidate == peer)
            candidate
        }

        /** Always [identifier]; for deployments that assign identifiers and for test vectors. */
        public fun fixed(identifier: ConnectionIdentifier): ConnectionIdentifierProvider =
            ConnectionIdentifierProvider { _, _ -> identifier }

        private const val ONE_BYTE_INTEGERS = 48
    }
}

/**
 * The byte-string identifier representation of RFC 9528 §3.3.2, shared by connection identifiers and
 * by the compact 'kid' encoding of ID_CRED_x (RFC 9528 §3.5.3.2).
 */
internal object ByteStringIdentifier {
    fun encode(bytes: ByteArray): ByteArray =
        if (isOneByteInteger(bytes)) bytes.copyOf() else CborWriter().writeBytes(bytes).toByteArray()

    /**
     * The byte string an encoded identifier stands for. An integer outside -24..23, and a byte string
     * that should have been sent as an integer (RFC 9529 §4.1.2, §4.1.7), are malformed.
     */
    fun decode(item: CborValue, what: String): ByteArray = when (item) {
        is CborInt -> {
            if (item.value !in -24L..23L) throw EdhocException.malformed("$what: integer identifier out of range")
            Cbor.encode(item)
        }
        is CborBytes -> {
            val bytes = item.bytes
            if (isOneByteInteger(bytes)) throw EdhocException.malformed("$what: identifier must be sent as an integer")
            bytes
        }
        else -> throw EdhocException.malformed("$what: identifier must be a byte string or an integer")
    }

    /** One byte in 0x00..0x17 (integers 0..23) or 0x20..0x37 (integers -1..-24). */
    private fun isOneByteInteger(bytes: ByteArray): Boolean {
        if (bytes.size != 1) return false
        val value = bytes[0].toInt() and 0xFF
        return value in 0x00..0x17 || value in 0x20..0x37
    }
}
