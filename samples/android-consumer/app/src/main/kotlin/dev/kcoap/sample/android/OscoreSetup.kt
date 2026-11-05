package dev.kcoap.sample.android

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import java.io.File

/** The two ends of one OSCORE association: the app's client and its loopback server. */
internal class OscoreContexts(val client: SecurityContext, val server: SecurityContext)

/**
 * Derives both ends of an OSCORE association from a pre-shared Master Secret and Master Salt
 * (RFC 8613 §3.2), with the values of RFC 8613 Appendix C.1: a test vector, never a key for real use.
 * The sequence numbers live in [dataDirectory] (RFC 8613 Appendix B.1.1), so a restarted app continues
 * where it stopped.
 */
internal suspend fun oscoreContexts(crypto: CryptoBackend, dataDirectory: File): OscoreContexts {
    val store = FileSequenceNumberStore(dataDirectory.toPath())
    val clientId = ByteArray(0)
    val serverId = byteArrayOf(0x01)
    val client = SecurityContext.derive(
        SecurityContextParameters(MASTER_SECRET, senderId = clientId, recipientId = serverId, masterSalt = MASTER_SALT),
        crypto,
        store,
    )
    val server = SecurityContext.derive(
        SecurityContextParameters(MASTER_SECRET, senderId = serverId, recipientId = clientId, masterSalt = MASTER_SALT),
        crypto,
        store,
    )
    return OscoreContexts(client, server)
}

private val MASTER_SECRET = ByteArray(16) { (it + 1).toByte() } // 0x0102...10
private val MASTER_SALT = byteArrayOf(0x9e.toByte(), 0x7c, 0xa9.toByte(), 0x22, 0x23, 0x78, 0x63, 0x40)
